package com.google.android.accessibility.talkback.licensing;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Offline activation protocol. Never bundle the issuer's private key. */
public final class LicenseProtocol {
  private LicenseProtocol() {}
  private static String enc(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
  private static byte[] dec(String s) {
    if (s.length() < 2 || s.length() > 6000 || !s.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Mã sai định dạng");
    return Base64.getUrlDecoder().decode(s);
  }
  private static PublicKey ec(String s, String prefix) throws Exception {
    if (!s.startsWith(prefix)) throw new IllegalArgumentException("Sai loại khóa");
    byte[] bytes = dec(s.substring(prefix.length()));
    if (bytes.length < 50 || bytes.length > 500) throw new IllegalArgumentException("Sai độ dài khóa");
    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes));
    if (!(key instanceof ECPublicKey) || ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize() != 256)
      throw new IllegalArgumentException("Khóa phải là EC P-256");
    return key;
  }
  public static String request(PublicKey device) { return "SFREQ1." + enc(device.getEncoded()); }
  public static PublicKey issuer(String pub) throws Exception { return ec(pub.trim(), "SFPUB1."); }
  public static String fingerprint(PublicKey key) throws Exception {
    return enc(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
  }
  static boolean provesPossession(PrivateKey privateKey, PublicKey publicKey) {
    try {
      byte[] challenge = new byte[32];
      new SecureRandom().nextBytes(challenge);
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(privateKey);
      signer.update(challenge);
      byte[] proof = signer.sign();
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(publicKey);
      verifier.update(challenge);
      return verifier.verify(proof);
    } catch (GeneralSecurityException | RuntimeException e) {
      return false;
    }
  }
  public static boolean verify(String request, String license, PublicKey issuer) {
    try {
      if (license == null || license.length() > 6000 || !license.startsWith("SFLIC1.")) return false;
      String[] parts = license.substring(7).split("\\.", -1);
      if (parts.length != 2) return false;
      byte[] payload = dec(parts[0]), signature = dec(parts[1]);
      if (payload.length > 512 || signature.length > 128) return false;
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(issuer);
      verifier.update(payload);
      if (!verifier.verify(signature)) return false;
      String[] values = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
      if (values.length != 4 || !"1".equals(values[0]) || !values[2].matches("[0-9]{1,15}")) return false;
      String expected = fingerprint(ec(request, "SFREQ1."));
      if (!MessageDigest.isEqual(values[1].getBytes(StandardCharsets.US_ASCII), expected.getBytes(StandardCharsets.US_ASCII))) return false;
      dec(values[3]);
      return true;
    } catch (Exception e) { return false; }
  }
}