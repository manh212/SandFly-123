package com.sandfly.offlinelicenser;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import io.meridian.licensing.RecoveryProtocol;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

final class IssuerKeys {
  private static final String ALIAS = "meridian.issuer.hardware.v2";
  private IssuerKeys() {}

  private static KeyStore store() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    return store;
  }

  static boolean exists() throws Exception { return store().containsAlias(ALIAS); }

  static void create(Context context) throws Exception {
    KeyguardManager guard = context.getSystemService(KeyguardManager.class);
    if (guard == null || !guard.isDeviceSecure()) {
      throw new IllegalStateException("Hãy đặt PIN hoặc mật khẩu khóa màn hình.");
    }
    if (exists()) { ensureHardware(); return; }
    boolean strongBox = Build.VERSION.SDK_INT >= 28
        && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
    try {
      generate(strongBox);
    } catch (Exception e) {
      if (!strongBox) throw e;
      store().deleteEntry(ALIAS);
      generate(false);
    }
    try { ensureHardware(); }
    catch (Exception e) { store().deleteEntry(ALIAS); throw e; }
  }

  private static void generate(boolean strongBox) throws Exception {
    KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS,
        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setUserAuthenticationRequired(true);
    if (Build.VERSION.SDK_INT >= 30) {
      spec.setUserAuthenticationParameters(0,
          KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
    } else {
      spec.setUserAuthenticationValidityDurationSeconds(10);
    }
    if (Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(strongBox);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
    generator.initialize(spec.build());
    generator.generateKeyPair();
  }

  private static PrivateKey privateKey() throws Exception {
    PrivateKey key = (PrivateKey) store().getKey(ALIAS, null);
    if (key == null) throw new IllegalStateException("Chưa có khóa cấp phép.");
    return key;
  }

  private static KeyInfo info() throws Exception {
    return KeyFactory.getInstance("EC", "AndroidKeyStore")
        .getKeySpec(privateKey(), KeyInfo.class);
  }

  private static void ensureHardware() throws Exception {
    KeyInfo info = info();
    boolean hardware = Build.VERSION.SDK_INT >= 31
        ? info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX
            || info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        : info.isInsideSecureHardware();
    if (!hardware) throw new IllegalStateException("Thiết bị không hỗ trợ khóa cấp phép trong phần cứng bảo mật.");
  }

  static String securityLevel() throws Exception {
    KeyInfo info = info();
    return Build.VERSION.SDK_INT >= 31
        && info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX
        ? "StrongBox" : "Phần cứng bảo mật";
  }

  static String publicKey() throws Exception {
    java.security.cert.Certificate certificate = store().getCertificate(ALIAS);
    if (certificate == null) throw new IllegalStateException("Chưa có khóa cấp phép.");
    return RecoveryProtocol.publicKey(certificate.getPublicKey());
  }

  static Signature signer() throws Exception {
    ensureHardware();
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(privateKey());
    return signer;
  }
}
