package io.meridian.licensing;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public final class LicensePolicy {
  public static final Set<String> FEATURES = Collections.unmodifiableSet(
      new TreeSet<>(Arrays.asList("ai", "dictation", "ocr", "scripts", "translation")));
  public final String device, state, kind, packageId, packageName;
  public final long revision, issuedAt, expiresAt;
  public final Set<String> features;

  public LicensePolicy(String request, long revision, long issuedAt, long expiresAt,
      String state, String kind, String packageId, Set<String> features) {
    this(request, revision, issuedAt, expiresAt, state, kind, packageId, packageId, features);
  }

  public LicensePolicy(String request, long revision, long issuedAt, long expiresAt,
      String state, String kind, String packageId, String packageName, Set<String> features) {
    device = RecoveryProtocol.deviceId(request);
    if (revision < 1 || revision > 999999999999999L || issuedAt < 0
        || issuedAt > 999999999999L || expiresAt < 0 || expiresAt > 999999999999L
        || (expiresAt != 0 && expiresAt <= issuedAt)
        || !Arrays.asList("active", "suspended", "revoked").contains(state)
        || !Arrays.asList("standard", "trial").contains(kind)
        || (kind.equals("trial") && expiresAt == 0)
        || packageId == null || !packageId.matches("[a-zA-Z0-9_-]{1,64}")
        || packageName == null || packageName.trim().isEmpty() || packageName.length() > 80
        || packageName.chars().anyMatch(Character::isISOControl)
        || features == null || !FEATURES.containsAll(features)) {
      throw new IllegalArgumentException("Nội dung giấy phép không hợp lệ.");
    }
    this.revision = revision;
    this.issuedAt = issuedAt;
    this.expiresAt = expiresAt;
    this.state = state;
    this.kind = kind;
    this.packageId = packageId;
    this.packageName = packageName;
    this.features = Collections.unmodifiableSet(new TreeSet<>(features));
  }

  private String payload() {
    return "3|meridian-policy|" + device + "|" + revision + "|" + issuedAt + "|"
        + expiresAt + "|" + state + "|" + kind + "|" + packageId + "|"
        + String.join(",", features) + "|" + encode(packageName.getBytes(StandardCharsets.UTF_8));
  }

  public String sign(Signature signer) throws Exception {
    byte[] payload = payload().getBytes(StandardCharsets.US_ASCII);
    signer.update(payload);
    return "SFLIC3." + encode(payload) + "." + encode(signer.sign());
  }

  public static LicensePolicy verify(String request, String token, PublicKey issuer) throws Exception {
    if (token == null || token.length() > 2048 || !token.startsWith("SFLIC3.")) {
      throw new IllegalArgumentException("Giấy phép sai định dạng.");
    }
    String[] parts = token.substring(7).split("\\.", -1);
    if (parts.length != 2) throw new IllegalArgumentException("Giấy phép sai định dạng.");
    byte[] payload = decode(parts[0], 1024);
    Signature verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(issuer);
    verifier.update(payload);
    if (!verifier.verify(decode(parts[1], 80))) throw new IllegalArgumentException("Sai chữ ký giấy phép.");
    String raw = new String(payload, StandardCharsets.US_ASCII);
    String[] f = raw.split("\\|", -1);
    if (f.length != 11 || !f[0].equals("3") || !f[1].equals("meridian-policy")
        || !f[2].equals(RecoveryProtocol.deviceId(request))) {
      throw new IllegalArgumentException("Giấy phép không khớp thiết bị.");
    }
    Set<String> features = new TreeSet<>();
    if (!f[9].isEmpty()) features.addAll(Arrays.asList(f[9].split(",", -1)));
    LicensePolicy result = new LicensePolicy(request, number(f[3]), number(f[4]), number(f[5]),
        f[6], f[7], f[8], new String(decode(f[10], 320), StandardCharsets.UTF_8), features);
    if (!result.payload().equals(raw)) throw new IllegalArgumentException("Giấy phép sai định dạng.");
    return result;
  }

  public boolean allows(String feature, long nowSeconds) {
    return active(nowSeconds) && features.contains(feature);
  }

  public boolean active(long nowSeconds) {
    return state.equals("active") && (expiresAt == 0
        || (nowSeconds >= issuedAt - 300 && nowSeconds < expiresAt));
  }

  private static long number(String value) {
    if (!value.matches("0|[1-9][0-9]{0,14}")) throw new IllegalArgumentException("Sai thời điểm giấy phép.");
    return Long.parseLong(value);
  }

  private static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] decode(String value, int max) {
    if (value.length() > max * 2 || !value.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Giấy phép sai định dạng.");
    }
    byte[] bytes = Base64.getUrlDecoder().decode(value);
    if (bytes.length > max || !encode(bytes).equals(value)) throw new IllegalArgumentException("Giấy phép sai định dạng.");
    return bytes;
  }
}
