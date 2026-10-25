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

package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The background prefetch takes ownership of the root it is handed: when it declines to walk (here,
 * the window is the app's own), it must still recycle the root rather than leak it. The walk itself
 * reads nodes through the accessibility cache, which a unit test cannot stage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowNodePrefetcherTest {

  @Test
  fun prefetchInBackground_ownPackage_recyclesRootAndReturns() {
    val rootInfo = AccessibilityNodeInfo.obtain()
    rootInfo.packageName = "io.github.aaron_gh.sandfly"

    WindowNodePrefetcher.prefetchInBackground(
      AccessibilityNodeInfoCompat.wrap(rootInfo),
      ownPackage = "io.github.aaron_gh.sandfly",
    )

    assertTrue(shadowOf(rootInfo).isRecycled)
  }
}
