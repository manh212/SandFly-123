package com.google.android.accessibility.talkback.licensing;

import android.content.Context;

final class LicenseNotifications {
  private LicenseNotifications() {}

  static void start(Context context) {
    LicenseRecovery.start(context, true, null);
  }

  static void stop() {}
}
