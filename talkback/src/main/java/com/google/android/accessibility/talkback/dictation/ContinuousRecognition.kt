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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.Locale

/**
 * Android speech recognition that goes on until [stop]: each time the recognizer ends after a
 * pause, its text is kept and it starts again. [onDone] gets all the text, on the main thread, once
 * [stop] is called and the last part is recognized; [onFailed] gets the error that ended it early.
 */
internal class ContinuousRecognition(
  private val context: Context,
  /** The recognition service to use, or null for the one chosen in Android's settings. */
  private val service: ComponentName?,
  private val onDone: (String) -> Unit,
  private val onFailed: (Int) -> Unit,

  private val onSpeech: () -> Unit = {},
) : RecognitionListener {
  private var recognizer: SpeechRecognizer? = null
  private val parts = mutableListOf<String>()
  private var partial = ""
  private var stopping = false
  private var finished = false
  private val handler = Handler(Looper.getMainLooper())
  /** Errors since the last result, so that a recognizer that keeps failing isn't restarted forever. */
  private var errors = 0
  private val speechActivity = SpeechActivityDetector()
  private val listenAgain = Runnable { if (!finished && !stopping) listen() }

  /** Starts recognizing. Returns false when Android has no speech recognition. */
  fun start(): Boolean {
    if (service == null && !SpeechRecognizer.isRecognitionAvailable(context)) return false
    recognizer =
      try {
        SpeechRecognizer.createSpeechRecognizer(context, service)
      } catch (e: RuntimeException) {
        LogUtils.w(TAG, "No speech recognizer: %s", e)
        return false
      }.also { it.setRecognitionListener(this) }
    listen()
    return true
  }

  private fun listen() {
    val intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Asks for long pauses before the recognizer ends; not every recognizer follows it.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_MS)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_MS)
    if (service != null) {
      // On the phone, with punctuation and capital letters where the recognizer can add them.
      intent
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.putExtra(
          RecognizerIntent.EXTRA_ENABLE_FORMATTING,
          RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
        )
      }
    }
    recognizer?.startListening(intent)
  }

  /** Ends recognition; [onDone] follows once the part spoken last is recognized. */
  fun stop() {
    if (finished || stopping) return
    stopping = true
    handler.removeCallbacks(listenAgain)
    recognizer?.stopListening()
    // Some recognizers say nothing more after they are stopped between parts.
    handler.postDelayed(::finish, STOP_WAIT_MS)
  }

  /** Ends recognition without [onDone]. */
  fun cancel() {
    finished = true
    release()
  }

  private fun finish() {
    if (finished) return
    finished = true
    val text = combineRecognitionParts(parts, partial)
    partial = ""
    release()
    onDone(text)
  }

  private fun release() {
    handler.removeCallbacksAndMessages(null)
    recognizer?.setRecognitionListener(null)
    recognizer?.cancel()
    recognizer?.destroy()
    recognizer = null
  }

  override fun onResults(results: Bundle?) {
    val text =
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        ?: partial.trim()
    partial = ""
    errors = 0
    if (text.isNotEmpty()) {
      parts.add(text)
    }
    if (stopping) finish() else listen()
  }

  override fun onPartialResults(partialResults: Bundle?) {
    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
      if (it.isNotBlank() && it != partial) {
        partial = it
        onSpeech()
      }
    }
  }

  override fun onError(error: Int) {
    if (finished) return
    when (error) {
      // Silence: wait for more speech, unless the user has stopped.
      SpeechRecognizer.ERROR_NO_MATCH,
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
        keepPartial()
        if (stopping) finish() else listen()
      }
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
      SpeechRecognizer.ERROR_CLIENT ->
        when {
          stopping -> {
            keepPartial()
            finish()
          }
          // Out of restarts: end with what was recognized, as the other errors do.
          ++errors > MAX_ERRORS -> {
            keepPartial()
            if (parts.isNotEmpty()) finish() else fail(error)
          }
          else -> handler.postDelayed(listenAgain, RETRY_DELAYS_MS[errors - 1])
        }
      else -> {
        LogUtils.w(TAG, "Speech recognition error %d", error)
        keepPartial()
        if (parts.isNotEmpty()) finish() else fail(error)
      }
    }
  }

  private fun fail(error: Int) {
    finished = true
    release()
    onFailed(error)
  }

  private fun keepPartial() {
    partial.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
    partial = ""
  }

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {
    speechActivity.beginningOfSpeech()
    onSpeech()
  }

  override fun onRmsChanged(rmsdB: Float) {
    if (speechActivity.isSpeechLevel(rmsdB)) onSpeech()
  }

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {}

  override fun onEvent(eventType: Int, params: Bundle?) {}

  private companion object {
    const val TAG = "ContinuousRecognition"
    const val PAUSE_MS = 10_000L
    const val STOP_WAIT_MS = 5_000L
    const val MAX_ERRORS = 5

    /** The wait before each restart after a busy or client error, growing to about 5 s in all. */
    val RETRY_DELAYS_MS = longArrayOf(300L, 600L, 1_000L, 1_500L, 2_000L)
  }
}

internal fun combineRecognitionParts(parts: List<String>, partial: String): String =
  (parts + listOfNotNull(partial.trim().takeIf { it.isNotEmpty() }))
    .filter { it.isNotBlank() }
    .joinToString(" ")

internal class SpeechActivityDetector {
  private var noiseFloorDb: Float? = null
  private var calibrateNextLevelAsSpeech = false

  fun beginningOfSpeech() {
    if (noiseFloorDb == null) calibrateNextLevelAsSpeech = true
  }

  fun isSpeechLevel(rmsdB: Float): Boolean {
    if (!rmsdB.isFinite()) return false
    val noiseFloor = noiseFloorDb
    if (noiseFloor == null) {
      if (calibrateNextLevelAsSpeech) {
        noiseFloorDb = rmsdB - SPEECH_LEVEL_MARGIN_DB
        calibrateNextLevelAsSpeech = false
        return true
      }
      noiseFloorDb = rmsdB
      return false
    }

    val speech = rmsdB - noiseFloor >= SPEECH_LEVEL_MARGIN_DB
    if (!speech) {
      noiseFloorDb =
        if (rmsdB < noiseFloor) rmsdB
        else noiseFloor + (rmsdB - noiseFloor) * NOISE_FLOOR_RISE_FACTOR
    }
    return speech
  }

  private companion object {
    const val SPEECH_LEVEL_MARGIN_DB = 6.0f
    const val NOISE_FLOOR_RISE_FACTOR = 0.02f
  }
}
