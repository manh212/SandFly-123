package com.google.android.accessibility.talkback.taskassistant

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.accessibility.talkback.BuildConfig
import com.google.android.accessibility.talkback.actor.gemini.AiProvider
import com.google.android.accessibility.talkback.actor.gemini.AiService
import com.google.android.accessibility.talkback.actor.gemini.GeminiApiKey
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModelManager
import com.google.android.accessibility.talkback.actor.gemini.local.LocalLlm
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

data class TaskAiChoice(val id: String, val label: String, val model: String, val provider: AiProvider?)

object TaskAiCatalog {
  const val PREF_CHOICE = "pref_task_assistant_ai"
  const val PREF_MODEL = "pref_task_assistant_model"

  fun choices(context: Context): List<TaskAiChoice> {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val cloud = AiProvider.entries.filter { it != AiProvider.NONE && it.isReady(prefs) }.map {
      TaskAiChoice(it.id, "${context.getString(it.label)} — ${it.model(prefs)}", it.model(prefs), it)
    }
    val savedProvider = cloud.firstOrNull { it.id == prefs.getString(PREF_CHOICE, null) }
    val savedModel = prefs.getString(PREF_MODEL, null)?.takeIf { it.isNotBlank() }
    val retained = if (savedProvider != null && savedModel != null && savedProvider.model != savedModel) {
      listOf(savedProvider.copy(model = savedModel,
        label = "${context.getString(savedProvider.provider!!.label)} — $savedModel"))
    } else emptyList()
    val local = LocalModelManager.get(context).activeModel()?.takeIf { it.writesJson }?.let {
      listOf(TaskAiChoice("local:${it.id}", "Trên thiết bị — ${it.name}", it.id, null))
    }.orEmpty()
    return cloud + retained + local
  }

  fun selected(context: Context): TaskAiChoice? {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    return selected(prefs, choices(context))
  }

  fun selected(prefs: SharedPreferences, choices: List<TaskAiChoice>): TaskAiChoice? {
    val id = prefs.getString(PREF_CHOICE, null)
    val model = prefs.getString(PREF_MODEL, null)
    return choices.firstOrNull { it.id == id && (model == null || it.model == model) }
  }

  fun select(context: Context, choice: TaskAiChoice) {
    select(SharedPreferencesUtils.getSharedPreferences(context), choice)
  }

  fun select(prefs: SharedPreferences, choice: TaskAiChoice) {
    prefs.edit().putString(PREF_CHOICE, choice.id).putString(PREF_MODEL, choice.model).apply()
  }
}

class TaskPlanner(private val context: Context) {
  private val queue = Volley.newRequestQueue(context.applicationContext)
  private val worker = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private val generation = AtomicLong()
  private val tag = Any()
  private var timeout: Runnable? = null
  @Volatile private var localModel: LocalLlm? = null

  fun request(prompt: String, choice: TaskAiChoice, done: (Result<String>) -> Unit) {
    cancel()
    val token = generation.get()
    val timedOut = Runnable {
      if (token == generation.get()) {
        cancel()
        done(Result.failure(IllegalStateException("AI phản hồi quá lâu")))
      }
    }
    timeout = timedOut
    main.postDelayed(timedOut, 60_000)
    val complete: (Result<String>) -> Unit = {
      main.removeCallbacks(timedOut)
      if (token == generation.get()) done(it)
    }
    if (choice.provider == null) {
      worker.execute {
        val result = runCatching {
          val manager = LocalModelManager.get(context)
          check(manager.activeModel()?.id == choice.model) { "Mô hình trên thiết bị đã thay đổi. Vui lòng chọn AI lại" }
          val model = checkNotNull(manager.llm()) { "Mô hình chưa sẵn sàng" }
          check(generation.get() == token)
          localModel = model
          try { model.generate(prompt, null) } finally { if (localModel === model) localModel = null }
        }
        main.post { complete(result) }
      }
      return
    }
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val provider = choice.provider
    if (!provider.isReady(prefs)) {
      complete(Result.failure(IllegalStateException("Dịch vụ AI đã chọn chưa sẵn sàng")))
      return
    }
    val gemini = provider == AiProvider.GEMINI
    val address = if (gemini) {
      "https://generativelanguage.googleapis.com/v1beta/models/${Uri.encode(choice.model.removePrefix("models/"))}:generateContent"
    } else AiService.chatUrl(provider.addressOf(prefs))
    val body = if (gemini) JSONObject()
      .put("contents", JSONArray().put(JSONObject().put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
      .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 4096))
    else JSONObject().put("model", choice.model)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
    val apiKey = if (gemini) GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY) else provider.apiKey(prefs)
    val request = object : JsonObjectRequest(Request.Method.POST, address, body, { response ->
      if (generation.get() == token) complete(runCatching {
        if (gemini) {
          val candidate = response.getJSONArray("candidates").getJSONObject(0)
          check(candidate.optString("finishReason") == "STOP") { "AI chưa trả lời đầy đủ" }
          val parts = candidate.getJSONObject("content").getJSONArray("parts")
          (0 until parts.length()).map { parts.getJSONObject(it) }
            .filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") }
        } else {
          val candidate = response.getJSONArray("choices").getJSONObject(0)
          check(candidate.optString("finish_reason") == "stop") { "AI chưa trả lời đầy đủ" }
          val content = candidate.getJSONObject("message").get("content")
          when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
            else -> error("AI không trả về văn bản")
          }
        }
      })
    }, {
      if (generation.get() == token) complete(Result.failure(IllegalStateException("Không kết nối được AI hoặc dịch vụ từ chối yêu cầu")))
    }) {
      override fun getHeaders(): MutableMap<String, String> = mutableMapOf(
        if (gemini) "x-goog-api-key" to apiKey else "Authorization" to "Bearer $apiKey"
      )
    }
    request.tag = tag
    request.retryPolicy = DefaultRetryPolicy(45_000, 0, 1f)
    queue.add(request)
  }

  fun cancel() {
    generation.incrementAndGet()
    timeout?.let(main::removeCallbacks)
    timeout = null
    queue.cancelAll(tag)
    localModel?.let { model -> workerCancel.execute { model.cancel() } }
  }

  fun close() {
    cancel()
    queue.stop()
    worker.shutdownNow()
  }

  companion object {
    private val workerCancel = Executors.newSingleThreadExecutor()
  }
}
