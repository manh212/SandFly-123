/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.dictation

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.talkback.actor.gemini.local.ModelFiles
import com.google.android.accessibility.talkback.dictation.DictationSettings.Recognition
import com.google.android.accessibility.talkback.dictation.WhisperModels.Model
import com.google.android.accessibility.talkback.dictation.WhisperModels.State
import com.google.android.accessibility.talkback.preference.base.TalkbackBaseFragment
import com.google.android.accessibility.utils.SharedPreferencesUtils

/**
 * Dictation settings: how speech is recognized, the Whisper models for recognition on the phone,
 * and the AI service that corrects dictation.
 */
class DictationFragment : TalkbackBaseFragment(R.xml.dictation_preferences) {
  private lateinit var prefs: SharedPreferences
  private lateinit var models: WhisperModels
  private lateinit var recognitionPref: Preference
  private lateinit var aiServicePref: Preference
  private lateinit var modelPref: Preference
  private lateinit var onlineRoutePref: Preference
  private lateinit var advancedPref: Preference
  private var advancedExpanded = false
  private var announcedMilestone = -1
  private var downloading: Model? = null

  private val listener: () -> Unit = { if (isAdded) refresh() }

  public override fun getTitle(): CharSequence = getText(R.string.title_pref_dictation)

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    val context = requireContext()
    prefs = SharedPreferencesUtils.getSharedPreferences(context)
    models = WhisperModels.get(context)
    advancedExpanded = savedInstanceState?.getBoolean("dictation_advanced_expanded") ?: false

    recognitionPref = onClick("pref_dictation_recognition_choice") { chooseRecognition() }
    modelPref = onClick("pref_dictation_whisper_model_choice") { chooseModel() }
    onlineRoutePref = onClick("pref_dictation_online_route_choice") { chooseOnlineRoute() }
    advancedPref = onClick("pref_dictation_advanced_toggle") {
      advancedExpanded = !advancedExpanded
      refresh()
    }
    aiServicePref = onClick("pref_dictation_ai_service_choice") { chooseAiService() }
    findPreference<Preference>(DictationSettings.PREF_SPEECH_THRESHOLD)?.onPreferenceChangeListener =
      Preference.OnPreferenceChangeListener { preference, value ->
        preference.summary = speechThresholdSummary((value as Int) - 60)
        true
      }
    refresh()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean("dictation_advanced_expanded", advancedExpanded)
    super.onSaveInstanceState(outState)
  }

  override fun onStart() {
    super.onStart()
    models.addListener(listener)
    refresh()
  }

  override fun onStop() {
    models.removeListener(listener)
    super.onStop()
  }

  private fun refresh() {
    val context = requireContext()
    val chosen = DictationSettings.recognition(prefs)
    recognitionPref.title =
      getString(R.string.dictation_compact_service_selected, recognitionLabel(chosen))
    recognitionPref.summary = null

    modelPref.isVisible = chosen == Recognition.WHISPER && WhisperEngines.isAvailable()
    val model = models.activeModel()
    val downloading = (models.state as? State.Downloading)?.model
    modelPref.title = getString(
      R.string.dictation_compact_model_selected,
      (model ?: downloading ?: Model.SMALL).label,
    )
    modelPref.summary = null

    onlineRoutePref.isVisible = chosen == Recognition.RAMBLER
    onlineRoutePref.title = getString(
      R.string.dictation_compact_route_selected,
      onlineRouteLabel(DictationSettings.onlineRoute(prefs)),
    )
    onlineRoutePref.summary = null
    findPreference<Preference>(DictationSettings.PREF_RAMBLER_LIVE)?.isVisible =
      chosen == Recognition.RAMBLER

    advancedPref.title =
      getString(R.string.dictation_compact_advanced) + if (advancedExpanded) " ▴" else " ▾"
    advancedPref.summary = null
    listOf(
      "pref_dictation_advanced_recording",
      "pref_dictation_ai",
      "pref_dictation_translation",
    ).forEach { key -> findPreference<Preference>(key)?.isVisible = advancedExpanded }

    findPreference<Preference>(DictationSettings.PREF_SPEECH_THRESHOLD)?.summary =
      speechThresholdSummary(DictationSettings.speechThresholdDbfs(prefs))
    aiServicePref.summary = aiServiceSummary(context)
    announceProgress(models.state)
  }

  private fun speechThresholdSummary(dbfs: Int): String =
    getString(R.string.dictation_speech_threshold_summary, dbfs)

  private fun recognitionLabel(recognition: Recognition): String =
    getString(
      when (recognition) {
        Recognition.RAMBLER -> R.string.dictation_compact_gemini
        Recognition.WHISPER -> R.string.dictation_recognition_whisper
        Recognition.ANDROID -> R.string.dictation_compact_android
      }
    )

  private fun onlineRouteLabel(route: DictationSettings.OnlineRoute): String =
    getString(
      when (route) {
        DictationSettings.OnlineRoute.GEMINI -> R.string.dictation_compact_gemini
        DictationSettings.OnlineRoute.DESCRIPTIONS -> R.string.dictation_compact_descriptions
      }
    )

  private fun chooseRecognition() {
    val choices =
      listOf(Recognition.WHISPER, Recognition.RAMBLER, Recognition.ANDROID)
        .filter { it != Recognition.WHISPER || WhisperEngines.isAvailable() }
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.dictation_compact_service)
      .setSingleChoiceItems(
        choices.map { recognitionLabel(it) }.toTypedArray(),
        choices.indexOf(DictationSettings.recognition(prefs)),
      ) { dialog, which ->
        dialog.dismiss()
        DictationSettings.setRecognition(prefs, choices[which])
        refresh()
      }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }

  private fun chooseModel() {
    val choices = Model.entries.toList()
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.dictation_compact_model)
      .setSingleChoiceItems(
        choices.map { it.label }.toTypedArray(),
        choices.indexOf(models.activeModel()),
      ) { dialog, which ->
        dialog.dismiss()
        onModelClicked(choices[which])
      }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }

  private fun chooseOnlineRoute() {
    val choices = DictationSettings.OnlineRoute.entries.toList()
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.dictation_compact_route)
      .setSingleChoiceItems(
        choices.map { onlineRouteLabel(it) }.toTypedArray(),
        choices.indexOf(DictationSettings.onlineRoute(prefs)),
      ) { dialog, which ->
        dialog.dismiss()
        DictationSettings.setOnlineRoute(prefs, choices[which])
        refresh()
      }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }

  /** [DictationSettings.AI_SAME], the services that are set up, and on-device AI when installed. */
  private fun aiServiceChoices(context: Context): List<String> =
    buildList {
      add(DictationSettings.AI_SAME)
      AiProvider.entries
        .filter { it != AiProvider.NONE && it.isReady(prefs) }
        .forEach { add(it.id) }
      if (LocalModelManager.get(context).isReady()) add(DictationSettings.AI_DEVICE)
    }

  private fun aiServiceLabel(service: String): String =
    when (service) {
      DictationSettings.AI_SAME -> getString(R.string.dictation_ai_same)
      DictationSettings.AI_DEVICE -> getString(R.string.title_pref_on_device_ai)
      else -> AiProvider.of(service)?.let { getString(it.label) } ?: service
    }

  private fun aiServiceSummary(context: Context): String {
    val label = aiServiceLabel(DictationSettings.aiService(prefs))
    return if (DictationSettings.correctingService(context, prefs) == null) {
      getString(R.string.ai_provider_chosen_not_ready, label)
    } else {
      label
    }
  }

  private fun chooseAiService() {
    val context = requireContext()
    val choices = aiServiceChoices(context)
    AlertDialog.Builder(context)
      .setTitle(R.string.title_pref_dictation_ai_service)
      .setSingleChoiceItems(
        choices.map { aiServiceLabel(it) }.toTypedArray(),
        choices.indexOf(DictationSettings.aiService(prefs)),
      ) { dialog, which ->
        dialog.dismiss()
        DictationSettings.setAiService(prefs, choices[which])
        refresh()
      }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }

  private fun onModelClicked(model: Model) {
    val state = models.state
    when {
      state is State.Downloading -> if (state.model == model) models.cancelDownload()
      models.isInstalled(model) -> modelActions(model)
      isMetered() ->
        AlertDialog.Builder(requireContext())
          .setTitle(R.string.on_device_ai_metered_title)
          .setMessage(size(model))
          .setPositiveButton(R.string.on_device_ai_metered_download) { _, _ -> models.startDownload(model) }
          .setNegativeButton(R.string.on_device_ai_cancel, null)
          .show()
      else -> models.startDownload(model)
    }
  }

  /** Offers to use or delete a downloaded model. */
  private fun modelActions(model: Model) {
    val use = getString(R.string.dictation_whisper_use)
    val delete = getString(R.string.manage_models_delete)
    val actions = if (models.activeModel() == model) arrayOf(delete) else arrayOf(use, delete)
    AlertDialog.Builder(requireContext())
      .setTitle(model.label)
      .setItems(actions) { _, which ->
        if (actions[which] == use) {
          models.setActive(model)
        } else {
          confirmDelete(model)
        }
      }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }

  private fun confirmDelete(model: Model) {
    AlertDialog.Builder(requireContext())
      .setTitle(getString(R.string.manage_models_delete_title, model.label))
      .setMessage(getString(R.string.manage_models_delete_message, size(model)))
      .setPositiveButton(R.string.manage_models_delete) { _, _ -> models.delete(model) }
      .setNegativeButton(R.string.on_device_ai_cancel, null)
      .show()
  }

  /** Speaks each quarter of the download and its end, because the numbers change silently. */
  private fun announceProgress(state: State) {
    val view = view ?: return
    if (state is State.Downloading) {
      val milestone = models.percent(state) / 25 * 25
      if (milestone > announcedMilestone) {
        view.announceForAccessibility(getString(R.string.on_device_ai_announce_progress, milestone))
      }
      announcedMilestone = milestone
      downloading = state.model
    } else {
      downloading?.let {
        if (models.isInstalled(it)) {
          view.announceForAccessibility(getString(R.string.on_device_ai_announce_done))
        }
      }
      announcedMilestone = -1
      downloading = null
    }
  }

  private fun size(model: Model): String = Formatter.formatShortFileSize(requireContext(), model.sizeBytes)

  private fun isMetered(): Boolean =
    (requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
      .isActiveNetworkMetered

  private fun errorText(failure: ModelFiles.Failure) =
    when (failure) {
      ModelFiles.Failure.NETWORK -> R.string.on_device_ai_error_network
      ModelFiles.Failure.HTTP -> R.string.on_device_ai_error_http
      ModelFiles.Failure.SIZE_MISMATCH,
      ModelFiles.Failure.HASH_MISMATCH -> R.string.on_device_ai_error_corrupt
      ModelFiles.Failure.STORAGE -> R.string.on_device_ai_error_storage
      ModelFiles.Failure.LOW_SPACE -> R.string.on_device_ai_error_low_space
      ModelFiles.Failure.TIME_LIMIT -> R.string.on_device_ai_error_time_limit
    }

  private fun onClick(key: String, action: () -> Unit): Preference =
    findPreference<Preference>(key)!!.apply {
      setOnPreferenceClickListener {
        action()
        true
      }
    }
}
