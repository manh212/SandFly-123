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

package com.google.android.accessibility.talkback.labeling

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.ErrorReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.FinishReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.GeminiResponseListener
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture
import com.google.android.libraries.accessibility.utils.bitmap.BitmapUtils
import java.util.Locale

/**
 * Asks the service that describes images for a label for an unlabeled button, and opens the
 * add-label dialog with the answer filled in for the user to review. Any failure says so and
 * opens the same dialog empty, exactly as adding a label by hand does.
 */
class LabelSuggester(
  private val capture: (AccessibilityNodeInfoCompat, (Bitmap?) -> Unit) -> Unit,
  private val performRequest: (String, Bitmap, GeminiResponseListener) -> Boolean,
  private val openDialog: (String?) -> Unit,
  private val speak: (String) -> Unit,
  private val failureMessage: String,
) {
  private val mainHandler = Handler(Looper.getMainLooper())
  private var finished = false

  /** Requests a suggestion for [node]; exactly one dialog is opened, with or without one. */
  fun suggest(node: AccessibilityNodeInfoCompat, appName: CharSequence?, windowTitle: CharSequence?) {
    capture(node) { image ->
      if (image == null) {
        fail()
        return@capture
      }
      val prompt = buildPrompt(appName, windowTitle, nearbyTexts(node), answerLanguage())
      val sent =
        performRequest(
          prompt,
          image,
          object : GeminiResponseListener {
            override fun onResponse(finishReason: FinishReason, text: String?) {
              val suggestion =
                if (finishReason == FinishReason.STOP) sanitizeSuggestion(text) else null
              if (suggestion != null) succeed(suggestion) else fail()
            }

            override fun onError(errorReason: ErrorReason) {
              fail()
            }
          },
        )
      if (!sent) fail()
    }
  }

  private fun succeed(suggestion: String) {
    mainHandler.post {
      if (finished) return@post
      finished = true
      speak(suggestion)
      openDialog(suggestion)
    }
  }

  private fun fail() {
    mainHandler.post {
      if (finished) return@post
      finished = true
      speak(failureMessage)
      openDialog(null)
    }
  }

  companion object {
    private const val MAX_SUGGESTION_LENGTH = 60
    private const val MAX_NEARBY_TEXTS = 12
    private const val MAX_NEARBY_TEXT_LENGTH = 40

    /** Asks for a suggestion for [node] and opens the add-label dialog with the outcome. */
    @JvmStatic
    fun request(
      service: TalkBackService,
      node: AccessibilityNodeInfoCompat,
      resourceName: String,
      pipeline: Pipeline.FeedbackReturner,
    ) {
      val suggester =
        LabelSuggester(
          capture = { target, callback -> captureAroundNode(service, target, callback) },
          performRequest = { prompt, image, listener ->
            service.geminiActor?.requestLabelSuggestion(prompt, image, listener) ?: false
          },
          openDialog = { suggestion ->
            if (suggestion == null) {
              LabelDialogManager.addLabel(service, resourceName, /* needToRestoreFocus= */ true, pipeline)
            } else {
              LabelDialogManager.addLabelWithInitialText(
                service,
                resourceName,
                /* needToRestoreFocus= */ true,
                pipeline,
                suggestion,
              )
            }
          },
          speak = { text ->
            pipeline.returnFeedback(Performance.EVENT_ID_UNTRACKED, Feedback.speech(text))
          },
          failureMessage = service.getString(R.string.label_suggestion_failed),
        )
      suggester.suggest(node, appName(service, node.packageName), node.window?.title)
    }

    internal fun buildPrompt(
      appName: CharSequence?,
      windowTitle: CharSequence?,
      nearbyTexts: List<String>,
      language: String,
    ): String {
      val prompt = StringBuilder()
      prompt.append(
        "The image is a small part of a phone screen, cropped around a button that has no label."
      )
      if (!appName.isNullOrEmpty()) prompt.append(" The app is ").append(appName).append('.')
      if (!windowTitle.isNullOrEmpty()) {
        prompt.append(" The screen is titled \"").append(windowTitle).append("\".")
      }
      if (nearbyTexts.isNotEmpty()) {
        prompt.append(" Text near the button: ").append(nearbyTexts.joinToString("; ")).append('.')
      }
      prompt
        .append(" The button to name is in the middle of the image.")
        .append(" Reply with only a short name for what this button does, at most four words, in ")
        .append(language)
        .append(". No quotes, no punctuation, no explanation.")
      return prompt.toString()
    }

    /** The first usable line of the model's answer, tidied into a label, or null if there is none. */
    internal fun sanitizeSuggestion(raw: String?): String? {
      val firstLine =
        raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
      val cleaned = firstLine.trim('"', '\'', '“', '”', ' ').trimEnd('.')
      return cleaned.take(MAX_SUGGESTION_LENGTH).ifEmpty { null }
    }

    /** The text of the node's parent and its children, which is the button's closest context. */
    internal fun nearbyTexts(node: AccessibilityNodeInfoCompat): List<String> {
      val parent = node.parent ?: return emptyList()
      val texts = LinkedHashSet<String>()
      fun add(candidate: AccessibilityNodeInfoCompat?) {
        val text =
          candidate?.let { AccessibilityNodeInfoUtils.getNodeText(it) }?.toString()?.trim()
        if (!text.isNullOrEmpty()) texts += text.take(MAX_NEARBY_TEXT_LENGTH)
      }
      add(parent)
      for (i in 0 until parent.childCount) {
        if (texts.size >= MAX_NEARBY_TEXTS) break
        add(parent.getChild(i))
      }
      return texts.toList()
    }

    /** The part of [full] around [node], with the node's own size again on every side as context. */
    internal fun cropAroundNode(
      full: Bitmap,
      node: AccessibilityNodeInfoCompat,
      captureByWindow: Boolean,
    ): Bitmap {
      val bounds = Rect()
      if (captureByWindow) node.getBoundsInWindow(bounds) else node.getBoundsInScreen(bounds)
      if (bounds.isEmpty) return full
      bounds.inset(-bounds.width(), -bounds.height())
      return BitmapUtils.cropBitmap(full, bounds)
    }

    private fun answerLanguage(): String =
      Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifEmpty { "English" }

    private fun appName(context: Context, packageName: CharSequence?): CharSequence? {
      if (packageName.isNullOrEmpty()) return null
      val packageManager = context.packageManager
      return try {
        packageManager.getApplicationLabel(
          packageManager.getApplicationInfo(packageName.toString(), 0)
        )
      } catch (e: PackageManager.NameNotFoundException) {
        null
      }
    }

    private fun captureAroundNode(
      service: TalkBackService,
      node: AccessibilityNodeInfoCompat,
      callback: (Bitmap?) -> Unit,
    ) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        callback(null)
        return
      }
      captureAroundNodeApi30(service, node, callback)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureAroundNodeApi30(
      service: TalkBackService,
      node: AccessibilityNodeInfoCompat,
      callback: (Bitmap?) -> Unit,
    ) {
      ScreenshotCapture.takeScreenshotByNode(
        service,
        node,
        object : ScreenshotCapture.WindowOrFullScreenScreenshotCallback() {
          override fun onFailure(errorCode: Int) {
            super.onFailure(errorCode)
            callback(null)
          }

          override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
            super.onSuccess(screenshot)
            val full = getBitmap()
            callback(full?.let { cropAroundNode(it, node, isCaptureByWindow) })
          }
        },
      )
    }
  }
}
