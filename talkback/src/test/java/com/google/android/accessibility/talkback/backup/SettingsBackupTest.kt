/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.backup

import android.content.Context
import com.google.android.accessibility.talkback.migration.MigrationFiles
import com.google.android.accessibility.talkback.preference.ItemOrder
import com.google.android.accessibility.talkback.selector.SelectorController
import com.google.android.accessibility.talkback.update.Updater
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private val extra = SharedPreferencesUtils.getSharedPreferences(context, "extra_settings")
  private val braille = SharedPreferencesUtils.getSharedPreferences(context, "braille_keyboard")

  @Test
  fun factoryDefaultsApplyToFreshInstallAndPreserveLaterChanges() {
    val state = SharedPreferencesUtils.getSharedPreferences(context, MigrationFiles.STATE_PREFS)
    state.edit().clear().commit()
    prefs.edit().clear().commit()
    extra.edit().clear().commit()
    braille.edit().clear().commit()

    SettingsBackup.applyInitialDefaultsIfNeeded(context)

    assertTrue(prefs.getBoolean("pref_speak_items_before_scroll_key", false))
    assertEquals("GRANULARITY_HEADINGS", prefs.getString("pref_current_selector_setting_key", null))
    assertEquals(bundledReadingControlOrder(), prefs.getString(ItemOrder.READING_CONTROLS_KEY, null))
    assertEquals("UEB", braille.getString("pref_brailleime_translator_code", null))
    assertFalse(prefs.contains("pref_problem_report_email"))
    assertFalse(prefs.contains("pref_stopwatch_history"))
    assertTrue(state.getBoolean("initial_settings_applied", false))
    assertTrue(!state.getString("initial_settings_snapshot", null).isNullOrBlank())

    prefs.edit().putBoolean("pref_speak_items_before_scroll_key", false).commit()
    SettingsBackup.applyInitialDefaultsIfNeeded(context)

    assertFalse(prefs.getBoolean("pref_speak_items_before_scroll_key", true))
  }

  @Test
  fun upgradeMigratesOldDefaultsAndPreservesUserChanges() {
    val state = SharedPreferencesUtils.getSharedPreferences(context, MigrationFiles.STATE_PREFS)
    state.edit().clear().putBoolean("initial_settings_applied", true).commit()
    prefs.edit().clear().commit()
    extra.edit().clear().commit()
    braille.edit().clear().commit()
    prefs
      .edit()
      .putBoolean("pref_speak_items_before_scroll_key", false)
      .putString("pref_current_selector_setting_key", "GRANULARITY_CHARACTERS")
      .putString(
        ItemOrder.READING_CONTROLS_KEY,
        SelectorController.defaultOrderKeys(context).joinToString(","),
      )
      .putString("custom_user_setting", "keep")
      .commit()

    SettingsBackup.applyInitialDefaultsIfNeeded(context)

    assertTrue(prefs.getBoolean("pref_speak_items_before_scroll_key", false))
    assertEquals("GRANULARITY_CHARACTERS", prefs.getString("pref_current_selector_setting_key", null))
    assertEquals(bundledReadingControlOrder(), prefs.getString(ItemOrder.READING_CONTROLS_KEY, null))
    assertEquals("keep", prefs.getString("custom_user_setting", null))
    assertTrue(state.getBoolean("initial_settings_applied", false))
    assertTrue(!state.getString("initial_settings_snapshot", null).isNullOrBlank())

    prefs.edit().putBoolean("pref_speak_items_before_scroll_key", false).commit()
    SettingsBackup.applyInitialDefaultsIfNeeded(context)

    assertFalse(prefs.getBoolean("pref_speak_items_before_scroll_key", true))
  }

  @Test
  fun laterDefaultChangesUpdateOnlyValuesThatStillMatchTheirPreviousDefaults() {
    val oldSettings =
      """{"format":"sandfly-settings","version":1,"files":{"device":{"default":{"pref_speak_items_before_scroll_key":{"type":"boolean","value":false},"pref_current_selector_setting_key":{"type":"string","value":"GRANULARITY_WORDS"},"removed_default":{"type":"string","value":"old"}}}}}"""
    val newSettings =
      """{"format":"sandfly-settings","version":1,"files":{"device":{"default":{"pref_speak_items_before_scroll_key":{"type":"boolean","value":true},"pref_current_selector_setting_key":{"type":"string","value":"GRANULARITY_HEADINGS"},"new_default":{"type":"boolean","value":true}}}}}"""
    prefs
      .edit()
      .clear()
      .putBoolean("pref_speak_items_before_scroll_key", false)
      .putString("pref_current_selector_setting_key", "GRANULARITY_CHARACTERS")
      .putString("removed_default", "old")
      .putString("custom_user_setting", "keep")
      .commit()

    assertTrue(SettingsBackup.applyInitialSettingsChanges(context, oldSettings, newSettings))

    assertTrue(prefs.getBoolean("pref_speak_items_before_scroll_key", false))
    assertEquals("GRANULARITY_CHARACTERS", prefs.getString("pref_current_selector_setting_key", null))
    assertTrue(prefs.getBoolean("new_default", false))
    assertFalse(prefs.contains("removed_default"))
    assertEquals("keep", prefs.getString("custom_user_setting", null))
  }

  @Test
  fun settingsComeBackAsTheyWere() {
    prefs
      .edit()
      .putBoolean("a_switch", true)
      .putInt("an_int", 3)
      .putLong("a_long", 1L shl 40)
      .putFloat("a_float", 1.5f)
      .putString("a_string", "x")
      .putStringSet("a_set", setOf("1", "2"))
      .commit()
    extra.edit().putString("code", "some_value").commit()
    val json = SettingsBackup.export(context)

    prefs.edit().clear().putBoolean("added_later", true).commit()
    extra.edit().clear().commit()
    assertTrue(SettingsBackup.import(context, json))

    assertEquals(true, prefs.getBoolean("a_switch", false))
    assertEquals(3, prefs.getInt("an_int", 0))
    assertEquals(1L shl 40, prefs.getLong("a_long", 0))
    assertEquals(1.5f, prefs.getFloat("a_float", 0f))
    assertEquals("x", prefs.getString("a_string", null))
    assertEquals(setOf("1", "2"), prefs.getStringSet("a_set", null))
    assertFalse(prefs.contains("added_later"))
    assertEquals("some_value", extra.getString("code", null))
  }

  @Test
  fun apiKeysAndTheUpdaterStateStayOnThePhone() {
    prefs
      .edit()
      .putString("pref_gemini_api_key", "secret")
      .putString("pref_ai_claude_api_key", "secret")
      .putLong(Updater.PREF_LAST_CHECK_TIME, 5)
      .commit()
    val json = SettingsBackup.export(context)
    assertFalse(json.contains("secret"))
    assertFalse(json.contains(Updater.PREF_LAST_CHECK_TIME))

    prefs.edit().putString("pref_gemini_api_key", "mine").putString("pref_ai_claude_api_key", "mine").commit()
    assertTrue(SettingsBackup.import(context, json))
    assertEquals("mine", prefs.getString("pref_gemini_api_key", null))
    assertEquals("mine", prefs.getString("pref_ai_claude_api_key", null))
    assertEquals(5, prefs.getLong(Updater.PREF_LAST_CHECK_TIME, 0))
  }

  @Test
  fun everyProviderKeyStaysLocalWhenExportingAndImportingOlderOrUntrustedFiles() {
    val keys =
      listOf(
        "pref_gemini_api_key",
        "pref_ai_service_api_key",
        "pref_ai_openai_api_key",
        "pref_ai_claude_api_key",
        "pref_ai_groq_api_key",
        "pref_ai_other_api_key",
      )
    val editor = prefs.edit()
    keys.forEachIndexed { index, key -> editor.putString(key, "local-$index") }
    editor.commit()

    val olderBackup = SettingsBackup.export(context)
    keys.forEachIndexed { index, key ->
      assertFalse(olderBackup.contains(key))
      assertFalse(olderBackup.contains("local-$index"))
    }
    assertTrue(SettingsBackup.import(context, olderBackup))
    keys.forEachIndexed { index, key -> assertEquals("local-$index", prefs.getString(key, null)) }

    val incomingKeys =
      keys.joinToString(",") { "\"$it\":{\"type\":\"string\",\"value\":\"incoming\"}" }
    val untrustedBackup =
      """{"format":"sandfly-settings","version":1,"files":{"device":{"default":{$incomingKeys}},"credential":{"default":{$incomingKeys}}}}"""
    assertTrue(SettingsBackup.import(context, untrustedBackup))
    keys.forEachIndexed { index, key -> assertEquals("local-$index", prefs.getString(key, null)) }
  }

  @Test
  fun otherFilesAreRefusedAndChangeNothing() {
    prefs.edit().putBoolean("kept", true).commit()
    assertFalse(SettingsBackup.import(context, "{}"))
    assertFalse(SettingsBackup.import(context, "not json"))
    assertFalse(
      SettingsBackup.import(
        context,
        """{"format":"sandfly-settings","version":1,"files":{"device":{"default":{"k":{"type":"x"}}}}}""",
      )
    )
    assertTrue(prefs.getBoolean("kept", false))
  }

  @Test
  fun aFileNameCannotLeaveTheSettingsFolder() {
    assertTrue(
      SettingsBackup.import(
        context,
        """{"format":"sandfly-settings","version":1,"files":{"device":{"../x":{}}}}""",
      )
    )
  }

  @Test
  fun aBacktalkFileIsImported() {
    prefs
      .edit()
      .clear()
      .putBoolean("sandfly_only", true)
      .putString("pref_gemini_api_key", "k")
      .commit()
    braille.edit().clear().commit()
    val backtalk =
      """{"format":1,"stores":{""" +
        """"main":{"a_switch":{"t":"bool","v":true},"a_float":{"t":"float","v":1.5},""" +
        """"a_set":{"t":"set","v":["1","2"]},"pref_gemini_api_key":{"t":"string","v":"x"},""" +
        """"tutorial_shown":{"t":"bool","v":true}},""" +
        """"braille_keyboard":{"code":{"t":"string","v":"EN_UEB"}}}}"""

    assertTrue(SettingsBackup.import(context, backtalk))

    assertEquals(true, prefs.getBoolean("a_switch", false))
    assertEquals(1.5f, prefs.getFloat("a_float", 0f))
    assertEquals(setOf("1", "2"), prefs.getStringSet("a_set", null))
    assertEquals("EN_UEB", braille.getString("code", null))
    // SandFly's own settings and the key stay; Backtalk's state isn't brought over.
    assertTrue(prefs.getBoolean("sandfly_only", false))
    assertEquals("k", prefs.getString("pref_gemini_api_key", null))
    assertFalse(prefs.contains("tutorial_shown"))
  }

  @Test
  fun aDamagedBacktalkFileChangesNothing() {
    prefs.edit().clear().putBoolean("a_switch", false).commit()
    val damaged =
      """{"format":1,"stores":{"main":{"a_switch":{"t":"bool","v":true},"b":{"t":"what"}}}}"""

    assertFalse(SettingsBackup.import(context, damaged))
    assertFalse(prefs.getBoolean("a_switch", true))
  }
  private fun bundledReadingControlOrder(): String {
    val settings =
      JSONObject(context.assets.open("initial-settings.json").bufferedReader().use { it.readText() })
    return settings
      .getJSONObject("files")
      .getJSONObject("device")
      .getJSONObject("default")
      .getJSONObject(ItemOrder.READING_CONTROLS_KEY)
      .getString("value")
  }

}
