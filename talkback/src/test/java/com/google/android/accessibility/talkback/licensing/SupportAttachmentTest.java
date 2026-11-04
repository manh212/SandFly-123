package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportAttachment;
import io.meridian.licensing.SupportProtocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public final class SupportAttachmentTest {
  private static final class Backend implements FirebaseRest.Storage, FirebaseRest.Transport {
    final Map<String, JSONObject> records = new HashMap<>();
    final Map<String, Integer> writes = new HashMap<>();
    String identity = "{}";
    boolean loseChunkResponse;
    @Override public String read() { return identity; }
    @Override public void write(String value) { identity = value; }
    @Override public String request(String address, String method, String body, String contentType) throws Exception {
      if (address.contains("accounts:signUp")) return "{\"localId\":\"alice\",\"refreshToken\":\"refresh\",\"idToken\":\"token\"}";
      if (address.contains("securetoken")) return "{\"user_id\":\"alice\",\"refresh_token\":\"refresh\",\"id_token\":\"token\"}";
      String path = URI.create(address).getPath().replaceFirst("^/", "").replaceFirst("\\.json$", "");
      if (method.equals("GET")) return records.containsKey(path) ? records.get(path).toString() : "null";
      JSONObject value = new JSONObject(body);
      for (String field : new String[]{"createdAt", "lastAt", "lastSeen", "changedAt", "lastReplyAt", "statusAt"}) {
        if (value.optJSONObject(field) != null) value.put(field, 1700000000123L);
      }
      if (method.equals("PATCH") && records.containsKey(path)) {
        JSONObject merged = new JSONObject(records.get(path).toString());
        for (String key : SupportProtocol.keys(value)) merged.put(key, value.get(key));
        value = merged;
      }
      records.put(path, value); writes.merge(path, 1, Integer::sum);
      if (path.startsWith("supportLogChunks/") && loseChunkResponse) {
        loseChunkResponse = false; throw new IOException("lost chunk response");
      }
      return "";
    }
    FirebaseRest service() throws Exception {
      return new FirebaseRest(new FirebaseRest.Config("{\"apiKey\":\"abcdefghijklmnopqrstuv\",\"databaseUrl\":\"https://demo.firebaseio.com\"}"), this, this);
    }
  }

  private JSONObject draft() throws Exception {
    return new JSONObject().put("ticket", SupportProtocol.id()).put("message", SupportProtocol.id())
        .put("name", "Người dùng").put("subject", "Nhật ký đầy đủ").put("type", "log")
        .put("request", "SFREQ2." + "a".repeat(64)).put("appVersion", "dev").put("androidVersion", "14")
        .put("text", "").put("log", "");
  }

  @Test public void fullTenMegabyteExportRoundTripsExactlyWithoutInlineHistoryPayload() throws Exception {
    byte[] bytes = new byte[SupportAttachment.MAX_BYTES]; Arrays.fill(bytes, (byte) 'x');
    byte[] special = "\r\n  Spoken: Văn bản trên màn hình 😀\nBearer literal-test-token\napi_key=literal-test-key\u0000\r\n".getBytes(StandardCharsets.UTF_8);
    System.arraycopy(special, 0, bytes, SupportAttachment.CHUNK_BYTES - 3, special.length);
    bytes[0] = '\n'; bytes[bytes.length - 1] = '\r';
    Backend backend = new Backend(); JSONObject draft = draft();
    SupportAttachment.Source source = () -> new ByteArrayInputStream(bytes);
    SupportProtocol.publishUser(backend.service(), draft, source, () -> false);
    String ticket = draft.getString("ticket"), message = draft.getString("message");
    JSONObject entry = backend.records.get("supportMessages/" + ticket + "/" + message);
    assertEquals(SupportAttachment.MAX_BYTES, entry.getInt("logBytes")); assertEquals("", entry.getString("log"));
    assertTrue(entry.toString().length() < 1000);
    ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
    SupportAttachment.download(backend.service(), ticket, message, entry, downloaded);
    assertArrayEquals(bytes, downloaded.toByteArray());
    assertEquals(SupportAttachment.MAX_PARTS, backend.records.keySet().stream().filter(k -> k.startsWith("supportLogChunks/")).count());
  }

  @Test public void lostChunkAcknowledgementResumesWithoutPublishingAnIncompleteMessage() throws Exception {
    byte[] bytes = new byte[SupportAttachment.CHUNK_BYTES + 7]; Arrays.fill(bytes, (byte) 'a');
    Backend backend = new Backend(); JSONObject draft = draft(); backend.loseChunkResponse = true;
    SupportAttachment.Source source = () -> new ByteArrayInputStream(bytes);
    assertThrows(IOException.class, () -> SupportProtocol.publishUser(backend.service(), draft, source, () -> false));
    String ticket = draft.getString("ticket"), message = draft.getString("message");
    String messagePath = "supportMessages/" + ticket + "/" + message;
    assertFalse(backend.records.containsKey(messagePath));
    SupportProtocol.publishUser(backend.service(), draft, source, () -> false);
    SupportProtocol.publishUser(backend.service(), draft, source, () -> false);
    assertEquals(Integer.valueOf(1), backend.writes.get(messagePath));
    assertEquals(Integer.valueOf(1), backend.writes.get("supportLogChunks/" + ticket + "/" + message + "/chunk0"));
    assertEquals(Integer.valueOf(1), backend.writes.get("supportLogChunks/" + ticket + "/" + message + "/chunk1"));
  }

  @Test public void utf8ByteLimitAndCorruptedDownloadsAreEnforced() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> SupportAttachment.inspect(() -> new ByteArrayInputStream(new byte[SupportAttachment.MAX_BYTES + 1])));
    assertThrows(IllegalArgumentException.class, () -> SupportAttachment.exact("界".repeat(SupportAttachment.MAX_BYTES / 3 + 1)));
    Backend backend = new Backend(); JSONObject draft = draft(); byte[] bytes = "nguyên bản\r\n".getBytes(StandardCharsets.UTF_8);
    SupportProtocol.publishUser(backend.service(), draft, () -> new ByteArrayInputStream(bytes), () -> false);
    String ticket = draft.getString("ticket"), message = draft.getString("message");
    JSONObject entry = backend.records.get("supportMessages/" + ticket + "/" + message);
    JSONObject part = backend.records.get("supportLogChunks/" + ticket + "/" + message + "/chunk0");
    byte[] changed = bytes.clone(); changed[0] ^= 1; part.put("data", Base64.getEncoder().encodeToString(changed));
    assertThrows(IllegalStateException.class, () -> SupportAttachment.download(backend.service(), ticket, message, entry, new ByteArrayOutputStream()));
    part.put("bytes", bytes.length + 1);
    assertThrows(IllegalStateException.class, () -> SupportAttachment.download(backend.service(), ticket, message, entry, new ByteArrayOutputStream()));
  }
}
