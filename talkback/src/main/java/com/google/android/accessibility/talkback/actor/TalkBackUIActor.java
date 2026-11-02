package com.google.android.accessibility.talkback.actor;

import android.content.Context;
import android.content.res.Configuration;
import com.google.android.accessibility.talkback.Feedback.TalkBackUI;

public class TalkBackUIActor {
  public enum Type {
    SELECTOR_MENU_ITEM_OVERLAY_SINGLE_FINGER,
    SELECTOR_MENU_ITEM_OVERLAY_MULTI_FINGER,
    SELECTOR_ITEM_ACTION_OVERLAY,
    GESTURE_OR_KEYBOARD_ACTION_OVERLAY,
  }

  public TalkBackUIActor(Context context) {}

  public boolean showQuickMenu(TalkBackUI talkBackUI) {
    return true;
  }

  public boolean hide(Type type) {
    return true;
  }

  public boolean setSupported(Type type, boolean supported) {
    return true;
  }

  public boolean isShowing(Type type) {
    return false;
  }

  public boolean onConfigurationChanged(Configuration configuration) {
    return true;
  }
}
