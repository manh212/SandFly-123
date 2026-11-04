package com.google.android.accessibility.talkback.volume

import android.os.Looper
import android.view.KeyEvent
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class VolumeKeyManagerTest {
  class RecordingService : TalkBackService() {
    val actions = mutableListOf<String>()
    var callActive = false
    override fun onCreate() {}
    override fun isPhoneCallActive() = callActive
    override fun isTouchInteracting() = false
    override fun resolveVolumeKeyUse(triggerModifierPressed: Boolean) = TalkBackService.VOLUME_KEY_CHANGES_VOLUME
    override fun performCatalogAction(action: String) { actions += action }
    override fun adjustContextualVolume(increase: Boolean, touching: Boolean) {
      actions += if (increase) "up" else "down"
    }
    override fun adjustUnmanagedVolumeKey(increase: Boolean, touching: Boolean) {
      actions += if (increase) "systemUp" else "systemDown"
    }
  }

  private lateinit var service: RecordingService
  private val up = KeyEvent.KEYCODE_VOLUME_UP
  private val down = KeyEvent.KEYCODE_VOLUME_DOWN

  @Before fun setUp() {
    VolumeKeyManager.reset()
    service = Robolectric.buildService(RecordingService::class.java).create().get()
    SharedPreferencesUtils.getSharedPreferences(service).edit().clear().commit()
  }

  @After fun tearDown() { VolumeKeyManager.reset() }

  private fun key(code: Int, action: Int): Boolean =
    VolumeKeyManager.onKeyEvent(service, KeyEvent(action, code), false)
  private fun tap(code: Int) {
    key(code, KeyEvent.ACTION_DOWN)
    key(code, KeyEvent.ACTION_UP)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
  }
  private fun waitForSequence() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))

  @Test fun defaultChordWorksInBothOrdersAndCancelsSinglePresses() {
    listOf(up to down, down to up).forEach { (first, second) ->
      key(first, KeyEvent.ACTION_DOWN)
      key(second, KeyEvent.ACTION_DOWN)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
      key(first, KeyEvent.ACTION_UP)
      key(second, KeyEvent.ACTION_UP)
    }
    assertEquals(listOf("TOGGLE_TOUCH_EXPLORATION", "TOGGLE_TOUCH_EXPLORATION"), service.actions)
  }

  @Test fun disabledMultiPressKeepsSinglePresses() {
    repeat(3) { tap(up) }
    waitForSequence()
    assertEquals(listOf("systemUp", "systemUp", "systemUp"), service.actions)
  }

  @Test fun enabledMultiPressIsUnassignedByDefault() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MULTI_ENABLED, true).commit()
    repeat(2) { tap(down) }
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }

  @Test fun twoThreeAndFourPressesRunOnlyTheMatchingAssignment() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().putBoolean(VolumeKeyManager.KEY_MULTI_ENABLED, true).apply()
    for (code in listOf(up, down)) {
      for (count in 2..4) {
        val action = "action_${code}_$count"
        prefs.edit().putString(VolumeKeyManager.multiKey(code == up, count), action).commit()
        repeat(count) { tap(code) }
        waitForSequence()
        assertEquals(action, service.actions.last())
      }
    }
    assertEquals(6, service.actions.size)
  }

  @Test fun resetDropsPendingSequenceAndHold() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MULTI_ENABLED, true)
      .putString(VolumeKeyManager.multiKey(true, 2), "BACK").commit()
    repeat(2) { tap(up) }
    VolumeKeyManager.reset()
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }

  @Test fun activeCallsLeaveKeysToSystemAndDropPendingActions() {
    tap(up)
    service.actions.clear()
    service.callActive = true
    assertFalse(key(down, KeyEvent.ACTION_DOWN))
    assertFalse(key(down, KeyEvent.ACTION_UP))
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }

  @Test fun managedHoldKeepsItsAssignmentAndDoesNotRunTheTap() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MANAGED, true)
      .putString(VolumeKeyManager.KEY_UP_HOLD_ACTION, "HOME").commit()
    key(up, KeyEvent.ACTION_DOWN)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
    key(up, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("HOME"), service.actions)
  }

  @Test fun chordDropsAnEarlierMultiPressSequence() {
    SharedPreferencesUtils.getSharedPreferences(service).edit()
      .putBoolean(VolumeKeyManager.KEY_MULTI_ENABLED, true)
      .putString(VolumeKeyManager.multiKey(true, 2), "BACK").commit()
    tap(up)
    key(up, KeyEvent.ACTION_DOWN)
    key(down, KeyEvent.ACTION_DOWN)
    key(up, KeyEvent.ACTION_UP)
    key(down, KeyEvent.ACTION_UP)
    waitForSequence()
    assertEquals(listOf("TOGGLE_TOUCH_EXPLORATION"), service.actions)
  }

  @Test fun disablingMultiPressDropsThePendingAssignment() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    prefs.edit().putBoolean(VolumeKeyManager.KEY_MULTI_ENABLED, true)
      .putString(VolumeKeyManager.multiKey(true, 2), "BACK").commit()
    repeat(2) { tap(up) }
    prefs.edit().putBoolean(VolumeKeyManager.KEY_MULTI_ENABLED, false).commit()
    waitForSequence()
    assertEquals(emptyList<String>(), service.actions)
  }
}
