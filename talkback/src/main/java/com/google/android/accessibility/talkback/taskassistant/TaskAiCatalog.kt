package com.google.android.accessibility.talkback.taskassistant

import android.content.Context
import android.content.SharedPreferences
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.AiService
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModel
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.utils.SharedPreferencesUtils

data class TaskAiChoice(val id: String, val label: String, val model: String, val provider: AiProvider?)

object TaskAiCatalog {
  const val PREF_CHOICE = "pref_task_assistant_ai"
  const val PREF_MODEL = "pref_task_assistant_model"
  const val PREF_MODE = "pref_task_assistant_mode"
  const val PREF_SERVICE = "pref_task_assistant_service"
  const val PREF_LOCAL = "pref_task_assistant_local_model"

  fun isLocal(prefs: SharedPreferences): Boolean = prefs.getString(PREF_MODE, null)?.let { it == "local" }
    ?: prefs.getString(PREF_CHOICE, "").orEmpty().startsWith("local:")

  fun setLocal(prefs: SharedPreferences, local: Boolean) {
    prefs.edit().putString(PREF_MODE, if (local) "local" else "cloud").apply()
  }

  fun provider(prefs: SharedPreferences): AiProvider =
    AiProvider.of(prefs.getString(PREF_SERVICE, null))?.takeUnless { it == AiProvider.NONE }
      ?: AiProvider.of(prefs.getString(PREF_CHOICE, null))?.takeUnless { it == AiProvider.NONE }
      ?: AiProvider.GEMINI

  fun setProvider(prefs: SharedPreferences, provider: AiProvider) {
    require(provider != AiProvider.NONE)
    prefs.edit().putString(PREF_SERVICE, provider.id).apply()
  }

  fun model(prefs: SharedPreferences, provider: AiProvider, defaultGemini: String = BuildConfig.GEMINI_MODEL): String {
    val saved = prefs.getString("pref_task_assistant_model_${provider.id}", null)
      ?: prefs.getString(PREF_MODEL, null)?.takeIf { prefs.getString(PREF_CHOICE, null) == provider.id }
      ?: provider.model(prefs)
    return if (provider == AiProvider.GEMINI) saved.trim().removePrefix("models/")
      .ifEmpty { defaultGemini.trim().removePrefix("models/").ifEmpty { "gemini-flash-latest" } }
    else saved.trim()
  }

  fun canConnect(prefs: SharedPreferences, provider: AiProvider, builtInKey: String = BuildConfig.GEMINI_API_KEY): Boolean =
    when (provider) {
      AiProvider.NONE -> false
      AiProvider.GEMINI -> GeminiApiKey.effectiveKey(prefs, builtInKey).isNotEmpty()
      else -> AiService.isValidAddress(provider.addressOf(prefs)) && provider.apiKey(prefs).isNotEmpty()
    }

  fun localModel(prefs: SharedPreferences): LocalModel {
    val id = prefs.getString(PREF_LOCAL, null)
      ?: prefs.getString(PREF_CHOICE, null)?.takeIf { it.startsWith("local:") }?.removePrefix("local:")
    return LocalModel.entries.firstOrNull { it.id == id } ?: LocalModel.entries.first { it.id == "e2b" }
  }

  fun setLocalModel(prefs: SharedPreferences, model: LocalModel) {
    require(model.writesJson)
    prefs.edit().putString(PREF_LOCAL, model.id).apply()
  }

  fun selected(context: Context): TaskAiChoice? {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (isLocal(prefs)) {
      val model = localModel(prefs)
      if (!model.writesJson || !LocalModelManager.get(context).store.isInstalled(model)) return null
      return TaskAiChoice("local:${model.id}", "Ngoại tuyến — ${model.name}", model.id, null)
    }
    val provider = provider(prefs)
    val model = model(prefs, provider)
    if (!canConnect(prefs, provider) || model.isBlank()) return null
    return TaskAiChoice(provider.id, "${context.getString(provider.label)} — $model", model, provider)
  }

  fun selected(prefs: SharedPreferences, choices: List<TaskAiChoice>): TaskAiChoice? {
    val id = prefs.getString(PREF_CHOICE, null)
    val model = prefs.getString(PREF_MODEL, null)
    return choices.firstOrNull { it.id == id && (model == null || it.model == model) }
  }

  fun select(context: Context, choice: TaskAiChoice) = select(SharedPreferencesUtils.getSharedPreferences(context), choice)

  fun select(prefs: SharedPreferences, choice: TaskAiChoice) {
    require(choice.model.isNotBlank())
    val edit = prefs.edit().putString(PREF_CHOICE, choice.id).putString(PREF_MODEL, choice.model)
      .putString(PREF_MODE, if (choice.provider == null) "local" else "cloud")
    if (choice.provider == null) edit.putString(PREF_LOCAL, choice.model)
    else edit.putString(PREF_SERVICE, choice.provider.id).putString("pref_task_assistant_model_${choice.provider.id}", choice.model)
    edit.apply()
  }
}
