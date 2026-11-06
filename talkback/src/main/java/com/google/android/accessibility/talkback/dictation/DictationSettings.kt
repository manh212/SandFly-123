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

package com.google.android.accessibility.talkback.dictation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import java.util.Locale

/** Dictation settings: how speech is recognized, and which AI service corrects it. */
object DictationSettings {
  const val PREF_RECOGNITION = "pref_dictation_recognition"
  const val PREF_AI_SERVICE = "pref_dictation_ai_service"
  const val PREF_ONLINE_ROUTE = "pref_dictation_online_route"
  const val PREF_WHISPER_MODEL = "pref_dictation_whisper_model"
  const val PREF_RAMBLER_LIVE = "pref_dictation_rambler_live"
  const val PREF_AUTO_STOP_MS = "pref_dictation_auto_stop_ms"
  const val PREF_SPEECH_THRESHOLD = "pref_dictation_speech_threshold"
  const val PREF_AI_EMOJI = "pref_dictation_online_ai_emoji"
  const val PREF_TRANSLATION_SOURCE = "pref_dictation_translation_source"
  const val PREF_TRANSLATION_TARGET = "pref_dictation_translation_target"

  private val TRANSLATION_LANGUAGES =
    setOf("vi", "en", "fr", "es", "de", "ja", "ko", "zh", "th", "id", "ru", "pt", "hi", "ar")

  fun translationSource(prefs: SharedPreferences): String =
    prefs.getString(PREF_TRANSLATION_SOURCE, "vi")
      ?.takeIf { it in TRANSLATION_LANGUAGES } ?: "vi"

  fun translationTarget(prefs: SharedPreferences): String =
    prefs.getString(PREF_TRANSLATION_TARGET, "en")
      ?.takeIf { it in TRANSLATION_LANGUAGES } ?: "en"

  fun addOnlineEmoji(prefs: SharedPreferences): Boolean =
    prefs.getBoolean(PREF_AI_EMOJI, false)

  /** The AI service of automatic descriptions corrects dictation too. */
  const val AI_SAME = "same"
  /** The on-device AI model corrects dictation. */
  const val AI_DEVICE = "device"

  /** The three available speech recognizers. */
  enum class Recognition(val id: String) {
    RAMBLER("rambler"),
    WHISPER("whisper"),
    ANDROID("android");

    companion object {
      fun of(id: String?): Recognition? = entries.firstOrNull { it.id == id }
    }
  }

  /** How online dictation text is post-processed after Gemini transcribes the audio. */
  enum class OnlineRoute(val id: String) {
    GEMINI("gemini"),
    DESCRIPTIONS("descriptions");

    companion object {
      fun of(id: String?): OnlineRoute? = entries.firstOrNull { it.id == id }
    }
  }

  fun recognition(prefs: SharedPreferences): Recognition =
    Recognition.of(prefs.getString(PREF_RECOGNITION, null)) ?: Recognition.ANDROID

  fun setRecognition(prefs: SharedPreferences, recognition: Recognition) =
    prefs.edit { putString(PREF_RECOGNITION, recognition.id) }

  fun onlineRoute(prefs: SharedPreferences): OnlineRoute =
    OnlineRoute.of(prefs.getString(PREF_ONLINE_ROUTE, null)) ?: OnlineRoute.GEMINI

  fun setOnlineRoute(prefs: SharedPreferences, route: OnlineRoute) =
    prefs.edit { putString(PREF_ONLINE_ROUTE, route.id) }

  /** The Gemini API key Rambler uses, or empty when there is none. */
  fun ramblerKey(prefs: SharedPreferences): String =
    GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY)

  /** Whether Rambler transcribes while the user speaks, so that the text is ready sooner. */
  fun ramblerLive(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_RAMBLER_LIVE, true)

  fun autoStopMs(prefs: SharedPreferences): Long =
    prefs.getString(PREF_AUTO_STOP_MS, null)?.toLongOrNull()?.coerceAtLeast(0) ?: 0

  fun speechThresholdDbfs(prefs: SharedPreferences): Int =
    -60 + prefs.getInt(PREF_SPEECH_THRESHOLD, 12).coerceIn(0, 45)

  fun recognizerSpeechMarginDb(prefs: SharedPreferences): Float =
    2f + (speechThresholdDbfs(prefs) + 60) * 0.25f

  /** Whether Rambler recognizes [locale]'s language. */
  fun ramblerSupports(locale: Locale): Boolean = locale.language in RAMBLER_LANGUAGES

  /**
   * Use the explicitly selected recognition service. A password field cannot send audio to
   * Gemini, so only in that case use a downloaded Whisper model or Android recognition.
   */
  fun effectiveRecognition(
    context: Context,
    prefs: SharedPreferences,
    skip: Set<Recognition> = emptySet(),
  ): Recognition {
    val selected = recognition(prefs)
    if (selected !in skip) return selected
    return if (WhisperModels.get(context).activeModel() != null) Recognition.WHISPER
    else Recognition.ANDROID
  }

  fun correctsWithAi(context: Context, prefs: SharedPreferences): Boolean =
    prefs.getBoolean(
      context.getString(R.string.pref_ai_dictation_key),
      context.resources.getBoolean(R.bool.pref_ai_dictation_default),
    )

  /** [AI_SAME], [AI_DEVICE] or an [AiProvider] id. */
  fun aiService(prefs: SharedPreferences): String =
    prefs.getString(PREF_AI_SERVICE, null)?.takeIf { it.isNotEmpty() } ?: AI_SAME

  fun setAiService(prefs: SharedPreferences, service: String) =
    prefs.edit { putString(PREF_AI_SERVICE, service) }

  /**
   * The AI service that corrects dictation: an [AiProvider] that is set up, [AI_DEVICE] when the
   * on-device model is installed, or null when none can.
   */
  fun correctingService(context: Context, prefs: SharedPreferences): String? {
    val chosen = aiService(prefs)
    if (chosen == AI_SAME) {
      if (LocalModelManager.get(context).answersRequests()) return AI_DEVICE
      val active = AiProvider.active(prefs)
      return active.id.takeIf { active != AiProvider.NONE && active.isReady(prefs) }
    }
    if (chosen == AI_DEVICE) return AI_DEVICE.takeIf { LocalModelManager.get(context).isReady() }
    val provider = AiProvider.of(chosen) ?: return null
    return provider.id.takeIf { provider != AiProvider.NONE && provider.isReady(prefs) }
  }

  /** Use the AI configuration shared with automatic image descriptions for text correction. */
  fun descriptionService(context: Context, prefs: SharedPreferences): String? {
    if (LocalModelManager.get(context).answersRequests()) return AI_DEVICE
    val provider = AiProvider.active(prefs)
    return provider.id.takeIf { provider != AiProvider.NONE && provider.isReady(prefs) }
  }

  /** A cloud AI provider for translation and opt-in emoji rewriting; never uses on-device AI. */
  fun onlineService(prefs: SharedPreferences): String? {
    val chosen = AiProvider.of(aiService(prefs))
    if (chosen != null && chosen != AiProvider.NONE && chosen.isReady(prefs)) return chosen.id
    val active = AiProvider.active(prefs)
    if (active != AiProvider.NONE && active.isReady(prefs)) return active.id
    return AiProvider.entries.firstOrNull { it != AiProvider.NONE && it.isReady(prefs) }?.id
  }

  /** The languages of Gemini 3.5 Transcribe, by ISO 639 code as Java's [Locale] gives them. */
  private val RAMBLER_LANGUAGES =
    setOf(
      "af", "am", "ar", "hy", "as", "az", "be", "bn", "bs", "bg", "my", "yue", "ca", "ceb", "km",
      "hr", "cs", "da", "nl", "en", "et", "fa", "fil", "tl", "fi", "fr", "gl", "ka", "de", "el",
      "gu", "ha", "he", "iw", "hi", "hu", "is", "id", "in", "it", "ja", "jv", "kea", "kn", "kk",
      "ko", "ky", "lv", "ln", "lt", "mk", "ms", "ml", "mt", "zh", "mr", "mn", "ne", "nb", "no",
      "or", "pl", "pt", "pa", "ro", "ru", "sr", "sd", "sk", "sl", "es", "sw", "sv", "tg", "te",
      "th", "tr", "uk", "uz", "vi",
    )
}
