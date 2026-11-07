package com.google.android.accessibility.talkback.licensing;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.google.android.accessibility.talkback.BuildConfig;
import android.provider.Settings;
import io.meridian.licensing.RecoveryProtocol;
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.LicenseClock;
import io.meridian.licensing.LicenseLease;
import android.os.SystemClock;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

public final class DeviceLicenseManager {
  private static final String DEVICE_ALIAS = "sandfly.meridian.device.v1";
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
    return BuildConfig.SANDFLY_ISSUER_PUBLIC_KEY.startsWith("SFPUB1.") || recoveryConfigured();
  }
  public static boolean recoveryConfigured() {
    return BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY.startsWith("SFPUB1.");
  }
  public static String recoveryRequest(Context context) throws Exception {
    return RecoveryProtocol.request(context.getPackageName(),
        Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
  }
  private static PublicKey deviceKey() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(DEVICE_ALIAS)) {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
      generator.initialize(new KeyGenParameterSpec.Builder(DEVICE_ALIAS,
          KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
          .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
          .setDigests(KeyProperties.DIGEST_SHA256).build());
      generator.generateKeyPair();
    }
    java.security.cert.Certificate certificate = ks.getCertificate(DEVICE_ALIAS);
    if (certificate == null) throw new IllegalStateException("Chưa tạo được khóa thiết bị");
    return certificate.getPublicKey();
  }
  public static String request(Context context) throws Exception {
    return LicenseProtocol.request(deviceKey());
  }
  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE);
  }
  private static boolean ownsDeviceKey() {
    try {
      KeyStore store = KeyStore.getInstance("AndroidKeyStore");
      store.load(null);
      Key key = store.getKey(DEVICE_ALIAS, null);
      java.security.cert.Certificate certificate = store.getCertificate(DEVICE_ALIAS);
      return key instanceof PrivateKey
          && certificate != null
          && LicenseProtocol.provesPossession((PrivateKey) key, certificate.getPublicKey());
    } catch (Exception e) {
      return false;
    }
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
      return !prefs(context).getString("license", "").isEmpty();
    } catch (Exception e) { return false; }
  }

  private static volatile long lastRestrictionCheck = -1;
  private static volatile boolean lastRestriction;

  public static boolean restricted(Context context) {
    if (!configured()) return false;
    long now = SystemClock.elapsedRealtime();
    if (lastRestrictionCheck >= 0 && now >= lastRestrictionCheck && now - lastRestrictionCheck < 1_000) {
      return lastRestriction;
    }
    boolean blocked;
    try {
      String token = prefs(context).getString("policy", "");
      if (!token.isEmpty()) {
        LicensePolicy policy = policy(context);
        blocked = policy == null || !policy.active(policyTime(context, policy)) || !recentlyVerified(context);
      } else {
        blocked = !prefs(context).getString("license", "").isEmpty() && !recentlyVerified(context);
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
    if (!configured()) return false;
    try {
      if (!recentlyVerified(context)) return false;
      String license = prefs(context).getString("license", "");
      if (!ApkSignatureVerifier.isOfficial(context)) return false;
      LicensePolicy policy = policy(context);
      if (policy != null) return policy.active(policyTime(context, policy));
      if (!prefs(context).getString("policy", "").isEmpty()) return false;
      if (license == null || license.isEmpty()) return false;
      if (license.startsWith("SFLIC2.")) {
        return verifiesRecovery(recoveryRequest(context), license,
            BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY);
      }
      return LicenseProtocol.verify(
              request(context),
              license,
              LicenseProtocol.issuer(BuildConfig.SANDFLY_ISSUER_PUBLIC_KEY))
          && ownsDeviceKey();
    } catch (Exception e) {
      return false;
    }
  }

  public static void activate(Context context, String license) throws Exception {
    if (!ApkSignatureVerifier.isOfficial(context)) {
      throw new IllegalStateException("Bản cài đặt Meridian không có chữ ký chính thức. Hãy cài APK từ nguồn phát hành chính thức.");
    }
    if (!configured()) throw new IllegalStateException("Khóa cấp phép của nhà phát hành chưa được cấu hình");
    if (license == null) throw new IllegalArgumentException("Chưa nhập mã kích hoạt");
    if (!prefs(context).getString("policy", "").isEmpty() || license.trim().startsWith("SFLIC3.")) {
      throw new IllegalStateException("Chọn Khôi phục kích hoạt để lấy trạng thái giấy phép mới nhất.");
    }
    if (license.trim().startsWith("SFLIC2.")) {
      if (!verifiesRecovery(recoveryRequest(context), license.trim(),
          BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY)) {
        throw new IllegalArgumentException("Giấy phép không khớp thiết bị hoặc khóa cấp phép mới chưa được tích hợp. Hãy cài bản Meridian mới nhất.");
      }
      save(context, license.trim());
      return;
    }
    if (!LicenseProtocol.verify(
            request(context), license.trim(),
            LicenseProtocol.issuer(BuildConfig.SANDFLY_ISSUER_PUBLIC_KEY))
        || !ownsDeviceKey()) {
      throw new IllegalArgumentException("Mã hoặc định danh thiết bị không hợp lệ");
    }
    save(context, license.trim());
  }
  static boolean verifiesRecovery(String request, String license, String issuer) {
    try {
      return RecoveryProtocol.verify(request, license, RecoveryProtocol.publicKey(issuer));
    } catch (Exception e) {
      return false;
    }
  }
  static synchronized boolean acceptRemote(Context context, String license, long serverMillis) throws Exception {
    if (!ApkSignatureVerifier.isOfficial(context)) throw new IllegalStateException("Bản cài đặt chưa được xác thực.");
    String request = recoveryRequest(context);
    String cached = prefs(context).getString("policy", "");
    if (license.startsWith("SFLIC3.")) {
      LicensePolicy next = LicensePolicy.verify(request, license,
          RecoveryProtocol.publicKey(BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY));
      LicensePolicy old = cached.isEmpty() ? null : LicensePolicy.verify(request, cached,
          RecoveryProtocol.publicKey(BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY));
      if (old != null && (next.revision < old.revision
          || (next.revision == old.revision && !cached.equals(license)))) {
        return false;
      }
      SharedPreferences.Editor edit = prefs(context).edit().putString("policy", license);
      if (serverMillis > 0) {
        edit.putLong("trusted_time", serverMillis / 1000L)
            .putLong("time_floor", Math.max(prefs(context).getLong("time_floor", 0), serverMillis / 1000L))
            .putLong("trusted_elapsed", SystemClock.elapsedRealtime() / 1000L)
            .putInt("trusted_boot", boot(context));
      }
      if (!edit.commit()) throw new IllegalStateException("Không lưu được trạng thái giấy phép.");
    } else {
      if (!cached.isEmpty()) return false;
      if (!verifiesRecovery(request, license, BuildConfig.SANDFLY_RECOVERY_PUBLIC_KEY)) {
        throw new IllegalArgumentException("Giấy phép không hợp lệ.");
      }
      save(context, license);
    }
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
    if (!LicensePolicy.FEATURES.contains(feature)) return false;
    if (!configured()) return true;
    try {
      if (!recentlyVerified(context)) return false;
      LicensePolicy policy = policy(context);
      return policy == null ? prefs(context).getString("policy", "").isEmpty()
          && (isLicensed(context) || prefs(context).getString("license", "").isEmpty())
          : ApkSignatureVerifier.isOfficial(context) && policy.allows(feature, policyTime(context, policy));
    } catch (Exception e) { return false; }
  }

  public static String status(Context context) {
    try {
      LicensePolicy policy = policy(context);
      if (policy != null && policy.state.equals("revoked")) return "Đã thu hồi giấy phép.";
      if (policy != null && policy.state.equals("suspended")) return "Giấy phép đang bị tạm khóa.";
      if (policy != null && policy.expiresAt > 0 && policyTime(context, policy) >= policy.expiresAt) {
        return "Giấy phép đã hết hạn.";
      }
      if (!recentlyVerified(context)) return "Cần kết nối Internet để xác minh giấy phép (tối đa 7 ngày ngoại tuyến).";
      if (policy == null) return isLicensed(context)
          ? "Đã đăng ký · Đầy đủ tính năng · Không thời hạn" : "Chưa có giấy phép hợp lệ.";
      String state = policy.state.equals("revoked") ? "Đã thu hồi"
          : policy.state.equals("suspended") ? "Tạm khóa"
          : policy.active(policyTime(context, policy)) ? "Đang hoạt động" : "Đã hết hạn hoặc cần kiểm tra ngày giờ";
      return state + " · " + (policy.kind.equals("trial") ? "Dùng thử" : "Chính thức")
          + " · Gói " + policy.packageName + "\n"
          + (policy.expiresAt == 0 ? "Không thời hạn" : "Hết hạn: "
              + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(policy.expiresAt * 1000L)));
    } catch (Exception e) { return "Trạng thái giấy phép không hợp lệ. Hãy khôi phục kích hoạt."; }
  }
  private static void save(Context context, String license) {
    if (!prefs(context).edit().putString("license", license).commit()) {
      throw new IllegalStateException("Không lưu được giấy phép");
    }
  }
  /** Existing users are not blocked until an actual issuer public key is configured. */
  public static boolean mayUpdate(Context context) {
    return !configured() || isLicensed(context);
  }
}
