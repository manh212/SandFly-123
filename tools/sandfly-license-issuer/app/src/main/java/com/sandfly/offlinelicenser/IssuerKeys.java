package com.sandfly.offlinelicenser;
import android.app.KeyguardManager;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.*;
import java.security.spec.ECGenParameterSpec;

final class IssuerKeys {
  private static final String ALIAS = "sandfly.offline.issuer.v1";
  private IssuerKeys() {}
  private static KeyStore store() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null); return ks;
  }
  static boolean exists() throws Exception { return store().containsAlias(ALIAS); }
  static void create(Context context) throws Exception {
    KeyguardManager guard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    if (guard == null || !guard.isDeviceSecure()) throw new IllegalStateException("Cần đặt mã PIN hoặc mật khẩu khóa màn hình.");
    if (exists()) return;
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
    gen.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
      .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationValidityDurationSeconds(30)
      .build());
    gen.generateKeyPair();
  }
  static PublicKey publicKey() throws Exception {
    java.security.cert.Certificate cert = store().getCertificate(ALIAS);
    if (cert == null) throw new IllegalStateException("Chưa có khóa cấp mã");
    return cert.getPublicKey();
  }
  static PrivateKey privateKey() throws Exception {
    PrivateKey privateKey = (PrivateKey) store().getKey(ALIAS, null);
    if (privateKey == null) throw new IllegalStateException("Khóa bí mật không khả dụng");
    return privateKey;
  }
}
