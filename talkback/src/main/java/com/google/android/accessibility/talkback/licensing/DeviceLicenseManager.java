package com.google.android.accessibility.talkback.licensing;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.google.android.accessibility.talkback.BuildConfig;
import android.provider.Settings;
import io.meridian.licensing.RecoveryProtocol;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

public final class DeviceLicenseManager {
  private static final String DEVICE_ALIAS = "sandfly.meridian.device.v1";
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

  public static boolean isLicensed(Context context) {
    if (!configured()) return false;
    try {
      String license = prefs(context).getString("license", "");
      if (license == null || license.isEmpty() || !ApkSignatureVerifier.isOfficial(context)) return false;
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
