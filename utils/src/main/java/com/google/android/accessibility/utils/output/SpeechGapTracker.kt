package com.google.android.accessibility.utils.output

internal class SpeechGapTracker {
  enum class Kind { RESUMED, TAIL, INTERRUPTED }
  data class Gap(val kind: Kind, val durationMs: Long)
  private var startedAt: Long? = null
  val waiting: Boolean
    get() = startedAt != null

  fun mix(written: Int, requested: Int, waitingForEngine: Boolean, now: Long): Gap? {
    val resumed = if (written > 0) close(Kind.RESUMED, now) else null
    if (written < requested && waitingForEngine && startedAt == null) startedAt = now
    return resumed
  }

  fun close(kind: Kind, now: Long): Gap? {
    val start = startedAt ?: return null
    startedAt = null
    return Gap(kind, (now - start).coerceAtLeast(0))
  }
}
