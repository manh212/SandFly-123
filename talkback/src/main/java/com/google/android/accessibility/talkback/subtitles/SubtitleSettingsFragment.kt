package com.google.android.accessibility.talkback.subtitles

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

class SubtitleSettingsFragment : TalkbackBaseFragment(R.xml.subtitle_preferences) {
  private var downloading = false

  override fun getTitle(): CharSequence = getString(R.string.subtitle_settings_title)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    for (key in listOf(SubtitleSettings.ENGINE, SubtitleSettings.REGION, SubtitleSettings.SPEECH, SubtitleSettings.INTERVAL)) {
      findPreference<ListPreference>(key)?.apply {
        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
        setOnPreferenceChangeListener { _, _ -> view?.post { refresh() }; true }
      }
    }
    val defaults = mapOf(SubtitleSettings.LEFT to 0, SubtitleSettings.TOP to 60,
      SubtitleSettings.RIGHT to 100, SubtitleSettings.BOTTOM to 100)
    for ((key, default) in defaults) {
      findPreference<SeekBarPreference>(key)?.apply {
        min = 0
        max = 100
        showSeekBarValue = true
        seekBarIncrement = 1
        setDefaultValue(default)
        setOnPreferenceChangeListener { _, value ->
          val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
          fun edge(name: String) = if (name == key) value as Int else prefs.getInt(name, defaults.getValue(name))
          val valid = edge(SubtitleSettings.LEFT) < edge(SubtitleSettings.RIGHT) &&
            edge(SubtitleSettings.TOP) < edge(SubtitleSettings.BOTTOM)
          if (!valid) Toast.makeText(requireContext(), R.string.subtitle_invalid_region, Toast.LENGTH_SHORT).show()
          valid
        }
      }
    }
    findPreference<Preference>("pref_subtitle_download")?.setOnPreferenceClickListener {
      if (!downloading) {
        downloading = true
        refresh()
        val context = requireContext().applicationContext
        TesseractOcr.downloadData(context) { success ->
          downloading = false
          Toast.makeText(context, if (success) R.string.subtitle_download_done else R.string.subtitle_download_failed, Toast.LENGTH_LONG).show()
          if (isAdded) refresh()
        }
      }
      true
    }
    refresh()
  }

  private fun refresh() {
    val prefs = SharedPreferencesUtils.getSharedPreferences(requireContext())
    val custom = SubtitleSettings.region(prefs) == "custom"
    for (key in listOf(SubtitleSettings.LEFT, SubtitleSettings.TOP, SubtitleSettings.RIGHT, SubtitleSettings.BOTTOM)) {
      findPreference<Preference>(key)?.isVisible = custom
    }
    findPreference<Preference>("pref_subtitle_download")?.apply {
      isVisible = SubtitleSettings.engine(prefs) == "tesseract"
      isEnabled = !downloading && !TesseractOcr.isDataDownloaded(requireContext())
      summary = getString(when {
        downloading -> R.string.subtitle_downloading
        TesseractOcr.isDataDownloaded(requireContext()) -> R.string.subtitle_model_ready
        else -> R.string.subtitle_model_not_downloaded
      })
    }
  }
}
