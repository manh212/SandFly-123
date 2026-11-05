package com.google.android.accessibility.talkback.selector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.talkback.TalkBackService;
import com.google.android.accessibility.talkback.gesture.SmartContextGestures;
import com.google.android.accessibility.utils.SharedPreferencesUtils;

/**
 * Reading control selected for an enabled smart action on the current screen.
 * Detection never performs the action; a user gesture is still required.
 */
public final class SmartContextSetting implements SelectorController.ContextualSetting {
  private static final long MATCH_CACHE_MS = 250;
  private String cachedPackage = "";
  private long lastChecked;
  private boolean cachedMatch;

  @Override
  public SelectorController.Setting getSetting() {
    return SelectorController.Setting.SMART_CONTEXT_GESTURES;
  }

  @Override
  public boolean isNodeSupportSetting(Context context, AccessibilityNodeInfoCompat node) {
    if (node == null) return false;
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    return prefs.getBoolean(
        context.getString(R.string.pref_selector_smart_context_key),
        context.getResources().getBoolean(R.bool.pref_selector_smart_context_default));
  }

  @Override
  public boolean shouldActivateSetting(Context context, AccessibilityNodeInfoCompat node) {
    if (!isNodeSupportSetting(context, node) || node.isEditable() || node.isPassword()) return false;
    SharedPreferences prefs = SharedPreferencesUtils.getSharedPreferences(context);
    if (!prefs.getBoolean(SmartContextGestures.PREF_AUTO_SWITCH, false)) return false;
    TalkBackService service = TalkBackService.getInstance();
    if (service == null || node == null || node.getPackageName() == null) return false;
    String packageName = node.getPackageName().toString();
    if (!packageName.equals(SmartContextGestures.currentPackage(service))) return false;

    long now = SystemClock.uptimeMillis();
    if (packageName.equals(cachedPackage) && now >= lastChecked
        && now - lastChecked < MATCH_CACHE_MS) return cachedMatch;
    cachedPackage = packageName;
    lastChecked = now;
    cachedMatch = SmartContextGestures.hasMatchingContext(service);
    return cachedMatch;
  }
}
