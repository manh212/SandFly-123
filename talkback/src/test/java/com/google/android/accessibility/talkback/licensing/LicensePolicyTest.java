package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import io.meridian.licensing.LicenseClock;
import io.meridian.licensing.LicensePolicy;
import io.meridian.licensing.RecoveryProtocol;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public final class LicensePolicyTest {
  private static final String REQUEST = "SFREQ2." + String.join("", Collections.nCopies(64, "a"));
  private static KeyPair key() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }
  private static String sign(LicensePolicy policy, KeyPair issuer) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(issuer.getPrivate());
    return policy.sign(signer);
  }
  private static LicensePolicy policy(String state, long expires) {
    return new LicensePolicy(REQUEST, 1, 1000, expires, state,
        expires == 0 ? "standard" : "trial", "basic", Collections.singleton("ocr"));
  }

  @Test public void signedPolicySurvivesReinstallButIsBoundToDeviceAndIssuer() throws Exception {
    KeyPair issuer = key();
    String token = sign(policy("active", 2000), issuer);
    LicensePolicy decoded = LicensePolicy.verify(REQUEST, token, issuer.getPublic());
    assertEquals(1, decoded.revision);
    assertEquals("trial", decoded.kind);
    assertEquals("basic", decoded.packageId);
    assertTrue(decoded.allows("ocr", 1500));
    assertFalse(decoded.allows("ai", 1500));
    assertThrows(Exception.class, () -> LicensePolicy.verify(REQUEST, token, key().getPublic()));
    assertThrows(Exception.class, () -> LicensePolicy.verify(REQUEST.replace('a', 'b'), token, issuer.getPublic()));
    String changed = token.substring(0, 15) + (token.charAt(15) == 'A' ? "B" : "A") + token.substring(16);
    assertThrows(Exception.class, () -> LicensePolicy.verify(REQUEST, changed, issuer.getPublic()));
  }

  @Test public void expiryIsExclusiveAndIndependentOfVerificationLease() {
    LicensePolicy trial = policy("active", 2000);
    assertTrue(trial.active(1999));
    assertFalse(trial.active(2000));
    assertFalse(trial.active(2001));
    assertTrue(policy("active", 0).active(10000000000L));
    assertFalse(trial.active(699));
  }

  @Test public void signedTwoDayLicenseExpiresAtItsOriginalAbsoluteDeadline() throws Exception {
    long issued = 1_700_000_000L;
    long expires = issued + 2 * 86_400L;
    LicensePolicy initial = new LicensePolicy(REQUEST, 5, issued, expires,
        "active", "trial", "basic", Collections.singleton("ocr"));
    KeyPair issuer = key();
    LicensePolicy received = LicensePolicy.verify(REQUEST, sign(initial, issuer), issuer.getPublic());
    assertEquals(expires, received.expiresAt);
    assertTrue(received.active(expires - 1));
    assertFalse(received.active(expires));
    assertFalse(received.allows("ocr", expires + 1000));
  }

  @Test public void suspendedAndRevokedPoliciesDenyAllFeaturesEvenBeforeExpiry() {
    for (String state : new String[]{"suspended", "revoked"}) {
      LicensePolicy blocked = policy(state, 2000);
      assertFalse(blocked.active(1500));
      assertFalse(blocked.allows("ocr", 1500));
      assertFalse(blocked.active(3000));
    }
    LicensePolicy suspended = policy("suspended", 2000);
    LicensePolicy resumed = new LicensePolicy(REQUEST, 2, suspended.issuedAt, suspended.expiresAt,
        "active", suspended.kind, suspended.packageId, suspended.features);
    assertEquals(2000, resumed.expiresAt);
    assertFalse(resumed.active(2000));
  }

  @Test public void packageTemplatesCannotChangeAlreadyIssuedFeatureSnapshots() throws Exception {
    Set<String> template = new HashSet<>(Collections.singleton("ocr"));
    LicensePolicy before = new LicensePolicy(REQUEST, 1, 1000, 0, "active", "standard", "custom", template);
    template.add("ai");
    assertFalse(before.features.contains("ai"));
    assertThrows(UnsupportedOperationException.class, () -> before.features.add("ai"));
    KeyPair issuer = key();
    assertFalse(LicensePolicy.verify(REQUEST, sign(before, issuer), issuer.getPublic()).features.contains("ai"));
  }

  @Test public void invalidTrialDurationStateAndUnknownFeaturesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> new LicensePolicy(REQUEST, 1, 1000, 0,
        "active", "trial", "full", LicensePolicy.FEATURES));
    assertThrows(IllegalArgumentException.class, () -> policy("other", 2000));
    assertThrows(IllegalArgumentException.class, () -> policy("active", 1000));
    assertThrows(IllegalArgumentException.class, () -> new LicensePolicy(REQUEST, 0, 1000, 0,
        "active", "standard", "full", LicensePolicy.FEATURES));
    assertThrows(IllegalArgumentException.class, () -> new LicensePolicy(REQUEST, 1, 1000, 0,
        "active", "standard", "full", Collections.singleton("unknown")));
  }

  @Test public void elapsedClockCannotBeExtendedByMovingTheWallClockBack() {
    assertEquals(1600, LicenseClock.now(900, 1500, 1000, 700, 100, true));
    assertEquals(Long.MAX_VALUE, LicenseClock.now(900, 1500, 1000, 20, 100, false));
    assertEquals(1510, LicenseClock.now(1510, 1500, 1000, 20, 100, false));
  }

  @Test public void legacyPermanentLicensesRemainVerifiable() throws Exception {
    KeyPair issuer = key();
    assertTrue(RecoveryProtocol.verify(REQUEST, RecoveryProtocol.issue(REQUEST, issuer.getPrivate(), 1000), issuer.getPublic()));
  }

  @Test public void eachFeatureRequiresAnExplicitSignedGrant() throws Exception {
    KeyPair issuer = key();
    for (String selected : LicensePolicy.FEATURES) {
      LicensePolicy single = new LicensePolicy(REQUEST, 1, 1000, 0, "active", "standard",
          "custom", "Gói cá nhân", Collections.singleton(selected));
      LicensePolicy restored = LicensePolicy.verify(REQUEST, sign(single, issuer), issuer.getPublic());
      assertEquals("Gói cá nhân", restored.packageName);
      for (String feature : LicensePolicy.FEATURES) assertEquals(selected.equals(feature), restored.allows(feature, 1500));
    }
  }
}
