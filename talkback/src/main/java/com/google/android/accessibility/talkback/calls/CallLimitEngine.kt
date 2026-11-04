package com.google.android.accessibility.talkback.calls

class CallLimitEngine(
  private val now: () -> Long,
  private val settings: () -> CallLimitSettings,
  private val schedule: (Long, () -> Unit) -> (() -> Unit),
  private val failed: () -> Unit,
  private val warning: () -> Unit = {},
) {
  private class Entry(
    val settings: CallLimitSettings,
    var deadline: Long,
    val valid: () -> Boolean,
    val end: () -> Boolean,
  ) {
    var cancel: (() -> Unit)? = null
    var cancelWarning: (() -> Unit)? = null
    var warned = false
  }

  private val entries = mutableMapOf<Any, Entry>()

  fun start(
    key: Any,
    incoming: Boolean,
    startedAt: Long,
    valid: () -> Boolean,
    end: () -> Boolean,
  ) {
    if (entries.containsKey(key)) return
    val selected = settings()
    if (!selected.appliesTo(incoming)) return
    val entry = Entry(selected, startedAt + selected.durationMillis, valid, end)
    entries[key] = entry
    arm(key, entry)
    if (selected.minutes > 1) armWarning(key, entry)
  }

  private fun valid(key: Any, entry: Entry): Boolean {
    if (entries[key] !== entry) return false
    if (entry.settings != settings() || !entry.valid()) {
      cancel(key)
      return false
    }
    return true
  }

  private fun armWarning(key: Any, entry: Entry) {
    val warningAt = entry.deadline - 60_000L
    entry.cancelWarning = schedule((warningAt - now()).coerceAtLeast(0)) {
      if (!valid(key, entry) || entry.warned) return@schedule
      if (now() < warningAt) armWarning(key, entry)
      else warn(key, entry)
    }
  }

  private fun warn(key: Any, entry: Entry) {
    if (entry.warned) return
    entry.warned = true
    if (entry.deadline < now() + 60_000L) {
      entry.deadline = now() + 60_000L
      entry.cancel?.invoke()
      arm(key, entry)
    }
    warning()
  }

  private fun arm(key: Any, entry: Entry) {
    entry.cancel = schedule((entry.deadline - now()).coerceAtLeast(0)) {
      if (!valid(key, entry)) return@schedule
      if (now() < entry.deadline) {
        arm(key, entry)
        return@schedule
      }
      if (entry.settings.minutes > 1 && !entry.warned) {
        warn(key, entry)
        return@schedule
      }
      cancel(key)
      if (!entry.end()) failed()
    }
  }

  fun cancel(key: Any) {
    entries.remove(key)?.let {
      it.cancel?.invoke()
      it.cancelWarning?.invoke()
    }
  }

  fun cancelAll() {
    entries.keys.toList().forEach(::cancel)
  }
}
