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
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.google.android.accessibility.talkback.clipboard

import android.content.ClipboardManager
import android.content.Context
import com.google.android.accessibility.talkback.utils.ClipboardUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks what clipboard copying and appending keeps in the history and system clipboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardUtilsTest {

  private val context: Context = RuntimeEnvironment.getApplication()
  private val clipboard: ClipboardManager
    get() = context.getSystemService(ClipboardManager::class.java)

  @Before
  fun clear() {
    ClipboardHistory.init(null)
    ClipboardHistory.clear()
    clipboard.clearPrimaryClip()
  }

  private fun clip(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

  @Test
  fun copiesAreKeptNewestFirstAndOnce() {
    assertTrue(ClipboardUtils.copyToClipboard(context, "one"))
    assertTrue(ClipboardUtils.copyToClipboard(context, "two"))
    assertTrue(ClipboardUtils.copyToClipboard(context, "one"))

    assertEquals(listOf("one", "two"), ClipboardHistory.getEntries())
    assertEquals("one", clip())
  }

  @Test
  fun addedTextGoesOnANewLineInTheExistingClipboard() {
    ClipboardUtils.copyToClipboard(context, "first")

    assertTrue(ClipboardUtils.appendToClipboard(context, "second"))
    assertTrue(ClipboardUtils.appendToClipboard(context, "third"))

    assertEquals("first\nsecond\nthird", clip())
    assertEquals("first\nsecond\nthird", ClipboardHistory.getEntries().get(0))
  }

  @Test
  fun addedTextIsUsedWhenTheClipboardIsEmpty() {
    assertTrue(ClipboardUtils.appendToClipboard(context, "only"))

    assertEquals("only", clip())
  }

  @Test
  fun removedAndClearedEntriesAreGone() {
    ClipboardHistory.record("a")
    ClipboardHistory.record("b")

    ClipboardHistory.remove("a")
    assertEquals(listOf("b"), ClipboardHistory.getEntries())

    ClipboardHistory.clear()
    assertEquals(emptyList<String>(), ClipboardHistory.getEntries())
  }
}
