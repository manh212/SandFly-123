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

package com.google.android.accessibility.utils.widget

import android.graphics.Rect
import android.icu.text.BreakIterator
import android.text.Selection
import android.view.KeyEvent
import java.lang.ref.WeakReference
import java.util.Locale
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatEditText
import com.google.android.accessibility.utils.input.KeyboardCalls

/**
 * A text field in one of the screen reader's own windows. A keyboard in another app has its calls
 * answered as [KeyboardCalls] describes, so typing doesn't stall the screen reader or the keyboard.
 *
 * The screen reader's own braille keyboard runs on this app's main thread, where Android answers
 * its calls at once, so it gets the field's own connection. Through [KeyboardCalls], each of its
 * questions would wait for the main thread it runs on, and get no answer: typing was slow, and
 * deleting a word did nothing.
 */
open class KeyboardSafeEditText
@JvmOverloads
constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle,
) : AppCompatEditText(context, attrs, defStyleAttr) {

  private val selectionKeys = HashSet<Int>()

  override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    super.onFocusChanged(focused, direction, previouslyFocusedRect)
    if (focused) currentEditor = WeakReference(this)
    else if (currentEditor?.get() === this) currentEditor = null
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
    selectWithKey(event) || super.onKeyDown(keyCode, event)

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
    selectWithKey(event) || super.onKeyUp(keyCode, event)

  private fun selectWithKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP) return selectionKeys.remove(event.keyCode)
    if (event.action != KeyEvent.ACTION_DOWN || !event.isCtrlPressed || !event.isShiftPressed ||
        event.isAltPressed || event.isMetaPressed || !isEnabled) return false
    if (event.keyCode !in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return false
    val editable = text ?: return false
    if (selectionStart < 0 || selectionEnd < 0) Selection.setSelection(editable, editable.length)
    when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> layout?.let { Selection.extendUp(editable, it) }
      KeyEvent.KEYCODE_DPAD_DOWN -> layout?.let { Selection.extendDown(editable, it) }
      else -> {
        val value = editable.toString()
        val iterator = BreakIterator.getWordInstance(Locale.getDefault()).apply { setText(value) }
        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        var position = selectionEnd
        var boundary = if (forward) iterator.following(position) else iterator.preceding(position)
        while (boundary != BreakIterator.DONE) {
          val part = value.substring(minOf(position, boundary), maxOf(position, boundary))
          if (part.any { it.isLetterOrDigit() }) break
          position = boundary
          boundary = if (forward) iterator.next() else iterator.previous()
        }
        Selection.extendSelection(editable, if (boundary == BreakIterator.DONE) {
          if (forward) value.length else 0
        } else boundary)
      }
    }
    selectionKeys += event.keyCode
    return true
  }

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
    val connection = super.onCreateInputConnection(outAttrs)
    return if (isOwnKeyboard(context)) connection else KeyboardCalls.wrap(connection)
  }

  companion object {
    private var currentEditor: WeakReference<KeyboardSafeEditText>? = null

    @JvmStatic
    fun handleSelectionKey(event: KeyEvent): Boolean {
      val field = currentEditor?.get() ?: return false
      return field.isFocused && field.hasWindowFocus() && field.selectWithKey(event)
    }

    /** Whether the keyboard in use belongs to this app, and so runs in its process. */
    @VisibleForTesting
    fun isOwnKeyboard(context: Context): Boolean = keyboardPackage(context) == context.packageName

    private fun keyboardPackage(context: Context): String? {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        context.getSystemService(InputMethodManager::class.java)?.currentInputMethodInfo?.let {
          return it.packageName
        }
      }
      return try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
          ?.let { ComponentName.unflattenFromString(it)?.packageName }
      } catch (e: SecurityException) {
        null
      }
    }
  }
}
