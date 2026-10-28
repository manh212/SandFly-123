package com.google.android.accessibility.talkback.selector;

import static com.google.android.accessibility.talkback.selector.SelectorController.Setting.ACTIONS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.contextmenu.ContextMenuItem;
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor;
import com.google.android.accessibility.talkback.menurules.NodeMenuRuleCreator;
import com.google.android.accessibility.talkback.menurules.NodeMenuRuleCreator.MenuRules;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ActionsSettingTest {

  private Context context;
  private SharedPreferences prefs;
  private AccessibilityNodeInfoCompat keyboardEditingNode;
  private ActionsSetting setting;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    NodeMenuRuleCreator nodeMenuRuleCreator =
        new NodeMenuRuleCreator(null, null, null, null) {
          @Override
          public List<ContextMenuItem> getNodeMenuByRule(
              @NonNull MenuRules rule,
              Context context,
              AccessibilityNodeInfoCompat node,
              boolean includeAncestors) {
            return Collections.singletonList(new ContextMenuItem(context, 0, 0, 0, ""));
          }
        };
    AccessibilityFocusMonitor focusMonitor =
        new AccessibilityFocusMonitor(null, null, null) {
          @Override
          public @Nullable AccessibilityNodeInfoCompat getEditingNodeFromFocusedKeyboard(
              AccessibilityNodeInfoCompat accessibilityFocusNode) {
            return keyboardEditingNode;
          }
        };
    setting = new ActionsSetting(nodeMenuRuleCreator, focusMonitor);
  }

  @Test
  public void editTextActivatesWhenActionsAreEnabled() {
    setActionsEnabled(true);

    assertTrue(setting.shouldActivateSetting(context, editTextNode()));
  }

  @Test
  public void editTextDoesNotActivateWhenActionsAreDisabled() {
    setActionsEnabled(false);

    assertFalse(setting.shouldActivateSetting(context, editTextNode()));
  }

  @Test
  public void keyboardFocusDoesNotActivateActionsForEditingNode() {
    setActionsEnabled(true);
    keyboardEditingNode = editTextNode();

    assertFalse(setting.shouldActivateSetting(context, editTextNode()));
  }

  private void setActionsEnabled(boolean enabled) {
    prefs.edit().putBoolean(context.getString(ACTIONS.prefKeyResId), enabled).commit();
  }

  private static AccessibilityNodeInfoCompat editTextNode() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName("android.widget.EditText");
    node.setEditable(true);
    return AccessibilityNodeInfoCompat.wrap(node);
  }
}
