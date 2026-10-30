package com.google.android.accessibility.talkback.gesture.extended;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.widget.Toast;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

public final class ExtendedGesturesController {
  private final TalkBackService service;
  private ExtendedTouchBackend backend;

  public ExtendedGesturesController(TalkBackService service) { this.service = service; }

  public void update(boolean active) {
    if (Build.VERSION.SDK_INT < 33) return;
    AccessibilityServiceInfo info = service.getServiceInfo();
    boolean enabled = active && info != null &&
        (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0 &&
        ExtendedGestures.isEnabled(SharedPreferencesUtils.getSharedPreferences(service));
    if (!enabled) { stop(); return; }
    if (backend != null) return;
    try {
      backend = new ExtendedTouchBackend(service, this::failed);
      backend.start();
    } catch (RuntimeException error) {
      failed();
    }
  }

  public void stop() {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) {
      backend.stop();
      backend = null;
    }
  }

  public void configurationChanged(Configuration configuration) {
    if (Build.VERSION.SDK_INT >= 33 && backend != null) backend.configurationChanged(configuration);
  }

  private void failed() {
    stop();
    SharedPreferencesUtils.getSharedPreferences(service).edit().putBoolean(ExtendedGestures.ENABLED, false).apply();
    Toast.makeText(service, R.string.extended_gestures_failed, Toast.LENGTH_LONG).show();
  }
}
