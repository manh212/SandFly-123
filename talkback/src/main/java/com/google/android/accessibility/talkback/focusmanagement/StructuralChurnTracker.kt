package com.google.android.accessibility.talkback.focusmanagement

/** Tracks bursty structural updates independently of the reading order cache. */
internal class StructuralChurnTracker {
  private data class Window(var firstMs: Long, var count: Int, var liveUntilMs: Long)
  private val windows = LinkedHashMap<Int, Window>(4, 0.75f, true)

  @Synchronized fun record(windowId: Int, nowMs: Long) {
    if (windowId < 0) return
    var state = windows[windowId]
    if (state == null) {
      if (windows.size == 4) {
        val iterator = windows.entries.iterator()
        if (iterator.hasNext()) {
          iterator.next()
          iterator.remove()
        }
      }
      state = Window(nowMs, 0, 0L)
      windows[windowId] = state
    }
    if (nowMs < state.firstMs || nowMs - state.firstMs > 1000L) {
      state.firstMs = nowMs
      state.count = 0
    }
    state.count++
    if (state.count >= 4) state.liveUntilMs = nowMs + 2000L
  }

  @Synchronized fun isChurning(windowId: Int, nowMs: Long): Boolean =
    windowId >= 0 && (windows[windowId]?.liveUntilMs ?: 0L) > nowMs
}
