package com.google.android.accessibility.talkback.calls

import android.content.SharedPreferences

data class CallLimitSettings(
  val enabled: Boolean = false,
  val minutes: Int = 10,
  val scope: Scope = Scope.OUTGOING,
) {
  enum class Scope { OUTGOING, BOTH }

  fun appliesTo(incoming: Boolean): Boolean = enabled && (!incoming || scope == Scope.BOTH)

  val durationMillis: Long get() = minutes * 60_000L

  companion object {
    const val ENABLED = "pref_call_limit_enabled"
    const val MINUTES = "pref_call_limit_minutes"
    const val SCOPE = "pref_call_limit_scope"
    val KEYS = setOf(ENABLED, MINUTES, SCOPE)

    fun read(prefs: SharedPreferences): CallLimitSettings = CallLimitSettings(
      enabled = prefs.getBoolean(ENABLED, false),
      minutes = prefs.all[MINUTES]?.toString()?.toIntOrNull()?.takeIf { it in 1..1440 } ?: 10,
      scope = runCatching { Scope.valueOf(prefs.getString(SCOPE, "OUTGOING").orEmpty()) }
        .getOrDefault(Scope.OUTGOING),
    )
  }
}
