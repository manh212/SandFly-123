/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.google.android.accessibility.talkback.selector;

import static com.google.android.accessibility.talkback.selector.SelectorController.keepsReadingControlInWebContent;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.accessibility.talkback.R;
import com.google.android.accessibility.utils.SharedPreferencesUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Tests the gate behind "Change reading control in selection mode": while the switch is off,
 * focus changes within web content must keep the user's reading control instead of replacing it
 * with a contextual setting, such as Actions. While the switch is on (the default), or outside
 * web content, focus changes behave as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SelectorControllerTest {

  private Context context;
  private SharedPreferences prefs;
  private String selectionModeReadingControlKey;

  @Before
  public void setUp() {
    context = RuntimeEnvironment.getApplication();
    prefs = SharedPreferencesUtils.getSharedPreferences(context);
    prefs.edit().clear().commit();
    selectionModeReadingControlKey =
        context.getString(R.string.pref_selection_mode_reading_control_key);
  }

  @Test
  public void switchOff_focusInsideWebView_keepsReadingControl() {
    setSwitch(false);
    assertTrue(keepsReadingControlInWebContent(context, prefs, webChildNode()));
  }

  @Test
  public void switchOff_focusOnWebViewItself_keepsReadingControl() {
    setSwitch(false);
    assertTrue(keepsReadingControlInWebContent(context, prefs, webViewNode()));
  }

  @Test
  public void switchOn_focusInsideWebView_doesNotKeepReadingControl() {
    setSwitch(true);
    assertFalse(keepsReadingControlInWebContent(context, prefs, webChildNode()));
  }

  @Test
  public void switchUnset_focusInsideWebView_doesNotKeepReadingControl() {
    // The switch is on by default, so nothing is kept until the user turns it off.
    assertFalse(keepsReadingControlInWebContent(context, prefs, webChildNode()));
  }

  @Test
  public void switchOff_focusOutsideWebContent_doesNotKeepReadingControl() {
    setSwitch(false);
    AccessibilityNodeInfo root = node(null, "android.widget.FrameLayout");
    AccessibilityNodeInfo button = node(root, "android.widget.Button");
    assertFalse(keepsReadingControlInWebContent(context, prefs, button));
  }

  private void setSwitch(boolean on) {
    prefs.edit().putBoolean(selectionModeReadingControlKey, on).commit();
  }

  /** A node inside a web page: a text node within a container within the WebView. */
  private AccessibilityNodeInfo webChildNode() {
    AccessibilityNodeInfo container = node(webViewNode(), "android.view.View");
    return node(container, "android.widget.TextView");
  }

  private AccessibilityNodeInfo webViewNode() {
    return node(null, "android.webkit.WebView");
  }

  private static AccessibilityNodeInfo node(AccessibilityNodeInfo parent, String className) {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setClassName(className);
    node.setVisibleToUser(true);
    if (parent != null) {
      shadowOf(parent).addChild(node);
    }
    return node;
  }
}
