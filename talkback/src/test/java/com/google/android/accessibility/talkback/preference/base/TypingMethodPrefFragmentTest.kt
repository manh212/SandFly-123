/*
 * Copyright 2026 The SandFly Authors
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

package com.google.android.accessibility.talkback.preference.base

import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.DOUBLE_TAP
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.FORCE_LIFT_TO_TYPE_ON_IME
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.LIFT_TO_TYPE_EXCEPT_ACTION_KEY
import com.google.android.accessibility.talkback.focusmanagement.FocusProcessorForTapAndTouchExploration.SINGLE_TAP_TO_TYPE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tap-delay row is on the typing-method screen for Direct key input alone. */
class TypingMethodPrefFragmentTest {

  @Test
  fun directKeyInput_showsTheTapDelayRow() {
    assertTrue(TypingMethodPrefFragment.isTapDelayRowVisible(SINGLE_TAP_TO_TYPE))
  }

  @Test
  fun everyOtherMethod_hidesTheTapDelayRow() {
    assertFalse(TypingMethodPrefFragment.isTapDelayRowVisible(DOUBLE_TAP))
    assertFalse(TypingMethodPrefFragment.isTapDelayRowVisible(LIFT_TO_TYPE))
    assertFalse(TypingMethodPrefFragment.isTapDelayRowVisible(FORCE_LIFT_TO_TYPE_ON_IME))
    assertFalse(TypingMethodPrefFragment.isTapDelayRowVisible(LIFT_TO_TYPE_EXCEPT_ACTION_KEY))
  }
}
