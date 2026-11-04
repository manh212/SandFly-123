package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SupportStore {
  private static final String ALIAS = "meridian.support.private.v1";
  private final Context context;
  SupportStore(Context context) { this.context = context.getApplicationContext(); }

  private SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec.Builder(ALIAS,
          KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }

  private JSONObject read() throws Exception {
    String raw = context.getSharedPreferences("meridian_support_private", 0).getString("data", "");
    if (raw.isEmpty()) return new JSONObject();
    String[] parts = raw.split("\\.", -1);
    if (parts.length != 2) throw new IllegalStateException("Không đọc được hộp thư trên máy.");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
    return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
  }

  private void write(JSONObject data) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
    String raw = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
        + Base64.encodeToString(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    if (!context.getSharedPreferences("meridian_support_private", 0).edit().putString("data", raw).commit()) {
      throw new IllegalStateException("Không lưu được nội dung trên máy.");
    }
  }

  JSONObject queue() throws Exception {
    synchronized (SupportStore.class) {
      JSONObject queue = read().optJSONObject("queue");
      return queue == null ? new JSONObject() : queue;
    }
  }

  void add(JSONObject item) throws Exception {
    synchronized (SupportStore.class) {
      JSONObject data = read(), queue = data.optJSONObject("queue");
      if (queue == null) queue = new JSONObject();
      if (queue.length() >= 20) throw new IllegalStateException("Hàng chờ đã có 20 nội dung. Hãy gửi hoặc hủy bớt trước.");
      queue.put(item.getString("message"), item);
      data.put("queue", queue).put("name", item.optString("name")); write(data);
    }
  }

  boolean remove(String id) throws Exception {
    synchronized (SupportStore.class) {
      JSONObject data = read(), queue = data.optJSONObject("queue");
      if (queue == null || !queue.has(id)) return false;
      queue.remove(id); data.put("queue", queue); write(data); return true;
    }
  }

  String name() throws Exception {
    synchronized (SupportStore.class) { return read().optString("name"); }
  }

  void failed(String id, String message) throws Exception {
    synchronized (SupportStore.class) {
      JSONObject data = read(), queue = data.optJSONObject("queue");
      if (queue == null || !queue.has(id)) return;
      queue.getJSONObject(id).put("error", message); data.put("queue", queue); write(data);
    }
  }

  void name(String value) throws Exception {
    synchronized (SupportStore.class) { JSONObject data = read(); data.put("name", value); write(data); }
  }
}
