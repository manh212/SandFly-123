package com.google.android.accessibility.talkback.soundthemes

import android.content.Context
import com.google.android.accessibility.talkback.individualfeedback.IndividualFeedbackSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BuiltInSoundThemeTest {
  @Test
  fun newSoundsAreOptionalAndResetKeepsTheirOwnDefaults() {
    val context: Context = RuntimeEnvironment.getApplication()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    prefs.edit().clear().commit()
    assertEquals(SoundThemes.BACKTALK, SoundThemes.activeId(prefs))
    assertTrue(SoundThemes.feedback(context, prefs).soundPaths.isEmpty())
    val theme = SoundThemes.installed(context).first { it.id == SoundThemes.MERIDIAN_NEW }
    assertEquals("Meridian mới", SoundThemesFragment.nameOf(context, theme))
    assertTrue(theme.builtInSounds.containsKey("focus"))
    SoundThemes.activate(context, prefs, theme.id)
    val item = IndividualFeedbackSettings.SOUNDS.first { it.key == "focus" }
    val original = SoundThemes.soundFile(context, prefs, item)!!
    SoundThemes.setNone(context, prefs, item)
    assertEquals(SoundThemeManifest.NONE, SoundThemes.feedback(context, prefs).soundPaths["focus"])
    SoundThemes.removeSound(context, prefs, item)
    assertTrue(original.isFile)
    assertEquals(original, SoundThemes.soundFile(context, prefs, item))
    val feedback = SoundThemes.feedback(context, prefs)
    assertNotEquals(feedback.soundPaths["radial_menu_1"], feedback.soundPaths["radial_menu_2"])
    SoundThemes.activate(context, prefs, SoundThemes.BACKTALK)
    assertTrue(SoundThemes.feedback(context, prefs).soundPaths.isEmpty())
  }
}
