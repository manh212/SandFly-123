/*
 * Copyright (C) 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for {@link ClipboardHistory}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ClipboardHistoryTest {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Before
  public void setUp() {
    ClipboardHistory.clear();
    ClipboardHistory.init(null);
  }

  @Test
  public void record_newestFirst() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");

    assertEquals(Arrays.asList("two", "one"), ClipboardHistory.getEntries());
  }

  @Test
  public void record_duplicateMovesToFrontWithoutDuplicating() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.record("one");

    assertEquals(Arrays.asList("one", "two"), ClipboardHistory.getEntries());
  }

  @Test
  public void record_ignoresEmptyText() {
    ClipboardHistory.record("");
    ClipboardHistory.record(null);

    assertTrue(ClipboardHistory.getEntries().isEmpty());
  }

  @Test
  public void record_hasNoCap() {
    for (int i = 0; i < 60; i++) {
      ClipboardHistory.record("text " + i);
    }

    assertEquals(60, ClipboardHistory.getEntries().size());
    assertEquals("text 59", ClipboardHistory.getEntries().get(0));
    assertEquals("text 0", ClipboardHistory.getEntries().get(59));
  }

  @Test
  public void remove_deletesOnlyThatEntry() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.record("three");

    ClipboardHistory.remove("two");

    assertEquals(Arrays.asList("three", "one"), ClipboardHistory.getEntries());
  }

  @Test
  public void init_reloadsStoredEntriesAndRemovals() throws Exception {
    File dir = folder.newFolder();
    ClipboardHistory.init(dir);
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.remove("one");

    ClipboardHistory.init(dir);

    assertEquals(Arrays.asList("two"), ClipboardHistory.getEntries());
  }
}
