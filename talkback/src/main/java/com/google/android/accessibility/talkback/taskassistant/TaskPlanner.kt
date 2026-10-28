package com.google.android.accessibility.talkback.taskassistant

import android.content.Context
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
import com.google.android.accessibility.talkback.actor.gemini.local.RemoteLocalLlm
import com.google.android.accessibility.talkback.actor.gemini.local.OnDeviceAiSettings
import com.google.android.accessibility.talkback.actor.gemini.local.LocalModel
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

class TaskPlanner(private val context: Context) {
  private val queue = Volley.newRequestQueue(context.applicationContext)
  private val worker = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())
  private val generation = AtomicLong()
  private val tag = Any()
  private var timeout: Runnable? = null
  @Volatile private var localModel: LocalLlm? = null
  private var localClient: RemoteLocalLlm? = null
  private var localKey = ""

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
          val selected = LocalModel.entries.firstOrNull { it.id == choice.model && it.writesJson }
            ?: error("Mô hình không hỗ trợ giao việc")
          check(manager.store.isInstalled(selected)) { "Mô hình chưa tải xong hoặc đã bị xóa" }
          val prefs = SharedPreferencesUtils.getSharedPreferences(context)
          val gpu = OnDeviceAiSettings.useGpu(prefs)
          val key = "${selected.id}:$gpu:${manager.store.file(selected).lastModified()}"
          if (localKey != key || localClient == null) {
            localClient?.close()
            localClient = RemoteLocalLlm(context, selected, manager.store.file(selected), gpu)
            localKey = key
          }
          val model = checkNotNull(localClient)
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
    if (!TaskAiCatalog.canConnect(prefs, provider)) {
      complete(Result.failure(IllegalStateException("Dịch vụ AI đã chọn chưa sẵn sàng")))
      return
    }
    if (choice.model.isBlank()) {
      complete(Result.failure(IllegalStateException("Chưa chọn mô hình. Hãy tải danh sách và chọn mô hình")))
      return
    }
    val gemini = provider == AiProvider.GEMINI
    val address = if (gemini) {
      TaskApiProtocol.geminiUrl(choice.model)
    } else AiService.chatUrl(provider.addressOf(prefs))
    val body = if (gemini) TaskApiProtocol.geminiRequest(prompt, choice.model)
    else JSONObject().put("model", choice.model)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
    val apiKey = if (gemini) GeminiApiKey.effectiveKey(prefs, BuildConfig.GEMINI_API_KEY) else provider.apiKey(prefs)
    val request = object : JsonObjectRequest(Request.Method.POST, address, body, { response ->
      if (generation.get() == token) complete(runCatching { TaskApiProtocol.response(response, gemini) })
    }, { error ->
      val reason = error.networkResponse?.statusCode?.let(TaskAiErrors::http)
        ?: if (error is com.android.volley.TimeoutError) "AI phản hồi quá lâu. Vui lòng thử lại" else "Không kết nối được dịch vụ AI. Hãy kiểm tra kết nối mạng"
      if (generation.get() == token) complete(Result.failure(IllegalStateException("${choice.model}: $reason")))
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
    worker.execute { localClient?.close(); localClient = null }
    worker.shutdown()
  }

  companion object {
    private val workerCancel = Executors.newSingleThreadExecutor()
  }
}
