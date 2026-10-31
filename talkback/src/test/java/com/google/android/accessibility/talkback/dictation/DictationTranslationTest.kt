package com.google.android.accessibility.talkback.dictation

import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationTranslationTest {
  private val prefs = SharedPreferencesUtils.getSharedPreferences(RuntimeEnvironment.getApplication())

  @Test fun translationLanguagesHaveDefaultsAndValidateUnknownCodes() {
    prefs.edit().remove(DictationSettings.PREF_TRANSLATION_SOURCE)
      .remove(DictationSettings.PREF_TRANSLATION_TARGET).commit()
    assertEquals("vi", DictationSettings.translationSource(prefs))
    assertEquals("en", DictationSettings.translationTarget(prefs))
    prefs.edit().putString(DictationSettings.PREF_TRANSLATION_SOURCE, "ja")
      .putString(DictationSettings.PREF_TRANSLATION_TARGET, "invalid").commit()
    assertEquals("ja", DictationSettings.translationSource(prefs))
    assertEquals("en", DictationSettings.translationTarget(prefs))
  }

  @Test fun translationPromptProducesTranslationOnly() {
    val prompt = AiDictation.translationPrompt("Xin chào", "vi", "en")
    assertTrue(prompt.contains("vi"))
    assertTrue(prompt.contains("en"))
    assertTrue(prompt.contains("Xin chào"))
    assertTrue(AiDictation.isTranslation("Xin chào", "Hello"))
    assertFalse(AiDictation.isTranslation("Xin chào", ""))
  }

  @Test fun emojiIsOptIn() {
    prefs.edit().remove(DictationSettings.PREF_AI_EMOJI).commit()
    assertFalse(DictationSettings.addOnlineEmoji(prefs))
    prefs.edit().putBoolean(DictationSettings.PREF_AI_EMOJI, true).commit()
    assertTrue(DictationSettings.addOnlineEmoji(prefs))
  }
}
