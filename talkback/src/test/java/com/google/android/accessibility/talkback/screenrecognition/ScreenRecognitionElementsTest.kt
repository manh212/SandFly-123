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

package com.google.android.accessibility.talkback.screenrecognition

import android.graphics.Rect
import com.google.android.accessibility.talkback.screenelements.ScreenElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenRecognitionElementsTest {
  @Test
  fun recognizedRolesMapToScreenElementsAndKeepTapActions() {
    val items =
      listOf(
        RecognizedItem("Apply", Rect(10, 10, 100, 50), false, "button"),
        RecognizedItem("Name", Rect(10, 60, 200, 100), false, "text_field"),
        RecognizedItem("Caption", Rect(10, 110, 300, 150), false, "text"),
      )

    val elements = ScreenRecognitionElements.from(items)

    assertEquals(
      listOf(ScreenElement.Role.BUTTON, ScreenElement.Role.TEXT_FIELD, ScreenElement.Role.TEXT),
      elements.map { it.role },
    )
    assertTrue(elements.all { it.isActionable })
  }
}
