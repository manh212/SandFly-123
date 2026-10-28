package com.google.android.accessibility.talkback.taskassistant

import android.content.SharedPreferences
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class TaskRemoteModel(val id: String, val name: String) {
  val label: String get() = if (name == id) id else "$name — $id"
}

object TaskModelDirectory {
  private val excluded = Regex("tts|embedding|transcribe|whisper|image|live|audio|veo|lyria|robotics|banana|moderation|rerank", RegexOption.IGNORE_CASE)

  fun parse(provider: AiProvider, body: JSONObject): List<TaskRemoteModel> {
    val data = body.optJSONArray(if (provider == AiProvider.GEMINI) "models" else "data") ?: return emptyList()
    return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { item ->
      val id = if (provider == AiProvider.GEMINI) item.optString("name").removePrefix("models/") else item.optString("id")
      val methods = item.optJSONArray("supportedGenerationMethods")
      val generates = provider != AiProvider.GEMINI || (methods != null &&
        (0 until methods.length()).any { methods.optString(it) == "generateContent" })
      if (id.isBlank() || id.length > 256 || excluded.containsMatchIn(id) || !generates) null
      else TaskRemoteModel(id, item.optString("displayName").ifBlank {
        item.optString("display_name").ifBlank { item.optString("name").ifBlank { id } }
      }.take(300))
    }.distinctBy { it.id }
  }

  fun cacheScope(provider: AiProvider, address: String, apiKey: String): String =
    MessageDigest.getInstance("SHA-256").digest("${provider.id}\n$address\n$apiKey".toByteArray())
      .joinToString("") { "%02x".format(it) }

  private fun scope(prefs: SharedPreferences, provider: AiProvider): String = cacheScope(provider,
    provider.addressOf(prefs), if (provider == AiProvider.GEMINI) GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY) else provider.apiKey(prefs))

  fun cached(prefs: SharedPreferences, provider: AiProvider): List<TaskRemoteModel> = runCatching {
    val json = JSONObject(prefs.getString("pref_task_assistant_models_${provider.id}", "{}").orEmpty())
    if (json.optString("scope") != scope(prefs, provider)) return@runCatching emptyList<TaskRemoteModel>()
    val data = json.getJSONArray("models")
    (0 until data.length()).map { data.getJSONObject(it) }.map { TaskRemoteModel(it.getString("id"), it.getString("name")) }
  }.getOrDefault(emptyList())

  fun remember(prefs: SharedPreferences, provider: AiProvider, models: List<TaskRemoteModel>, expectedScope: String) {
    if (scope(prefs, provider) != expectedScope) return
    prefs.edit().putString("pref_task_assistant_models_${provider.id}", JSONObject().put("scope", expectedScope)
      .put("models", JSONArray(models.map { JSONObject().put("id", it.id).put("name", it.name) })).toString()).apply()
  }

  fun currentScope(prefs: SharedPreferences, provider: AiProvider): String = scope(prefs, provider)

  fun load(prefs: SharedPreferences, provider: AiProvider, fetch: (String, AiProvider, String) -> JSONObject = ::read): List<TaskRemoteModel> {
    require(TaskAiCatalog.canConnect(prefs, provider)) { "Dịch vụ chưa được cấu hình khóa API hoặc địa chỉ hợp lệ" }
    val key = if (provider == AiProvider.GEMINI) GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY) else provider.apiKey(prefs)
    val base = if (provider == AiProvider.GEMINI) "https://generativelanguage.googleapis.com/v1beta/models"
      else provider.addressOf(prefs).trim().trimEnd('/').removeSuffix("/chat/completions") + "/models"
    val models = LinkedHashMap<String, TaskRemoteModel>()
    val seen = mutableSetOf<String>()
    var page = ""
    repeat(20) {
      val query = when (provider) {
        AiProvider.GEMINI -> "?pageSize=1000" + if (page.isNotEmpty()) "&pageToken=${URLEncoder.encode(page, "UTF-8")}" else ""
        AiProvider.CLAUDE -> "?limit=1000" + if (page.isNotEmpty()) "&after_id=${URLEncoder.encode(page, "UTF-8")}" else ""
        else -> ""
      }
      val body = fetch(base + query, provider, key)
      parse(provider, body).forEach { models[it.id] = it }
      page = when (provider) {
        AiProvider.GEMINI -> body.optString("nextPageToken")
        AiProvider.CLAUDE -> if (body.optBoolean("has_more")) body.optString("last_id") else ""
        else -> ""
      }
      if (page.isEmpty()) return models.values.toList()
      check(seen.add(page)) { "Dịch vụ trả lại trang mô hình bị lặp" }
    }
    throw IOException("Danh sách mô hình quá dài. Vui lòng thử lại sau")
  }

  private fun read(address: String, provider: AiProvider, key: String): JSONObject {
    val connection = URL(address).openConnection() as HttpURLConnection
    try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 15_000
      connection.readTimeout = 15_000
      if (provider == AiProvider.GEMINI) connection.setRequestProperty("x-goog-api-key", key)
      else {
        connection.setRequestProperty("Authorization", "Bearer $key")
        if (provider == AiProvider.CLAUDE) {
          connection.setRequestProperty("x-api-key", key)
          connection.setRequestProperty("anthropic-version", "2023-06-01")
        }
      }
      val code = connection.responseCode
      if (code != 200) throw TaskHttpException(code)
      val out = java.io.ByteArrayOutputStream()
      connection.inputStream.use { input ->
        val buffer = ByteArray(8192)
        while (out.size() <= 4 * 1024 * 1024) {
          val count = input.read(buffer)
          if (count < 0) break
          out.write(buffer, 0, count)
        }
      }
      check(out.size() <= 4 * 1024 * 1024) { "Danh sách mô hình quá dài" }
      return JSONObject(out.toString("UTF-8"))
    } finally { connection.disconnect() }
  }
}

class TaskHttpException(val code: Int) : IOException(TaskAiErrors.http(code))

object TaskAiErrors {
  fun listFailure(error: Throwable): String = when (error) {
    is TaskHttpException -> http(error.code)
    is java.net.SocketTimeoutException -> "Dịch vụ phản hồi quá lâu. Hãy thử lại"
    is org.json.JSONException -> "Dịch vụ trả lại danh sách mô hình không hợp lệ"
    is IllegalStateException -> "Không đọc được đầy đủ danh sách mô hình. Hãy thử lại"
    else -> "Hãy kiểm tra kết nối mạng và cấu hình dịch vụ"
  }

  fun http(code: Int): String = when (code) {
    400 -> "Dịch vụ không chấp nhận yêu cầu. Hãy kiểm tra mô hình và khả năng trả lời có cấu trúc (HTTP 400)"
    401 -> "Khóa API không được chấp nhận (HTTP 401)"
    403 -> "Dịch vụ từ chối quyền truy cập. Hãy kiểm tra quyền của khóa API (HTTP 403)"
    404 -> "Không tìm thấy dịch vụ hoặc mô hình đã chọn. Hãy tải lại danh sách mô hình (HTTP 404)"
    429 -> "Đã chạm hạn mức hoặc gửi quá nhiều yêu cầu. Vui lòng chờ rồi thử lại (HTTP 429)"
    in 500..599 -> "Máy chủ AI đang gặp lỗi (HTTP $code)"
    else -> "Không truy cập được dịch vụ AI (HTTP $code)"
  }
}
