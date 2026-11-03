package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.RecoveryProtocol;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.Test;

public final class RecoveryProtocolTest {
  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  @Test public void reinstallDoesNotDependOnThePreviousInstallationKey() throws Exception {
    String first = RecoveryProtocol.request("io.github.aaron_gh.meridian", "123456789abcdef0");
    KeyPair issuer = keyPair();
    String license = RecoveryProtocol.issue(first, issuer.getPrivate(), 1700000000L);
    keyPair();
    String reinstalled = RecoveryProtocol.request("io.github.aaron_gh.meridian", "123456789ABCDEF0");
    assertEquals(first, reinstalled);
    assertEquals(RecoveryProtocol.request("meridian", "123"),
        RecoveryProtocol.request("meridian", "0000000000000123"));
    assertTrue(RecoveryProtocol.verify(reinstalled, license, issuer.getPublic()));
  }

  @Test public void differentDeviceOrApplicationCannotUseTheRecord() throws Exception {
    KeyPair issuer = keyPair();
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    String license = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    assertFalse(RecoveryProtocol.verify(RecoveryProtocol.request("meridian", "223456789abcdef0"), license, issuer.getPublic()));
    assertFalse(RecoveryProtocol.verify(RecoveryProtocol.request("another.app", "123456789abcdef0"), license, issuer.getPublic()));
  }

  @Test public void changedPayloadSignatureAndUnknownIssuerAreRejected() throws Exception {
    KeyPair issuer = keyPair();
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    String license = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    String[] pieces = license.split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(pieces[1]), StandardCharsets.US_ASCII);
    String changed = "SFLIC2." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(payload.replace("1700000000", "1800000000").getBytes(StandardCharsets.US_ASCII))
        + "." + pieces[2];
    assertFalse(RecoveryProtocol.verify(request, changed, issuer.getPublic()));
    assertFalse(RecoveryProtocol.verify(request, license, keyPair().getPublic()));
    assertFalse(RecoveryProtocol.verify(request, license + ".extra", issuer.getPublic()));
    assertFalse(RecoveryProtocol.verify(request, "SFLIC1." + license.substring(7), issuer.getPublic()));
  }

  @Test public void pinnedPublicKeyRoundTripsAndMalformedRequestsAreRejected() throws Exception {
    KeyPair issuer = keyPair();
    assertArrayEquals(issuer.getPublic().getEncoded(),
        RecoveryProtocol.publicKey(RecoveryProtocol.publicKey(issuer.getPublic())).getEncoded());
    assertThrows(IllegalArgumentException.class, () -> RecoveryProtocol.deviceId("SFREQ2.../../profile"));
    assertThrows(IllegalArgumentException.class, () -> RecoveryProtocol.request("meridian", null));
    assertThrows(IllegalArgumentException.class, () -> RecoveryProtocol.request("meridian", "0000000000000000"));
    assertThrows(IllegalArgumentException.class, () -> RecoveryProtocol.publicKey("SFPUB1.not-valid"));
  }
}
