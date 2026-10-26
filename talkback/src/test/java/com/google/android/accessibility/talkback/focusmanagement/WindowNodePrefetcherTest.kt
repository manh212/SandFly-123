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

package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The background prefetch takes ownership of the root its supplier returns: when it declines to
 * walk (here, the window is the app's own), it must still recycle the root rather than leak it.
 * Robolectric's shadow exposes no way to ask whether a node was recycled, so the root records
 * the call itself. The walk itself reads nodes through the accessibility cache, which a unit
 * test cannot stage.
 *
 * The root is also resolved off the calling thread, so that the event path that asks for a
 * prefetch pays nothing for it, and a call declined by the single-flight gate never resolves
 * a root at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowNodePrefetcherTest {

  private class RecordingRoot(info: AccessibilityNodeInfo) : AccessibilityNodeInfoCompat(info) {
    @Volatile var recycled = false

    override fun recycle() {
      recycled = true
      super.recycle()
    }
  }

  private fun root(packageName: String): RecordingRoot {
    val info = AccessibilityNodeInfo.obtain()
    info.packageName = packageName
    return RecordingRoot(info)
  }

  private fun waitUntil(description: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (condition()) {
        return
      }
      Thread.sleep(10)
    }
    fail("Timed out waiting for: $description")
  }

  /**
   * The prefetcher is a singleton with a single flight: a call made while an earlier test's
   * flight is still draining is declined, and a declined call never invokes its supplier.
   * Retrying until the supplier runs is therefore the only reliable way to place a call, and
   * it cannot double-resolve: only the accepted call ever invokes the supplier.
   */
  private fun callUntilInvoked(
    windowId: Int = WINDOW_ID,
    supplier: () -> AccessibilityNodeInfoCompat?,
  ) {
    val invoked = AtomicBoolean(false)
    waitUntil("the call to be accepted") {
      WindowNodePrefetcher.prefetchInBackground(
        windowId,
        {
          invoked.set(true)
          supplier()
        },
        ownPackage = OWN_PACKAGE,
      )
      invoked.get()
    }
  }

  @Test
  fun prefetchInBackground_ownPackage_recyclesRootAndReturns() {
    val root = root(OWN_PACKAGE)

    callUntilInvoked { root }

    waitUntil("the root to be recycled") { root.recycled }
    assertTrue(root.recycled)
  }

  @Test
  fun prefetchInBackground_resolvesTheRootOnAPrefetcherThread() {
    val resolvingThread = AtomicReference<String>()
    val root = root("com.example.app")

    callUntilInvoked {
      resolvingThread.set(Thread.currentThread().name)
      root
    }

    waitUntil("the root to be resolved and recycled") { root.recycled }
    assertTrue(resolvingThread.get().startsWith("BacktalkPrefetch"))
  }

  @Test
  fun prefetchInBackground_whileRunning_neverInvokesTheNextSupplier() {
    val releaseFirst = CountDownLatch(1)
    val firstResolving = CountDownLatch(1)
    val firstRoot = root("com.example.first")
    callUntilInvoked {
      firstResolving.countDown()
      releaseFirst.await(WAIT_MS, TimeUnit.MILLISECONDS)
      firstRoot
    }
    assertTrue(firstResolving.await(WAIT_MS, TimeUnit.MILLISECONDS))

    val secondInvoked = AtomicBoolean(false)
    WindowNodePrefetcher.prefetchInBackground(
      WINDOW_ID,
      {
        secondInvoked.set(true)
        root("com.example.second")
      },
      ownPackage = OWN_PACKAGE,
    )

    // The second call is declined by the single-flight gate on this thread, so its supplier
    // can never have run.
    assertFalse(secondInvoked.get())

    releaseFirst.countDown()
    waitUntil("the first root to be recycled") { firstRoot.recycled }
  }

  /**
   * Places a background read whose supplier stays blocked until [releaseSupplier] is counted
   * down, so the read is in flight — recorded, but with its walk not yet started — for as long
   * as the test needs. Returns the root the supplier will hand over.
   */
  private fun blockedBackgroundRead(
    windowId: Int,
    releaseSupplier: CountDownLatch,
  ): RecordingRoot {
    val supplierEntered = CountDownLatch(1)
    val backgroundRoot = root("com.example.app")
    callUntilInvoked(windowId) {
      supplierEntered.countDown()
      releaseSupplier.await(WAIT_MS, TimeUnit.MILLISECONDS)
      backgroundRoot
    }
    assertTrue(supplierEntered.await(WAIT_MS, TimeUnit.MILLISECONDS))
    return backgroundRoot
  }

  @Test
  fun prefetch_backgroundReadOfSameWindowInFlight_waitsForItInsteadOfWalking() {
    val releaseSupplier = CountDownLatch(1)
    val backgroundRoot = blockedBackgroundRead(backgroundWindowId(), releaseSupplier)

    // The background read is still resolving its root, so nothing has walked yet. A walk of
    // this childless root would return at once; the prefetch must instead wait for the
    // background read, up to the usual wait budget, rather than walk the same window again.
    val start = System.currentTimeMillis()
    WindowNodePrefetcher.prefetch(root("com.example.app"), OWN_PACKAGE)
    val elapsed = System.currentTimeMillis() - start

    assertTrue("prefetch waited only ${elapsed}ms", elapsed >= 200)
    releaseSupplier.countDown()
    waitUntil("the background root to be recycled") { backgroundRoot.recycled }
  }

  @Test
  fun prefetch_backgroundReadOfAnotherWindowInFlight_doesNotWait() {
    val releaseSupplier = CountDownLatch(1)
    // The read in flight is recorded under a different window than the prefetch below asks
    // about, so the prefetch walks its own window as usual and returns at once.
    val backgroundRoot = blockedBackgroundRead(backgroundWindowId() + 1, releaseSupplier)

    val start = System.currentTimeMillis()
    WindowNodePrefetcher.prefetch(root("com.example.app"), OWN_PACKAGE)
    val elapsed = System.currentTimeMillis() - start

    assertTrue("prefetch waited ${elapsed}ms", elapsed < 200)
    releaseSupplier.countDown()
    waitUntil("the background root to be recycled") { backgroundRoot.recycled }
  }

  /** The window the roots staged here report, read back so no default has to be assumed. */
  private fun backgroundWindowId(): Int = root("com.example.app").windowId

  private companion object {
    const val OWN_PACKAGE = "io.github.aaron_gh.sandfly"
    const val WINDOW_ID = 1
    const val WAIT_MS = 5_000L
  }
}
