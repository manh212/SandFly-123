package com.google.android.accessibility.talkback.licensing;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.google.android.accessibility.talkback.BuildConfig;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

/** Grants offline permission to updater; never disables the accessibility service. */
public final class DeviceLicenseManager {
  private static final String DEVICE_ALIAS = "sandfly.meridian.device.v1";
  private DeviceLicenseManager() {}
  public static boolean configured() {
    return BuildConfig.SANDFLY_ISSUER_PUBLIC_KEY.startsWith("SFPUB1.");
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
  public static boolean isLicensed(Context context) {
    try {
      return configured() && LicenseProtocol.verify(request(context),
          prefs(context).getString("license", ""), LicenseProtocol.issuer(BuildConfig.SANDFLY_ISSUER_PUBLIC_KEY));
    } catch (Exception e) { return false; }
  }
  public static void activate(Context context, String license) throws Exception {
    if (!configured()) throw new IllegalStateException("Khóa cấp phép của nhà phát hành chưa được cấu hình");
    if (!LicenseProtocol.verify(request(context), license.trim(),
          LicenseProtocol.issuer(BuildConfig.SANDFLY_ISSUER_PUBLIC_KEY)))
      throw new IllegalArgumentException("Mã không đúng cho thiết bị này");
    if (!prefs(context).edit().putString("license", license.trim()).commit())
      throw new IllegalStateException("Không lưu được giấy phép");
  }
  /** Existing users are not blocked until an actual issuer public key is configured. */
  public static boolean mayUpdate(Context context) {
    return !configured() || isLicensed(context);
  }
}