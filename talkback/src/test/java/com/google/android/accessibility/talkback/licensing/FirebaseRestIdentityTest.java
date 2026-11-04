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
      assertEquals(1, registrations.get()); assertTrue(storage.read().contains("rotated"));
    } finally { executor.shutdownNow(); }
  }

  @Test public void refreshCannotChangeTheInboxIdentity() throws Exception {
    Storage storage = new Storage(); storage.value = "{\"uid\":\"alice\",\"refresh\":\"old\"}";
    FirebaseRest service = new FirebaseRest(config(), storage, (a, m, b, c) ->
        "{\"user_id\":\"bob\",\"refresh_token\":\"new\",\"id_token\":\"token\"}");
    assertThrows(IllegalStateException.class, service::uid);
    assertTrue(storage.read().contains("alice"));
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
    int calls = addresses.size();
    assertThrows(IllegalArgumentException.class, () -> service.get("supportIndex/../admins"));
    assertThrows(IllegalArgumentException.class, () -> service.get("supportUsers/alice/ticket/owner"));
    assertThrows(IllegalArgumentException.class, () -> service.recent("supportIndex", "owner", 20, null));
    assertThrows(IllegalArgumentException.class, () -> service.recent("supportIndex", "$key", 101, null));
    assertEquals(calls, addresses.size());
  }
}
