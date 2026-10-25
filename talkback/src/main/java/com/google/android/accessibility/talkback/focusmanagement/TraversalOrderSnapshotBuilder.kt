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

import android.os.SystemClock
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.LeanTraversalOrderBuilder
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

  /** The ask was taken on: it is the build now waited for, or the one running. */
  const val OUTCOME_ACCEPTED = 0

  /** The ask named the very build already on its way, so nothing new was posted. */
  const val OUTCOME_DEDUPED = 1

  /** The ask was folded into the build on its way; one more build follows when that one lands. */
  const val OUTCOME_COALESCED = 2

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
    val source: Int,
  )

  private val lock = Any()
  private var pending: Request? = null
  private var running = false

  /** The window of the request being built right now, or -1 when no build is running. */
  private var runningWindowId = -1

  /**
   * The newest listener ask folded into the build on its way, kept so a build from the newest
   * tree follows when that build lands. A root supplier resolves the current root whenever it
   * is called, and the follow-up build reads the generation again when it is taken up, so it
   * always starts from the tree as it stands then — the fold can delay a build, never stale it.
   */
  private var dirtyRequest: Request? = null

  /** The (window, generation) of the request waiting or running, if any. */
  private var askedKey: Pair<Int, Long>? = null

  /**
   * The (window, generation) the newest saved snapshot was built for. Publishing does not move
   * the generation on, so without remembering this, a second request for the order that has
   * just landed — a screen change and a touch arriving together ask for exactly that — would
   * build the same order all over again and save it a second time.
   */
  private var publishedKey: Pair<Int, Long>? = null

  /**
   * Queues a build of the order of [windowId], replacing any older build still waiting, and
   * says how the ask was taken on. A listener ask that arrives while a build for the same
   * window is already running is folded into it instead of replacing the build still waiting:
   * in a churning window those asks arrived by the hundred and most of the builds they queued
   * died unpublished, while the fold keeps exactly one follow-up build, from the newest tree.
   */
  fun request(
    windowId: Int,
    generation: Long,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
    source: Int = Performance.ORDER_BUILD_SOURCE_EVENT_CLEAR,
  ): Int {
    val key = windowId to generation
    val start =
      synchronized(lock) {
        if (key == askedKey || key == publishedKey) {
          Performance.getInstance().onOrderBuildRequestDeduped()
          return OUTCOME_DEDUPED
        }
        if (source == Performance.ORDER_BUILD_SOURCE_LISTENER &&
          TraversalTreeCache.listenerBuildCoalescingEnabled &&
          running &&
          (runningWindowId == windowId || pending?.windowId == windowId)) {
          dirtyRequest = Request(windowId, generation, rootSupplier, ownPackage, source)
          Performance.getInstance().onListenerBuildCoalesced()
          return OUTCOME_COALESCED
        }
        askedKey = key
        pending = Request(windowId, generation, rootSupplier, ownPackage, source)
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
        synchronized(lock) {
          running = false
          if (askedKey == key) {
            askedKey = null
          }
        }
      }
    }
    return OUTCOME_ACCEPTED
  }

  private fun drain() {
    while (true) {
      val request =
        synchronized(lock) {
          var next = pending
          pending = null
          if (next == null) {
            // Nothing newer was posted, but listener asks may have been folded into the build
            // that just ran: their follow-up build is owed, from the tree as it stands now.
            val dirty = dirtyRequest
            dirtyRequest = null
            if (dirty != null) {
              next =
                Request(
                  dirty.windowId,
                  TraversalTreeCache.currentGeneration(),
                  dirty.rootSupplier,
                  dirty.ownPackage,
                  dirty.source,
                )
              askedKey = next.windowId to next.generation
            }
          }
          if (next == null) {
            running = false
            runningWindowId = -1
          } else {
            runningWindowId = next.windowId
          }
          next
        } ?: return
      // A request the window has already moved past is not worth even resolving its root for:
      // in a churning window, builds used to queue up behind one another and each walked the
      // whole tree before its snapshot was refused, keeping the single builder busy with dead
      // work while the live request waited.
      if (request.generation != TraversalTreeCache.currentGeneration()) {
        Performance.getInstance().onOrderBuildDied(request.source)
        synchronized(lock) {
          if (askedKey == (request.windowId to request.generation) && pending == null) {
            askedKey = null
          }
        }
        continue
      }
      val published = build(request)
      synchronized(lock) {
        if (published) {
          publishedKey = request.windowId to request.generation
        }
        if (askedKey == (request.windowId to request.generation) && pending == null) {
          askedKey = null
        }
      }
    }
  }

  /**
   * Builds one requested order and publishes it, saying whether it was published. Runs the
   * whole pipeline on the calling thread.
   */
  fun buildNow(
    windowId: Int,
    generation: Long,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
  ): Boolean {
    return build(Request(windowId, generation, rootSupplier, ownPackage,
      Performance.ORDER_BUILD_SOURCE_EVENT_CLEAR))
  }

  private fun build(request: Request): Boolean {
    val started = SystemClock.uptimeMillis()
    try {
      return buildOrder(request)
    } finally {
      Performance.getInstance()
        .onOrderBuildDuration(SystemClock.uptimeMillis() - started)
    }
  }

  private fun buildOrder(request: Request): Boolean {
    val root =
      try {
        request.rootSupplier.get()
      } catch (e: RuntimeException) {
        Log.w(TAG, "Could not resolve the root of the window to build", e)
        null
      } ?: return false
    var published = false
    try {
      if (root.windowId != request.windowId) {
        return false
      }
      if (TraversalTreeCache.currentGeneration() != request.generation) {
        Performance.getInstance().onOrderBuildDied(request.source)
        return false
      }
      // The read stops early if the window moves on while it runs, so a dead build hands the
      // builder back at once instead of walking the whole tree for a refused snapshot.
      WindowNodePrefetcher.prefetch(root, request.ownPackage) {
        TraversalTreeCache.currentGeneration() != request.generation
      }
      if (TraversalTreeCache.currentGeneration() != request.generation) {
        Performance.getInstance().onOrderBuildDied(request.source)
        return false
      }
      val strategy = buildStrategy(root)
      published = TraversalTreeCache.publishSnapshot(root, strategy, request.generation)
      if (!published) {
        Performance.getInstance().onOrderBuildDied(request.source)
      }
      if (published) {
        Performance.getInstance().onOrderSnapshotPublished(request.source)
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
    return published
  }

  /**
   * Builds the order of [root] with the lean builder while it is switched on, and with the
   * standard builder otherwise — and with the standard builder whenever the lean one fails,
   * so a window the lean builder cannot handle costs one wasted walk, never a missing order.
   */
  private fun buildStrategy(root: AccessibilityNodeInfoCompat): OrderedTraversalStrategy {
    if (!TraversalTreeCache.leanOrderBuilderEnabled) {
      return OrderedTraversalStrategy(root)
    }
    return try {
      val strategy = LeanTraversalOrderBuilder.build(root)
      Performance.getInstance().onLeanOrderBuild(false)
      strategy
    } catch (e: RuntimeException) {
      Log.w(TAG, "The lean order builder failed; building the standard way", e)
      Performance.getInstance().onLeanOrderBuild(true)
      OrderedTraversalStrategy(root)
    }
  }
}
