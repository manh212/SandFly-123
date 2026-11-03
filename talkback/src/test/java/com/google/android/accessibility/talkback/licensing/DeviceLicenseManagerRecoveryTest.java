package com.google.android.accessibility.talkback.licensing;

import static org.junit.Assert.*;

import android.content.Context;
import android.provider.Settings;
import io.meridian.licensing.RecoveryProtocol;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
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

  @Test public void clearingLocalPreferencesKeepsTheRecoveryRequestAndValidSignature() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ANDROID_ID, "123456789abcdef0");
    String request = DeviceLicenseManager.recoveryRequest(context);
    KeyPair issuer = keyPair();
    String license = RecoveryProtocol.issue(request, issuer.getPrivate(), 1700000000L);
    context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE)
        .edit().putString("license", license).commit();
    context.getSharedPreferences("sandfly_offline_license", Context.MODE_PRIVATE).edit().clear().commit();
    assertEquals(request, DeviceLicenseManager.recoveryRequest(context));
    assertTrue(DeviceLicenseManager.verifiesRecovery(request, license, RecoveryProtocol.publicKey(issuer.getPublic())));
  }

  @Test public void recordCannotChooseItsOwnTrustedIssuer() throws Exception {
    String request = RecoveryProtocol.request("meridian", "123456789abcdef0");
    KeyPair attacker = keyPair();
    KeyPair trusted = keyPair();
    String forged = RecoveryProtocol.issue(request, attacker.getPrivate(), 1700000000L);
    assertFalse(DeviceLicenseManager.verifiesRecovery(request, forged, RecoveryProtocol.publicKey(trusted.getPublic())));
    assertFalse(DeviceLicenseManager.verifiesRecovery(request, forged, ""));
    assertFalse(DeviceLicenseManager.verifiesRecovery(request, forged, "SFPUB1.invalid"));
  }
}
