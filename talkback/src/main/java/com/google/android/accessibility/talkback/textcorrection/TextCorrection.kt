package com.google.android.accessibility.talkback.textcorrection

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.granularediting.GranularEditing
import java.lang.ref.WeakReference

object TextCorrection {
  internal data class Source(val original: String, val start: Int, val end: Int) {
    val selected: String get() = original.substring(start, end)
    fun replace(text: String): String = original.substring(0, start) + text + original.substring(end)
  }

  private data class Session(
    val source: Source,
    val target: AccessibilityNodeInfoCompat,
    val service: WeakReference<TalkBackService>,
  )

  private val sessions = HashMap<Int, Session>()
  private var nextId = 1
  private val handler = Handler(Looper.getMainLooper())
  internal const val EXTRA_SESSION = "text_correction_session"

  @JvmStatic fun open(service: TalkBackService, node: AccessibilityNodeInfoCompat?): Boolean {
    if (node == null || !node.isEditable || !node.isEnabled) return refuse(service, R.string.correction_no_editor)
    if (node.isPassword) return refuse(service, R.string.correction_password)
    val original = GranularEditing.fieldText(node)
    val source = source(original, node.textSelectionStart, node.textSelectionEnd)
    if (source.selected.isBlank()) return refuse(service, R.string.correction_empty)
    val id = nextId++
    sessions[id] = Session(source, AccessibilityNodeInfoCompat.obtain(node), WeakReference(service))
    try {
      service.startActivity(Intent(service, TextCorrectionActivity::class.java)
        .putExtra(EXTRA_SESSION, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (failure: RuntimeException) {
      discard(id)
      throw failure
    }
    return true
  }

  internal fun source(original: String, selectionStart: Int, selectionEnd: Int): Source {
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    return if (start >= 0 && end <= original.length && start < end) Source(original, start, end)
      else Source(original, 0, original.length)
  }

  internal fun source(id: Int): Source? = sessions[id]?.source

  internal fun discard(id: Int) {
    sessions.remove(id)?.target?.recycle()
  }

  internal fun apply(id: Int, result: String) {
    val session = sessions.remove(id) ?: return
    val replacement = session.source.replace(result)
    fun attempt(number: Int) {
      val service = session.service.get()
      if (service == null || !TalkBackService.isServiceActive()) {
        session.target.recycle()
        return
      }
      val roots = service.windows.mapNotNull { it.root?.let(AccessibilityNodeInfoCompat::wrap) }
      val field = GranularEditing.findMatchingFieldAcrossRoots(roots, session.target)
      if (field == null) {
        if (number < 12) handler.postDelayed({ attempt(number + 1) }, 150)
        else {
          refuse(service, R.string.correction_no_editor)
          session.target.recycle()
        }
        return
      }
      if (field.isPassword || GranularEditing.fieldText(field) != session.source.original) {
        refuse(service, R.string.correction_changed)
        session.target.recycle()
        return
      }
      val arguments = Bundle().apply {
        putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
      }
      val success = field.performAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT, arguments)
      if (success) {
        val cursor = session.source.start + result.length
        field.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, Bundle().apply {
          putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
          putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
      }
      refuse(service, if (success) R.string.correction_applied else R.string.correction_apply_failed)
      session.target.recycle()
    }
    handler.postDelayed({ attempt(0) }, 200)
  }

  private fun refuse(service: TalkBackService, message: Int): Boolean {
    service.announceKeyboardScheme(service.getString(message))
    return false
  }
}
