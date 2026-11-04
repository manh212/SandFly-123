package com.google.android.accessibility.talkback.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallLimitEngineTest {
  private var now = 100_000L
  private var settings = CallLimitSettings(enabled = true, minutes = 1)
  private var ended = 0
  private var failures = 0
  private var warnings = 0
  private data class Job(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
  private val jobs = mutableListOf<Job>()
  private val engine = CallLimitEngine({ now }, { settings }, { delay, action ->
    val job = Job(now + delay, action)
    jobs += job
    val cancel: () -> Unit = { job.cancelled = true }
    cancel
  }, { failures++ }, { warnings++ })

  private fun advance(millis: Long) {
    now += millis
    jobs.toList().filter { !it.cancelled && it.due <= now }.forEach { it.action() }
  }

  private fun start(key: String, incoming: Boolean = false, start: Long = now, valid: () -> Boolean = { true }) {
    engine.start(key, incoming, start, valid, { ended++; true })
  }

  @Test fun countdownUsesConnectedTimeAndFiresOnce() {
    start("call", start = now - 10_000L)
    advance(49_999)
    assertEquals(0, ended)
    advance(1)
    jobs.first().action()
    assertEquals(1, ended)
  }

  @Test fun defaultScopeLeavesIncomingCallsAlone() {
    start("incoming", incoming = true)
    start("outgoing")
    advance(60_000)
    assertEquals(1, ended)
  }

  @Test fun bothScopeAllowsIncomingAndOutgoingCalls() {
    settings = settings.copy(scope = CallLimitSettings.Scope.BOTH)
    start("incoming", incoming = true)
    start("outgoing")
    advance(60_000)
    assertEquals(2, ended)
  }

  @Test fun disabledSettingCreatesNoTimer() {
    settings = settings.copy(enabled = false)
    start("call")
    advance(120_000)
    assertEquals(0, ended)
    assertEquals(0, jobs.size)
  }

  @Test fun cancelledTimerCannotEndTheNextCall() {
    start("first")
    val stale = jobs.single()
    engine.cancel("first")
    advance(30_000)
    start("second")
    advance(30_000)
    stale.action()
    assertEquals(0, ended)
    advance(30_000)
    assertEquals(1, ended)
  }

  @Test fun duplicateStateUpdatesDoNotRestartDeadline() {
    start("call")
    advance(30_000)
    start("call")
    advance(30_000)
    assertEquals(1, ended)
  }

  @Test fun changedSettingsAndInvalidTargetsDoNotDisconnect() {
    start("changed")
    settings = settings.copy(minutes = 2)
    advance(60_000)
    assertEquals(0, ended)
    start("ended", valid = { false })
    advance(120_000)
    assertEquals(0, ended)
  }

  @Test fun cancellationDropsEveryPendingDeadline() {
    start("first")
    start("second")
    engine.cancelAll()
    advance(120_000)
    jobs.forEach { it.action() }
    assertEquals(0, ended)
  }

  @Test fun rejectedDisconnectReportsFailureOnlyOnce() {
    engine.start("call", false, now, { true }, { false })
    advance(60_000)
    jobs.single().action()
    assertEquals(1, failures)
  }
  @Test fun everyLimitLongerThanOneMinuteWarnsExactlyOneMinuteBeforeEnding() {
    for (minutes in listOf(2, 5, 10, 15)) {
      engine.cancelAll()
      jobs.clear()
      warnings = 0
      ended = 0
      settings = settings.copy(minutes = minutes)
      start("call")
      advance((minutes - 1) * 60_000L - 1)
      assertEquals(0, warnings)
      advance(1)
      assertEquals(1, warnings)
      assertEquals(0, ended)
      jobs.last().action()
      assertEquals(1, warnings)
      advance(60_000L)
      assertEquals(1, ended)
    }
  }

  @Test fun oneMinuteLimitDoesNotWarn() {
    start("call")
    advance(59_999L)
    assertEquals(0, warnings)
    advance(1L)
    assertEquals(1, ended)
    assertEquals(0, warnings)
  }

  @Test fun cancelledCallsCannotProduceAnOldWarning() {
    settings = settings.copy(minutes = 10)
    start("call")
    val staleWarning = jobs.last()
    engine.cancel("call")
    advance(540_000L)
    staleWarning.action()
    assertEquals(0, warnings)
    assertEquals(0, ended)
  }
  @Test fun delayedDeliveryStillGivesAFullMinuteAfterTheWarning() {
    settings = settings.copy(minutes = 10)
    start("call")
    advance(600_000L)
    assertEquals(1, warnings)
    assertEquals(0, ended)
    advance(59_999L)
    assertEquals(0, ended)
    advance(1L)
    assertEquals(1, ended)
  }
}
