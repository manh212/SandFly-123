package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportProtocol;
import io.meridian.licensing.SupportAttachment;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public final class SupportProtocolTest {
  private static final String REQUEST = "SFREQ2." + "a".repeat(64);

  private static final class Backend implements FirebaseRest.Storage, FirebaseRest.Transport {
    final Map<String, JSONObject> records = new HashMap<>();
    String identity = "{}";
    boolean loseMessageResponse;
    int messageWrites;
    @Override public String read() { return identity; }
    @Override public void write(String value) { identity = value; }
    @Override public String request(String address, String method, String body, String contentType) throws Exception {
      if (address.contains("accounts:signUp")) return "{\"localId\":\"alice\",\"refreshToken\":\"refresh\",\"idToken\":\"token\"}";
      if (address.contains("securetoken")) return "{\"user_id\":\"alice\",\"refresh_token\":\"refresh\",\"id_token\":\"token\"}";
      String path = URI.create(address).getPath().replaceFirst("^/", "").replaceFirst("\\.json$", "");
      if (method.equals("GET")) return records.containsKey(path) ? records.get(path).toString() : "null";
      JSONObject value = new JSONObject(body);
      for (String field : new String[]{"createdAt", "lastAt", "lastSeen"}) {
        if (value.optJSONObject(field) != null) value.put(field, 1700000000123L);
      }
      records.put(path, value);
      if (path.startsWith("supportMessages/")) {
        messageWrites++;
        if (loseMessageResponse) { loseMessageResponse = false; throw new IOException("lost response"); }
      }
      return value.toString();
    }
    FirebaseRest service() throws Exception {
      return new FirebaseRest(new FirebaseRest.Config("{\"apiKey\":\"abcdefghijklmnopqrstuv\",\"databaseUrl\":\"https://demo.firebaseio.com\"}"), this, this);
    }
  }

  private JSONObject draft() throws Exception {
    return new JSONObject().put("ticket", SupportProtocol.id()).put("message", SupportProtocol.id())
        .put("name", "Người dùng").put("subject", "Lỗi kích hoạt").put("type", "bug").put("request", REQUEST)
        .put("appVersion", "dev 1405").put("androidVersion", "14").put("text", "Cách tái hiện").put("log", "");
  }

  @Test public void manualMessagesValidateLimitsWithoutRequiringAnAttachment() throws Exception {
    assertEquals("", SupportProtocol.message("alice", "", "user", "Feedback", "").getString("log"));
    assertEquals(32000, SupportProtocol.message("alice", "", "user", "", "x".repeat(32000)).getString("log").length());
    assertThrows(IllegalArgumentException.class, () -> SupportProtocol.message("alice", "", "user", "", ""));
    assertThrows(IllegalArgumentException.class, () -> SupportProtocol.message("alice", "", "staff", "x".repeat(8001), ""));
    assertThrows(IllegalArgumentException.class, () -> SupportProtocol.message("alice", "", "user", "Body", "x".repeat(32001)));
    assertThrows(IllegalArgumentException.class, () -> SupportProtocol.text("x\u0000y", 80, true));
    assertTrue(SupportProtocol.id().matches("[0-9]{13}-[0-9a-f]{20}"));
  }

  @Test public void attachmentSummaryAllowsLogOnlyMessagesAndDetectsDifferentContentOnRetry() throws Exception {
    JSONObject info = SupportAttachment.inspect(() -> new java.io.ByteArrayInputStream("original log".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    JSONObject message = SupportProtocol.message("alice", "", "user", "", "", info);
    assertEquals("", message.getString("log")); assertEquals(12, message.getInt("logBytes"));
    assertTrue(SupportProtocol.sameMessage(message, new JSONObject(message.toString())));
    JSONObject changed = new JSONObject(message.toString()).put("logSha256", "a".repeat(64));
    assertFalse(SupportProtocol.sameMessage(message, changed));
  }

  @Test public void lostAcknowledgementRetriesWithoutDuplicatingTheMessageOrChangingItsTime() throws Exception {
    Backend backend = new Backend(); JSONObject draft = draft(); backend.loseMessageResponse = true;
    assertThrows(IOException.class, () -> SupportProtocol.publishUser(backend.service(), draft));
    String path = "supportMessages/" + draft.getString("ticket") + "/" + draft.getString("message");
    long firstTime = backend.records.get(path).getLong("createdAt");
    SupportProtocol.publishUser(backend.service(), draft);
    assertEquals(1, backend.messageWrites);
    assertEquals(firstTime, backend.records.get(path).getLong("createdAt"));
    assertEquals(1700000000123L, backend.records.get("supportUsers/alice/" + draft.getString("ticket")).getLong("createdAt"));
    assertEquals(REQUEST, backend.records.get("supportContacts/alice").getString("request"));
    draft.put("text", "Changed after prior upload");
    assertThrows(IllegalStateException.class, () -> SupportProtocol.publishUser(backend.service(), draft));
    assertEquals(1, backend.messageWrites);
  }

  @Test public void aDifferentInboxOwnerCannotBeOverwritten() throws Exception {
    Backend backend = new Backend(); JSONObject draft = draft();
    backend.records.put("supportIndex/" + draft.getString("ticket"), new JSONObject().put("owner", "bob"));
    assertThrows(IllegalStateException.class, () -> SupportProtocol.publishUser(backend.service(), draft));
    assertEquals(0, backend.messageWrites);
  }
}
