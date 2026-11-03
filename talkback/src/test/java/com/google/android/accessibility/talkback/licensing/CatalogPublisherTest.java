package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.CatalogPublisher;
import io.meridian.licensing.RecoveryProtocol;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

public final class CatalogPublisherTest {
  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static final class FakeClient implements CatalogPublisher.Client {
    final Map<String, CatalogPublisher.RemoteFile> files = new HashMap<>();
    int writes;
    int conflicts;
    boolean offline;
    @Override public CatalogPublisher.RemoteFile get(String path) throws Exception {
      if (offline) throw new java.io.IOException("offline");
      return files.get(path);
    }
    @Override public void put(String path, String content, String sha) throws Exception {
      if (conflicts-- > 0) throw new CatalogPublisher.SyncException("conflict", true, true);
      CatalogPublisher.RemoteFile before = files.get(path);
      assertEquals(before == null ? null : before.sha, sha);
      files.put(path, new CatalogPublisher.RemoteFile(content, "sha-" + ++writes));
    }
  }

  @Test public void bootstrapPinsTheKeyAndNeverReplacesAnotherIssuersKey() throws Exception {
    FakeClient client = new FakeClient();
    client.files.put(CatalogPublisher.PROFILE_PATH,
        new CatalogPublisher.RemoteFile("{\"format\":2,\"publicKey\":\"\"}", "initial"));
    CatalogPublisher publisher = new CatalogPublisher(client);
    String publicKey = RecoveryProtocol.publicKey(keyPair().getPublic());
    publisher.register(publicKey);
    assertEquals(publicKey, new JSONObject(client.files.get(CatalogPublisher.PROFILE_PATH).content).getString("publicKey"));
    assertThrows(CatalogPublisher.SyncException.class,
        () -> publisher.register(RecoveryProtocol.publicKey(keyPair().getPublic())));
    assertEquals(1, client.writes);
  }

  @Test public void retriesConflictsAndPreservesOtherDevicesWithoutPublishingNames() throws Exception {
    FakeClient client = new FakeClient();
    KeyPair issuer = keyPair();
    String publicKey = RecoveryProtocol.publicKey(issuer.getPublic());
    CatalogPublisher publisher = new CatalogPublisher(client);
    publisher.register(publicKey);
    String first = RecoveryProtocol.request("meridian", "123456789abcdef0");
    String second = RecoveryProtocol.request("meridian", "223456789abcdef0");
    String firstLicense = RecoveryProtocol.issue(first, issuer.getPrivate(), 1700000000L);
    publisher.publish(first, firstLicense, publicKey);
    client.conflicts = 1;
    String secondLicense = RecoveryProtocol.issue(second, issuer.getPrivate(), 1700000001L);
    publisher.publish(second, secondLicense, publicKey);
    JSONObject record = new JSONObject(client.files.get(RecoveryProtocol.recordPath(second)).content);
    assertEquals(2, record.length());
    assertEquals(secondLicense, record.getString("value"));
    assertEquals(firstLicense,
        new JSONObject(client.files.get(RecoveryProtocol.recordPath(first)).content).getString("value"));
    int writes = client.writes;
    publisher.publish(second, secondLicense, publicKey);
    assertEquals(writes, client.writes);
  }

  @Test public void unsignedOrWrongDeviceDataNeverReachesGitHub() throws Exception {
    FakeClient client = new FakeClient();
    KeyPair issuer = keyPair();
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    CatalogPublisher publisher = new CatalogPublisher(client);
    assertThrows(CatalogPublisher.SyncException.class,
        () -> publisher.publish(request, "SFLIC2.forged.data", RecoveryProtocol.publicKey(issuer.getPublic())));
    assertTrue(client.files.isEmpty());
  }

  @Test public void offlineAndPersistentConflictsAreReportedForRetry() throws Exception {
    FakeClient client = new FakeClient();
    CatalogPublisher publisher = new CatalogPublisher(client);
    String publicKey = RecoveryProtocol.publicKey(keyPair().getPublic());
    client.offline = true;
    assertThrows(java.io.IOException.class, () -> publisher.register(publicKey));
    client.offline = false;
    client.conflicts = 3;
    CatalogPublisher.SyncException error = assertThrows(CatalogPublisher.SyncException.class,
        () -> publisher.register(publicKey));
    assertTrue(error.retryable);
    assertTrue(client.files.isEmpty());
  }
}
