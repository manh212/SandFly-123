package com.google.android.accessibility.talkback.translation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutoTranslationControllerTest {
  private class FakeClient : AutoTranslationController.TranslationClient {
    val started = mutableListOf<String>()
    val callbacks = mutableListOf<OfflineTranslator.TranslateCallback>()

    override fun translate(text: String, callback: OfflineTranslator.TranslateCallback) {
      started.add(text)
      callbacks.add(callback)
    }
  }

  private val client = FakeClient()

  @Before
  fun setUp() {
    AutoTranslationController.setTranslatorForTesting(client)
  }

  @After
  fun tearDown() {
    AutoTranslationController.setTranslatorForTesting(null)
  }

  @Test
  fun translationsRunInOrderAndKeepEachUtterance() {
    val translated = mutableListOf<String>()
    AutoTranslationController.translate("first") { translated.add(it) }
    AutoTranslationController.translate("second") { translated.add(it) }

    assertEquals(listOf("first"), client.started)
    client.callbacks[0].onSuccess("uno")
    assertEquals(listOf("uno"), translated)
    assertEquals(listOf("first", "second"), client.started)
    client.callbacks[1].onFailure()
    assertEquals(listOf("uno", "second"), translated)
  }

  @Test
  fun stoppingDropsLateTranslationAndCompletesEveryQueuedUtterance() {
    val translated = mutableListOf<String>()
    AutoTranslationController.translate("first") { translated.add(it) }
    AutoTranslationController.translate("second") { translated.add(it) }

    AutoTranslationController.cancelPending()
    client.callbacks[0].onSuccess("late speech")

    assertEquals(listOf("", ""), translated)
    assertEquals(listOf("first"), client.started)
  }
}
