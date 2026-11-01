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
package com.google.android.accessibility.talkback.review

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.talkback.keyboard.TalkBackPhysicalKeyboardShortcut
import com.google.android.accessibility.talkback.keyboard.TalkBackPhysicalKeyboardShortcut as Key
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Filter
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechCleanupUtils
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

class ScreenReview(
  private val service: AccessibilityService,
  private val pipeline: Pipeline.FeedbackReturner,
  private val focusMonitor: AccessibilityFocusMonitor,
) {
  private val handler = Handler(Looper.getMainLooper())
  private var objectTarget: AccessibilityNodeInfoCompat? = null

  private var cursor: ReviewCursor? = null

  private var stale = true

  private var readAt = 0L

  private var startAtFocus = true

  var isCursorOn = false
    private set

  private val takenKeys = HashSet<Int>()

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
      AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
        objectTarget = null
        startAtFocus = true
        stale = true
      }
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
        objectTarget = null
        startAtFocus = true
        stale = true
      }
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> stale = true
    }
  }

  fun currentObject(): AccessibilityNodeInfoCompat? {
    val target = objectTarget ?: return null
    if (target.refresh() && target.isVisibleToUser) return target
    objectTarget = null
    return null
  }

  fun selectObject(node: AccessibilityNodeInfoCompat) {
    objectTarget = node
    cursor = null
    startAtFocus = true
    stale = true
    val label = node.contentDescription?.takeIf { it.isNotBlank() }
        ?: node.text?.takeIf { it.isNotBlank() }
        ?: service.getString(R.string.review_object, node.childCount)
    speak(label)
  }

  fun perform(action: TalkBackPhysicalKeyboardShortcut): Boolean {
    when (action) {
      Key.REVIEW_PREVIOUS_LINE -> move(Spoken.LINE, forward = false) { it.moveLine(-1) }
      Key.REVIEW_CURRENT_LINE -> speakCurrent(Spoken.LINE)
      Key.REVIEW_NEXT_LINE -> move(Spoken.LINE, forward = true) { it.moveLine(1) }
      Key.REVIEW_PREVIOUS_WORD -> move(Spoken.WORD, forward = false) { it.moveWord(forward = false) }
      Key.REVIEW_CURRENT_WORD -> speakCurrent(Spoken.WORD)
      Key.REVIEW_NEXT_WORD -> move(Spoken.WORD, forward = true) { it.moveWord(forward = true) }
      Key.REVIEW_PREVIOUS_CHARACTER ->
        move(Spoken.CHARACTER, forward = false) { it.moveCharacter(forward = false) }
      Key.REVIEW_CURRENT_CHARACTER -> speakCurrent(Spoken.CHARACTER)
      Key.REVIEW_NEXT_CHARACTER ->
        move(Spoken.CHARACTER, forward = true) { it.moveCharacter(forward = true) }
      Key.REVIEW_TOP -> move(Spoken.LINE, forward = false) { it.moveToTop() }
      Key.REVIEW_BOTTOM -> move(Spoken.LINE, forward = true) { it.moveToBottom() }
      Key.REVIEW_LINE_START ->
        move(Spoken.CHARACTER, forward = false) {
          it.moveToLineStart()
          true
        }
      Key.REVIEW_LINE_END ->
        move(Spoken.CHARACTER, forward = true) {
          it.moveToLineEnd()
          true
        }
      Key.REVIEW_CLICK -> click(long = false)
      Key.REVIEW_LONG_CLICK -> click(long = true)
      Key.REVIEW_MOVE_TO_FOCUS -> {
        objectTarget = null
        startAtFocus = true
        speakCurrent(Spoken.LINE)
      }
      Key.FOCUS_REVIEW_CURSOR -> focusReviewCursor()
      Key.REVIEW_CURSOR_ON -> setCursorOn(true)
      Key.REVIEW_CURSOR_OFF -> setCursorOn(false)
      else -> return false
    }
    return true
  }

  fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP) {
      return takenKeys.remove(event.keyCode)
    }
    if (event.action != KeyEvent.ACTION_DOWN || !isCursorOn) {
      return false
    }
    val modifiers =
      event.metaState and
        (KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON)
    val action =
      when (modifiers) {
        0 ->
          when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.REVIEW_PREVIOUS_LINE
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.REVIEW_NEXT_LINE
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.REVIEW_PREVIOUS_CHARACTER
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.REVIEW_NEXT_CHARACTER
            KeyEvent.KEYCODE_MOVE_HOME -> Key.REVIEW_LINE_START
            KeyEvent.KEYCODE_MOVE_END -> Key.REVIEW_LINE_END
            KeyEvent.KEYCODE_PAGE_UP -> Key.REVIEW_TOP
            KeyEvent.KEYCODE_PAGE_DOWN -> Key.REVIEW_BOTTOM
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.REVIEW_CLICK
            KeyEvent.KEYCODE_ESCAPE -> Key.REVIEW_CURSOR_OFF
            else -> null
          }
        KeyEvent.META_CTRL_ON ->
          when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.REVIEW_PREVIOUS_WORD
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.REVIEW_NEXT_WORD
            KeyEvent.KEYCODE_MOVE_HOME -> Key.REVIEW_TOP
            KeyEvent.KEYCODE_MOVE_END -> Key.REVIEW_BOTTOM
            else -> null
          }
        KeyEvent.META_SHIFT_ON ->
          when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.REVIEW_LONG_CLICK
            else -> null
          }
        else -> null
      } ?: return false
    takenKeys.add(event.keyCode)
    perform(action)
    return true
  }

  fun resetKeys() {
    takenKeys.clear()
  }

  fun shutdown() {
    handler.removeCallbacksAndMessages(null)
    takenKeys.clear()
    objectTarget = null
    cursor = null
    isCursorOn = false
  }

  private enum class Spoken {
    LINE,
    WORD,
    CHARACTER,
  }

  private fun move(spoken: Spoken, forward: Boolean, movement: (ReviewCursor) -> Boolean) {
    val cursor = currentCursor() ?: return
    if (!movement(cursor)) {
      speak(service.getString(if (forward) R.string.review_bottom else R.string.review_top))
      return
    }
    speak(cursor, spoken)
  }

  private fun speakCurrent(spoken: Spoken) {
    val cursor = currentCursor() ?: return
    speak(cursor, spoken)
  }

  private fun speak(cursor: ReviewCursor, spoken: Spoken) {
    val text =
      when (spoken) {
        Spoken.LINE -> cursor.currentLine?.text
        Spoken.WORD -> cursor.currentWord?.text
        Spoken.CHARACTER ->
          cursor.currentCharacter?.let { SpeechCleanupUtils.getCleanValueFor(service, it) }
      }
    speak(if (text.isNullOrBlank()) service.getString(R.string.review_blank) else text)
  }

  private fun speak(text: CharSequence) {
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, SPEAK_OPTIONS))
  }

  private fun currentCursor(): ReviewCursor? {
    val old = cursor

    if (
      old != null &&
        !startAtFocus &&
        (!stale || SystemClock.uptimeMillis() - readAt < REREAD_MS)
    ) {
      return old
    }
    val text = ReviewText(screenItems())
    if (text.isEmpty) {
      cursor = null
      speak(service.getString(R.string.review_no_text))
      return null
    }
    val fresh = ReviewCursor(text)
    val word = old?.currentWord
    if (startAtFocus || old == null || word == null) {
      placeAtFocus(fresh)
    } else {
      fresh.moveNear(word.centerX, word.centerY)

      fresh.currentWord?.let { landed ->
        if (landed.text == word.text) {
          repeat(old.offset - word.start) { fresh.moveCharacter(forward = true) }
        }
      }
    }
    cursor = fresh
    stale = false
    readAt = SystemClock.uptimeMillis()
    startAtFocus = false
    return fresh
  }

  private fun placeAtFocus(cursor: ReviewCursor) {
    val focus = currentObject() ?: focus() ?: return
    if (cursor.moveToSource { it is AccessibilityNodeInfoCompat && (it == focus || hasAncestor(it, focus)) }) {
      return
    }
    val bounds = Rect().also { focus.getBoundsInScreen(it) }
    cursor.moveNear(bounds.centerX(), bounds.centerY())
  }

  private fun hasAncestor(node: AccessibilityNodeInfoCompat, ancestor: AccessibilityNodeInfoCompat) =
    AccessibilityNodeInfoUtils.hasAncestor(node, ancestor)

  private fun focus(): AccessibilityNodeInfoCompat? =
    focusMonitor.getAccessibilityFocus(                            false) ?: focusMonitor.inputFocus

  private fun reviewRoot(): AccessibilityNodeInfoCompat? =
    AccessibilityNodeInfoUtils.getRoot(focus())
      ?: service.rootInActiveWindow?.let { AccessibilityNodeInfoCompat.wrap(it) }

  private fun screenItems(): List<ReviewItem> {
    val root = reviewRoot() ?: return emptyList()
    val windowBounds = Rect().also { root.getBoundsInScreen(it) }
    val items = mutableListOf<ReviewItem>()
    val stack = ArrayDeque<Pair<AccessibilityNodeInfoCompat, Int>>()
    stack.addLast(root to 0)
    var visited = 0
    while (stack.isNotEmpty() && visited < MAX_NODES) {
      val (node, depth) = stack.removeLast()
      visited++
      if (!node.isVisibleToUser) continue
      val bounds = Rect().also { node.getBoundsInScreen(it) }
      if (!bounds.intersect(windowBounds) || bounds.isEmpty) continue
      val text = node.text?.toString()?.takeIf { it.isNotBlank() }
      val label =
        text
          ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
          ?: node.hintText?.toString()?.takeIf { it.isNotBlank() && node.isEditable }
      if (label != null) {
        items.add(ReviewItem(label, bounds.left, bounds.top, bounds.right, bounds.bottom, node))

        if (text == null && !node.contentDescription.isNullOrBlank()) continue
      }
      if (depth >= MAX_DEPTH) continue

      for (index in node.childCount - 1 downTo 0) {
        node.getChild(index)?.let { stack.addLast(it to depth + 1) }
      }
    }
    return items
  }

  private fun focusReviewCursor() {
    val source = currentObject() ?: currentCursor()?.currentWord?.item?.source as? AccessibilityNodeInfoCompat
    val target =
      source?.let {
        AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(it, AccessibilityNodeInfoUtils.FILTER_SHOULD_FOCUS)
      } ?: source
    if (target == null) {
      speak(service.getString(R.string.review_no_item))
      return
    }
    pipeline.returnFeedback(
      EVENT_ID_UNTRACKED,
      Feedback.focus(
          target,
          FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),
        )
        .setForceRefocus(true),
    )
  }

  private fun click(long: Boolean) {
    val node = currentObject() ?: currentCursor()?.currentWord?.item?.source as? AccessibilityNodeInfoCompat
    val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
    val target = node?.let {
      AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(it, object : Filter<AccessibilityNodeInfoCompat>() {
        override fun accept(candidate: AccessibilityNodeInfoCompat?): Boolean =
          candidate != null && candidate.isEnabled && candidate.isVisibleToUser &&
            (if (long) candidate.isLongClickable else candidate.isClickable)
      })
    }
    stale = true
    pipeline.returnFeedback(EVENT_ID_UNTRACKED, Feedback.part().setInterruptGentle(true))
    if (target?.performAction(action) != true) speak(service.getString(R.string.review_cannot_click))
  }

  private fun setCursorOn(on: Boolean, announce: Boolean = true) {
    if (isCursorOn == on) {
      if (announce) {
        speak(service.getString(if (on) R.string.review_cursor_on else R.string.review_cursor_off))
      }
      return
    }
    isCursorOn = on
    if (on) {
      startAtFocus = true
    }
    if (announce) {
      speak(service.getString(if (on) R.string.review_cursor_on else R.string.review_cursor_off))
    }
  }

  private companion object {
    const val TAG = "ScreenReview"
    const val MAX_NODES = 4000
    const val MAX_DEPTH = 60
    const val PASS_THROUGH_WAIT_MS = 350L
    const val SCREENSHOT_RETRY_MS = 400L
    const val REREAD_MS = 500L

    val SPEAK_OPTIONS: SpeakOptions =
      SpeakOptions.create()
        .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
        .setFlags(0)
  }
}
