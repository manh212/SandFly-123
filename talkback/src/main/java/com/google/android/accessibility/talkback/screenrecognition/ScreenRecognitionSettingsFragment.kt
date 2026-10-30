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
    val ocr = findPreference<ListPreference>(getString(R.string.pref_ocr_engine_key))
    ocr?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
    method?.setOnPreferenceChangeListener { _, value ->
      val showOcr = value == "ocr"
      ocr?.isVisible = showOcr
      findPreference<Preference>("screen_recognition_privacy")?.isVisible = !showOcr
      true
    }
    val current = SharedPreferencesUtils.getSharedPreferences(requireContext())
      .getString("pref_screen_recognition_method", "ocr")
    ocr?.isVisible = current != "ai"
    findPreference<Preference>("screen_recognition_privacy")?.isVisible = current == "ai"
  }
}
