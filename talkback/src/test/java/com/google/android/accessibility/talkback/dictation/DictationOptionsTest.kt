package com.google.android.accessibility.talkback.dictation

import com.google.android.accessibility.talkback.dictation.DictationSettings.OnlineRoute
import com.google.android.accessibility.talkback.dictation.DictationSettings.Recognition
import com.google.android.accessibility.talkback.dictation.WhisperModels.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationOptionsTest {
  @Test fun speechServicesExcludeAutomaticAndGoogleOnDevice() {
    assertEquals(listOf(Recognition.RAMBLER, Recognition.WHISPER, Recognition.ANDROID), Recognition.entries)
  }

  @Test fun vietnameseWhisperCatalogContainsOnlyThreeModels() {
    assertEquals(listOf(Model.BASE, Model.SMALL, Model.LARGE_TURBO), Model.entries)
    assertEquals(
      listOf("Whisper Base", "Whisper Small", "Whisper Large v3 Turbo"),
      Model.entries.map { it.label },
    )
  }

  @Test fun onlineRouteDoesNotExposeModelNames() {
    assertEquals(listOf(OnlineRoute.GEMINI, OnlineRoute.DESCRIPTIONS), OnlineRoute.entries)
  }
}
