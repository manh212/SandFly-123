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

import android.os.Handler
import android.os.Looper
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

  /** The ask was held until the swipe burst passes; it is taken up when the hold lifts. */
  const val OUTCOME_DEFERRED = 3

  /**
   * How many background builds in a row may die unpublished for one window before the next
   * navigation stops waiting for the background route and builds on the spot, publishing
   * however the window moved while the order was read. One death is ordinary churn — in a
   * busy window roughly two builds in five died even before this machinery — and the settle
   * retry gives the background route its second chances; three in a row for the same window
   * means that route is starved, and the user is walking an order known to be incomplete
   * meanwhile.
   */
  private const val DEATHS_BEFORE_ESCALATION = 3

  /** How long after the last navigation an ask is still inside the swipe burst. */
  private const val BURST_WINDOW_MS = 300L

  /**
   * The longest an ask may be held by burst deferral, however long the burst runs: a burst
   * that never pauses must not starve the window of its order.
   */
  private const val DEFER_MAX_MS = 1500L

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
    /** An escalation build: publish the order however the window moved while it was read. */
    val forcePublish: Boolean = false,
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

  /**
   * The newest ask held back by burst deferral, and when the hold began. The ask is kept whole
   * — its root supplier resolves the current root whenever it is called — and is taken up with
   * a fresh generation when the burst passes, so the hold can delay a build, never stale it,
   * and the last ask of a burst is never lost.
   */
  private var deferredRequest: Request? = null
  private var deferredSinceMs: Long = 0L

  private val flushHandler = Handler(Looper.getMainLooper())
  private val flushDeferredTask = Runnable { flushDeferred() }

  /** Background builds that died unpublished in a row, and the window they were for. */
  private var consecutiveDeaths = 0
  private var deathsWindowId = -1

  /** Notes one more background build for [windowId] that died unpublished. */
  private fun noteBuildDied(windowId: Int) {
    synchronized(lock) {
      if (windowId == deathsWindowId) {
        consecutiveDeaths++
      } else {
        deathsWindowId = windowId
        consecutiveDeaths = 1
      }
    }
  }

  /** Notes a background build for [windowId] that landed: the death run is over. */
  private fun noteBuildPublished(windowId: Int) {
    synchronized(lock) {
      if (windowId == deathsWindowId) {
        consecutiveDeaths = 0
      }
    }
  }

  /**
   * Whether [windowId]'s background builds have died unpublished [DEATHS_BEFORE_ESCALATION]
   * times in a row, so the next navigation for it escalates to a forced on-the-spot build.
   */
  fun escalationArmed(windowId: Int): Boolean =
    synchronized(lock) {
      windowId == deathsWindowId && consecutiveDeaths >= DEATHS_BEFORE_ESCALATION
    }

  /** Spends the escalation armed for [windowId]: one forced build per arming. */
  fun consumeEscalation(windowId: Int) {
    synchronized(lock) {
      if (windowId == deathsWindowId) {
        consecutiveDeaths = 0
      }
    }
  }

  /**
   * Takes up at once an ask held by burst deferral for [windowId], if one is held: called
   * when a structural change expires that window's served order, whose replacement build must
   * not wait out the burst it is meant to repair. The ask goes through [request] again, where
   * the cache's deferral exemption for the window lets it proceed.
   */
  fun releaseDeferred(windowId: Int) {
    val held =
      synchronized(lock) {
        deferredRequest?.windowId == windowId
      }
    if (held) {
      flushDeferred()
    }
  }

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
        // A build starting now would compete for the main thread with the swipes still
        // landing; the ask is held instead, newest one winning, until the burst has been
        // quiet for the burst window — or until it has been held the maximum, so a burst
        // that never pauses cannot starve the window of its order.
        val lastNavigation = TraversalTreeCache.lastNavigationTimeMs()
        val now = SystemClock.uptimeMillis()
        val inBurst = lastNavigation > 0 && now - lastNavigation < BURST_WINDOW_MS
        val heldTooLong =
          deferredRequest != null && deferredSinceMs > 0 && now - deferredSinceMs >= DEFER_MAX_MS
        if (inBurst && !heldTooLong) {
          // The first ask after a structural change expired this window's served order
          // goes ahead even inside the burst: holding it back would keep navigation
          // walking an order known to be incomplete — the failure the hold exists to
          // avoid costing the user, not one it may cause.
          if (TraversalTreeCache.buildDeferralExempt(windowId)) {
            Performance.getInstance().onBuildDeferralExemptionUsed()
          } else {
            if (deferredRequest == null) {
              deferredSinceMs = now
            }
            deferredRequest = Request(windowId, generation, rootSupplier, ownPackage, source)
            flushHandler.removeCallbacks(flushDeferredTask)
            flushHandler.postDelayed(
              flushDeferredTask, lastNavigation + BURST_WINDOW_MS - now)
            Performance.getInstance().onBackgroundBuildDeferredDuringBurst()
            return OUTCOME_DEFERRED
          }
        }
        if (source == Performance.ORDER_BUILD_SOURCE_LISTENER &&
          running &&
          (runningWindowId == windowId || pending?.windowId == windowId)) {
          dirtyRequest = Request(windowId, generation, rootSupplier, ownPackage, source)
          Performance.getInstance().onListenerBuildCoalesced()
          return OUTCOME_COALESCED
        }
        if (deferredRequest?.windowId == windowId) {
          // This ask supersedes the one being held for the same window.
          deferredRequest = null
          deferredSinceMs = 0L
          flushHandler.removeCallbacks(flushDeferredTask)
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

  /**
   * Takes up the ask held by burst deferral, with the generation read fresh, as the coalesced
   * follow-up builds are taken up. If navigations have landed again since, the ask defers
   * once more — with the hold's start kept, so the maximum hold still bounds the chain.
   */
  private fun flushDeferred() {
    val held =
      synchronized(lock) {
        val taken = deferredRequest
        deferredRequest = null
        taken to deferredSinceMs
      }
    val (deferred, heldSince) = held
    if (deferred == null) {
      return
    }
    val outcome =
      request(
        deferred.windowId,
        TraversalTreeCache.currentGeneration(),
        deferred.rootSupplier,
        deferred.ownPackage,
        deferred.source,
      )
    if (outcome == OUTCOME_DEFERRED && heldSince > 0) {
      synchronized(lock) {
        deferredSinceMs = heldSince
      }
    }
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
        noteBuildDied(request.windowId)
        synchronized(lock) {
          if (askedKey == (request.windowId to request.generation) && pending == null) {
            askedKey = null
          }
        }
        continue
      }
      val published = build(request)
      if (published) {
        noteBuildPublished(request.windowId)
      } else {
        noteBuildDied(request.windowId)
      }
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
   * whole pipeline on the calling thread. With [forcePublish] the build is an escalation
   * build: it publishes however the window moved while the order was read.
   */
  fun buildNow(
    windowId: Int,
    generation: Long,
    rootSupplier: Supplier<AccessibilityNodeInfoCompat?>,
    ownPackage: String,
    forcePublish: Boolean = false,
  ): Boolean {
    return build(Request(windowId, generation, rootSupplier, ownPackage,
      Performance.ORDER_BUILD_SOURCE_EVENT_CLEAR, forcePublish))
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
      // An escalation build skips the moved-on checks below: the window moving while the
      // order is read is the very starvation it answers, and the order it publishes was read
      // from the one tree it started from.
      if (!request.forcePublish &&
        TraversalTreeCache.currentGeneration() != request.generation) {
        Performance.getInstance().onOrderBuildDied(request.source)
        return false
      }
      // The read stops early if the window moves on while it runs, so a dead build hands the
      // builder back at once instead of walking the whole tree for a refused snapshot.
      WindowNodePrefetcher.prefetch(root, request.ownPackage) {
        !request.forcePublish && TraversalTreeCache.currentGeneration() != request.generation
      }
      if (!request.forcePublish &&
        TraversalTreeCache.currentGeneration() != request.generation) {
        Performance.getInstance().onOrderBuildDied(request.source)
        return false
      }
      val strategy = buildStrategy(root)
      published =
        if (request.forcePublish) {
          TraversalTreeCache.publishSnapshotForced(root, strategy)
          true
        } else {
          TraversalTreeCache.publishSnapshot(root, strategy, request.generation)
        }
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
   * Builds the order of [root] with the lean builder — and with the standard builder whenever
   * the lean one fails, so a window the lean builder cannot handle costs one wasted walk,
   * never a missing order.
   */
  private fun buildStrategy(root: AccessibilityNodeInfoCompat): OrderedTraversalStrategy {
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
