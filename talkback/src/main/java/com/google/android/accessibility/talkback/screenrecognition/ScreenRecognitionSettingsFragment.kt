package com.google.android.accessibility.talkback.screenrecognition

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

class ScreenRecognitionSettingsFragment : TalkbackBaseFragment(R.xml.screen_recognition_preferences) {
  override fun getTitle(): CharSequence = getString(R.string.title_screen_recognition)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val method = findPreference<ListPreference>("pref_screen_recognition_method")
    method?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    val ocrSettings = findPreference<Preference>("pref_screen_recognition_ocr_settings")
    method?.setOnPreferenceChangeListener { _, value ->
      val useAi = value == "ai"
      ocrSettings?.isVisible = !useAi
      findPreference<Preference>("screen_recognition_privacy")?.isVisible = useAi
      true
    }
    val useAi =
      SharedPreferencesUtils.getSharedPreferences(requireContext())
        .getString("pref_screen_recognition_method", "ocr") == "ai"
    ocrSettings?.isVisible = !useAi
    findPreference<Preference>("screen_recognition_privacy")?.isVisible = useAi
  }
}
