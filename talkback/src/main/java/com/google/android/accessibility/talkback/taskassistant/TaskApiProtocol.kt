package com.google.android.accessibility.talkback.taskassistant

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object TaskApiProtocol {
  fun geminiUrl(model: String): String {
    val id = model.trim().removePrefix("models/")
    require(id.isNotEmpty()) { "Chưa chọn mô hình Gemini" }
    return "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(id, "UTF-8").replace("+", "%20")}:generateContent"
  }

  fun geminiRequest(prompt: String, model: String = "gemini"): JSONObject {
    val fields = JSONObject().put("snapshot", JSONObject().put("type", "INTEGER"))
      .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray(TaskOperation.entries.map { it.wire })))
    listOf("node", "package", "text", "message", "evidence").forEach { fields.put(it, JSONObject().put("type", "STRING")) }
    fields.put("checked", JSONObject().put("type", "BOOLEAN")).put("sensitive", JSONObject().put("type", "BOOLEAN"))
    val schema = JSONObject().put("type", "OBJECT").put("properties", fields)
      .put("required", JSONArray(listOf("snapshot", "action")))
    val config = JSONObject().put("maxOutputTokens", 4096)
    if (model.removePrefix("models/").startsWith("gemini")) {
      config.put("responseMimeType", "application/json").put("responseSchema", schema)
    }
    return JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
      .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
      .put("generationConfig", config)
  }

  fun response(json: JSONObject, gemini: Boolean): String = try {
    val text = if (gemini) {
      val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        ?: error("Mô hình không trả về câu trả lời. Hãy kiểm tra mô hình hoặc nội dung yêu cầu")
      when (candidate.optString("finishReason")) {
        "STOP" -> Unit
        "MAX_TOKENS" -> error("Mô hình đã dùng hết giới hạn trả lời. Hãy rút ngắn yêu cầu hoặc chọn mô hình khác")
        "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT" -> error("Dịch vụ đã chặn câu trả lời theo chính sách nội dung")
        else -> error("Mô hình chưa trả lời đầy đủ. Hãy thử lại hoặc chọn mô hình khác")
      }
      val parts = candidate.getJSONObject("content").getJSONArray("parts")
      (0 until parts.length()).map { parts.getJSONObject(it) }.filterNot { it.optBoolean("thought") }
        .joinToString("") { it.optString("text") }
    } else {
      val candidate = json.getJSONArray("choices").getJSONObject(0)
      check(candidate.optString("finish_reason") == "stop") { "Mô hình chưa trả lời đầy đủ. Hãy thử lại hoặc chọn mô hình khác" }
      when (val content = candidate.getJSONObject("message").get("content")) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
        else -> error("Mô hình không trả về văn bản")
      }
    }
    text.also { check(it.isNotBlank()) { "Mô hình trả về câu trả lời trống" } }
  } catch (_: JSONException) {
    throw IllegalStateException("Không đọc được câu trả lời của mô hình. Hãy chọn mô hình hỗ trợ trả lời bằng văn bản có cấu trúc")
  }
}
