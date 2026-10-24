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

package com.google.android.accessibility.talkback

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.PrefsSelfHeal.Result
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSelfHealTest {
  private val context: Context = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)

  @Before
  fun setUp() {
    prefs.edit().clear().commit()
    PrefsSelfHeal.rewriteCount = 0
  }

  @Test
  fun versionChanged_rewritesOnceAndStampsTheMarker() {
    seedValues()

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})
    assertEquals(200L, prefs.getLong(PrefsSelfHeal.KEY_HEALED_VERSION, -1L))
    assertEquals(1, PrefsSelfHeal.rewriteCount)

    // The next start on the same version must not rewrite again.
    assertEquals(Result.NOT_NEEDED, PrefsSelfHeal.heal(prefs, 200L) {})
    assertEquals(1, PrefsSelfHeal.rewriteCount)
  }

  @Test
  fun sameVersion_doesNotRewrite() {
    seedValues()
    prefs.edit().putLong(PrefsSelfHeal.KEY_HEALED_VERSION, 200L).commit()

    assertEquals(Result.NOT_NEEDED, PrefsSelfHeal.heal(prefs, 200L) {})
    assertEquals(0, PrefsSelfHeal.rewriteCount)
  }

  @Test
  fun aLaterVersionChange_rewritesAgain() {
    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})
    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 201L) {})

    assertEquals(201L, prefs.getLong(PrefsSelfHeal.KEY_HEALED_VERSION, -1L))
    assertEquals(2, PrefsSelfHeal.rewriteCount)
  }

  @Test
  fun everyValueTypeSurvivesTheRewriteUnchanged() {
    seedValues()
    prefs.edit().putLong(PrefsSelfHeal.KEY_HEALED_VERSION, 100L).commit()
    val before = prefs.all.toMap() - PrefsSelfHeal.KEY_HEALED_VERSION

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})

    assertEquals(before, prefs.all.toMap() - PrefsSelfHeal.KEY_HEALED_VERSION)
    assertEquals(true, prefs.getBoolean("a_switch", false))
    assertEquals(3, prefs.getInt("an_int", 0))
    assertEquals(1L shl 40, prefs.getLong("a_long", 0))
    assertEquals(1.5f, prefs.getFloat("a_float", 0f))
    assertEquals("x", prefs.getString("a_string", null))
    assertEquals(setOf("1", "2"), prefs.getStringSet("a_set", null))
  }

  @Test
  fun snapshotRunsBeforeTheRewriteAndSeesThePreHealState() {
    prefs.edit().putLong(PrefsSelfHeal.KEY_HEALED_VERSION, 100L).commit()
    var snapshotRan = false
    var markerSeenBySnapshot: Any? = null

    val result =
      PrefsSelfHeal.heal(prefs, 200L) {
        snapshotRan = true
        markerSeenBySnapshot = prefs.all[PrefsSelfHeal.KEY_HEALED_VERSION]
      }

    assertEquals(Result.HEALED, result)
    assertTrue(snapshotRan)
    assertEquals(100L, markerSeenBySnapshot)
  }

  @Test
  fun snapshotIsNotTakenWhenNoHealIsNeeded() {
    prefs.edit().putLong(PrefsSelfHeal.KEY_HEALED_VERSION, 200L).commit()
    var snapshotRan = false

    assertEquals(Result.NOT_NEEDED, PrefsSelfHeal.heal(prefs, 200L) { snapshotRan = true })
    assertFalse(snapshotRan)
  }

  @Test
  fun rewriteTouchesThePreferencesFileOnDisk() {
    seedValues()
    val file = prefsFile()
    assertTrue(file.isFile)
    val longAgo = 1_000_000_000_000L
    assertTrue(file.setLastModified(longAgo))

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})

    assertTrue(file.lastModified() > longAgo)
  }

  @Test
  fun unexpectedValuesAreSkippedNotFatal() {
    val fake =
      FakePrefs(
        prefs,
        allOverride =
          mapOf<String, Any?>(
            "a_string" to "kept",
            "a_double" to 1.5,
            "an_int_set" to setOf(1, 2),
            "a_null" to null,
          ),
      )

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(fake, 200L) {})

    assertEquals("kept", prefs.getString("a_string", null))
    assertEquals(200L, prefs.getLong(PrefsSelfHeal.KEY_HEALED_VERSION, -1L))
    assertFalse(prefs.contains("a_double"))
    assertFalse(prefs.contains("an_int_set"))
    assertFalse(prefs.contains("a_null"))
  }

  @Test
  fun unreadablePreferences_failSoftly() {
    val fake = FakePrefs(prefs, throwOnGetAll = true)

    assertEquals(Result.FAILED, PrefsSelfHeal.heal(fake, 200L) {})
    assertEquals(0, PrefsSelfHeal.rewriteCount)
  }

  @Test
  fun failingCommit_failSoftlyAndIsRetriedOnTheNextStart() {
    seedValues()
    val fake = FakePrefs(prefs, throwOnCommit = true)

    assertEquals(Result.FAILED, PrefsSelfHeal.heal(fake, 200L) {})
    assertEquals(0, PrefsSelfHeal.rewriteCount)
    // The marker was never stamped, so the next start tries the heal again.
    assertFalse(prefs.contains(PrefsSelfHeal.KEY_HEALED_VERSION))
    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})
  }

  @Test
  fun startupEntryPoint_healsOnceForTheInstalledVersion() {
    PrefsSelfHeal.healIfVersionChanged(context)
    assertEquals(1, PrefsSelfHeal.rewriteCount)
    assertTrue(prefs.contains(PrefsSelfHeal.KEY_HEALED_VERSION))

    PrefsSelfHeal.healIfVersionChanged(context)
    assertEquals(1, PrefsSelfHeal.rewriteCount)
  }

  private fun seedValues() {
    prefs
      .edit()
      .putBoolean("a_switch", true)
      .putInt("an_int", 3)
      .putLong("a_long", 1L shl 40)
      .putFloat("a_float", 1.5f)
      .putString("a_string", "x")
      .putStringSet("a_set", setOf("1", "2"))
      .commit()
  }

  /** The file behind the main preferences, resolved the way the diagnostics digest resolves it. */
  private fun prefsFile(): File {
    val deContext = ContextCompat.createDeviceProtectedStorageContext(context) ?: context
    val name = PreferenceManager.getDefaultSharedPreferencesName(deContext)
    return File(File(deContext.dataDir, "shared_prefs"), "$name.xml")
  }

  /** Delegates to a real preferences file, with one failure or one result overridden. */
  private class FakePrefs(
    private val delegate: SharedPreferences,
    private val allOverride: Map<String, Any?>? = null,
    private val throwOnGetAll: Boolean = false,
    private val throwOnCommit: Boolean = false,
  ) : SharedPreferences by delegate {
    override fun getAll(): MutableMap<String, Any?> =
      when {
        throwOnGetAll -> throw RuntimeException("simulated read failure")
        allOverride != null -> HashMap(allOverride)
        else -> delegate.all
      }

    override fun edit(): SharedPreferences.Editor {
      val editor = delegate.edit()
      return if (throwOnCommit) ThrowingEditor(editor) else editor
    }
  }

  private class ThrowingEditor(private val delegate: SharedPreferences.Editor) :
    SharedPreferences.Editor by delegate {
    override fun commit(): Boolean = throw RuntimeException("simulated write failure")

    override fun apply() {
      throw RuntimeException("simulated write failure")
    }
  }
}
