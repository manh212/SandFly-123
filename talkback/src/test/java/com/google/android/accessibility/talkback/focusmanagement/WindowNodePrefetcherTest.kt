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
import org.robolectric.annotation.Config

/**
 * The background prefetch takes ownership of the root it is handed: when it declines to walk (here,
 * the window is the app's own), it must still recycle the root rather than leak it. Robolectric's
 * shadow exposes no way to ask whether a node was recycled, so the root records the call itself.
 * The walk itself reads nodes through the accessibility cache, which a unit test cannot stage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowNodePrefetcherTest {

  private class RecordingRoot(info: AccessibilityNodeInfo) : AccessibilityNodeInfoCompat(info) {
    var recycled = false

    override fun recycle() {
      recycled = true
      super.recycle()
    }
  }

  @Test
  fun prefetchInBackground_ownPackage_recyclesRootAndReturns() {
    val rootInfo = AccessibilityNodeInfo.obtain()
    rootInfo.packageName = "io.github.aaron_gh.sandfly"
    val root = RecordingRoot(rootInfo)

    WindowNodePrefetcher.prefetchInBackground(root, ownPackage = "io.github.aaron_gh.sandfly")

    assertTrue(root.recycled)
  }
}
