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

package com.google.android.accessibility.talkback.clipboard;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for the search filtering in {@link ClipboardHistoryDialog}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ClipboardHistoryDialogTest {

  @Test
  public void filter_emptyQueryReturnsAllInOrder() {
    List<String> entries = Arrays.asList("two", "one");

    assertEquals(entries, ClipboardHistoryDialog.filterEntries(entries, ""));
    assertEquals(entries, ClipboardHistoryDialog.filterEntries(entries, null));
  }

  @Test
  public void filter_ignoresDiacriticsAndCase() {
    List<String> entries = Arrays.asList("mật khẩu", "hello");

    assertEquals(
        Arrays.asList("mật khẩu"),
        ClipboardHistoryDialog.filterEntries(entries, "mat khau"));
    assertEquals(
        Arrays.asList("mật khẩu"),
        ClipboardHistoryDialog.filterEntries(entries, "MẬT"));
  }

  @Test
  public void filter_noMatchReturnsEmpty() {
    List<String> entries = Arrays.asList("mật khẩu", "hello");

    assertEquals(0, ClipboardHistoryDialog.filterEntries(entries, "xyz").size());
  }

  @Test
  public void normalize_stripsDiacriticsAndLowercases() {
    assertEquals("mat khau", ClipboardHistoryDialog.normalizeForSearch("Mật Khẩu"));
    assertEquals("", ClipboardHistoryDialog.normalizeForSearch(null));
  }
}
