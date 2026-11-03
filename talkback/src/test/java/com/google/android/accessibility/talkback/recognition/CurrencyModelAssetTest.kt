package com.google.android.accessibility.talkback.recognition

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrencyModelAssetTest {
  @Test fun packagedModelMatchesVerifiedSource() {
    val app = RuntimeEnvironment.getApplication()
    assertTrue(CurrencyRecognizer.isAvailable(app))
    val bytes = app.assets.open(CurrencyRecognizer.MODEL_ASSET).use { it.readBytes() }
    assertEquals(3_027_064, bytes.size)
    assertEquals("TFL3", String(bytes.copyOfRange(4, 8), Charsets.US_ASCII))
    val checksum = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 255) }
    assertEquals("b3d3727f9ca1fb41789071e5590f7677bca83e9dd61505b322580c52251fddd9", checksum)
  }
}
