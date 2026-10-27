package com.google.android.accessibility.talkback.preference.base

import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.TwoStatePreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.notifications.NotificationReadingFragment
import com.google.android.accessibility.talkback.notifications.NotificationWhenFragment
import com.google.android.accessibility.talkback.notifications.NotificationRepeatsFragment
import com.google.android.accessibility.talkback.notifications.NotificationStopFragment
import com.google.android.accessibility.talkback.utils.VerbosityPreferences
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrganizedSettingsTest {
  private lateinit var controller: ActivityController<FragmentActivity>
  private lateinit var activity: FragmentActivity
  private lateinit var prefs: SharedPreferences

  @Before
  fun setUp() {
    controller = Robolectric.buildActivity(FragmentActivity::class.java)
    activity = controller.get()
    activity.setTheme(R.style.TalkBackSettings)
    FormFactorUtils.initialize(activity)
    prefs = SharedPreferencesUtils.getSharedPreferences(activity)
    prefs.edit().clear().putString(
      activity.getString(R.string.pref_verbosity_preset_key),
      activity.getString(R.string.pref_verbosity_preset_value_custom),
    ).commit()
    controller.setup()
  }

  @After
  fun tearDown() {
    controller.pause().stop().destroy()
  }

  private fun open(fragment: TalkbackBaseFragment) {
    activity.supportFragmentManager.beginTransaction()
      .replace(android.R.id.content, fragment).commitNow()
  }

  private fun presetKey(preset: Int, key: Int): String =
    VerbosityPreferences.toVerbosityPrefKey(
      activity.getString(preset), activity.getString(key),
    )

  @Test
  fun navigationControlsKeepExistingCustomValuesAndWriteToTheSameKeys() {
    val key = activity.getString(R.string.pref_speak_container_element_positions_key)
    val customKey = presetKey(
      R.string.pref_verbosity_preset_value_custom,
      R.string.pref_speak_container_element_positions_key,
    )
    prefs.edit().putBoolean(customKey, false).commit()
    val fragment = NavigationGroupFragment()
    open(fragment)
    val row = requireNotNull(fragment.findPreference<TwoStatePreference>(customKey))
    assertFalse(row.isChecked)
    row.isChecked = true
    assertTrue(VerbosityPreferences.getPreferenceValueBool(prefs, activity.resources, key, false))
  }

  @Test
  fun speechControlsFollowPresetChangesAndRestoreCustomValues() {
    val customKey = presetKey(R.string.pref_verbosity_preset_value_custom, R.string.pref_a11y_hints_key)
    prefs.edit().putBoolean(customKey, false).commit()
    val fragment = SpeechGroupFragment()
    open(fragment)
    for (preset in listOf(R.string.pref_verbosity_preset_value_high, R.string.pref_verbosity_preset_value_low)) {
      prefs.edit().putString(
        activity.getString(R.string.pref_verbosity_preset_key), activity.getString(preset),
      ).commit()
      val row = requireNotNull(fragment.findPreference<TwoStatePreference>(presetKey(preset, R.string.pref_a11y_hints_key)))
      assertEquals(preset == R.string.pref_verbosity_preset_value_high, row.isChecked)
      assertFalse(row.isEnabled)
    }
    prefs.edit().putString(
      activity.getString(R.string.pref_verbosity_preset_key),
      activity.getString(R.string.pref_verbosity_preset_value_custom),
    ).commit()
    val restored = requireNotNull(fragment.findPreference<TwoStatePreference>(customKey))
    assertFalse(restored.isChecked)
    assertTrue(restored.isEnabled)
  }

  @Test
  fun typingSwitchesAndExternalKeyboardEchoChangesStayUsable() {
    val fragment = TypingGroupFragment()
    open(fragment)
    val enterKey = activity.getString(R.string.pref_interrupt_enter_key)
    val enter = requireNotNull(fragment.findPreference<TwoStatePreference>(enterKey))
    enter.isChecked = !enter.isChecked
    assertEquals(enter.isChecked, prefs.getBoolean(enterKey, !enter.isChecked))
    val echoKey = activity.getString(R.string.pref_echo_physical_keyboard_key)
    val echo = requireNotNull(fragment.findPreference<ListPreference>(echoKey))
    val value = activity.resources.getStringArray(R.array.pref_keyboard_echo_values).last()
    prefs.edit().putString(echoKey, value).commit()
    assertEquals(value, echo.value)
  }

  @Test
  fun longPressDurationFollowsTheTypingMethodWithoutLosingItsValue() {
    val methodKey = activity.getString(R.string.pref_typing_confirmation_key)
    val durationKey = activity.getString(R.string.pref_typing_long_press_duration_key)
    val duration = activity.resources.getStringArray(R.array.pref_typing_long_press_values).last()
    prefs.edit().putString(methodKey, DOUBLE_TAP.toString()).putString(durationKey, duration).commit()
    val fragment = TypingGroupFragment()
    open(fragment)
    val row = requireNotNull(fragment.findPreference<ListPreference>(durationKey))
    assertFalse(row.isEnabled)
    prefs.edit().putString(methodKey, LIFT_TO_TYPE.toString()).commit()
    assertTrue(row.isEnabled)
    assertEquals(duration, row.value)
  }

  @Test
  fun notificationTimingChoicesRetainSavedValuesOnTheirRelatedPages() {
    val cases = listOf(
      Triple(NotificationWhenFragment(), "pref_notification_delay", "5"),
      Triple(NotificationRepeatsFragment(), "pref_notification_repeat_minutes", "2"),
      Triple(NotificationStopFragment(), "pref_notification_max_seconds", "30"),
    )
    for ((fragment, key, value) in cases) {
      prefs.edit().putString(key, value).commit()
      open(fragment)
      val row = requireNotNull(fragment.findPreference<ListPreference>(key))
      assertTrue(row.entries.isNotEmpty())
      assertEquals(value, row.value)
    }
    val reading = NotificationReadingFragment()
    open(reading)
    for ((_, key, _) in cases) {
      assertTrue(reading.findPreference<androidx.preference.Preference>(key) == null)
    }
  }

  @Test
  fun responseResetPreservesUnrelatedSettingsAndCustomReadingValues() {
    val customKey = presetKey(R.string.pref_verbosity_preset_value_custom, R.string.pref_a11y_hints_key)
    val typingKey = activity.getString(R.string.pref_typing_confirmation_key)
    prefs.edit()
      .putString(typingKey, LIFT_TO_TYPE.toString())
      .putBoolean("pref_low_latency_audio", false)
      .putBoolean("pref_always_interrupt_speech", true)
      .putBoolean(customKey, false)
      .putString("pref_notification_format", "saved format")
      .commit()
    ResponseSettingsReset.reset(activity)
    assertFalse(prefs.contains(typingKey))
    assertFalse(prefs.contains("pref_low_latency_audio"))
    assertFalse(prefs.contains("pref_always_interrupt_speech"))
    assertFalse(prefs.getBoolean(customKey, true))
    assertEquals("saved format", prefs.getString("pref_notification_format", null))
  }
}
