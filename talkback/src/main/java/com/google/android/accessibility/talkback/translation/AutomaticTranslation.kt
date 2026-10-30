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

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import android.util.LruCache
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechControllerImpl
import com.google.android.libraries.accessibility.utils.log.LogUtils
import com.google.mlkit.common.MlKitException
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import java.util.Locale
import java.util.function.Consumer

class AutomaticTranslation
@VisibleForTesting
internal constructor(context: Context, private val engine: Engine) : SpeechControllerImpl.SpeechHold {
  private val appContext = context.applicationContext
  private val prefs = SharedPreferencesUtils.getSharedPreferences(appContext)
  private val handler = Handler(Looper.getMainLooper())

  private val languages = LruCache<String, Detection>(MAX_LANGUAGES)

  private val translations = LruCache<String, String>(MAX_TRANSLATIONS)
  private val pending = ArrayDeque<Held>()
  /** The timeouts of the translations under way, which end them untranslated. */
  private val inFlight = LinkedHashSet<Runnable>()
  /** Texts identified less surely in each language, up to [DOWNLOAD_AFTER_TEXTS], by hash. */
  private val sightings = mutableMapOf<String, MutableSet<Int>>()

  private var lastSeen: String? = null

  private val announcedDownloads = mutableSetOf<String>()
  private var announcedNotSetUp = false

  private inner class Held(
    val text: CharSequence,

    val uninterruptible: Boolean,
    val release: Consumer<CharSequence?>,
  ) {
    var result: CharSequence? = null
    var dropped = false
    val done: Boolean
      get() = result != null || dropped

    fun ready(spoken: CharSequence) {
      if (done) return
      result = spoken
      releaseReady()
    }
  }

  override fun hold(
    text: CharSequence,
    queueMode: Int,
    flags: Int,
    release: Consumer<CharSequence?>,
  ): Boolean {
    if (queueMode and SpeechController.QUEUE_MODE_BIT_INTERRUPT != 0) dropPending()
    val plain = text.toString()

    val moved = queueMode and SpeechController.QUEUE_MODE_BIT_INTERRUPT != 0 ||
      flags and FeedbackItem.FLAG_ADVANCE_CONTINUOUS_READING != 0
    if (moved || lastSeen == null) lastSeen = plain
    val known = if (letters(plain) < MIN_LETTERS) NO_LANGUAGE else languages.get(plain)?.language
    if (pending.isEmpty() && known != null && !translates(known)) return false
    val held =
      Held(text, queueMode and SpeechController.QUEUE_MODE_BIT_UNINTERRUPTIBLE_BY_NEW_SPEECH != 0, release)
    pending.addLast(held)
    process(text, notification = false) { held.ready(it) }
    return true
  }

  private fun process(text: CharSequence, notification: Boolean, done: (CharSequence) -> Unit) {
    val plain = text.toString()
    var finished = false
    val timeout =
      object : Runnable {
        override fun run() {
          inFlight.remove(this)
          if (!finished) {
            finished = true
            done(text)
          }
        }
      }
    val finish = { result: CharSequence ->
      if (!finished) {
        finished = true
        handler.removeCallbacks(timeout)
        inFlight.remove(timeout)
        done(result)
      }
    }
    if (letters(plain) < MIN_LETTERS) return finish(text)
    inFlight += timeout
    handler.postDelayed(timeout, MAX_WAIT_MS)
    val waitForDownload = {
      handler.removeCallbacks(timeout)
      handler.postDelayed(timeout, MAX_DOWNLOAD_WAIT_MS)
      Unit
    }
    val withLanguage = { detection: Detection? ->
      if (detection != null && translates(detection.language)) {
        translate(plain, detection, notification, waitForDownload) { finish(it ?: text) }
      } else {
        finish(text)
      }
    }
    val known = languages.get(plain)
    if (known != null) return withLanguage(known)
    engine.detect(plain) { detection ->
      if (detection != null) languages.put(plain, detection)
      if (!finished) withLanguage(detection)
    }
  }

  @VisibleForTesting
  internal fun translateNotification(text: CharSequence, done: (CharSequence) -> Unit) =
    process(text, notification = true, done)

  private fun translates(language: String): Boolean {
    if (language == NO_LANGUAGE) return false
    val source = TranslationSettings.source(prefs)
    return !TranslationLanguages.same(language, TranslationSettings.target(prefs)) &&
      (source == null || TranslationLanguages.same(language, source)) &&
      TranslationSettings.untranslated(prefs).none { TranslationLanguages.same(it, language) }
  }

  private fun translate(
    text: String,
    detection: Detection,
    notification: Boolean,
    waitForDownload: () -> Unit,
    done: (CharSequence?) -> Unit,
  ) {
    val from = detection.language
    val service = TranslationSettings.autoService(prefs, engine.onDeviceAvailable)
    val to = TranslationSettings.target(prefs)
    val key = translationKey(service, from, to, text)
    translations.get(key)?.let { return done(spoken(it, to)) }
    val downloads =
      when {
        !downloadsFor(text, detection) -> Translator.Downloads.NONE
        notification -> Translator.Downloads.WAIT
        else -> Translator.Downloads.START
      }
    val onDownloaded = { if (!closed && lastSeen == text) sayAgain(text, from) }
    engine.translate(text, service, from, to, downloads, onDownloaded) { result ->
      when (result) {
        is Translator.Result.Done -> {
          translations.put(key, result.translation.text)
          done(spoken(result.translation.text, to))
        }
        is Translator.Result.Downloading -> {
          announceDownload(result.languages)
          if (downloads == Translator.Downloads.WAIT) waitForDownload()
        }
        is Translator.Result.Failed -> {
          if (result.failure.kind == TranslationFailure.Kind.NOT_SET_UP) announceNotSetUp(service)
          else if (result.failure.kind != TranslationFailure.Kind.DOWNLOADING) {
            LogUtils.w(TAG, "Not translated: %s %s", result.failure.kind, result.failure.detail)
          }
          done(null)
        }
      }
    }
  }

  private fun downloadsFor(text: String, detection: Detection): Boolean {
    if (detection.confidence >= SURE_CONFIDENCE && letters(text) >= SURE_LETTERS) return true
    val seen = sightings.getOrPut(detection.language) { mutableSetOf() }
    if (seen.size < DOWNLOAD_AFTER_TEXTS) seen += text.hashCode()
    return seen.size >= DOWNLOAD_AFTER_TEXTS
  }

  private fun sayAgain(text: String, from: String) {
    val service = TranslationSettings.autoService(prefs, engine.onDeviceAvailable)
    val to = TranslationSettings.target(prefs)
    engine.translate(text, service, from, to, Translator.Downloads.NONE, {}) { result ->
      if (result !is Translator.Result.Done || lastSeen != text) return@translate
      translations.put(translationKey(service, from, to, text), result.translation.text)
      engine.speak(spoken(result.translation.text, to), history = true)
    }
  }

  private fun spoken(translation: String, to: String): CharSequence {
    if (!TranslationSettings.targetVoice(prefs)) return translation
    val text = SpannableString(translation)
    text.setSpan(LocaleSpan(Locale.forLanguageTag(to)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return text
  }

  private fun releaseReady() {
    while (pending.isNotEmpty()) {
      val first = pending.first()
      if (!first.done) return
      pending.removeFirst()
      if (!first.dropped) first.release.accept(first.result)
    }
  }

  private fun dropPending() {
    val dropped = pending.filter { !it.uninterruptible }
    pending.removeAll { !it.uninterruptible }
    for (held in dropped) {
      held.dropped = true
      held.release.accept(null)
    }
    releaseReady()
  }

  private fun announceDownload(languages: List<String>) {
    if (!announcedDownloads.addAll(languages)) return
    val names = android.icu.text.ListFormatter.getInstance().format(languages.map { TranslationLanguages.name(it) })
    status(appContext.getString(R.string.translation_downloading, names))
  }

  private fun announceNotSetUp(service: TranslationService) {
    if (announcedNotSetUp) return
    announcedNotSetUp = true
    val name = appContext.getString(service.label)
    status(
      appContext.getString(
        if (service.needsKey) R.string.translation_error_no_key else R.string.translation_error_no_address,
        name,
      )
    )
  }

  private fun status(text: String) {
    if (!closed) engine.speak(text, history = false)
  }

  @VisibleForTesting
  internal fun close() {
    closed = true
    handler.removeCallbacksAndMessages(null)

    val held = pending.toList()
    pending.clear()
    for (item in held) {
      if (!item.dropped) item.release.accept(item.result ?: item.text)
      item.dropped = true
    }
    // Translations under way end untranslated too, such as notifications, which are read then.
    val translating = inFlight.toList()
    inFlight.clear()
    translating.forEach { it.run() }
    engine.close()
  }

  internal interface Engine {
    val onDeviceAvailable: Boolean

    fun detect(text: String, done: (Detection?) -> Unit)

    fun translate(
      text: String,
      service: TranslationService,
      from: String,
      to: String,
      downloads: Translator.Downloads,
      onDownloaded: () -> Unit,
      done: (Translator.Result) -> Unit,
    )

    fun speak(text: CharSequence, history: Boolean)

    fun close()
  }

  internal data class Detection(val language: String, val confidence: Float)

  private class PhoneEngine(context: Context) : Engine {
    private val translator = Translator(context)
    private var identifier: LanguageIdentifier? = null
    override val onDeviceAvailable = OnDeviceTranslations.isAvailable

    override fun detect(text: String, done: (Detection?) -> Unit) {
      val client =
        identifier
          ?: try {
            LanguageIdentification.getClient(
                LanguageIdentificationOptions.Builder().setConfidenceThreshold(MIN_CONFIDENCE).build()
              )
              .also { identifier = it }
          } catch (e: IllegalStateException) {

            LogUtils.w(TAG, "No language identification: %s", e)
            null
          }
      if (client == null) return done(null)
      client
        .identifyPossibleLanguages(text)
        .addOnSuccessListener { found ->
          val best = found.maxByOrNull { it.confidence }
          val code = best?.languageTag.orEmpty()

          val language = (if (code.endsWith("-Latn")) null else TranslationLanguages.normalize(code)) ?: NO_LANGUAGE
          done(Detection(language, if (language == NO_LANGUAGE) 0f else best!!.confidence))
        }
        .addOnFailureListener { e ->

          if ((e as? MlKitException)?.errorCode != MlKitException.UNAVAILABLE) {
            LogUtils.w(TAG, "Language not identified: %s", e)
          }
          done(null)
        }
    }

    override fun translate(
      text: String,
      service: TranslationService,
      from: String,
      to: String,
      downloads: Translator.Downloads,
      onDownloaded: () -> Unit,
      done: (Translator.Result) -> Unit,
    ) = translator.translateNow(text, service, from, to, downloads, onDownloaded, done)

    override fun speak(text: CharSequence, history: Boolean) {
      val flags = if (history) 0 else FeedbackItem.FLAG_FORCE_FEEDBACK_ALL or FeedbackItem.FLAG_NO_HISTORY
      TalkBackService.getInstance()
        ?.speechController
        ?.speak(
          text,
          EVENT_ID_UNTRACKED,
          SpeechController.SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
            .setFlags(flags)
            .setSpeechParams(untranslated()),
        )
    }

    override fun close() {
      translator.shutdown()
      identifier?.close()
      identifier = null
    }
  }

  companion object {
    private const val TAG = "AutomaticTranslation"
    internal const val NO_LANGUAGE = ""

    private const val MIN_LETTERS = 3

    private const val MIN_CONFIDENCE = 0.7f

    private const val MAX_WAIT_MS = 3_000L

    private const val MAX_DOWNLOAD_WAIT_MS = 30_000L

    private const val SURE_CONFIDENCE = 0.9f
    private const val SURE_LETTERS = 12
    private const val MAX_LANGUAGES = 500
    private const val MAX_TRANSLATIONS = 300

    private const val DOWNLOAD_AFTER_TEXTS = 2

    private var instance: AutomaticTranslation? = null

    @JvmStatic
    fun update(context: Context) {
      val prefs = SharedPreferencesUtils.getSharedPreferences(context)
      val auto = TranslationSettings.auto(prefs)
      if (!auto && !TranslationSettings.notifications(prefs)) return stop()
      val translation =
        instance ?: AutomaticTranslation(context, PhoneEngine(context.applicationContext)).also { instance = it }
      SpeechControllerImpl.setSpeechHold(if (auto) translation else null)
    }

    @JvmStatic
    fun translatesNotifications(): Boolean = instance != null

    @JvmStatic
    fun translate(text: CharSequence, done: (CharSequence) -> Unit) {
      val translation = instance ?: return done(text)
      translation.translateNotification(text, done)
    }

    @JvmStatic
    fun stop() {
      SpeechControllerImpl.setSpeechHold(null)
      instance?.close()
      instance = null
    }

    @JvmStatic
    fun untranslated(): Bundle = Bundle().apply { putBoolean(SpeechControllerImpl.SPEECH_PARAM_RELEASED, true) }

    fun letters(text: String): Int = text.count { it.isLetter() }

    fun translationKey(service: TranslationService, from: String, to: String, text: String): String =
      "${service.id}|$from|$to|$text"
  }
}
