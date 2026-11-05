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

package com.google.android.accessibility.utils.widget

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import android.view.inputmethod.EditorInfo
import androidx.appcompat.R
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeyboardSafeEditTextTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun useKeyboard(packageName: String) {
    Settings.Secure.putString(
      context.contentResolver,
      Settings.Secure.DEFAULT_INPUT_METHOD,
      "$packageName/.Keyboard",
    )
  }

  private fun connectionIsWrapped(): Boolean {
    context.setTheme(R.style.Theme_AppCompat)
    val field = KeyboardSafeEditText(context)
    val connection = field.onCreateInputConnection(EditorInfo())!!
    return Proxy.isProxyClass(connection.javaClass)
  }

  @Test
  fun anotherAppsKeyboardGoesThroughKeyboardCalls() {
    useKeyboard("com.example.keyboard")
    assertFalse(KeyboardSafeEditText.isOwnKeyboard(context))
    assertTrue(connectionIsWrapped())
  }

  @Test
  fun theAppsOwnKeyboardGetsTheFieldsConnection() {
    useKeyboard(context.packageName)
    assertTrue(KeyboardSafeEditText.isOwnKeyboard(context))
    assertFalse(connectionIsWrapped())
  }
  @Test fun selectsWordsInsideMeridiansOwnField() {
    context.setTheme(R.style.Theme_AppCompat)
    val field = KeyboardSafeEditText(context)
    field.setText("Một hai ba")
    field.setSelection(0)
    val key = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0,
      KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
    assertTrue(field.onKeyDown(key.keyCode, key))
    assertEquals("Một", field.text!!.substring(field.selectionStart, field.selectionEnd))
  }

  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Test fun usesOwnDisplayedLinesWithoutDependingOnSourceApplication() {
    context.setTheme(R.style.Theme_AppCompat)
    val field = KeyboardSafeEditText(context).apply {
      layoutParams = ViewGroup.LayoutParams(180, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    field.setSingleLine(false)
    field.setHorizontallyScrolling(false)
    field.textSize = 18f
    field.setText("Một đoạn văn bản dài được tự xuống hàng trong hộp sửa riêng của Meridian.")
    field.measure(View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
    field.layout(0, 0, 180, field.measuredHeight)
    assertTrue(field.layout.lineCount > 1)
    val secondLine = field.layout.getLineStart(1)
    field.setSelection(secondLine)
    val key = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0,
      KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
    assertTrue(field.onKeyDown(key.keyCode, key))
    assertEquals(secondLine, field.selectionStart)
    assertEquals(0, field.selectionEnd)
  }
}
