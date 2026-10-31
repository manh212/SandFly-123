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

package com.google.android.accessibility.talkback.screenrecognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.graphics.Rect;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScreenRecognitionAiTest {
  @Test
  public void parseKeepsElementRolesAndScreenBounds() {
    List<RecognizedItem> items =
        ScreenRecognitionAi.parse(
            "{\"elements\":[{\"label\":\"Search\",\"role\":\"text_field\","
                + "\"bounds\":[10,20,500,100]},{\"label\":\"Apply\","
                + "\"role\":\"button\",\"bounds\":[510,20,900,100]}]}",
            1000,
            2000);

    assertNotNull(items);
    assertEquals(2, items.size());
    assertEquals("text_field", items.get(0).role);
    assertEquals(new Rect(10, 40, 500, 200), items.get(0).bounds);
    assertEquals("button", items.get(1).role);
  }

  @Test
  public void unknownElementRoleBecomesText() {
    List<RecognizedItem> items =
        ScreenRecognitionAi.parse(
            "{\"elements\":[{\"label\":\"Status\",\"role\":\"unknown\","
                + "\"bounds\":[0,0,100,100]}]}",
            1000,
            1000);

    assertNotNull(items);
    assertEquals("text", items.get(0).role);
  }
}
