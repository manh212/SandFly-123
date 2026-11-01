package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;

public final class ApkSignatureVerifierTest {
  @Test
  public void acceptsOnlyTheMatchingCertificateDigest() throws Exception {
    byte[] officialCertificate = "meridian signing certificate".getBytes(StandardCharsets.UTF_8);
    byte[] officialDigest = MessageDigest.getInstance("SHA-256").digest(officialCertificate);
    assertTrue(ApkSignatureVerifier.matchesCertificateDigest(officialCertificate, officialDigest));
  }

  @Test
  public void rejectsModifiedOrDifferentCertificates() throws Exception {
    byte[] officialCertificate = "meridian signing certificate".getBytes(StandardCharsets.UTF_8);
    byte[] officialDigest = MessageDigest.getInstance("SHA-256").digest(officialCertificate);
    byte[] modifiedCertificate = "modified signing certificate".getBytes(StandardCharsets.UTF_8);
    assertFalse(ApkSignatureVerifier.matchesCertificateDigest(modifiedCertificate, officialDigest));
  }

  @Test
  public void rejectsMissingOrMalformedCertificateData() throws Exception {
    byte[] officialCertificate = "meridian signing certificate".getBytes(StandardCharsets.UTF_8);
    byte[] officialDigest = MessageDigest.getInstance("SHA-256").digest(officialCertificate);
    assertFalse(ApkSignatureVerifier.matchesCertificateDigest(null, officialDigest));
    assertFalse(ApkSignatureVerifier.matchesCertificateDigest(new byte[0], officialDigest));
    assertFalse(ApkSignatureVerifier.matchesCertificateDigest(officialCertificate, null));
    assertFalse(ApkSignatureVerifier.matchesCertificateDigest(officialCertificate, new byte[31]));
  }
}
