package io.meridian.licensing;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class EndpointCodec {
  private EndpointCodec() {}

  public static String decode(String encoded, int seed) {
    if (seed < 0 || seed > 255 || encoded.length() > 32768) {
      throw new IllegalArgumentException("Cấu hình địa chỉ không hợp lệ.");
    }
    byte[] bytes = Base64.getUrlDecoder().decode(encoded);
    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (bytes[i] ^ ((seed + i * 31) & 255));
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
