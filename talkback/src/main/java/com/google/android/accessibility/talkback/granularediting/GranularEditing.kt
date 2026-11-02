/*
 * Copyright 2026 SandFly contributors
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

package com.google.android.accessibility.talkback.granularediting

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.utils.ClipboardHistory
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Role

/**
 * Granular editing: the text of the focused item opens in a screen of its own, split into pieces
 * that can be edited one at a time. Saving writes the text back to the text field it came from.
 */
object GranularEditing {
  /** One editing session per screen. Nested screens preserve their parent session. */
  private data class Session(
    val originalText: String,
    val target: AccessibilityNodeInfoCompat?,
    val sourceService: WeakReference<AccessibilityService>?,
    val parentId: Int?,
    val parentFieldIndex: Int?,
  )

  internal data class ChildResult(val fieldIndex: Int, val text: String)

  private val sessions = mutableMapOf<Int, Session>()
  private val activities = mutableMapOf<Int, WeakReference<GranularEditingActivity>>()
  private val childResults = mutableMapOf<Int, MutableList<ChildResult>>()
  private var nextSessionId = 1

  private val handler = Handler(Looper.getMainLooper())

  @JvmStatic
  fun open(context: Context, node: AccessibilityNodeInfoCompat?): Boolean {
    if (node == null) return refuse(context, R.string.granular_editing_no_text)
    if (node.isPassword) return refuse(context, R.string.granular_editing_password)
    val editable = node.isEditable || Role.getRole(node) == Role.ROLE_EDIT_TEXT
    val nodeText =
      when {
        editable && node.isShowingHintText -> ""
        editable -> fieldText(node)
        else -> AccessibilityNodeInfoUtils.getText(node)?.toString().orEmpty()
      }
    if (!editable && nodeText.isBlank()) return refuse(context, R.string.granular_editing_no_text)

    // The currently displayed editor may have an EditText in its own dialog.
    // A child editor must return to that field, not replace the original app's target.
    val parent =
      if (editable) {
        activities.entries.toList().asReversed().firstNotNullOfOrNull { (id, activityRef) ->
          activityRef.get()?.nestedFieldIndex(node)?.let { id to it }
        }
      } else {
        null
      }

    val sessionId = nextSessionId++
    sessions[sessionId] =
      Session(
        originalText = nodeText,
        target = if (editable) AccessibilityNodeInfoCompat.obtain(node) else null,
        sourceService = (context as? AccessibilityService)?.let { WeakReference(it) },
        parentId = parent?.first,
        parentFieldIndex = parent?.second,
      )
    try {
      // NEW_TASK reuses the dedicated editor task and stacks the new activity.
      // CLEAR_TASK would destroy its parent and the dialog waiting for this text.
      val intent =
        Intent(context, GranularEditingActivity::class.java)
          .putExtra(EXTRA_SESSION_ID, sessionId)
      val parentActivity = parent?.first?.let { activities[it]?.get() }
      if (parentActivity != null) {
        // Launch from the parent activity so Android keeps it underneath this editor.
        parentActivity.startActivity(intent)
      } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      }
    } catch (exception: RuntimeException) {
      sessions.remove(sessionId)
      throw exception
    }
    return true
  }

  internal fun registerActivity(id: Int, activity: GranularEditingActivity) {
    activities[id] = WeakReference(activity)
  }

  internal fun unregisterActivity(id: Int, activity: GranularEditingActivity) {
    if (activities[id]?.get() === activity) activities.remove(id)
  }

  internal fun initialText(id: Int): String? = sessions[id]?.originalText

  internal fun isEditable(id: Int): Boolean = sessions[id]?.target != null

  internal fun takeChildResults(id: Int): List<ChildResult> =
    childResults.remove(id)?.toList().orEmpty()

  /** Child edits are staged here; they never write to the outer application's input. */
  internal fun queueChildResult(parentId: Int, fieldIndex: Int, newText: String): Boolean {
    if (!sessions.containsKey(parentId)) return false
    childResults.getOrPut(parentId) { mutableListOf() }.add(ChildResult(fieldIndex, newText))
    return true
  }

  /**
   * Saving a nested editor returns text to its parent's input dialog. Only the outermost
   * editor writes to the original app, after the user explicitly presses Save there.
   */
  internal fun save(context: Context, id: Int, newText: String) {
    val session = sessions.remove(id) ?: return
    val parentId = session.parentId
    val parentIndex = session.parentFieldIndex
    if (parentId != null && parentIndex != null) {
      if (!queueChildResult(parentId, parentIndex, newText)) {
        copy(context, newText)
        Toast.makeText(context, R.string.granular_editing_not_saved, Toast.LENGTH_LONG).show()
      }
      return
    }

    val field = session.target ?: return
    val service = session.sourceService?.get()
    val appContext = context.applicationContext
    var tries = 0
    val attempt =
      object : Runnable {
        override fun run() {
          if (write(field, newText, service)) return
          if (++tries < SAVE_TRIES) {
            handler.postDelayed(this, SAVE_INTERVAL_MS)
            return
          }
          copy(appContext, newText)
          Toast.makeText(appContext, R.string.granular_editing_not_saved, Toast.LENGTH_LONG).show()
        }
      }
    handler.postDelayed(attempt, SAVE_INTERVAL_MS)
  }

  /** Dismisses only this editor; enclosing editors and their source fields stay intact. */
  internal fun close(id: Int) {
    sessions.remove(id)
    childResults.remove(id)
  }

  fun copy(context: Context, copied: String) {
    ClipboardHistory.copy(context, copied)
  }

  internal const val EXTRA_SESSION_ID = "meridian_granular_session_id"

  private fun write(
    field: AccessibilityNodeInfoCompat,
    newText: String,
    service: AccessibilityService?,
  ): Boolean {
    if (field.refresh() && setText(field, newText)) return true
    // Android may assign a new window ID when the original app returns from the editor.
    // Search visible windows, and write only when there is exactly one matching field.
    val roots =
      service?.windows?.mapNotNull { it.root?.let(AccessibilityNodeInfoCompat::wrap) }
        ?: return false
    val replacement = findMatchingFieldAcrossRoots(roots, field) ?: return false
    return setText(replacement, newText)
  }

  private fun setText(field: AccessibilityNodeInfoCompat, newText: String): Boolean {
    val arguments = Bundle()
    arguments.putCharSequence(
      AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
      newText,
    )
    if (!field.performAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT, arguments)) return false
    field.refresh()
    val selection = Bundle()
    selection.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
    selection.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
    field.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, selection)
    return true
  }

  internal fun findMatchingFieldAcrossRoots(
    roots: List<AccessibilityNodeInfoCompat>,
    original: AccessibilityNodeInfoCompat,
  ): AccessibilityNodeInfoCompat? {
    var result: AccessibilityNodeInfoCompat? = null
    for (root in roots) {
      val candidate = findMatchingField(root, original) ?: continue
      if (result != null) return null
      result = candidate
    }
    return result
  }

  internal fun findMatchingField(
    root: AccessibilityNodeInfoCompat,
    original: AccessibilityNodeInfoCompat,
  ): AccessibilityNodeInfoCompat? {
    val sourceId = original.viewIdResourceName
    val sourcePackage = original.packageName?.toString()
    val sourceClass = original.className?.toString()
    val sourceBounds = Rect()
    original.getBoundsInScreen(sourceBounds)
    val pending = ArrayDeque<AccessibilityNodeInfoCompat>()
    pending.add(root)
    var match: AccessibilityNodeInfoCompat? = null
    var visited = 0
    while (pending.isNotEmpty() && visited++ < 4096) {
      val node = pending.removeFirst()
      if (
        node.isEditable &&
          node.isEnabled &&
          node.isVisibleToUser &&
          !node.isPassword &&
          node.packageName?.toString() == sourcePackage &&
          node.className?.toString() == sourceClass
      ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val sameField =
          if (!sourceId.isNullOrEmpty()) {
            node.viewIdResourceName == sourceId
          } else {
            !sourceBounds.isEmpty && bounds == sourceBounds
          }
        if (sameField) {
          if (match != null) return null
          match = node
        }
      }
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let { pending.addLast(it) }
      }
    }
    return match
  }

  /**
   * The text typed in [field]. Compose text fields report their label after the text, as "text,
   * label", in both the text and the content description, with no hint of their own. The real
   * text is as long as the furthest the cursor can go, which this finds by moving the cursor, then
   * puts it back.
   */
  private fun fieldText(field: AccessibilityNodeInfoCompat): String {
    val reported = field.text?.toString().orEmpty()
    val mayHaveLabel =
      reported.contains(", ") && field.contentDescription?.toString() == reported
    if (!mayHaveLabel) return reported
    val start = field.textSelectionStart
    val end = field.textSelectionEnd
    if (canPlaceCursor(field, reported.length)) {
      setSelection(field, start, end)
      return reported
    }
    // The furthest the cursor can be placed.
    var low = 0
    var high = reported.length - 1
    while (low < high) {
      val middle = (low + high + 1) / 2
      if (canPlaceCursor(field, middle)) low = middle else high = middle - 1
    }
    setSelection(field, start, end)
    return reported.substring(0, low)
  }

  private fun canPlaceCursor(field: AccessibilityNodeInfoCompat, at: Int): Boolean =
    setSelection(field, at, at)

  private fun setSelection(field: AccessibilityNodeInfoCompat, start: Int, end: Int): Boolean {
    val arguments = Bundle()
    arguments.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, start)
    arguments.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, end)
    return field.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, arguments)
  }

  private fun refuse(context: Context, message: Int): Boolean {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    return false
  }

  private const val SAVE_TRIES = 15
  private const val SAVE_INTERVAL_MS = 200L
}
