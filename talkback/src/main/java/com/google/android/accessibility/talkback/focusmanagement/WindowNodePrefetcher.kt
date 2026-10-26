/*
 * Copyright 2026 Backtalk contributors
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

import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.WebInterfaceUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Supplier
import kotlin.concurrent.withLock

/**
 * Reads the nodes of a window from the app on several threads at once, before the reading order is
 * built from them on the main thread.
 *
 * Building the order asks the app for one node at a time. Each request waits until the app's main
 * thread has finished what it is doing, and an app that redraws on every frame, such as One UI's
 * notification shade with five or more silent notifications, answers each one only after its next
 * frame: about 8 ms at 120 Hz, or over half a second for the shade. Requests from several threads
 * arrive together and are answered after the same frame, and each answer carries up to 50 nodes.
 * Android drops the extra nodes of an ordinary request when the next request has already been sent,
 * which during a build it always has, so these requests ask for them to be sent with the answer.
 *
 * The nodes land in Android's node cache. Events clear that cache on the main thread, so they stay
 * there until the build on the main thread is done.
 */
object WindowNodePrefetcher {
  private const val TAG = "BacktalkPrefetch"

  private const val THREADS = 8

  /** How long the main thread waits for the nodes before it reads the rest itself. */
  private const val MAX_WAIT_MS = 300L

  /** Stops a window with a very large or looped tree. */
  private const val MAX_NODES = 5000

  /** How often a read with an abort check asks whether it should give up. */
  private const val ABORT_POLL_MS = 10L

  /**
   * How long after a walk of a window finished another walk of the same window counts as a
   * duplicate: the nodes it would read are the ones the finished walk just put in the cache.
   */
  private const val DUPLICATE_WALK_WINDOW_MS = 150L

  private const val FLAGS =
    AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS or
      AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
      AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

  /** Android keeps a request client for every thread that asks for nodes, so the threads stay. */
  private val executor by lazy {
    val number = AtomicInteger()
    Executors.newFixedThreadPool(THREADS) { runnable ->
      Thread(runnable, "$TAG-${number.incrementAndGet()}").apply { isDaemon = true }
    }
  }

  /** Threads reading nodes now. All of them can still be waiting for an app that stopped answering. */
  private val running = AtomicInteger()

  /** Whether a background read is still warming the cache. */
  private val backgroundRunning = AtomicBoolean()

  /** Windows a walk is reading right now, so a second walk of the same window is a duplicate. */
  private val walksInFlight = ConcurrentHashMap.newKeySet<Int>()

  /** When the newest walk of each window finished, by the uptime clock. */
  private val lastWalkFinishedMs = ConcurrentHashMap<Int, Long>()

  /**
   * Whether a walk of a window starting at [nowMs] would only re-read what a walk of the same
   * window is reading now, or read a moment ago: the nodes land in the same cache either way,
   * so the second walk's binder calls buy nothing. Right after a window's order is thrown
   * away, a background build and the swipe it was too slow for used to walk the same tree side
   * by side.
   */
  internal fun duplicateWalk(inFlight: Boolean, lastFinishedMs: Long, nowMs: Long): Boolean =
    inFlight || (lastFinishedMs > 0L && nowMs - lastFinishedMs < DUPLICATE_WALK_WINDOW_MS)

  /**
   * Claims the walk of [windowId] for the caller, or returns false when the walk would be a
   * duplicate of one on its way or just finished. A window id of -1 names no window, so its
   * walks are never claimed or deduplicated.
   */
  private fun claimWalk(windowId: Int, nowMs: Long): Boolean {
    if (windowId < 0) {
      return true
    }
    if (
      duplicateWalk(
        walksInFlight.contains(windowId),
        lastWalkFinishedMs[windowId] ?: 0L,
        nowMs,
      )
    ) {
      return false
    }
    return walksInFlight.add(windowId)
  }

  /** Releases the claim [claimWalk] took and remembers when the window's walk finished. */
  private fun finishWalk(windowId: Int) {
    if (windowId < 0) {
      return
    }
    walksInFlight.remove(windowId)
    lastWalkFinishedMs[windowId] = SystemClock.uptimeMillis()
    if (lastWalkFinishedMs.size > 64) {
      val cutoff = SystemClock.uptimeMillis() - 5_000L
      lastWalkFinishedMs.entries.removeIf { it.value < cutoff }
    }
  }

  /**
   * Reads the nodes below [root] into Android's node cache, waiting at most [MAX_WAIT_MS]. Does
   * nothing for a window of [ownPackage]: its requests are answered on the main thread, which is
   * waiting here. Does nothing either while every thread is still waiting for an earlier window,
   * since none of them could start on this one.
   */
  @JvmStatic
  fun prefetch(root: AccessibilityNodeInfoCompat, ownPackage: String) {
    prefetch(root, ownPackage, null)
  }

  /**
   * As [prefetch], except that the read stops early once [abortCheck] reports true — a
   * background build whose window has already moved on should hand its threads back instead of
   * walking a tree whose order will be refused.
   */
  @JvmStatic
  fun prefetch(
    root: AccessibilityNodeInfoCompat,
    ownPackage: String,
    abortCheck: (() -> Boolean)?,
  ) {
    if (
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        TextUtils.equals(root.packageName, ownPackage) ||
        running.get() >= THREADS
    ) {
      return
    }
    val start = SystemClock.uptimeMillis()
    val windowId = root.windowId
    if (!claimWalk(windowId, start)) {
      // A walk of this window is reading, or just read, the same nodes into the same cache;
      // the build that called for this one reads what it still needs itself, exactly as it
      // does when a walk's wait runs out.
      return
    }
    try {
      val walk = Walk(start + MAX_WAIT_MS, abortCheck)
      walk.submit(root.unwrap())
      walk.await()
      if (LogUtils.shouldLog(Log.DEBUG)) {
        Log.d(TAG, "Read ${walk.nodes.get()} nodes in ${SystemClock.uptimeMillis() - start} ms")
      }
    } finally {
      finishWalk(windowId)
    }
  }

  /**
   * Reads the nodes below the root that [rootSupplier] returns into Android's node cache without
   * waiting for them, so that a reading order built soon after finds them already read. The
   * supplier runs on this executor, never on the calling thread: finding the root costs binder
   * calls the caller should not pay for. Takes ownership of the supplied root, which is recycled
   * once the read ends. Does nothing while an earlier background read is still running, and under
   * the same conditions as [prefetch]; a call that does nothing never invokes the supplier.
   */
  @JvmStatic
  fun prefetchInBackground(
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
  ) {
    if (
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        running.get() >= THREADS ||
        !backgroundRunning.compareAndSet(false, true)
    ) {
      return
    }
    try {
      executor.execute {
        try {
          val root = rootSupplier.get()
          if (root != null) {
            try {
              if (!TextUtils.equals(root.packageName, ownPackage)) {
                val windowId = root.windowId
                if (claimWalk(windowId, SystemClock.uptimeMillis())) {
                  try {
                    val walk = Walk(SystemClock.uptimeMillis() + MAX_WAIT_MS)
                    walk.submit(root.unwrap())
                    walk.await()
                  } finally {
                    finishWalk(windowId)
                  }
                }
              }
            } finally {
              root.recycle()
            }
          }
        } finally {
          backgroundRunning.set(false)
        }
      }
    } catch (e: RejectedExecutionException) {
      backgroundRunning.set(false)
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private class Walk(private val deadline: Long, private val abortCheck: (() -> Boolean)? = null) {
    private val lock = ReentrantLock()
    private val finished = lock.newCondition()
    private var pending = 0
    @Volatile private var stopped = false
    /** Nodes compare by window and node ID. */
    private val seen = ConcurrentHashMap.newKeySet<AccessibilityNodeInfo>()
    val nodes = AtomicInteger()

    fun submit(node: AccessibilityNodeInfo) {
      lock.withLock { pending++ }
      try {
        executor.execute {
          running.incrementAndGet()
          try {
            readChildren(node)
          } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read nodes", e)
          } finally {
            running.decrementAndGet()
            done()
          }
        }
      } catch (e: RejectedExecutionException) {
        done()
      }
    }

    private fun done() {
      lock.withLock {
        pending--
        if (pending == 0) {
          finished.signalAll()
        }
      }
    }

    private fun readChildren(node: AccessibilityNodeInfo) {
      for (i in 0 until node.childCount) {
        if (stopped) {
          return
        }
        val child = node.getChild(i, FLAGS) ?: continue
        if (nodes.incrementAndGet() >= MAX_NODES) {
          stopped = true
          return
        }
        // The order is not built inside web content, which is read through its own actions.
        if (
          child.childCount > 0 &&
            seen.add(child) &&
            !WebInterfaceUtils.supportsWebActions(AccessibilityNodeInfoCompat.wrap(child))
        ) {
          submit(child)
        }
      }
    }

    fun await() {
      lock.withLock {
        while (pending > 0) {
          val left = deadline - SystemClock.uptimeMillis()
          if (left <= 0) {
            break
          }
          // With an abort check, wait in short slices so a dead read is given up at once
          // rather than at its deadline.
          val slice = if (abortCheck != null) minOf(left, ABORT_POLL_MS) else left
          finished.await(slice, TimeUnit.MILLISECONDS)
          if (abortCheck?.invoke() == true) {
            break
          }
        }
      }
      stopped = true
    }
  }
}
