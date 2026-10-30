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

package com.google.android.accessibility.talkback.translation

import android.icu.text.ListFormatter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LocaleSpan
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.ClipboardHistory
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.translation.TranslationFailure.Kind
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechControllerImpl
import com.google.android.accessibility.utils.output.LastSpokenTextHolder
import java.util.Locale

class TranslateCommand(private val context: Context, private val speaker: SpeechControllerImpl) {
  private val prefs = SharedPreferencesUtils.getSharedPreferences(context)
  private var translator: Translator? = null

  private var original: String? = null
  private var spoken: String? = null

  private var cached: Translation? = null
  private var cachedFor: String? = null

  fun translateLast() = translateText(LastSpokenTextHolder.getLastSpokenText())

  fun translateSaved() = translateText(LastSpokenTextHolder.getSavedSpokenText())

  private fun translateText(value: String?) {
    val text = value?.trim().orEmpty()
    val again = text.isNotEmpty() && (text == spoken || text == cached?.text)
    val source = if (again) original ?: text else text
    if (source.isEmpty()) return status(context.getString(R.string.translation_nothing))
    val translator = translator ?: Translator(context).also { translator = it }
    val key = settingsKey(translator.service()) + "\n" + source
    cached?.let { if (key == cachedFor) return speak(source, it) }
    translator.translate(source) { result ->
      when (result) {
        is Translator.Result.Done -> {
          cached = result.translation
          cachedFor = key
          speak(source, result.translation)
        }
        is Translator.Result.Downloading ->
          status(context.getString(R.string.translation_downloading, names(result.languages)))
        is Translator.Result.Failed -> status(failureText(result.failure, result.service))
      }
    }
  }

  fun shutdown() {
    translator?.shutdown()
    translator = null
  }

  private fun speak(source: String, translation: Translation) {
    if (TranslationLanguages.same(translation.from, translation.to) && translation.text == source) {
      return status(context.getString(R.string.translation_already_in, TranslationLanguages.name(translation.to)))
    }
    val text = SpannableString(translation.text)
    if (TranslationSettings.targetVoice(prefs)) {
      text.setSpan(LocaleSpan(Locale.forLanguageTag(translation.to)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val speech = SpannableStringBuilder()
    val from = translation.from
    if (TranslationSettings.saySource(prefs) && from != null) {
      speech.append(context.getString(R.string.translation_from, TranslationLanguages.name(from))).append(' ')
    }
    speech.append(text)
    original = source
    spoken = speech.toString()
    if (TranslationSettings.copy(prefs)) {
      ClipboardHistory.copy(service, translation.text)
    }

    speaker.speak(speech, EVENT_ID_UNTRACKED, options(FeedbackItem.FLAG_FORCE_FEEDBACK_ALL))
  }

  private fun status(text: String) {
    speaker.speak(
      text,
      EVENT_ID_UNTRACKED,
      options(FeedbackItem.FLAG_FORCE_FEEDBACK_ALL or FeedbackItem.FLAG_NO_HISTORY),
    )
  }

  private fun options(flags: Int): SpeechController.SpeakOptions =
    SpeechController.SpeakOptions.create()
      .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
      .setFlags(flags)
      .setSpeechParams(AutomaticTranslation.untranslated())

  private fun settingsKey(translationService: TranslationService): String =
    listOf(
        translationService.id,
        TranslationSettings.source(prefs),
        TranslationSettings.target(prefs),
        TranslationSettings.secondTarget(prefs),
      )
      .joinToString("|")

  private fun names(languages: List<String>): String =
    ListFormatter.getInstance().format(languages.map { TranslationLanguages.name(it) })

  private fun failureText(failure: TranslationFailure, translationService: TranslationService): String {
    val name = context.getString(translationService.label)
    return when (failure.kind) {
      Kind.NOT_SET_UP ->
        when {
          translationService == TranslationService.AI -> context.getString(R.string.ai_service_not_set_up)
          translationService.needsKey -> context.getString(R.string.translation_error_no_key, name)
          else -> context.getString(R.string.translation_error_no_address, name)
        }
      Kind.CONNECTION ->
        if (translationService == TranslationService.ON_DEVICE) context.getString(R.string.translation_error_download)
        else context.getString(R.string.translation_error_connection, name)
      Kind.API_KEY -> context.getString(R.string.translation_error_api_key, name)
      Kind.QUOTA -> context.getString(R.string.translation_error_quota, name)
      Kind.LANGUAGE ->
        context.getString(R.string.translation_error_language, name, TranslationLanguages.name(failure.detail))
      Kind.NOT_DETECTED -> context.getString(R.string.translation_error_not_detected)
      Kind.DOWNLOADING -> context.getString(R.string.translation_error_downloading)
      Kind.DETECTION_DOWNLOADING -> context.getString(R.string.translation_error_detection_downloading)
      Kind.OTHER ->
        when {

          translationService == TranslationService.AI && failure.detail.isNotEmpty() -> failure.detail
          failure.detail.isEmpty() -> context.getString(R.string.translation_error, name)
          else -> context.getString(R.string.translation_error_details, name, failure.detail)
        }
    }
  }
}
