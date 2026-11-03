package com.sandfly.offlinelicenser;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.security.MessageDigest;

final class OfficialInstall {
  private OfficialInstall() {}
  static boolean verified(Context context) {
    if (!context.getPackageName().equals("com.sandfly.offlinelicenser.v2")) return false;
    try {
      PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
          Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
      Signature[] signatures = Build.VERSION.SDK_INT >= 28
          ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
      if (signatures == null || signatures.length != 1) return false;
      byte[] actual = MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
      String trusted = "7fde506fa8d1652fb21f1bc8f9fd98666f7a92187636340f4b95672d4068f3f8";
      byte[] expected = new byte[32];
      for (int i = 0; i < expected.length; i++) {
        expected[i] = (byte) Integer.parseInt(trusted.substring(i * 2, i * 2 + 2), 16);
      }
      return MessageDigest.isEqual(actual, expected);
    } catch (Exception e) { return false; }
  }
}
