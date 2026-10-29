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
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.function.Consumer
import org.json.JSONArray
import org.json.JSONObject

class AiTextRequests(context: Context, announce: Consumer<String>) {
  private val appContext = context.applicationContext
  private val prefs = SharedPreferencesUtils.getSharedPreferences(appContext)
  private val performer = LocalGemmaRequestPerformer(appContext, announce)

  fun ask(prompt: String, json: Boolean, done: (String?, GeminiFailure?) -> Unit) {
    val request =
      JSONObject()
        .put(
          "contents",
          JSONArray()
            .put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))),
        )
    if (json) request.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
    val urls = GeminiModel.chain(prefs).map { GEMINI_URL + it + ":generateContent" }
    performer.performRequest(
      urls,
      geminiKey(prefs),
      request,
      object : GeminiRestRequestPerformer.GeminiRestResponseCallback {
        override fun onResponse(response: DataFieldUtils.GeminiResponse) {
          val text = response.text()?.trim().orEmpty()
          if (text.isEmpty()) done(null, GeminiFailure.other("Empty answer")) else done(text, null)
        }

        override fun onFailure(failure: GeminiFailure) = done(null, failure)

        override fun onCancelled() = done(null, null)
      },
    )
  }

  fun cancel() = performer.cancelExistingRequestIfNeeded()

  companion object {
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

    @JvmStatic
    fun isSetUp(context: Context): Boolean {
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      return LocalModelManager.get(context).answersRequests() ||
        AiService.isSetUp(prefs) ||
        geminiKey(prefs).isNotEmpty()
    }

    private fun geminiKey(prefs: SharedPreferences): String =
      if (AiProvider.active(prefs) != AiProvider.GEMINI) ""
      else GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY)
  }
}
