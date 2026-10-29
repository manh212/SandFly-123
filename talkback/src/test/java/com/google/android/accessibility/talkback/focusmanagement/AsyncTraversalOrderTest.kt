package com.google.android.accessibility.talkback.focusmanagement

import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.LatencyExperiments
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AsyncTraversalOrderTest {
  @Before
  fun setUp() {
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, true)
    TraversalTreeCache.enabled = true
    TraversalTreeCache.clear("test")
  }

  @After
  fun reset() {
    AsyncTraversalOrder.cancel()
    LatencyExperiments.setEnabled(LatencyExperiments.Feature.ASYNC_ORDER, false)
    TraversalTreeCache.clear("test")
  }

  private fun root(): AccessibilityNodeInfoCompat =
    AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo.obtain().apply { packageName = "example" })

  private fun await(result: AtomicReference<AsyncTraversalOrder.Result?>): AsyncTraversalOrder.Result {
    val end = System.nanoTime() + 3_000_000_000L
    while (System.nanoTime() < end) {
      shadowOf(Looper.getMainLooper()).idle()
      result.get()?.let { return it }
      Thread.sleep(5)
    }
    fail("Background order did not publish a result at uptime ${SystemClock.uptimeMillis()}")
    throw AssertionError()
  }

  @Test
  fun completeOrderPublishesBeforeTheNavigationCallback() {
    val root = root()
    val result = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root, null) {
      if (it == AsyncTraversalOrder.Result.READY) assertNotNull(TraversalTreeCache.get(root, root))
      result.set(it)
    }
    assertEquals(AsyncTraversalOrder.Result.READY, await(result))
  }

  @Test
  fun changedContentRejectsTheOrderBeforePublishingIt() {
    val result = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root(), null) { result.set(it) }
    val changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    TraversalTreeCache.onAccessibilityEvent(changed)
    changed.recycle()
    assertEquals(AsyncTraversalOrder.Result.STALE, await(result))
  }

  @Test
  fun cancellationImmediatelyReleasesTheWaitingNavigation() {
    var callbacks = 0
    val result = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root(), null) { callbacks++; result.set(it) }
    AsyncTraversalOrder.cancel()
    assertEquals(AsyncTraversalOrder.Result.CANCELLED, await(result))
    assertEquals(1, callbacks)
  }
  @Test
  fun newRequestCanCompleteImmediatelyAfterCancellation() {
    val first = AtomicReference<AsyncTraversalOrder.Result?>()
    val next = AtomicReference<AsyncTraversalOrder.Result?>()
    AsyncTraversalOrder.request(root(), null) { first.set(it) }
    AsyncTraversalOrder.cancel()
    AsyncTraversalOrder.request(root(), null) { next.set(it) }
    assertEquals(AsyncTraversalOrder.Result.CANCELLED, first.get())
    assertEquals(AsyncTraversalOrder.Result.READY, await(next))
  }
}
