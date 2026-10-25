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
    val bookkeeping = setOf(PrefsSelfHeal.KEY_HEALED_VERSION, PrefsSelfHeal.KEY_PENDING_LIVE_REAPPLY)
    val before = prefs.all.toMap() - bookkeeping

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})

    assertEquals(before, prefs.all.toMap() - bookkeeping)
    assertEquals(true, prefs.getBoolean("a_switch", false))
    assertEquals(3, prefs.getInt("an_int", 0))
    assertEquals(1L shl 40, prefs.getLong("a_long", 0))
    assertEquals(1.5f, prefs.getFloat("a_float", 0f))
    assertEquals("x", prefs.getString("a_string", null))
    assertEquals(setOf("1", "2"), prefs.getStringSet("a_set", null))
  }

  @Test
  fun versionChanged_stampsThePendingLiveReapply() {
    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})

    assertEquals(200L, prefs.getLong(PrefsSelfHeal.KEY_PENDING_LIVE_REAPPLY, -1L))
    assertEquals(200L, PrefsSelfHeal.pendingLiveReapplyVersion(prefs))
  }

  @Test
  fun sameVersion_keepsAnUnconsumedPendingLiveReapply() {
    // The service was restarted before the keyboard ever appeared: the marker says this
    // version is healed, but the live re-apply is still owed and must survive the no-op.
    prefs.edit().putLong(PrefsSelfHeal.KEY_HEALED_VERSION, 200L).commit()
    prefs.edit().putLong(PrefsSelfHeal.KEY_PENDING_LIVE_REAPPLY, 200L).commit()

    assertEquals(Result.NOT_NEEDED, PrefsSelfHeal.heal(prefs, 200L) {})
    assertEquals(200L, PrefsSelfHeal.pendingLiveReapplyVersion(prefs))
  }

  @Test
  fun aLaterVersionChange_movesThePendingLiveReapplyToTheNewVersion() {
    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 200L) {})
    PrefsSelfHeal.clearPendingLiveReapply(prefs)
    assertEquals(0L, PrefsSelfHeal.pendingLiveReapplyVersion(prefs))

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(prefs, 201L) {})
    assertEquals(201L, PrefsSelfHeal.pendingLiveReapplyVersion(prefs))
  }

  @Test
  fun noPendingLiveReapply_readsAsZeroAndClearingIsHarmless() {
    assertEquals(0L, PrefsSelfHeal.pendingLiveReapplyVersion(prefs))

    PrefsSelfHeal.clearPendingLiveReapply(prefs)
    assertFalse(prefs.contains(PrefsSelfHeal.KEY_PENDING_LIVE_REAPPLY))
  }

  @Test
  fun failingCommit_stampsNoPendingLiveReapply() {
    val stub = StubPrefs(prefs, throwOnCommit = true)

    assertEquals(Result.FAILED, PrefsSelfHeal.heal(stub, 200L) {})
    assertFalse(prefs.contains(PrefsSelfHeal.KEY_PENDING_LIVE_REAPPLY))
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
    val stub =
      StubPrefs(
        prefs,
        allOverride =
          mapOf<String, Any?>(
            "a_string" to "kept",
            "a_double" to 1.5,
            "an_int_set" to setOf(1, 2),
            "a_null" to null,
          ),
      )

    assertEquals(Result.HEALED, PrefsSelfHeal.heal(stub, 200L) {})

    assertEquals("kept", prefs.getString("a_string", null))
    assertEquals(200L, prefs.getLong(PrefsSelfHeal.KEY_HEALED_VERSION, -1L))
    assertFalse(prefs.contains("a_double"))
    assertFalse(prefs.contains("an_int_set"))
    assertFalse(prefs.contains("a_null"))
  }

  @Test
  fun unreadablePreferences_failSoftly() {
    val stub = StubPrefs(prefs, throwOnGetAll = true)

    assertEquals(Result.FAILED, PrefsSelfHeal.heal(stub, 200L) {})
    assertEquals(0, PrefsSelfHeal.rewriteCount)
  }

  @Test
  fun failingCommit_failSoftlyAndIsRetriedOnTheNextStart() {
    seedValues()
    val stub = StubPrefs(prefs, throwOnCommit = true)

    assertEquals(Result.FAILED, PrefsSelfHeal.heal(stub, 200L) {})
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

  /**
   * Forwards everything to a real preferences file, with one result or one failure replaced.
   * Written out method by method (the signatures mirror the in-tree FakeSharedPreferences)
   * rather than by interface delegation.
   */
  private class StubPrefs(
    private val delegate: SharedPreferences,
    private val allOverride: Map<String, Any?>? = null,
    private val throwOnGetAll: Boolean = false,
    private val throwOnCommit: Boolean = false,
  ) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> =
      when {
        throwOnGetAll -> throw RuntimeException("simulated read failure")
        allOverride != null -> HashMap(allOverride)
        else -> delegate.all
      }

    override fun getString(key: String, defValue: String?): String? =
      delegate.getString(key, defValue)

    override fun getStringSet(
      key: String,
      defValues: MutableSet<String>?,
    ): MutableSet<String>? = delegate.getStringSet(key, defValues)

    override fun getInt(key: String, defValue: Int): Int = delegate.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = delegate.getLong(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = delegate.getFloat(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
      delegate.getBoolean(key, defValue)

    override fun contains(key: String): Boolean = delegate.contains(key)

    override fun edit(): SharedPreferences.Editor {
      val editor = delegate.edit()
      return if (throwOnCommit) ThrowingEditor(editor) else editor
    }

    override fun registerOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
      delegate.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
      delegate.unregisterOnSharedPreferenceChangeListener(listener)
    }
  }

  /** Forwards every staged change to the real editor, but the write itself always fails. */
  private class ThrowingEditor(private val delegate: SharedPreferences.Editor) :
    SharedPreferences.Editor {
    override fun putString(key: String, value: String?): SharedPreferences.Editor =
      delegate.putString(key, value)

    override fun putStringSet(
      key: String,
      values: MutableSet<String>?,
    ): SharedPreferences.Editor = delegate.putStringSet(key, values)

    override fun putInt(key: String, value: Int): SharedPreferences.Editor =
      delegate.putInt(key, value)

    override fun putLong(key: String, value: Long): SharedPreferences.Editor =
      delegate.putLong(key, value)

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
      delegate.putFloat(key, value)

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
      delegate.putBoolean(key, value)

    override fun remove(key: String): SharedPreferences.Editor = delegate.remove(key)

    override fun clear(): SharedPreferences.Editor = delegate.clear()

    override fun commit(): Boolean = throw RuntimeException("simulated write failure")

    override fun apply() {
      throw RuntimeException("simulated write failure")
    }
  }
}
