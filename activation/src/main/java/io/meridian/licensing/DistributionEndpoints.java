package io.meridian.licensing;

public final class DistributionEndpoints {
  private DistributionEndpoints() {}

  public static String owner() {
    return EndpointCodec.decode("RisH4JX31w", 43);
  }

  public static String repositoryName() {
    return EndpointCodec.decode("Eev4k-qaKFlgGaXT", 97);
  }

  public static String apiOrigin() {
    return EndpointCodec.decode("y7aVcGwEclP6yrDWcF8hHObQ_5NgQw", 163);
  }

  public static String webOrigin() {
    return EndpointCodec.decode("u4ZlQDxUooOsg31AMgSrx6yP", 211);
  }

  public static String rawOrigin() {
    return EndpointCodec.decode("Jxr53LjQJgc1B_KKpIt1SEo8CO_eqJp3WSIQ-sf8kn9C", 79);
  }

  public static String repository() { return owner() + "/" + repositoryName(); }
  public static String apiRepository() { return apiOrigin() + "/repos/" + repository(); }
  public static String webRepository() { return webOrigin() + "/" + repository(); }
  public static String catalogRoot() { return rawOrigin() + "/" + repository() + "/master/"; }
}
