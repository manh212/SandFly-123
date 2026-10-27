package com.google.android.accessibility.talkback.taskassistant

import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
