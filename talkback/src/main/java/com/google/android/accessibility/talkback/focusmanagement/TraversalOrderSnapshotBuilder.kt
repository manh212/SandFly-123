/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 (the "License"); you may not
 * use this file except in compliance with the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.android.accessibility.talkback.focusmanagement

import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.OrderedTraversalStrategy
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.function.Supplier

/**
 * Builds reading orders off the main thread after [TraversalTreeCache] throws one away, and
 * publishes each finished order back to the cache as a snapshot, so that the next swipe finds
 * the order already built instead of paying for the build itself.
 *
 * A request carries the cache generation of the throw-away that asked for it. Anything that
 * changes the window afterwards moves the generation on, and the cache then refuses the
 * snapshot (see [TraversalTreeCache.publishSnapshot]); a swipe that arrives before a snapshot
 * lands, or after one was refused, builds its order the way it always did. Only the newest
 * request is kept: builds run one at a time on a single thread, and a request that arrives
 * while one is running replaces any older request still waiting.
 */
internal object TraversalOrderSnapshotBuilder {
  private const val TAG = "BacktalkOrderBuild"

  private val executor by lazy {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, TAG).apply { isDaemon = true }
    }
  }

  private class Request(
    val windowId: Int,
    val generation: Long,
    val rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    val ownPackage: String,
  )

  private val lock = Any()
  private var pending: Request? = null
  private var running = false

  /** Queues a build of the order of [windowId], replacing any older build still waiting. */
  fun request(
    windowId: Int,
    generation: Long,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
  ) {
    val start =
      synchronized(lock) {
        pending = Request(windowId, generation, rootSupplier, ownPackage)
        if (running) {
          false
        } else {
          running = true
          true
        }
      }
    if (start) {
      try {
        executor.execute { drain() }
      } catch (e: RejectedExecutionException) {
        synchronized(lock) { running = false }
      }
    }
  }

  private fun drain() {
    while (true) {
      val request =
        synchronized(lock) {
          val next = pending
          pending = null
          if (next == null) {
            running = false
          }
          next
        } ?: return
      // A request the window has already moved past is not worth even resolving its root for:
      // in a churning window, builds used to queue up behind one another and each walked the
      // whole tree before its snapshot was refused, keeping the single builder busy with dead
      // work while the live request waited.
      if (request.generation != TraversalTreeCache.currentGeneration()) {
        Performance.getInstance().onOrderBuildDied()
        continue
      }
      build(request)
    }
  }

  /** Builds one requested order and publishes it. Runs the whole pipeline on the calling thread. */
  fun buildNow(
    windowId: Int,
    generation: Long,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
  ) {
    build(Request(windowId, generation, rootSupplier, ownPackage))
  }

  private fun build(request: Request) {
    val root =
      try {
        request.rootSupplier.get()
      } catch (e: RuntimeException) {
        Log.w(TAG, "Could not resolve the root of the window to build", e)
        null
      } ?: return
    var published = false
    try {
      if (root.windowId != request.windowId) {
        return
      }
      if (TraversalTreeCache.currentGeneration() != request.generation) {
        Performance.getInstance().onOrderBuildDied()
        return
      }
      // The read stops early if the window moves on while it runs, so a dead build hands the
      // builder back at once instead of walking the whole tree for a refused snapshot.
      WindowNodePrefetcher.prefetch(root, request.ownPackage) {
        TraversalTreeCache.currentGeneration() != request.generation
      }
      if (TraversalTreeCache.currentGeneration() != request.generation) {
        Performance.getInstance().onOrderBuildDied()
        return
      }
      val strategy = OrderedTraversalStrategy(root)
      published = TraversalTreeCache.publishSnapshot(root, strategy, request.generation)
      if (!published) {
        Performance.getInstance().onOrderBuildDied()
      }
      if (published) {
        Performance.getInstance().onOrderSnapshotPublished()
        if (LogUtils.shouldLog(Log.DEBUG)) {
          Log.d(TAG, "Built the order of window ${request.windowId} in the background")
        }
      }
    } catch (e: RuntimeException) {
      Log.w(TAG, "Could not build the reading order in the background", e)
    } finally {
      // A published snapshot's root belongs to the cache, as with an order saved by a swipe.
      if (!published) {
        root.recycle()
      }
    }
  }
}
