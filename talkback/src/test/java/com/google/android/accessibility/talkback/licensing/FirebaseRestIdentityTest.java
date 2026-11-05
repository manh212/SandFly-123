package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.FirebaseRest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class FirebaseRestIdentityTest {
  private static final class Storage implements FirebaseRest.Storage {
    String value = "{}";
    @Override public synchronized String read() { return value; }
    @Override public synchronized void write(String next) { value = next; }
  }

  private FirebaseRest.Config config() throws Exception {
    return new FirebaseRest.Config("{\"apiKey\":\"abcdefghijklmnopqrstuv\",\"databaseUrl\":\"https://demo.firebaseio.com\"}");
  }

  @Test public void enrollmentAndSupportStartingTogetherShareOnePersistentIdentity() throws Exception {
    Storage storage = new Storage(); AtomicInteger registrations = new AtomicInteger();
    FirebaseRest.Transport network = (address, method, body, contentType) -> {
      if (address.contains("accounts:signUp")) {
        int next = registrations.incrementAndGet();
        return "{\"localId\":\"uid" + next + "\",\"refreshToken\":\"refresh\",\"idToken\":\"token\"}";
      }
      return "{\"user_id\":\"uid1\",\"refresh_token\":\"rotated\",\"id_token\":\"token\"}";
    };
    FirebaseRest first = new FirebaseRest(config(), storage, network), second = new FirebaseRest(config(), storage, network);
    ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
    try {
      Future<String> one = executor.submit(() -> { start.await(); return first.uid(); });
      Future<String> two = executor.submit(() -> { start.await(); return second.uid(); });
      start.countDown(); assertEquals(one.get(), two.get()); assertEquals("uid1", first.uid());
      assertEquals(1, registrations.get()); assertTrue(storage.read().contains("refresh"));
    } finally { executor.shutdownNow(); }
  }

  @Test public void refreshCannotChangeTheInboxIdentity() throws Exception {
    Storage storage = new Storage(); storage.value = "{\"uid\":\"alice\",\"refresh\":\"old\"}";
    FirebaseRest service = new FirebaseRest(config(), storage, (a, m, b, c) ->
        "{\"user_id\":\"bob\",\"refresh_token\":\"new\",\"id_token\":\"token\"}");
    assertThrows(IllegalStateException.class, service::uid);
    assertTrue(storage.read().contains("alice"));
  }

  @Test public void separateClientsReuseTheSameUnexpiredSessionAndKeepScopesIsolated() throws Exception {
    String[] saved = {"{}"};
    String scope = java.util.UUID.randomUUID().toString();
    java.util.function.Supplier<FirebaseRest.Storage> storage = () -> new FirebaseRest.Storage() {
      @Override public String sessionKey() { return scope; }
      @Override public String read() { return saved[0]; }
      @Override public void write(String value) { saved[0] = value; }
    };
    AtomicInteger authentications = new AtomicInteger();
    FirebaseRest.Transport network = (a, m, b, c) -> {
      authentications.incrementAndGet();
      return "{\"localId\":\"alice\",\"refreshToken\":\"refresh\",\"idToken\":\"token\",\"expiresIn\":3600}";
    };
    assertEquals("alice", new FirebaseRest(config(), storage.get(), network).uid());
    assertEquals("alice", new FirebaseRest(config(), storage.get(), network).uid());
    assertEquals(1, authentications.get());
    FirebaseRest other = new FirebaseRest(config(), new Storage(), (a, m, b, c) ->
        "{\"localId\":\"bob\",\"refreshToken\":\"other\",\"idToken\":\"other\"}");
    assertEquals("bob", other.uid());
  }

  @Test public void expiredAuthenticationRetriesOnceAndPermissionFailuresDoNotRefresh() throws Exception {
    AtomicInteger auth = new AtomicInteger(), reads = new AtomicInteger();
    FirebaseRest service = new FirebaseRest(config(), new Storage(), (a, m, b, c) -> {
      if (a.contains("googleapis.com")) {
        int number = auth.incrementAndGet();
        return number == 1
            ? "{\"localId\":\"alice\",\"refreshToken\":\"refresh\",\"idToken\":\"one\"}"
            : "{\"user_id\":\"alice\",\"refresh_token\":\"refresh\",\"id_token\":\"two\"}";
      }
      if (reads.incrementAndGet() == 1) throw new FirebaseRest.AuthenticationExpired();
      return "{}";
    });
    service.get("devices");
    assertEquals(2, auth.get()); assertEquals(2, reads.get());
    AtomicInteger deniedCalls = new AtomicInteger();
    FirebaseRest denied = new FirebaseRest(config(), new Storage(), (a, m, b, c) -> {
      if (a.contains("googleapis.com")) return "{\"localId\":\"alice\",\"refreshToken\":\"r\",\"idToken\":\"t\"}";
      deniedCalls.incrementAndGet(); throw new FirebaseRest.PermissionDenied();
    });
    assertThrows(FirebaseRest.PermissionDenied.class, () -> denied.get("devices"));
    assertEquals(1, deniedCalls.get());
  }

  @Test public void boundedStateQueriesAndAtomicUpdatesValidateEveryPathBeforeSending() throws Exception {
    List<String> calls = new ArrayList<>();
    Storage storage = new Storage(); storage.value = "{\"uid\":\"alice\",\"refresh\":\"old\"}";
    FirebaseRest service = new FirebaseRest(config(), storage, (a, m, b, c) -> {
      calls.add(m + " " + a);
      if (a.contains("googleapis.com")) return "{\"user_id\":\"alice\",\"refresh_token\":\"r\",\"id_token\":\"t\"}";
      return "null";
    });
    service.keyRange("supportStates", "1700000000000-a", "1700000000001-b", 1000);
    assertTrue(calls.get(calls.size() - 1).contains("limitToFirst=1000"));
    assertTrue(calls.get(calls.size() - 1).contains("startAt=%22"));
    org.json.JSONObject changes = new org.json.JSONObject().put("licenseEvents/" + "a".repeat(64), new org.json.JSONObject());
    service.update(changes);
    assertTrue(calls.get(calls.size() - 1).startsWith("PATCH https://demo.firebaseio.com/.json?"));
    int before = calls.size();
    changes.put("../admins/alice", true);
    assertThrows(IllegalArgumentException.class, () -> service.update(changes));
    assertThrows(IllegalArgumentException.class, () -> service.keyRange("supportStates", "z", "a", 1000));
    assertEquals(before, calls.size());
  }

  @Test public void roleFlagsAndQueriesAreBoundedEncodedAndRejectUnapprovedPaths() throws Exception {
    Storage storage = new Storage(); storage.value = "{\"uid\":\"alice\",\"refresh\":\"old\"}";
    List<String> addresses = Collections.synchronizedList(new ArrayList<>());
    FirebaseRest service = new FirebaseRest(config(), storage, (a, m, b, c) -> {
      addresses.add(a);
      if (a.contains("securetoken")) return "{\"user_id\":\"alice\",\"refresh_token\":\"new\",\"id_token\":\"token\"}";
      return a.contains("admins/") ? "true" : "null";
    });
    assertTrue(service.flag("admins/alice"));
    assertEquals(0, service.recent("supportUsers/alice", "$key", 21, "1700000000000-abcdef").length());
    String query = addresses.get(addresses.size() - 1);
    assertTrue(query.contains("orderBy=%22%24key%22")); assertTrue(query.contains("limitToLast=21"));
    assertTrue(query.contains("endAt=%221700000000000-abcdef%22"));
    service.get("supportLogChunks/ticket/message/chunk0");
    service.get("supportAttachments/ticket/message");
    int calls = addresses.size();
    assertThrows(IllegalArgumentException.class, () -> service.get("supportIndex/../admins"));
    assertThrows(IllegalArgumentException.class, () -> service.get("supportUsers/alice/ticket/owner"));
    assertThrows(IllegalArgumentException.class, () -> service.recent("supportIndex", "owner", 20, null));
    assertThrows(IllegalArgumentException.class, () -> service.recent("supportIndex", "$key", 101, null));
    assertEquals(calls, addresses.size());
  }

  @Test public void cancellingADownloadCancelsOnlyItsOwnTransport() throws Exception {
    AtomicInteger cancellations = new AtomicInteger();
    FirebaseRest.Transport transport = new FirebaseRest.Transport() {
      @Override public String request(String address, String method, String body, String contentType) { return "null"; }
      @Override public void cancel() { cancellations.incrementAndGet(); }
    };
    FirebaseRest service = new FirebaseRest(config(), new Storage(), transport);
    FirebaseRest other = new FirebaseRest(config(), new Storage(), (a, b, c, d) ->
        "{\"localId\":\"other\",\"refreshToken\":\"refresh\",\"idToken\":\"token\"}");
    service.cancelRequests(); assertEquals(1, cancellations.get());
    assertEquals("other", other.uid()); assertEquals(1, cancellations.get());
  }
}
