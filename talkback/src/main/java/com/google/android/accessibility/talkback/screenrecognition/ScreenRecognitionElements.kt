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

import com.google.android.accessibility.talkback.screenelements.ScreenElement

internal object ScreenRecognitionElements {
  @JvmStatic
  fun from(items: List<RecognizedItem>): List<ScreenElement> =
    items.map { ScreenElement(it.text, role(it.role), it.bounds, actionable = true) }

  private fun role(role: String?): ScreenElement.Role =
    when (role) {
      "button" -> ScreenElement.Role.BUTTON
      "heading" -> ScreenElement.Role.HEADING
      "image" -> ScreenElement.Role.IMAGE
      "text_field" -> ScreenElement.Role.TEXT_FIELD
      "check_box" -> ScreenElement.Role.CHECK_BOX
      "switch" -> ScreenElement.Role.SWITCH
      "slider" -> ScreenElement.Role.SLIDER
      "tab" -> ScreenElement.Role.TAB
      "link" -> ScreenElement.Role.LINK
      else -> ScreenElement.Role.TEXT
    }
}
