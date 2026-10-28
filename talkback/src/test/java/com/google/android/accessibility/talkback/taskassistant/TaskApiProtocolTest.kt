package com.google.android.accessibility.talkback.taskassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskApiProtocolTest {
  @Test fun geminiUrlUsesOneConcreteModelAndStripsTheResourcePrefix() {
    assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent",
      TaskApiProtocol.geminiUrl("models/gemini-test"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun aBlankModelCanNeverReachTheGeminiEndpoint() { TaskApiProtocol.geminiUrl(" ") }

  @Test fun geminiRequestAsksForAValidatedTaskObject() {
    val json = TaskApiProtocol.geminiRequest("request")
    val config = json.getJSONObject("generationConfig")
    assertEquals("application/json", config.getString("responseMimeType"))
    val schema = config.getJSONObject("responseSchema")
    assertEquals("OBJECT", schema.getString("type"))
    assertEquals("INTEGER", schema.getJSONObject("properties").getJSONObject("snapshot").getString("type"))
    assertEquals("BOOLEAN", schema.getJSONObject("properties").getJSONObject("checked").getString("type"))
    assertTrue(schema.getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString().contains("open_app"))
  }

  @Test fun nonGeminiModelsDoNotReceiveGeminiOnlyOutputOptions() {
    val config = TaskApiProtocol.geminiRequest("request", "gemma-model").getJSONObject("generationConfig")
    assertFalse(config.has("responseSchema"))
    assertFalse(config.has("responseMimeType"))
  }

  @Test fun thinkingPartsCannotBecomeAnExecutableAnswer() {
    val json = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
      .put("content", JSONObject().put("parts", JSONArray()
        .put(JSONObject().put("thought", true).put("text", "private reasoning"))
        .put(JSONObject().put("text", "{\"snapshot\":1,\"action\":\"wait\"}"))))))
    assertEquals("{\"snapshot\":1,\"action\":\"wait\"}", TaskApiProtocol.response(json, true))
  }

  @Test fun truncatedAndBlockedResponsesAreRejectedWithUsefulReasons() {
    for (reason in listOf("MAX_TOKENS", "SAFETY")) {
      val json = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", reason)))
      try { TaskApiProtocol.response(json, true); fail("The partial answer must not execute") }
      catch (error: IllegalStateException) { assertTrue(error.message!!.contains(if (reason == "MAX_TOKENS") "giới hạn" else "chặn")) }
    }
  }
}
