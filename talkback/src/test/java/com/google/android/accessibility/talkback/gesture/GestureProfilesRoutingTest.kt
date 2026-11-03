package com.google.android.accessibility.talkback.gesture

import android.accessibilityservice.AccessibilityService
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-ldltr")
class GestureProfilesRoutingTest {
  private val context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private lateinit var mapping: GestureShortcutMapping

  @Before fun start() {
    FormFactorUtils.initialize(context)
    prefs.edit().clear().commit()
    GestureProfiles.reset(prefs)
    AppGestures.load(prefs)
    AppGestures.onWindowShown(null)
    mapping = GestureShortcutMapping(context)
  }

  @After fun stop() {
    mapping.onUnbind()
    prefs.edit().clear().commit()
    GestureProfiles.load(prefs)
    AppGestures.load(prefs)
    AppGestures.onWindowShown(null)
  }

  private fun select(gestures: Map<String, String>) {
    GestureProfiles.write(prefs, listOf(GestureProfiles.Profile("one", "Một", gestures)))
    GestureProfiles.select(context, prefs, "one")
  }

  @Test fun applicationAssignmentOverridesProfileAndProfileOverridesGlobal() {
    val key = context.getString(R.string.pref_shortcut_up_key)
    prefs.edit().putString(key, "GLOBAL").commit()
    select(mapOf(key to "PROFILE"))
    assertEquals("PROFILE", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
    AppGestures.write(prefs, mapOf("example.app" to AppGestures.App("App", mapOf(key to "APP"))))
    AppGestures.load(prefs)
    AppGestures.onWindowShown("example.app")
    assertEquals("APP", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
    AppGestures.onWindowShown("another.app")
    assertEquals("PROFILE", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
  }

  @Test fun reversedDirectionsApplyToProfileAndItsHint() {
    val key = context.getString(R.string.pref_shortcut_up_key)
    prefs.edit().putBoolean(ReverseSwipeSettings.KEY, true).commit()
    select(mapOf(key to "PROFILE"))
    assertEquals("PROFILE", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_LEFT))
    assertEquals(
      GestureShortcutMapping.getGestureString(context, AccessibilityService.GESTURE_SWIPE_LEFT),
      mapping.getGestureFromActionKey("PROFILE"),
    )
  }

  @Test fun extendedGesturesAndRotationHaveAssignableProfileKeys() {
    val extended = context.getString(R.string.pref_extended_gesture_up_down_key)
    val rotation = context.getString(R.string.pref_shortcut_2finger_rotate_clockwise_key)
    select(mapOf(extended to "EXTENDED", rotation to "ROTATION"))
    assertEquals("EXTENDED", mapping.getActionKeyFromGestureId(10001))
    assertEquals("ROTATION", mapping.getActionKeyFromGestureId(GestureShortcutMapping.ROTATE_CLOCKWISE))
    assertTrue(mapping.hasGestureOverride(GestureShortcutMapping.ROTATE_CLOCKWISE))
  }

  @Test fun unassignedProfileIsDistinctFromInheritedGlobalAssignment() {
    val key = context.getString(R.string.pref_shortcut_up_key)
    prefs.edit().putString(key, "GLOBAL").commit()
    select(mapOf(key to context.getString(R.string.shortcut_value_unassigned)))
    assertEquals(context.getString(R.string.shortcut_value_unassigned), mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
    GestureProfiles.setRowValue(prefs, GestureProfiles.rowKey("one", key), GestureProfiles.DEFAULT_VALUE)
    assertEquals("GLOBAL", mapping.getActionKeyFromGestureId(AccessibilityService.GESTURE_SWIPE_UP))
  }
}
