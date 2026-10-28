/*
 * Copyright (C) 2026 The SandFly Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipboardManager;
import android.content.Context;
import com.google.android.accessibility.talkback.clipboard.ClipboardHistory;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Tests for the history recording in {@link ClipboardUtils}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ClipboardUtilsTest {

  private Context context;
  private ClipboardManager clipboard;

  @Before
  public void setUp() {
    ClipboardHistory.clear();
    ClipboardHistory.init(null);
    context = RuntimeEnvironment.getApplication();
    clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
  }

  @Test
  public void copyToClipboard_recordsInHistory() {
    assertTrue(ClipboardUtils.copyToClipboard(context, "hello"));

    assertEquals("hello", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    assertEquals(Arrays.asList("hello"), ClipboardHistory.getEntries());
  }

  @Test
  public void copyToClipboardWithoutRecording_leavesHistoryEmpty() {
    assertTrue(ClipboardUtils.copyToClipboardWithoutRecording(context, "hello"));

    assertEquals("hello", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
    assertTrue(ClipboardHistory.getEntries().isEmpty());
  }

  @Test
  public void copyToClipboard_emptyTextReturnsFalse() {
    assertFalse(ClipboardUtils.copyToClipboard(context, ""));
    assertFalse(ClipboardUtils.copyToClipboard(context, null));

    assertTrue(ClipboardHistory.getEntries().isEmpty());
  }
}
