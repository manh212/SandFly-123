package io.meridian.licensing;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

public final class RecoveryProtocol {
  private static final BigInteger ORDER = new BigInteger(
      "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

  private RecoveryProtocol() {}

  public static String request(String packageName, String androidId) throws Exception {
    if (packageName == null || packageName.isEmpty() || androidId == null
        || !androidId.matches("[0-9a-fA-F]{1,16}") || androidId.matches("0+")) {
      throw new IllegalArgumentException("Không đọc được định danh thiết bị ổn định");
    }
    String normalized = String.format(Locale.ROOT, "%16s", androidId.toLowerCase(Locale.ROOT)).replace(' ', '0');
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(
        ("meridian-recovery-v2\u0000" + packageName + "\u0000"
            + normalized).getBytes(StandardCharsets.UTF_8));
    StringBuilder id = new StringBuilder(64);
    for (byte value : digest) {
      id.append(Character.forDigit((value >>> 4) & 15, 16));
      id.append(Character.forDigit(value & 15, 16));
    }
    return "SFREQ2." + id;
  }

  public static String deviceId(String request) {
    if (request == null || !request.matches("SFREQ2\\.[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Cần mã yêu cầu SFREQ2 hợp lệ");
    }
    return request.substring(7);
  }

  public static String publicKey(PublicKey key) {
    return "SFPUB1." + encode(key.getEncoded());
  }

  public static PublicKey publicKey(String encoded) throws Exception {
    if (encoded == null || !encoded.startsWith("SFPUB1.")) {
      throw new IllegalArgumentException("Khóa công khai không hợp lệ");
    }
    PublicKey key = KeyFactory.getInstance("EC").generatePublic(
        new X509EncodedKeySpec(decode(encoded.substring(7), 500)));
    if (!(key instanceof ECPublicKey)) {
      throw new IllegalArgumentException("Khóa phải là EC P-256");
    }
    ECPublicKey ec = (ECPublicKey) key;
    if (ec.getParams().getCurve().getField().getFieldSize() != 256
        || !ORDER.equals(ec.getParams().getOrder()) || ec.getParams().getCofactor() != 1) {
      throw new IllegalArgumentException("Khóa phải là EC P-256");
    }
    return key;
  }

  public static String issue(String request, PrivateKey key, long issuedAt) throws Exception {
    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key);
    return issue(request, signer, issuedAt);
  }

  public static String issue(String request, Signature signer, long issuedAt) throws Exception {
    if (issuedAt < 0 || issuedAt > 999999999999999L) {
      throw new IllegalArgumentException("Thời điểm cấp phép không hợp lệ");
    }
    byte[] payload = ("2|meridian-recovery|" + deviceId(request) + "|" + issuedAt)
        .getBytes(StandardCharsets.US_ASCII);
    signer.update(payload);
    return "SFLIC2." + encode(payload) + "." + encode(signer.sign());
  }

  public static boolean verify(String request, String license, PublicKey issuer) {
    try {
      String expected = deviceId(request);
      if (license == null || license.length() > 1024 || !license.startsWith("SFLIC2.")) {
        return false;
      }
      String[] parts = license.substring(7).split("\\.", -1);
      if (parts.length != 2) return false;
      byte[] payload = decode(parts[0], 200);
      byte[] signature = decode(parts[1], 80);
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(issuer);
      verifier.update(payload);
      if (!verifier.verify(signature)) return false;
      String[] fields = new String(payload, StandardCharsets.US_ASCII).split("\\|", -1);
      return fields.length == 4 && fields[0].equals("2")
          && fields[1].equals("meridian-recovery") && fields[2].matches("[0-9a-f]{64}")
          && fields[3].matches("0|[1-9][0-9]{0,14}")
          && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
              fields[2].getBytes(StandardCharsets.US_ASCII));
    } catch (Exception e) {
      return false;
    }
  }

  public static String recordPath(String request) {
    return "docs/catalog/" + deviceId(request) + ".json";
  }

  private static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decode(String value, int maximum) {
    if (value == null || value.length() < 2 || value.length() > maximum * 2
        || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Mã sai định dạng");
    byte[] decoded = Base64.getUrlDecoder().decode(value);
    if (decoded.length > maximum || !encode(decoded).equals(value)) {
      throw new IllegalArgumentException("Mã sai định dạng");
    }
    return decoded;
  }
}
