package com.google.android.accessibility.talkback.taskassistant

import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModel
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import org.junit.Test

class TaskAiSelectionTest {
  @Test fun taskSelectionLeavesOtherFeaturesAndCredentialsUnchanged() {
    val prefs = FakeSharedPreferences()
    AiProvider.OPENAI.setApiKey(prefs, "existing-key")
    AiProvider.OPENAI.setModel(prefs, "existing-model")
    AiProvider.setActive(prefs, AiProvider.OPENAI)
    val choice = TaskAiChoice("openai", "AI riêng", "task-model", AiProvider.OPENAI)
    TaskAiCatalog.select(prefs, choice)
    assertEquals(choice, TaskAiCatalog.selected(prefs, listOf(choice)))
    assertEquals("existing-key", AiProvider.OPENAI.apiKey(prefs))
    assertEquals("existing-model", AiProvider.OPENAI.model(prefs))
    assertEquals(AiProvider.OPENAI, AiProvider.active(prefs))
  }

  @Test fun modelSelectionCannotSilentlyMoveToAnotherModelOrProvider() {
    val prefs = FakeSharedPreferences()
    val chosen = TaskAiChoice("openai", "AI riêng", "chosen", AiProvider.OPENAI)
    TaskAiCatalog.select(prefs, chosen)
    val replacement = chosen.copy(model = "replacement")
    assertNull(TaskAiCatalog.selected(prefs, listOf(replacement)))
    assertEquals(chosen, TaskAiCatalog.selected(prefs, listOf(replacement, chosen)))
    assertNull(TaskAiCatalog.selected(prefs, emptyList()))
  }
  @Test fun automaticGeminiAlwaysResolvesToANonemptyModel() {
    val prefs = FakeSharedPreferences()
    GeminiApiKey.setUserKey(prefs, "configured-key")
    assertTrue(TaskAiCatalog.canConnect(prefs, AiProvider.GEMINI, ""))
    assertEquals("gemini-test", TaskAiCatalog.model(prefs, AiProvider.GEMINI, "models/gemini-test"))
    prefs.edit().putString(TaskAiCatalog.PREF_CHOICE, "gemini").putString(TaskAiCatalog.PREF_MODEL, "").apply()
    assertEquals("gemini-test", TaskAiCatalog.model(prefs, AiProvider.GEMINI, "gemini-test"))
    assertTrue(TaskAiCatalog.model(prefs, AiProvider.GEMINI, "").isNotBlank())
  }

  @Test fun taskModelDoesNotRequireAProviderWideModelSelection() {
    val prefs = FakeSharedPreferences()
    AiProvider.OPENAI.setApiKey(prefs, "key")
    assertTrue(TaskAiCatalog.canConnect(prefs, AiProvider.OPENAI, ""))
    assertFalse(AiProvider.OPENAI.isReady(prefs))
    TaskAiCatalog.select(prefs, TaskAiChoice("openai", "AI", "task-model", AiProvider.OPENAI))
    assertEquals("task-model", TaskAiCatalog.model(prefs, AiProvider.OPENAI))
    assertEquals("", AiProvider.OPENAI.model(prefs))
  }

  @Test fun eachServiceRetainsItsOwnTaskModelAcrossModeChanges() {
    val prefs = FakeSharedPreferences()
    TaskAiCatalog.select(prefs, TaskAiChoice("openai", "AI", "openai-task", AiProvider.OPENAI))
    TaskAiCatalog.select(prefs, TaskAiChoice("gemini", "AI", "gemini-task", AiProvider.GEMINI))
    TaskAiCatalog.setLocal(prefs, true)
    TaskAiCatalog.setLocalModel(prefs, LocalModel.entries.first { it.id == "e2b" })
    TaskAiCatalog.setLocal(prefs, false)
    TaskAiCatalog.setProvider(prefs, AiProvider.OPENAI)
    assertEquals("openai-task", TaskAiCatalog.model(prefs, AiProvider.OPENAI))
    assertEquals("gemini-task", TaskAiCatalog.model(prefs, AiProvider.GEMINI))
  }

  @Test fun localSelectionDoesNotChangeTheModelOfOtherFeatures() {
    val prefs = FakeSharedPreferences()
    val global = LocalModel.entries.first { it.id == "e4b" }
    val task = LocalModel.entries.first { it.id == "e2b" }
    OnDeviceAiSettings.setPreferredModel(prefs, global)
    TaskAiCatalog.setLocal(prefs, true)
    TaskAiCatalog.setLocalModel(prefs, task)
    assertEquals(task, TaskAiCatalog.localModel(prefs))
    assertEquals(global, OnDeviceAiSettings.preferredModel(prefs))
  }
}
