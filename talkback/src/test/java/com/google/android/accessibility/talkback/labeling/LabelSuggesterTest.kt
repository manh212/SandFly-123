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

import android.graphics.Bitmap
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.ErrorReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.FinishReason
import com.google.android.accessibility.talkback.actor.gemini.GeminiActor.GeminiResponseListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [LabelSuggester] with the capture and the description service faked: a suggestion is
 * spoken and filled into the dialog; any failure falls back to the manual dialog with a notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelSuggesterTest {
  private val spoken = mutableListOf<String>()
  private val dialogs = mutableListOf<String?>()
  private var lastPrompt: String? = null

  private fun node(): AccessibilityNodeInfoCompat {
    val info = AccessibilityNodeInfo()
    info.packageName = "com.example"
    return AccessibilityNodeInfoCompat.wrap(info)
  }

  private fun suggester(
    performRequest: (String, Bitmap, GeminiResponseListener) -> Boolean,
    captureSucceeds: Boolean = true,
  ): LabelSuggester =
    LabelSuggester(
      capture = { _, callback ->
        callback(
          if (captureSucceeds) Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) else null
        )
      },
      performRequest = { prompt, image, listener ->
        lastPrompt = prompt
        performRequest(prompt, image, listener)
      },
      openDialog = { dialogs += it },
      speak = { spoken += it },
      failureMessage = "no suggestion",
    )

  private fun runToEnd(suggester: LabelSuggester) {
    suggester.suggest(node(), "Example app", "Example screen")
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun suggestion_isSpokenAndFilledIntoTheDialog() {
    runToEnd(
      suggester({ _, _, listener ->
        listener.onResponse(FinishReason.STOP, "Send message")
        true
      })
    )

    assertEquals(listOf("Send message"), spoken)
    assertEquals(listOf("Send message"), dialogs)
    assertTrue(lastPrompt!!.contains("Example app"))
    assertTrue(lastPrompt!!.contains("Example screen"))
  }

  @Test
  fun providerError_fallsBackToTheManualDialogWithANotice() {
    runToEnd(
      suggester({ _, _, listener ->
        listener.onResponse(FinishReason.ERROR_RESPONSE, "the provider said no")
        true
      })
    )

    assertEquals(listOf("no suggestion"), spoken)
    assertEquals(listOf<String?>(null), dialogs)
  }

  @Test
  fun requestError_fallsBackToTheManualDialogWithANotice() {
    runToEnd(
      suggester({ _, _, listener ->
        listener.onError(ErrorReason.NETWORK_ERROR)
        true
      })
    )

    assertEquals(listOf("no suggestion"), spoken)
    assertEquals(listOf<String?>(null), dialogs)
  }

  @Test
  fun requestNotSent_fallsBackToTheManualDialogWithANotice() {
    runToEnd(suggester({ _, _, _ -> false }))

    assertEquals(listOf("no suggestion"), spoken)
    assertEquals(listOf<String?>(null), dialogs)
  }

  @Test
  fun captureFailure_fallsBackToTheManualDialogWithANotice() {
    runToEnd(suggester({ _, _, _ -> true }, captureSucceeds = false))

    assertEquals(listOf("no suggestion"), spoken)
    assertEquals(listOf<String?>(null), dialogs)
  }

  @Test
  fun emptySuggestion_fallsBackToTheManualDialogWithANotice() {
    runToEnd(
      suggester({ _, _, listener ->
        listener.onResponse(FinishReason.STOP, "  \n ")
        true
      })
    )

    assertEquals(listOf("no suggestion"), spoken)
    assertEquals(listOf<String?>(null), dialogs)
  }

  @Test
  fun sanitize_takesTheFirstLineWithoutQuotesOrFullStop() {
    assertEquals("Send message", LabelSuggester.sanitizeSuggestion("\"Send message.\"\nMore text"))
    assertEquals("Thích", LabelSuggester.sanitizeSuggestion("  Thích  "))
    assertNull(LabelSuggester.sanitizeSuggestion(null))
    assertNull(LabelSuggester.sanitizeSuggestion("   "))
  }

  @Test
  fun prompt_carriesContextAndAsksForAShortLabel() {
    val prompt =
      LabelSuggester.buildPrompt("Example app", "Inbox", listOf("Compose", "Search"), "Vietnamese")

    assertTrue(prompt.contains("Example app"))
    assertTrue(prompt.contains("Inbox"))
    assertTrue(prompt.contains("Compose; Search"))
    assertTrue(prompt.contains("Vietnamese"))
  }
}
