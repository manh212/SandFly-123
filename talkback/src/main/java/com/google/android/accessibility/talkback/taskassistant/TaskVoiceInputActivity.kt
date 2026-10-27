package com.google.android.accessibility.talkback.taskassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

class TaskVoiceInputActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) return
    try {
      startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói việc bạn muốn SandFly thực hiện")
      }, 1)
    } catch (_: Exception) {
      finish()
      TaskAssistant.onVoiceResult(intent.getLongExtra(REQUEST_TOKEN, 0), null)
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != 1) return
    val text = if (resultCode == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
    finish()
    TaskAssistant.onVoiceResult(intent.getLongExtra(REQUEST_TOKEN, 0), text)
  }
  companion object { const val REQUEST_TOKEN = "task_voice_request" }
}
