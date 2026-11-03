package com.sandfly.offlinelicenser;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.json.JSONObject;
import org.junit.Test;

public class GitHubAppProtocolTest {
  @Test public void manifestRequestsOnlyRepositoryContentsAndNoOAuthOrWebhook() throws Exception {
    JSONObject manifest = GitHubAppProtocol.manifest("http://127.0.0.1:12345", "/random");
    assertFalse(manifest.getBoolean("public"));
    assertFalse(manifest.getBoolean("request_oauth_on_install"));
    assertFalse(manifest.getJSONObject("hook_attributes").getBoolean("active"));
    assertEquals(1, manifest.getJSONObject("default_permissions").length());
    assertEquals("write", manifest.getJSONObject("default_permissions").getString("contents"));
    assertEquals("http://127.0.0.1:12345/random/created", manifest.getString("redirect_url"));
    assertEquals("http://127.0.0.1:12345/random/installed", manifest.getString("setup_url"));
    JSONObject token = GitHubAppProtocol.tokenRequest();
    assertEquals(1, token.getJSONArray("repositories").length());
    assertEquals("SandFly-123", token.getJSONArray("repositories").getString(0));
    assertEquals(1, token.getJSONObject("permissions").length());
  }

  @Test public void returnRejectsForgedExpiredUnverifiedAndReplayedSessions() throws Exception {
    String state = GitHubAppProtocol.random();
    JSONObject pending = new JSONObject().put("state", state).put("ready", true).put("expires", 2000);
    String uri = GitHubAppProtocol.RETURN_URI + "?state=" + state;
    GitHubAppProtocol.validateReturn(uri, pending, 1000);
    for (String invalid : new String[] {uri + "&state=" + state, uri + "#fragment",
        uri.replace("github/", "elsewhere/"), uri.replace(state, GitHubAppProtocol.random()),
        uri.replace("/complete", "/complete/"), uri.replace("github", "user@github")}) {
      assertThrows(Exception.class, () -> GitHubAppProtocol.validateReturn(invalid, pending, 1000));
    }
    assertThrows(Exception.class, () -> GitHubAppProtocol.validateReturn(uri, pending, 2001));
    assertThrows(Exception.class, () -> GitHubAppProtocol.validateReturn(uri, new JSONObject(), 1000));
    pending.put("ready", false);
    assertThrows(Exception.class, () -> GitHubAppProtocol.validateReturn(uri, pending, 1000));
  }

  @Test public void appJwtIsSignedAndShortLivedAndBootstrapDiscardsUnneededSecrets() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    JSONObject response = new JSONObject().put("id", 123).put("slug", "meridian-test")
        .put("owner", new JSONObject().put("login", "manh212"))
        .put("permissions", new JSONObject().put("contents", "write"))
        .put("pem", "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----")
        .put("client_secret", "discard-me").put("webhook_secret", "discard-me");
    JSONObject stored = GitHubAppProtocol.credentials(response, 12345, "/random");
    assertFalse(stored.has("client_secret"));
    assertFalse(stored.has("webhook_secret"));
    String jwt = GitHubAppProtocol.jwt(stored, 1000);
    String[] parts = jwt.split("\\.");
    JSONObject claims = new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    assertEquals(940, claims.getLong("iat"));
    assertEquals(1480, claims.getLong("exp"));
    assertEquals(123, claims.getLong("iss"));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(keys.getPublic());
    signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
    response.getJSONObject("owner").put("login", "another-account");
    assertThrows(IllegalStateException.class, () -> GitHubAppProtocol.credentials(response, 12345, "/random"));
  }

  @Test public void githubPkcs1PrivateKeyCanSignJwt() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keys = generator.generateKeyPair();
    byte[] pkcs8 = keys.getPrivate().getEncoded();
    int offset = 1;
    int lengthByte = pkcs8[offset++] & 255;
    if (lengthByte >= 128) offset += lengthByte & 127;
    offset += 3 + 15;
    assertEquals(4, pkcs8[offset++] & 255);
    lengthByte = pkcs8[offset++] & 255;
    if (lengthByte >= 128) offset += lengthByte & 127;
    byte[] pkcs1 = java.util.Arrays.copyOfRange(pkcs8, offset, pkcs8.length);
    JSONObject credentials = new JSONObject().put("id", 123)
        .put("pem", "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(pkcs1)
            + "\n-----END RSA PRIVATE KEY-----");
    String jwt = GitHubAppProtocol.jwt(credentials, 1000);
    String[] parts = jwt.split("\\.");
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(keys.getPublic());
    signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])));
  }

  @Test public void completionUsesVerifiedInstallationWithoutBrowserRedirect() throws Exception {
    JSONObject app = appCredentials();
    java.util.List<String> calls = new java.util.ArrayList<>();
    String token = GitHubConnection.installationToken(app, (method, path, authorization, body) -> {
      calls.add(method + " " + path);
      assertNotNull(authorization);
      if ("GET".equals(method)) return installation();
      assertEquals("POST", method);
      assertEquals("/app/installations/456/access_tokens", path);
      assertEquals("SandFly-123", body.getJSONArray("repositories").getString(0));
      assertEquals(1, body.getJSONArray("repositories").length());
      assertEquals("write", body.getJSONObject("permissions").getString("contents"));
      return new JSONObject().put("token", "ghs_example_installation_token");
    });
    assertEquals("ghs_example_installation_token", token);
    assertEquals(java.util.Arrays.asList("GET /repos/manh212/SandFly-123/installation",
        "POST /app/installations/456/access_tokens"), calls);
  }

  @Test public void completionRejectsWrongInstallationAndOfflineBeforeAcceptingConnection() throws Exception {
    JSONObject app = appCredentials();
    JSONObject wrongOwner = installation();
    wrongOwner.getJSONObject("account").put("login", "someone-else");
    JSONObject readOnly = installation();
    readOnly.getJSONObject("permissions").put("contents", "read");
    for (JSONObject invalid : new JSONObject[] {installation().put("app_id", 999),
        installation().put("repository_selection", "all"), wrongOwner, readOnly}) {
      assertThrows(IllegalStateException.class, () -> GitHubConnection.installationToken(app,
          (method, path, authorization, body) -> {
            assertEquals("GET", method);
            return invalid;
          }));
    }
    assertThrows(java.io.IOException.class, () -> GitHubConnection.installationToken(app,
        (method, path, authorization, body) -> { throw new java.io.IOException("offline"); }));
    assertThrows(IllegalStateException.class, () -> GitHubConnection.installationToken(app,
        (method, path, authorization, body) -> "GET".equals(method) ? installation()
            : new JSONObject().put("token", "")));
  }

  private static JSONObject installation() throws Exception {
    return new JSONObject().put("app_id", 123).put("id", 456).put("repository_selection", "selected")
        .put("account", new JSONObject().put("login", "manh212"))
        .put("permissions", new JSONObject().put("contents", "write"));
  }

  private static JSONObject appCredentials() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return new JSONObject().put("id", 123).put("pem", "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----");
  }
}
