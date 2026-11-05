package com.google.android.accessibility.talkback.notifications

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationWindowPolicyTest {
  class Service : AccessibilityService() {
    var shown: List<AccessibilityWindowInfo> = emptyList()
    override fun getWindows() = shown
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
  }

  @Test fun suppressesAutomaticOverlayButKeepsExplicitlyFocusedNotificationPanel() {
    val service = Robolectric.buildService(Service::class.java).get()
    val row = AccessibilityNodeInfo.obtain().apply {
      packageName = "com.android.systemui"
      viewIdResourceName = "com.android.systemui:id/expandableNotificationRow"
      isVisibleToUser = true
    }
    val window = AccessibilityWindowInfo.obtain()
    shadowOf(window).apply {
      setId(7)
      setType(AccessibilityWindowInfo.TYPE_SYSTEM)
      setRoot(row)
      setFocused(false)
    }
    service.shown = listOf(window)
    assertTrue(NotificationWindowPolicy.isAutomaticNotificationWindow(service, 7))
    shadowOf(window).setFocused(true)
    assertFalse(NotificationWindowPolicy.isAutomaticNotificationWindow(service, 7))
    shadowOf(window).setFocused(false)
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
    assertFalse(NotificationWindowPolicy.isAutomaticNotificationWindow(service, 7))
  }
}
