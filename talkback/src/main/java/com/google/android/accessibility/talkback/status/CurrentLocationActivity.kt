package com.google.android.accessibility.talkback.status

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.output.SpeechController
import java.util.function.Consumer

class CurrentLocationActivity : AppCompatActivity() {
  private var reader: CurrentLocationReader? = null
  private var started = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setTitle(R.string.shortcut_read_current_location)
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      val padding = (24 * resources.displayMetrics.density).toInt()
      setPadding(padding, padding, padding, padding)
      addView(TextView(this@CurrentLocationActivity).apply { setText(R.string.location_working) })
      addView(Button(this@CurrentLocationActivity).apply {
        setText(android.R.string.cancel)
        setOnClickListener { finish() }
      })
    }
    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val padding = (24 * resources.displayMetrics.density).toInt()
      view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding)
      insets
    }
    setContentView(content)
    if (permitted()) start()
    else requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
  }

  private fun permitted() = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 1) {
      if (permitted()) start() else complete(getString(R.string.location_permission_needed))
    }
  }

  private fun start() {
    if (started) return
    started = true
    reader = CurrentLocationReader(this, Consumer(::complete)).also { it.read() }
  }

  private fun complete(text: String) {
    if (isFinishing) return
    finish()
    val app = applicationContext
    Handler(Looper.getMainLooper()).postDelayed({
      val service = TalkBackService.getInstance()
      if (service != null) service.speechController.speak(text, EVENT_ID_UNTRACKED,
        SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE))
      else Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
    }, 250)
  }

  override fun onStop() {
    reader?.cancel()
    if (started && !isFinishing) finish()
    super.onStop()
  }

  override fun onDestroy() {
    reader?.shutdown()
    super.onDestroy()
  }

  companion object {
    @JvmStatic fun open(context: Context): Boolean {
      context.startActivity(Intent(context, CurrentLocationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      return true
    }
  }
}
