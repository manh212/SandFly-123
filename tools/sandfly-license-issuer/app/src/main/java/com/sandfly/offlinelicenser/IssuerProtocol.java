package com.sandfly.offlinelicenser;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class IssuerProtocol {
  private IssuerProtocol() {}
  static String enc(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  static PublicKey requestKey(String request) throws Exception {
    if (request == null || !request.trim().startsWith("SFREQ1.")) throw new IllegalArgumentException("Cần mã yêu cầu SFREQ1");
    String data = request.trim().substring("SFREQ1.".length());
    if (data.length() < 60 || data.length() > 700 || !data.matches("[A-Za-z0-9_-]+"))
      throw new IllegalArgumentException("Yêu cầu kích hoạt không hợp lệ");
    byte[] encoded = Base64.getUrlDecoder().decode(data);
    PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    if (!(key instanceof ECPublicKey) || ((ECPublicKey)key).getParams().getCurve().getField().getFieldSize() != 256)
      throw new IllegalArgumentException("Khóa thiết bị phải là EC P-256");
    return key;
  }
  static String publicKey() throws Exception { return "SFPUB1." + enc(IssuerKeys.publicKey().getEncoded()); }
  static String fingerprint(PublicKey key) throws Exception {
    return enc(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
  }
  static String issue(String request, String name, PrivateKey signingKey) throws Exception {
    PublicKey device = requestKey(request);
    String person = name == null ? "" : name.trim();
    if (person.length() > 80 || person.indexOf(0) >= 0) throw new IllegalArgumentException("Tên người nhận không hợp lệ");
    byte[] nameBytes = person.isEmpty() ? new byte[]{0} : person.getBytes(StandardCharsets.UTF_8);
    String body = "1|" + fingerprint(device) + "|" + System.currentTimeMillis() / 1000L + "|" + enc(nameBytes);
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    Signature signature = Signature.getInstance("SHA256withECDSA");
    signature.initSign(signingKey);
    signature.update(payload);
    return "SFLIC1." + enc(payload) + "." + enc(signature.sign());
  }
}
