/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.actor.gemini

import android.content.Context
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestRequestPerformer.GeminiRestResponseCallback
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONException
import org.json.JSONObject

/** Sends Gemini requests to the [AiService] the user set up, as chat requests. */
internal class AiServiceRequestPerformer(private val requestQueue: RequestQueue) {
  constructor(context: Context) : this(GeminiRestRequestPerformer.sharedQueue(context))
  private val sessionsLock = Any()
  private var currentSession: RequestSession? = null

  private class RequestSession(val callback: GeminiRestResponseCallback) {
    val requests: MutableSet<Request<*>> =
      Collections.newSetFromMap(IdentityHashMap<Request<*>, Boolean>())
    var cancelled = false
    var finished = false
  }

  fun performRequest(
    address: String,
    model: String,
    apiKey: String,
    geminiRequest: JSONObject,
    callback: GeminiRestResponseCallback,
  ) {
    val session = startSession(callback)
    if (!AiService.isValidAddress(address)) {
      finishFailure(session, GeminiFailure.other("The AI service address must start with https"))
      return
    }
    val chatRequest =
      try {
        AiService.toChatRequest(geminiRequest, model)
      } catch (e: JSONException) {
        finishFailure(session, GeminiFailure.other("Could not make the request: $e"))
        return
      }
    val wantsJson = AiService.wantsJson(geminiRequest)
    val requestReference = AtomicReference<Request<*>?>()
    val request =
      object :
        JsonObjectRequest(
          Request.Method.POST,
          AiService.chatUrl(address),
          chatRequest,
          { response ->
            removeRequest(session, requestReference.get())
            if (isActive(session)) {
              try {
                finishResponse(session, AiService.parseChatResponse(response, wantsJson))
              } catch (e: JSONException) {
                finishFailure(session, GeminiFailure.other("Could not read the answer: $e"))
              }
            }
          },
          { error ->
            removeRequest(session, requestReference.get())
            finishFailure(session, GeminiFailure.fromVolleyError(error).fromAiService())
          },
        ) {
        override fun getHeaders(): MutableMap<String, String> {
          val headers = HashMap(super.getHeaders())
          // Sent in a header rather than the address, which Volley and the system log.
          if (apiKey.isNotEmpty()) headers["Authorization"] = "Bearer $apiKey"
          return headers
        }
      }
    request.retryPolicy =
      DefaultRetryPolicy(TIMEOUT_MS, MAX_RETRIES, DefaultRetryPolicy.DEFAULT_BACKOFF_MULT)
    requestReference.set(request)
    if (!trackRequest(session, request)) {
      request.cancel()
      return
    }
    try {
      requestQueue.add(request)
    } catch (e: RuntimeException) {
      removeRequest(session, request)
      finishFailure(session, GeminiFailure.other("Could not start the request: $e"))
    }
  }

  private fun startSession(callback: GeminiRestResponseCallback): RequestSession {
    val next = RequestSession(callback)
    val previous = synchronized(sessionsLock) {
      currentSession.also {
        it?.cancelled = true
        currentSession = next
      }
    }
    cancelSession(previous)
    return next
  }

  private fun isActive(session: RequestSession): Boolean =
    synchronized(sessionsLock) {
      currentSession === session && !session.cancelled && !session.finished
    }

  private fun trackRequest(session: RequestSession, request: Request<*>): Boolean =
    synchronized(sessionsLock) {
      if (currentSession !== session || session.cancelled || session.finished) {
        false
      } else {
        session.requests.add(request)
      }
    }

  private fun removeRequest(session: RequestSession, request: Request<*>?) {
    if (request != null) synchronized(sessionsLock) { session.requests.remove(request) }
  }

  private fun finish(session: RequestSession): Boolean = synchronized(sessionsLock) {
    if (currentSession !== session || session.cancelled || session.finished) {
      false
    } else {
      session.finished = true
      currentSession = null
      true
    }
  }

  private fun finishResponse(session: RequestSession, response: DataFieldUtils.GeminiResponse) {
    if (finish(session)) session.callback.onResponse(response)
  }

  private fun finishFailure(session: RequestSession, failure: GeminiFailure) {
    if (finish(session)) session.callback.onFailure(failure)
  }

  private fun cancelSession(session: RequestSession?) {
    if (session == null) return
    val cancellation = synchronized(sessionsLock) {
      val notify = !session.finished
      session.cancelled = true
      session.finished = true
      val requests = session.requests.toList()
      session.requests.clear()
      requests to notify
    }
    cancellation.first.forEach { it.cancel() }
    if (cancellation.second) session.callback.onCancelled()
  }

  fun cancelExistingRequestIfNeeded() {
    val previous = synchronized(sessionsLock) {
      currentSession.also {
        currentSession = null
        it?.cancelled = true
      }
    }
    cancelSession(previous)
  }

  fun hasPendingTransaction(): Boolean = synchronized(sessionsLock) {
    currentSession?.let { !it.cancelled && !it.finished } == true
  }

  private companion object {
    // Services that run large models on demand can take longer than Gemini to answer.
    const val TIMEOUT_MS = 60_000
    // Retrying a request that timed out sends and pays for the same slow request again.
    const val MAX_RETRIES = 0
  }
}
