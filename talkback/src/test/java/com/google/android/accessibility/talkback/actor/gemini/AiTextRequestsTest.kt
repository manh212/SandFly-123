/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.actor.gemini

import android.content.Context
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.function.Consumer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiTextRequestsTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private class RecordingPerformer : AiTextRequestPerformer {
    var cloudRequests = 0
    var onDeviceRequests = 0

    override fun performRequest(
      urls: List<String>,
      apiKey: String,
      postData: JSONObject,
      callback: GeminiRestRequestPerformer.GeminiRestResponseCallback,
    ) {
      cloudRequests++
      callback.onFailure(GeminiFailure.other("Cloud request was not expected"))
    }

    override fun performOnDeviceRequest(
      postData: JSONObject,
      callback: GeminiRestRequestPerformer.GeminiRestResponseCallback,
    ) {
      onDeviceRequests++
      callback.onFailure(GeminiFailure.other("On-device recognition failed"))
    }

    override fun cancelExistingRequestIfNeeded() {}
  }

  @Test
  fun imageRecognitionStaysOnDeviceWhenThatProviderIsSelected() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val wasOnDevice = OnDeviceAiSettings.isOnDevice(prefs)
    OnDeviceAiSettings.setOnDevice(prefs, true)
    val performer = RecordingPerformer()
    var answer: String? = null
    var failure: GeminiFailure? = null

    try {
      AiTextRequests(context, Consumer<String> {}, performer)
        .askImage("Recognize this screen", byteArrayOf(1, 2, 3)) { text, error ->
          answer = text
          failure = error
        }

      assertEquals(1, performer.onDeviceRequests)
      assertEquals(0, performer.cloudRequests)
      assertNull(answer)
      assertEquals("On-device recognition failed", failure?.message)
    } finally {
      OnDeviceAiSettings.setOnDevice(prefs, wasOnDevice)
    }
  }
}
