package com.google.android.accessibility.talkback.textcorrection

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.AiTextRequests
import com.google.android.accessibility.utils.widget.KeyboardSafeEditText
import java.util.function.Consumer

class TextCorrectionActivity : AppCompatActivity() {
  private var sessionId = -1
  private lateinit var source: TextCorrection.Source
  private lateinit var requests: AiTextRequests
  private var generation = 0
  private var instruction = ""
  private var result: String? = null
  private val styles = listOf(
    R.string.correction_professional to "Make the wording professional.",
    R.string.correction_friendly to "Make the wording friendly and warm.",
    R.string.correction_formal to "Make the wording formal and respectful.",
    R.string.correction_simple to "Use simple, easy-to-understand wording.",
    R.string.correction_shorter to "Shorten the wording while keeping its essential information.",
    R.string.correction_longer to "Expand the wording without adding facts or new ideas.",
    R.string.correction_spelling to "Correct spelling, punctuation and recognition errors only; keep the writing style.",
    R.string.correction_coherent to "Improve coherence and sentence order, and remove repetition.",
    R.string.correction_custom to "",
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    sessionId = intent.getIntExtra(TextCorrection.EXTRA_SESSION, -1)
    source = TextCorrection.source(sessionId) ?: run { finish(); return }
    requests = AiTextRequests(this, Consumer { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() })
    if (!AiTextRequests.isSetUp(this)) {
      Toast.makeText(this, R.string.ai_service_not_set_up, Toast.LENGTH_LONG).show()
      finish()
      return
    }
    instruction = savedInstanceState?.getString("instruction").orEmpty()
    result = savedInstanceState?.getString("result")
    if (result != null) preview()
    else if (instruction.isNotBlank()) request()
    else chooseStyle()
  }

  private fun chooseStyle() {
    setTitle(R.string.correction_title)
    val list = ListView(this).apply {
      adapter = ArrayAdapter(this@TextCorrectionActivity, android.R.layout.simple_list_item_1,
        styles.map { getString(it.first) })
      setOnItemClickListener { _, _, position, _ ->
        val style = styles[position]
        if (style.first == R.string.correction_custom) custom()
        else {
          instruction = style.second
          request()
        }
      }
    }
    val root = column()
    root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(button(android.R.string.cancel) { finish() })
    show(root)
  }

  private fun custom() {
    val field = KeyboardSafeEditText(this).apply {
      setHint(R.string.correction_instruction)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
    val box = column().apply { addView(field) }
    val dialog = AlertDialog.Builder(this)
      .setTitle(R.string.correction_custom)
      .setView(box)
      .setPositiveButton(android.R.string.ok, null)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    dialog.setOnShowListener {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        val value = field.text.toString().trim()
        if (value.isNotEmpty()) {
          instruction = value
          dialog.dismiss()
          request()
        }
      }
      field.requestFocus()
      dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
      field.post { getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
    }
    dialog.show()
  }

  private fun request() {
    val requestId = ++generation
    requests.cancel()
    result = null
    setTitle(R.string.correction_title)
    show(column().apply {
      addView(label(R.string.correction_working))
      addView(button(android.R.string.cancel) { finish() })
    })
    requests.ask(CorrectionPrompt.build(source.selected, instruction), true) { answer, failure ->
      runOnUiThread {
        if (isFinishing || isDestroyed || requestId != generation) return@runOnUiThread
        result = answer?.let(CorrectionPrompt::result)
        if (result != null) preview()
        else show(column().apply {
          addView(TextView(this@TextCorrectionActivity).apply {
            text = failure?.userMessage(this@TextCorrectionActivity) ?: getString(R.string.correction_failed)
          })
          addView(button(R.string.correction_retry) { request() })
          addView(button(android.R.string.cancel) { finish() })
        })
      }
    }
  }

  private fun preview() {
    val edited = result ?: return
    setTitle(R.string.correction_preview)
    val text = column().apply {
      addView(label(R.string.correction_original))
      addView(body(source.selected))
      addView(label(R.string.correction_result))
      addView(body(edited))
    }
    val root = column()
    root.addView(ScrollView(this).apply { addView(text) },
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    root.addView(LinearLayout(this).apply {
      addView(button(R.string.correction_apply) {
        TextCorrection.apply(sessionId, edited)
        finish()
      }, weight())
      addView(button(R.string.correction_retry) { request() }, weight())
      addView(button(android.R.string.cancel) { finish() }, weight())
    })
    show(root)
  }

  private fun column() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val padding = (12 * resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
  }

  private fun label(resource: Int) = TextView(this).apply {
    setText(resource)
    textSize = 20f
    ViewCompat.setAccessibilityHeading(this, true)
  }

  private fun body(value: String) = TextView(this).apply {
    text = value
    textSize = 18f
    setTextIsSelectable(true)
    setPadding(0, 12, 0, 24)
  }

  private fun button(resource: Int, action: () -> Unit) = Button(this).apply {
    setText(resource)
    isAllCaps = false
    setOnClickListener { action() }
  }

  private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

  private fun show(view: View) {
    ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
      val padding = (12 * resources.displayMetrics.density).toInt()
      target.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bars.bottom + padding)
      insets
    }
    setContentView(view)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putString("instruction", instruction)
    result?.let { outState.putString("result", it) }
    super.onSaveInstanceState(outState)
  }

  override fun onDestroy() {
    generation++
    if (::requests.isInitialized) requests.cancel()
    if (!isChangingConfigurations) TextCorrection.discard(sessionId)
    super.onDestroy()
  }
}
