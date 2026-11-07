package com.google.android.accessibility.talkback.licensing;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.accessibility.talkback.BuildConfig;
import android.provider.Settings;
import io.meridian.licensing.RecoveryProtocol;
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.LicenseClock;
import io.meridian.licensing.LicenseLease;
import android.os.SystemClock;

public final class DeviceLicenseManager {
  private static volatile CachedPolicy verifiedPolicy;
  private static final class CachedPolicy {
    final String token, request;
    final LicensePolicy policy;
    CachedPolicy(String token, String request, LicensePolicy policy) {
      this.token = token; this.request = request; this.policy = policy;
    }
  }
  private DeviceLicenseManager() {}
  public static boolean configured() {
    return recoveryConfigured();
  }
  public static boolean recoveryConfigured() {
    return BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY.startsWith("SFPUB1.");
  }
  public static String recoveryRequest(Context context) throws Exception {
    return RecoveryProtocol.request(context.getPackageName(),
        Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
  }
  private static SharedPreferences prefs(Context context) {
    SharedPreferences saved = context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE);
    if (saved.contains("license")) saved.edit().remove("license").apply();
    return saved;
  }
  private static SharedPreferences verification(Context context) {
    return context.getSharedPreferences("meridian_license_verification", Context.MODE_PRIVATE);
  }

  private static boolean recentlyVerified(Context context) {
    SharedPreferences check = verification(context);
    long wall = System.currentTimeMillis();
    boolean allowed = LicenseLease.valid(wall, SystemClock.elapsedRealtime(), boot(context),
        check.getLong("server", 0), check.getLong("elapsed", -1),
        check.getInt("boot", -2), check.getLong("highestWall", 0));
    if (allowed && wall - check.getLong("highestWall", 0) >= 60_000L) {
      check.edit().putLong("highestWall", wall).apply();
    }
    return allowed;
  }

  private static void recordVerification(Context context, long serverMillis) throws Exception {
    if (serverMillis <= 0) return;
    SharedPreferences check = verification(context);
    long prior = check.getLong("server", 0);
    if (!LicenseLease.newerVerification(serverMillis, prior)) return;
    long wall = System.currentTimeMillis();
    if (!check.edit().putLong("server", serverMillis).putLong("elapsed", SystemClock.elapsedRealtime())
        .putInt("boot", boot(context)).putLong("highestWall", Math.max(wall, serverMillis))
        .commit()) throw new IllegalStateException("Không lưu được thời điểm xác minh giấy phép.");
  }

  public static boolean offlineLocked(Context context) {
    if (!configured() || recentlyVerified(context)) return false;
    try {
      String token = prefs(context).getString("policy", "");
      if (!token.isEmpty()) {
        LicensePolicy policy = policy(context);
        return policy != null && policy.state.equals("active")
            && (policy.expiresAt == 0 || policyTime(context, policy) < policy.expiresAt);
      }
      return false;
    } catch (Exception e) { return false; }
  }

  private static volatile long lastRestrictionCheck = -1;
  private static volatile boolean lastRestriction;

  public static boolean restricted(Context context) {
    if (!configured()) return true;
    long now = SystemClock.elapsedRealtime();
    if (lastRestrictionCheck >= 0 && now >= lastRestrictionCheck && now - lastRestrictionCheck < 1_000) {
      return lastRestriction;
    }
    boolean blocked;
    try {
      String token = prefs(context).getString("policy", "");
      if (!token.isEmpty()) {
        blocked = !isLicensed(context);
      } else {
        blocked = false;
      }
    } catch (Exception error) { blocked = true; }
    lastRestriction = blocked;
    lastRestrictionCheck = now;
    return blocked;
  }

  public static long remainingUntilExpiryMillis(Context context) {
    try {
      LicensePolicy policy = policy(context);
      if (policy == null || policy.expiresAt <= 0) return Long.MAX_VALUE;
      long remainingSeconds = policy.expiresAt - policyTime(context, policy);
      return remainingSeconds <= 0 ? 0 : Math.min(Long.MAX_VALUE / 1000, remainingSeconds) * 1000;
    } catch (Exception error) { return 0; }
  }

  public static boolean isLicensed(Context context) {
    try {
      prefs(context);
      if (!recoveryConfigured() || !ApkSignatureVerifier.isOfficial(context)
          || !recentlyVerified(context)) return false;
      LicensePolicy online = policy(context);
      return online != null && online.active(policyTime(context, online));
    } catch (Exception error) {
      return false;
    }
  }

  static synchronized boolean acceptRemote(Context context, String license, long serverMillis) throws Exception {
    if (license == null || !license.startsWith("SFLIC3.") || serverMillis <= 0) {
      throw new IllegalArgumentException("Giấy phép trực tuyến không hợp lệ.");
    }
    if (!ApkSignatureVerifier.isOfficial(context) || !recoveryConfigured()) {
      throw new IllegalStateException("Bản cài đặt chưa được xác thực.");
    }
    String request = recoveryRequest(context);
    String cached = prefs(context).getString("policy", "");
    LicensePolicy next = LicensePolicy.verify(request, license,
        RecoveryProtocol.publicKey(BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY));
    LicensePolicy old = cached.isEmpty() ? null : LicensePolicy.verify(request, cached,
        RecoveryProtocol.publicKey(BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY));
    if (old != null && (next.revision < old.revision
        || (next.revision == old.revision && !cached.equals(license)))) {
      return false;
    }
    SharedPreferences.Editor edit = prefs(context).edit().putString("policy", license)
        .remove("license")
        .putLong("trusted_time", serverMillis / 1000L)
        .putLong("time_floor", Math.max(prefs(context).getLong("time_floor", 0), serverMillis / 1000L))
        .putLong("trusted_elapsed", SystemClock.elapsedRealtime() / 1000L)
        .putInt("trusted_boot", boot(context));
    if (!edit.commit()) throw new IllegalStateException("Không lưu được trạng thái giấy phép.");
    recordVerification(context, serverMillis);
    lastRestrictionCheck = -1;
    return true;
  }

  private static LicensePolicy policy(Context context) throws Exception {
    String token = prefs(context).getString("policy", "");
    if (token.isEmpty()) return null;
    String request = recoveryRequest(context);
    CachedPolicy cached = verifiedPolicy;
    if (cached != null && cached.token.equals(token) && cached.request.equals(request)) return cached.policy;
    LicensePolicy policy = LicensePolicy.verify(request, token,
        RecoveryProtocol.publicKey(BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY));
    verifiedPolicy = new CachedPolicy(token, request, policy);
    return policy;
  }

  private static int boot(Context context) {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  private static synchronized long policyTime(Context context, LicensePolicy policy) {
    long wall = System.currentTimeMillis() / 1000L;
    SharedPreferences data = prefs(context);
    long floor = data.getLong("time_floor", 0);
    long trusted = data.getLong("trusted_time", 0);
    int currentBoot = boot(context);
    wall = LicenseClock.now(wall, floor, trusted, SystemClock.elapsedRealtime() / 1000L,
        data.getLong("trusted_elapsed", 0), currentBoot >= 0 && currentBoot == data.getInt("trusted_boot", -2));
    if (wall == Long.MAX_VALUE) return wall;
    if (wall > floor + 60) data.edit().putLong("time_floor", wall).commit();
    return wall;
  }

  public static boolean allows(Context context, String feature) {
    if (!LicensePolicy.FEATURES.contains(feature) || !recoveryConfigured()) return false;
    try {
      if (!recentlyVerified(context) || !ApkSignatureVerifier.isOfficial(context)) return false;
      LicensePolicy online = policy(context);
      return online != null && online.allows(feature, policyTime(context, online));
    } catch (Exception error) {
      return false;
    }
  }

  public static String status(Context context) {
    if (!recoveryConfigured()) return "Chưa cấu hình xác minh trực tuyến.";
    try {
      LicensePolicy policy = policy(context);
      if (policy == null) return "Chưa có giấy phép trực tuyến.";
      if (policy.state.equals("revoked")) return "Đã thu hồi giấy phép.";
      if (policy.state.equals("suspended")) return "Giấy phép đang bị tạm khóa.";
      if (policy.expiresAt > 0 && policyTime(context, policy) >= policy.expiresAt) {
        return "Giấy phép đã hết hạn.";
      }
      if (!recentlyVerified(context)) return "Cần xác minh giấy phép trực tuyến.";
      String state = policy.state.equals("revoked") ? "Đã thu hồi"
          : policy.state.equals("suspended") ? "Tạm khóa"
          : policy.active(policyTime(context, policy)) ? "Đang hoạt động" : "Đã hết hạn hoặc cần kiểm tra ngày giờ";
      return state + " · " + (policy.kind.equals("trial") ? "Dùng thử" : "Chính thức")
          + " · Gói " + policy.packageName + "\n"
          + (policy.expiresAt == 0 ? "Không thời hạn" : "Hết hạn: "
              + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(policy.expiresAt * 1000L)));
    } catch (Exception e) { return "Trạng thái giấy phép không hợp lệ. Hãy khôi phục kích hoạt."; }
  }
}
