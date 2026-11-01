package com.google.android.accessibility.talkback.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitTextTest {
  private val words = listOf("Gửi", "Gửi tin nhắn", "Send", "Send message")

  @Test
  fun acceptsOnlyKnownSendLabels() {
    for (label in words) assertTrue(SubmitText.isSendLabel(label, words))
    assertTrue(SubmitText.isSendLabel(" GỬI ", words))
    for (label in listOf("Search", "Done", "Next", "Enter", "Send money", "Gửi quà", "Sender", "")) {
      assertFalse(SubmitText.isSendLabel(label, words))
    }
    assertFalse(SubmitText.isSendLabel(null, words))
  }
}
