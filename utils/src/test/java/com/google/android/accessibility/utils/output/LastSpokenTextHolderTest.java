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

package com.google.android.accessibility.utils.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LastSpokenTextHolderTest {

  @Before
  public void setUp() {
    LastSpokenTextHolder.clearSpokenHistoryForTest();
  }

  @Test
  public void history_keepsUtterancesNewestFirst() {
    LastSpokenTextHolder.setLastSpokenText("first");
    LastSpokenTextHolder.setLastSpokenText("second");
    LastSpokenTextHolder.setLastSpokenText("third");

    assertEquals(List.of("third", "second", "first"), LastSpokenTextHolder.getSpokenHistory());
    assertEquals("third", LastSpokenTextHolder.getLastSpokenText());
  }

  @Test
  public void history_isCappedAtFifty_droppingTheOldest() {
    for (int i = 1; i <= 55; i++) {
      LastSpokenTextHolder.setLastSpokenText("utterance " + i);
    }

    List<String> history = LastSpokenTextHolder.getSpokenHistory();
    assertEquals(50, history.size());
    assertEquals("utterance 55", history.get(0));
    assertEquals("utterance 6", history.get(49));
  }

  @Test
  public void history_ignoresEmptyText_likeTheLastSpokenCell() {
    LastSpokenTextHolder.setLastSpokenText("");
    LastSpokenTextHolder.setLastSpokenText("   ");
    LastSpokenTextHolder.setLastSpokenText(null);

    assertTrue(LastSpokenTextHolder.getSpokenHistory().isEmpty());
  }
}
