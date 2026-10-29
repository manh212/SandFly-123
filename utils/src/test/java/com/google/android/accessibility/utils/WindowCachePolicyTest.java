package com.google.android.accessibility.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.view.accessibility.AccessibilityEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WindowCachePolicyTest {
  private AccessibilityEvent change(int window, int flags) {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
    shadowOf(event).setWindowId(window);
    ReflectionHelpers.callInstanceMethod(event, "setWindowChanges", ClassParameter.from(int.class, flags));
    return event;
  }

  @Test
  public void foreignWindowUpdatesPreserveOrderButActiveChangesInvalidateRoot() {
    AccessibilityEvent event = change(8, AccessibilityEvent.WINDOWS_CHANGE_BOUNDS);
    assertFalse(WindowCachePolicy.affectsOrder(event, 7));
    assertFalse(WindowCachePolicy.affectsRoot(event, 7));
    event.recycle();
    event = change(8, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE);
    assertFalse(WindowCachePolicy.affectsOrder(event, 7));
    assertTrue(WindowCachePolicy.affectsRoot(event, 7));
    event.recycle();
  }

  @Test
  public void sameWindowStructureAndUnknownWindowsAreConservative() {
    AccessibilityEvent event = change(7, AccessibilityEvent.WINDOWS_CHANGE_TITLE);
    assertFalse(WindowCachePolicy.affectsOrder(event, 7));
    assertTrue(WindowCachePolicy.affectsOrder(event, -1));
    event.recycle();
    event = change(7, AccessibilityEvent.WINDOWS_CHANGE_BOUNDS);
    assertTrue(WindowCachePolicy.affectsOrder(event, 7));
    event.recycle();
    event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    shadowOf(event).setWindowId(8);
    assertTrue(WindowCachePolicy.affectsRoot(event, 7));
    event.recycle();
  }

  @Test
  public void contentDeferralNeverTreatsUnknownOrSubtreeChangesAsTextOnly() {
    AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    assertFalse(WindowCachePolicy.isNonStructuralContent(event));
    event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        | AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION);
    assertTrue(WindowCachePolicy.isNonStructuralContent(event));
    event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
        | AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
    assertFalse(WindowCachePolicy.isNonStructuralContent(event));
    event.recycle();
  }
}
