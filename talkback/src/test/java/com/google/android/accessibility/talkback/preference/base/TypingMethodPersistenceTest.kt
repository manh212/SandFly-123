package com.google.android.accessibility.talkback.preference.base

import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.accessibility.material.preference.AccessibilitySuiteRadioButtonPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypingMethodPersistenceTest {
  @Test fun directSilentSelectionSurvivesReopeningAndParentShowsSameMethod() {
    val controller = Robolectric.buildActivity(FragmentActivity::class.java)
    val activity = controller.get()
    activity.setTheme(R.style.TalkBackSettings)
    FormFactorUtils.initialize(activity)
    val prefs = SharedPreferencesUtils.getSharedPreferences(activity)
    prefs.edit().clear().commit()
    controller.setup()
    try {
      fun open(fragment: TalkbackBaseFragment) {
        activity.supportFragmentManager.beginTransaction()
          .replace(android.R.id.content, fragment).commitNow()
      }
      val chooser = TypingMethodPrefFragment()
      open(chooser)
      val key = activity.getString(R.string.pref_typing_confirmation_key)
      val category = requireNotNull(chooser.findPreference<PreferenceCategory>(key))
      val row = (0 until category.preferenceCount).map { category.getPreference(it) }
        .first { it.title == activity.getString(R.string.value_type_confirmation_direct_silent) }
      chooser.onPreferenceTreeClick(row)
      assertEquals("6", prefs.getString(key, ""))
      val parent = OnScreenKeyboardFragment()
      open(parent)
      assertEquals(activity.getString(R.string.value_type_confirmation_direct_silent),
        parent.findPreference<Preference>(key)?.summary)
      val reopened = TypingMethodPrefFragment()
      open(reopened)
      val reopenedCategory = requireNotNull(reopened.findPreference<PreferenceCategory>(key))
      val selected = (0 until reopenedCategory.preferenceCount)
        .map { reopenedCategory.getPreference(it) as AccessibilitySuiteRadioButtonPreference }
        .filter { it.isChecked }
      assertEquals(1, selected.size)
      assertEquals(row.title, selected.single().title)
    } finally {
      controller.pause().stop().destroy()
    }
  }
}
