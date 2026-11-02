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

import com.google.android.accessibility.talkback.report.LatencyMeasurementSession
import com.google.android.accessibility.talkback.gesture.GestureProfiles
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TalkBackServiceBeforeConnectTest {
  private class InfrastructureReached : RuntimeException()

  class StartupCheckpointService : TalkBackService() {
    fun connect() = onServiceConnected()

    override fun initializeInfrastructure() {
      throw InfrastructureReached()
    }
  }

  @Test
  fun freshConnectionInitializesPreferencesBeforeResettingGestureProfiles() {
    checkConnection(existingProfile = false)
  }

  @Test
  fun reconnectAfterUpdateSelectsDefaultWithoutDeletingSavedProfilesOrSettings() {
    checkConnection(existingProfile = true)
  }

  private fun checkConnection(existingProfile: Boolean) {
    val service = Robolectric.buildService(StartupCheckpointService::class.java).create().get()
    val prefs = SharedPreferencesUtils.getSharedPreferences(service)
    val prefsField = TalkBackService::class.java.getDeclaredField("prefs").apply { isAccessible = true }
    assertNull(prefsField.get(service))
    prefs.edit().clear().putBoolean("startup_setting_to_keep", true).commit()
    val profile = GestureProfiles.Profile("work", "Work", mapOf("pref_shortcut_up" to "HOME"))
    if (existingProfile) {
      GestureProfiles.write(prefs, listOf(profile))
      GestureProfiles.select(service, prefs, profile.id)
      assertTrue(GestureProfiles.isActive())
    }
    try {
      assertThrows(InfrastructureReached::class.java) { service.connect() }
      assertNotNull(prefsField.get(service))
      assertEquals("", prefs.getString(GestureProfiles.ACTIVE_KEY, null))
      assertFalse(GestureProfiles.isActive())
      assertEquals(if (existingProfile) listOf(profile) else emptyList(), GestureProfiles.read(prefs))
      assertTrue(prefs.getBoolean("startup_setting_to_keep", false))
    } finally {
      prefs.edit().clear().commit()
      GestureProfiles.load(prefs)
    }
  }

  @Test
  fun noExitControllerBeforeTheServiceIsConnected() {
    val service = Robolectric.buildService(TalkBackService::class.java).create().get()

    assertNull(service.talkBackExitController)
  }
  @Test
  fun measurementSessionCanStartBeforeSpeechInitialization() {
    Robolectric.buildService(TalkBackService::class.java).create().get()

    LatencyMeasurementSession.reset()
  }
}
