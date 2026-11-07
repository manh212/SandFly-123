package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import android.content.Context;
import android.provider.Settings;
import io.meridian.licensing.RecoveryProtocol;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class DeviceLicenseManagerRecoveryTest {
  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    return generator.generateKeyPair();
  }

  @Test public void recoveryRequestSurvivesLegacyPreferenceRemoval() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ANDROID_ID, "123456789abcdef0");
    String request = DeviceLicenseManager.recoveryRequest(context);
    KeyPair issuer = keyPair();
    String oldCode = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .edit().putString("license", oldCode).remove("policy").commit();
    assertFalse(DeviceLicenseManager.isLicensed(context));
    assertFalse(DeviceLicenseManager.allows(context, "scripts"));
    assertFalse(context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .contains("license"));
    assertEquals(request, DeviceLicenseManager.recoveryRequest(context));
  }

  @Test public void onlineCatalogRejectsLegacyFormatEvenWithValidIssuerSignature() throws Exception {
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    KeyPair issuer = keyPair();
    String oldCode = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    assertTrue(RecoveryProtocol.verify(request, oldCode, issuer.getPublic()));
    JSONObject record = new JSONObject().put("format", 2).put("value", oldCode);
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(record));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(new JSONObject().put("format", 3).put("value", oldCode)));
    try {
      DeviceLicenseManager.acceptRemote(RuntimeEnvironment.getApplication(), oldCode,
          System.currentTimeMillis());
      fail("Legacy remote tokens must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("Giấy phép trực tuyến không hợp lệ.", expected.getMessage());
    }
  }

  @Test public void onlineCatalogRequiresNewFormatAndServerRecord() throws Exception {
    assertTrue(LicenseRecovery.isOnlinePolicyRecord(
        new JSONObject().put("format", 3).put("value", "SFLIC3.sample")));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(new JSONObject()));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(null));
    assertFalse(LicenseRecovery.isOnlinePolicyRecord(
        new JSONObject().put("format", 2).put("value", "SFLIC3.sample")));
    try {
      DeviceLicenseManager.acceptRemote(RuntimeEnvironment.getApplication(),
          "SFLIC3.sample", 0L);
      fail("Missing server time must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("Giấy phép trực tuyến không hợp lệ.", expected.getMessage());
    }
  }

  @Test public void forgedLegacyTokenIsNotMadeValidByItsOwnPublicKey() throws Exception {
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    KeyPair attacker = keyPair();
    KeyPair trusted = keyPair();
    String forged = RecoveryProtocol.issue(request, attacker.getPrivate(), 1700000000L);
    assertFalse(RecoveryProtocol.verify(request, forged, trusted.getPublic()));
  }
}
