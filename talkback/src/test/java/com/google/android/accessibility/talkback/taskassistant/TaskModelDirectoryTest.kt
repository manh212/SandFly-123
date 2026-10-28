package com.google.android.accessibility.talkback.taskassistant

import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.directtouch.FakeSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskModelDirectoryTest {
  private fun models(first: Int, count: Int, next: String = ""): JSONObject = JSONObject()
    .put("models", JSONArray((first until first + count).map {
      JSONObject().put("name", "models/gemini-$it").put("displayName", "Mô hình $it")
        .put("supportedGenerationMethods", JSONArray(listOf("generateContent")))
    })).put("nextPageToken", next)

  @Test fun allPagesAndMoreThanOneHundredModelsAreReturned() {
    val prefs = FakeSharedPreferences()
    GeminiApiKey.setUserKey(prefs, "key")
    val urls = mutableListOf<String>()
    val found = TaskModelDirectory.load(prefs, AiProvider.GEMINI) { url, _, _ ->
      urls += url
      if (urls.size == 1) models(0, 120, "page two") else models(100, 30)
    }
    assertEquals(130, found.size)
    assertTrue(urls.last().contains("pageToken=page+two"))
    assertEquals("gemini-129", found.last().id)
  }

  @Test(expected = IllegalStateException::class)
  fun repeatingPageTokensCannotLoopForever() {
    val prefs = FakeSharedPreferences()
    GeminiApiKey.setUserKey(prefs, "key")
    TaskModelDirectory.load(prefs, AiProvider.GEMINI) { _, _, _ -> models(0, 1, "repeated") }
  }

  @Test fun nonTextModelsAreNotOfferedForTaskExecution() {
    val body = models(0, 2)
    body.getJSONArray("models").put(JSONObject().put("name", "models/gemini-live")
      .put("supportedGenerationMethods", JSONArray(listOf("generateContent"))))
      .put(JSONObject().put("name", "models/embedding").put("supportedGenerationMethods", JSONArray(listOf("embedContent"))))
    assertEquals(2, TaskModelDirectory.parse(AiProvider.GEMINI, body).size)
  }

  @Test fun cloudListsAreScopedToTheServiceAddressAndCredentials() {
    val prefs = FakeSharedPreferences()
    AiProvider.OTHER.setApiKey(prefs, "first-key")
    AiProvider.setOtherAddress(prefs, "https://first.example/v1")
    val scope = TaskModelDirectory.currentScope(prefs, AiProvider.OTHER)
    val models = listOf(TaskRemoteModel("model", "Mô hình"))
    TaskModelDirectory.remember(prefs, AiProvider.OTHER, models, scope)
    assertEquals(models, TaskModelDirectory.cached(prefs, AiProvider.OTHER))
    AiProvider.OTHER.setApiKey(prefs, "second-key")
    assertTrue(TaskModelDirectory.cached(prefs, AiProvider.OTHER).isEmpty())
    TaskModelDirectory.remember(prefs, AiProvider.OTHER, models, scope)
    assertTrue(TaskModelDirectory.cached(prefs, AiProvider.OTHER).isEmpty())
    assertNotEquals(scope, TaskModelDirectory.cacheScope(AiProvider.OTHER, "https://second.example/v1", "first-key"))
  }

  @Test fun publicErrorsDoNotExposeARawServerMessageOrCredentials() {
    assertTrue(TaskAiErrors.http(429).contains("hạn mức"))
    assertTrue(TaskAiErrors.http(404).contains("mô hình"))
    assertFalse(TaskAiErrors.listFailure(java.io.IOException("api_key=private-key")).contains("private-key"))
    assertTrue(TaskAiErrors.listFailure(TaskHttpException(401)).contains("401"))
  }
}
