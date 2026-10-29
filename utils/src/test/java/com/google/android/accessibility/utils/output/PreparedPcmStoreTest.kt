package com.google.android.accessibility.utils.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPcmStoreTest {
  @Test
  fun byteLimitEvictsLeastRecentlyUsedAudioAndPreservesTheUsedEntry() {
    val store = PreparedPcmStore<String>(24, 8)
    val first = floatArrayOf(1f, 2f)
    store.put("first", first)
    store.put("second", floatArrayOf(3f, 4f))
    assertSame(first, store.get("first"))
    store.put("third", floatArrayOf(5f, 6f, 7f, 8f))
    assertNull(store.get("second"))
    assertSame(first, store.get("first"))
    assertEquals(24, store.byteCount())
  }

  @Test
  fun oversizedAudioIsRejectedWithoutRemovingPreparedAudio() {
    val store = PreparedPcmStore<String>(8, 1)
    val ready = floatArrayOf(1f, 2f)
    assertTrue(store.put("ready", ready))
    assertFalse(store.put("oversized", FloatArray(3)))
    assertSame(ready, store.get("ready"))
    assertEquals(8, store.byteCount())
    store.clear()
    assertEquals(0, store.byteCount())
    assertNull(store.get("ready"))
  }

  @Test
  fun distinctVoiceKeysNeverShareAudioAndEntryLimitAlsoApplies() {
    val store = PreparedPcmStore<Pair<String, String>>(1024, 1)
    store.put("voice-a" to "text", floatArrayOf(1f))
    assertNull(store.get("voice-b" to "text"))
    val replacement = floatArrayOf(2f)
    store.put("voice-b" to "text", replacement)
    assertNull(store.get("voice-a" to "text"))
    assertSame(replacement, store.get("voice-b" to "text"))
    assertEquals(4, store.byteCount())
  }
}
