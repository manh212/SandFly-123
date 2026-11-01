package com.google.android.accessibility.talkback.licensing;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import com.google.android.accessibility.talkback.BuildConfig;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ApkSignatureVerifier {
  private static final String OFFICIAL_CERTIFICATE_SHA256 =
      "7fde506fa8d1652fb21f1bc8f9fd98666f7a92187636340f4b95672d4068f3f8";
  private static volatile boolean verifiedOfficialSignature;

  private ApkSignatureVerifier() {}

  static boolean isOfficial(Context context) {
    if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName())) {
      return false;
    }
    if (verifiedOfficialSignature) {
      return true;
    }
    try {
      PackageManager packageManager = context.getPackageManager();
      Signature[] signatures;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageInfo packageInfo =
            packageManager.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        SigningInfo signingInfo = packageInfo.signingInfo;
        signatures = signingInfo == null ? null : signingInfo.getApkContentsSigners();
      } else {
        PackageInfo packageInfo =
            packageManager.getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNATURES);
        signatures = packageInfo.signatures;
      }
      if (signatures == null || signatures.length != 1 || signatures[0] == null) {
        return false;
      }
      boolean official =
          matchesCertificateDigest(
              signatures[0].toByteArray(), fromHex(OFFICIAL_CERTIFICATE_SHA256));
      if (official) {
        verifiedOfficialSignature = true;
      }
      return official;
    } catch (PackageManager.NameNotFoundException | RuntimeException e) {
      return false;
    }
  }

  static boolean matchesCertificateDigest(byte[] certificate, byte[] expectedSha256) {
    if (certificate == null || certificate.length == 0
        || expectedSha256 == null || expectedSha256.length != 32) {
      return false;
    }
    try {
      byte[] actualSha256 = MessageDigest.getInstance("SHA-256").digest(certificate);
      return MessageDigest.isEqual(actualSha256, expectedSha256);
    } catch (NoSuchAlgorithmException e) {
      return false;
    }
  }

  private static byte[] fromHex(String value) {
    if (value.length() != 64) {
      throw new IllegalArgumentException("Invalid signing certificate digest");
    }
    byte[] bytes = new byte[32];
    for (int index = 0; index < bytes.length; index++) {
      int high = Character.digit(value.charAt(index * 2), 16);
      int low = Character.digit(value.charAt(index * 2 + 1), 16);
      if (high < 0 || low < 0) {
        throw new IllegalArgumentException("Invalid signing certificate digest");
      }
      bytes[index] = (byte) ((high << 4) | low);
    }
    return bytes;
  }
}
