package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.FirebaseRest;
import io.meridian.licensing.SupportProtocol;
import io.meridian.licensing.SupportAttachment;
import io.meridian.licensing.SupportChanges;
import io.meridian.licensing.SupportInbox;
import io.meridian.licensing.FirebaseEvents;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
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
      for (String field : new String[]{"createdAt", "lastAt", "lastSeen", "changedAt", "lastReplyAt", "statusAt"}) {
        if (value.optJSONObject(field) != null) value.put(field, 1700000000123L);
      }
      if (method.equals("PATCH") && records.containsKey(path)) {
        JSONObject merged = new JSONObject(records.get(path).toString());
        for (String key : SupportProtocol.keys(value)) merged.put(key, value.get(key));
        value = merged;
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
  private static String key(int value) { return String.format(java.util.Locale.ROOT, "%013d-%020x", value, value); }

  private static JSONObject update(String path, JSONObject data) throws Exception {
    return new JSONObject().put("path", path).put("data", data);
  }

  @Test public void streamingParsesCompletePutPatchAndHeartbeatEvents() throws Exception {
    List<String> events = new ArrayList<>();
    String stream = ": heartbeat\n\nevent: put\ndata: {\"path\":\"/\",\"data\":{}}\n\n"
        + "event: patch\ndata: {\"path\":\"/ticket\",\ndata: \"data\":{\"status\":\"working\"}}\n\n";
    FirebaseEvents.parse(new StringReader(stream), (event, body) -> events.add(event + body.getString("path")), () -> false);
    assertEquals(java.util.Arrays.asList("put/", "patch/ticket"), events);
    assertThrows(IOException.class, () -> FirebaseEvents.parse(new StringReader(
        "event: auth_revoked\ndata: \"expired\"\n\n"), (event, body) -> {}, () -> false));
    FirebaseEvents.parse(new StringReader(stream), (event, body) -> fail("Stopped streams must not deliver"), () -> true);
  }

  @Test public void repliesAndActualStatusTransitionsNotifyOnceAcrossReconnects() throws Exception {
    String ticket = key(1);
    JSONObject item = new JSONObject().put("subject", "Lỗi").put("lastReplyId", key(2))
        .put("status", "new").put("statusAt", 1);
    JSONObject snapshot = new JSONObject().put(ticket, item);
    SupportChanges changes = new SupportChanges(new JSONObject());
    List<String> events = new ArrayList<>();
    SupportChanges.Sink sink = (id, value, reply) -> events.add(id + (reply ? ":reply" : ":status"));
    changes.accept("put", update("/", snapshot), sink);
    assertTrue(events.isEmpty());
    JSONObject patch = new JSONObject().put("lastReplyId", key(3)).put("lastReplyAt", 2)
        .put("status", "working").put("statusAt", 3);
    changes.accept("patch", update("/" + ticket, patch), sink);
    assertEquals(java.util.Arrays.asList(ticket + ":reply", ticket + ":status"), events);
    item.put("lastReplyId", key(3)).put("lastReplyAt", 2).put("status", "working").put("statusAt", 3);
    SupportChanges restored = new SupportChanges(changes.saved());
    restored.accept("put", update("/", snapshot), sink);
    assertEquals(2, events.size());
    restored.accept("patch", update("/" + ticket, new JSONObject().put("statusAt", 4)), sink);
    assertEquals(2, events.size());
    restored.accept("patch", update("/" + ticket,
        new JSONObject().put("status", "resolved").put("statusAt", 5)), sink);
    assertEquals(3, events.size());
  }

  @Test public void initiallyEmptyInboxStillNotifiesWhenAFirstReplyArrives() throws Exception {
    SupportChanges changes = new SupportChanges(new JSONObject());
    List<String> events = new ArrayList<>();
    SupportChanges.Sink sink = (ticket, item, reply) -> events.add(ticket);
    changes.accept("put", update("/", new JSONObject()), sink);
    changes.accept("patch", update("/", new JSONObject().put(key(1),
        new JSONObject().put("lastReplyId", key(2)))), sink);
    assertEquals(java.util.Collections.singletonList(key(1)), events);
  }

  @Test public void latestActivitySortsUpdatedOldRequestsBeforeNewerCreations() throws Exception {
    JSONObject items = new JSONObject().put(key(1), new JSONObject().put("createdAt", 1).put("lastAt", 9))
        .put(key(2), new JSONObject().put("createdAt", 2).put("lastAt", 5));
    assertEquals(java.util.Arrays.asList(key(1), key(2)), SupportInbox.newest(items));
    items.getJSONObject(key(2)).put("statusAt", 10);
    assertEquals(java.util.Arrays.asList(key(2), key(1)), SupportInbox.newest(items));
  }

  private static FirebaseRest listing(JSONObject items) throws Exception {
    Backend identity = new Backend();
    return new FirebaseRest(new FirebaseRest.Config("{\"apiKey\":\"abcdefghijklmnopqrstuv\",\"databaseUrl\":\"https://demo.firebaseio.com\"}"),
        identity, (address, method, body, type) -> {
          if (!address.contains("/supportIndex.json")) return identity.request(address, method, body, type);
          String query = URLDecoder.decode(URI.create(address).getRawQuery(), "UTF-8");
          Long before = null, equal = null;
          for (String part : query.split("&")) {
            if (part.startsWith("endAt=")) before = Long.valueOf(part.substring(6));
            if (part.startsWith("equalTo=")) equal = Long.valueOf(part.substring(8));
          }
          JSONObject filtered = new JSONObject();
          int count = 0;
          for (String key : SupportInbox.newest(items)) {
            long time = items.getJSONObject(key).getLong("lastAt");
            if ((before != null && time > before) || (equal != null && time != equal)) continue;
            if (equal == null && count >= 100) break;
            filtered.put(key, items.get(key));
            count++;
          }
          return filtered.toString();
        });
  }

  @Test public void resolvedRequestsDoNotHideOlderOpenRequestsAndRemainInHistory() throws Exception {
    JSONObject items = new JSONObject(), states = new JSONObject();
    for (int index = 1; index <= 121; index++) {
      items.put(key(index), new JSONObject().put("lastAt", index).put("createdAt", index));
      if (index > 21) states.put(key(index), new JSONObject().put("state", "resolved"));
    }
    SupportInbox.Page inbox = SupportInbox.load(listing(items), states, false, null);
    assertEquals(20, inbox.ids.size());
    assertEquals(key(21), inbox.ids.get(0));
    assertNotNull(inbox.next);
    SupportInbox.Page next = SupportInbox.load(listing(items), states, false, inbox.next);
    assertEquals(java.util.Collections.singletonList(key(1)), next.ids);
    SupportInbox.Page history = SupportInbox.load(listing(items), states, true, null);
    assertEquals(key(121), history.ids.get(0));
  }

  @Test public void paginationDoesNotLoseRequestsWithTheSameUpdateTimestamp() throws Exception {
    JSONObject items = new JSONObject();
    for (int index = 1; index <= 150; index++) items.put(key(index),
        new JSONObject().put("lastAt", 500).put("createdAt", 1));
    SupportInbox.Page page = SupportInbox.load(listing(items), new JSONObject(), true, "500:" + key(51));
    assertEquals(key(50), page.ids.get(0));
    assertEquals(key(31), page.ids.get(19));
    assertNotNull(page.next);
  }

  @Test public void statusAndReplyPublicationPreserveInboxHeaders() throws Exception {
    Backend backend = new Backend();
    JSONObject draft = draft();
    String ticket = draft.getString("ticket");
    SupportProtocol.publishUser(backend.service(), draft);
    SupportProtocol.changeState(backend.service(), ticket, "resolved", "Quản trị viên");
    String path = "supportUsers/alice/" + ticket;
    assertEquals("resolved", backend.records.get(path).getString("status"));
    assertEquals(draft.getString("subject"), backend.records.get(path).getString("subject"));
    String reply = SupportProtocol.id();
    SupportProtocol.publishReply(backend.service(), ticket, reply, "Quản trị viên", "Đã sửa lỗi");
    assertEquals(reply, backend.records.get(path).getString("lastReplyId"));
    assertEquals("resolved", backend.records.get(path).getString("status"));
    int writes = backend.messageWrites;
    SupportProtocol.publishReply(backend.service(), ticket, reply, "Quản trị viên", "Đã sửa lỗi");
    assertEquals(writes, backend.messageWrites);
  }

}
