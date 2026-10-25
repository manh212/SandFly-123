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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link ClipboardHistory}. */
@RunWith(RobolectricTestRunner.class)
public class ClipboardHistoryTest {

  @Before
  public void setUp() {
    ClipboardHistory.clear();
  }

  @Test
  public void record_newestFirst() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");

    assertThat(ClipboardHistory.getEntries()).containsExactly("two", "one").inOrder();
  }

  @Test
  public void record_duplicateMovesToFrontWithoutDuplicating() {
    ClipboardHistory.record("one");
    ClipboardHistory.record("two");
    ClipboardHistory.record("one");

    assertThat(ClipboardHistory.getEntries()).containsExactly("one", "two").inOrder();
  }

  @Test
  public void record_ignoresEmptyText() {
    ClipboardHistory.record("");
    ClipboardHistory.record(null);

    assertThat(ClipboardHistory.getEntries()).isEmpty();
  }

  @Test
  public void record_capsAtFiftyEntries() {
    for (int i = 0; i < 60; i++) {
      ClipboardHistory.record("text " + i);
    }

    assertThat(ClipboardHistory.getEntries()).hasSize(50);
    assertThat(ClipboardHistory.getEntries().get(0)).isEqualTo("text 59");
    assertThat(ClipboardHistory.getEntries().get(49)).isEqualTo("text 10");
  }
}
