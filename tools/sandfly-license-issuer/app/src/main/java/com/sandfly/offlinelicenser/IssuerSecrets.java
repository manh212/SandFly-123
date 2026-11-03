package com.sandfly.offlinelicenser;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;

final class IssuerSecrets {
  private static final String ALIAS = "meridian.admin.storage.v2";
  private final Context context;
  private final SharedPreferences preferences;

  IssuerSecrets(Context context) {
    this.context = context.getApplicationContext();
    preferences = this.context.getSharedPreferences("meridian_admin_private", Context.MODE_PRIVATE);
  }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      boolean strongBox = Build.VERSION.SDK_INT >= 28
          && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
      try { generate(strongBox); }
      catch (Exception e) {
        if (!strongBox) throw e;
        store.deleteEntry(ALIAS);
        generate(false);
      }
    }
    SecretKey key = (SecretKey) store.getKey(ALIAS, null);
    KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
        .getKeySpec(key, KeyInfo.class);
    boolean hardware = Build.VERSION.SDK_INT >= 31
        ? info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_STRONGBOX
            || info.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
        : info.isInsideSecureHardware();
    if (!hardware) {
      store.deleteEntry(ALIAS);
      throw new IllegalStateException("Thiết bị không hỗ trợ mã hóa bằng khóa phần cứng.");
    }
    return key;
  }

  private void generate(boolean strongBox) throws Exception {
    KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true);
    if (Build.VERSION.SDK_INT >= 28) spec.setIsStrongBoxBacked(strongBox);
    KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
    generator.init(spec.build());
    generator.generateKey();
  }

  String read(String name, String fallback) throws Exception {
    String encoded = preferences.getString(name, null);
    if (encoded == null) return fallback;
    String[] parts = encoded.split(":", -1);
    if (parts.length != 2) throw new IllegalStateException("Dữ liệu quản trị bị hỏng.");
    byte[] iv = Base64.getDecoder().decode(parts[0]);
    if (iv.length != 12) throw new IllegalStateException("Dữ liệu quản trị bị hỏng.");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
    cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
  }

  void write(String name, String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
    String encoded = Base64.getEncoder().encodeToString(cipher.getIV()) + ":"
        + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    if (!preferences.edit().putString(name, encoded).commit()) {
      throw new IllegalStateException("Không lưu được dữ liệu quản trị.");
    }
  }
}
