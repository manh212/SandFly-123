package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.Test;

public final class LicenseProtocolTest {
  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String token(KeyPair issuer, KeyPair device) throws Exception {
    String fingerprint = LicenseProtocol.fingerprint(device.getPublic());
    byte[] payload = ("1|" + fingerprint + "|123456789|dGVzdA")
        .getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(issuer.getPrivate());
    signer.update(payload);
    return "SFLIC1." + encode(payload) + "." + encode(signer.sign());
  }

  @Test
  public void previouslyIssuedTokenVerifiesWithoutMigration() throws Exception {
    KeyPair issuer = keyPair();
    KeyPair device = keyPair();
    String request = LicenseProtocol.request(device.getPublic());
    assertTrue(LicenseProtocol.verify(request, token(issuer, device), issuer.getPublic()));
  }

  @Test
  public void tokenCannotBeReusedWithAnotherDeviceKey() throws Exception {
    KeyPair issuer = keyPair();
    KeyPair originalDevice = keyPair();
    KeyPair anotherDevice = keyPair();
    assertFalse(
        LicenseProtocol.verify(
            LicenseProtocol.request(anotherDevice.getPublic()),
            token(issuer, originalDevice),
            issuer.getPublic()));
  }

  @Test
  public void modifiedTokenOrWrongIssuerIsRejected() throws Exception {
    KeyPair issuer = keyPair();
    KeyPair otherIssuer = keyPair();
    KeyPair device = keyPair();
    String token = token(issuer, device);
    String[] parts = token.split("\\.");
    byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
    payload[0] = (byte) (payload[0] == '1' ? '2' : '1');
    String modified = "SFLIC1." + encode(payload) + "." + parts[2];
    assertFalse(LicenseProtocol.verify(LicenseProtocol.request(device.getPublic()), modified, issuer.getPublic()));
    assertFalse(LicenseProtocol.verify(LicenseProtocol.request(device.getPublic()), token, otherIssuer.getPublic()));
  }

  @Test
  public void deviceKeyOwnershipRequiresMatchingPrivateKey() throws Exception {
    KeyPair registeredDevice = keyPair();
    KeyPair otherDevice = keyPair();
    assertTrue(
        LicenseProtocol.provesPossession(
            registeredDevice.getPrivate(), registeredDevice.getPublic()));
    assertFalse(
        LicenseProtocol.provesPossession(
            otherDevice.getPrivate(), registeredDevice.getPublic()));
  }
}
