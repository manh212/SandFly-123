/*
 * Copyright (C) 2026 The SandFly Authors
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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.accessibility.AccessibilityEvent;
import com.google.android.accessibility.talkback.pause.PauseController;
import com.google.android.accessibility.talkback.quicksettings.SystemPanelClicker;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Events that {@link TalkBackService#onAccessibilityEvent} drops before they can reach the event
 * processor — while paused, and while the system's quick settings are being used — must still
 * dirty the cached root node when they report a window change, exactly as the processor would
 * have. The menu-automation drop branch marks the cache through the same call and is covered by
 * inspection: it needs a live automation session, which the service-level test cannot stage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TalkBackServiceRootDirtyTest {

  private TalkBackService service;

  @Before
  public void setUp() {
    service = Robolectric.buildService(TalkBackService.class).create().get();
    setPaused(false);
    setSystemPanelWorking(false);
  }

  @After
  public void tearDown() {
    setPaused(false);
    setSystemPanelWorking(false);
  }

  @Test
  public void paused_windowStateChanged_dirtiesRootCache() {
    setPaused(true);
    service.setRootDirty(false);

    service.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.app"));

    assertTrue(isRootNodeDirty());
  }

  @Test
  public void paused_nonWindowEvent_doesNotDirtyRootCache() {
    setPaused(true);
    service.setRootDirty(false);

    service.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.example.app"));

    assertFalse(isRootNodeDirty());
  }

  @Test
  public void systemPanel_windowStateChanged_dirtiesRootCache() {
    setSystemPanelWorking(true);
    service.setRootDirty(false);

    service.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.android.systemui"));

    assertTrue(isRootNodeDirty());
  }

  @Test
  public void systemPanel_nonWindowEvent_doesNotDirtyRootCache() {
    setSystemPanelWorking(true);
    service.setRootDirty(false);

    service.onAccessibilityEvent(
        event(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.android.systemui"));

    assertFalse(isRootNodeDirty());
  }

  private boolean isRootNodeDirty() {
    try {
      Field field = TalkBackService.class.getDeclaredField("isRootNodeDirty");
      field.setAccessible(true);
      return field.getBoolean(service);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  // Both flags are @Volatile companion/object properties, so their backing fields are static
  // fields of the outer class, not of the companion instance.
  private static void setPaused(boolean paused) {
    setStaticBoolean(PauseController.class, "pausedNow", paused);
  }

  private static void setSystemPanelWorking(boolean working) {
    setStaticBoolean(SystemPanelClicker.class, "working", working);
  }

  private static void setStaticBoolean(Class<?> clazz, String fieldName, boolean value) {
    try {
      Field field = clazz.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.setBoolean(null, value);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  private static AccessibilityEvent event(int type, String packageName) {
    AccessibilityEvent event = AccessibilityEvent.obtain(type);
    event.setPackageName(packageName);
    return event;
  }
}
