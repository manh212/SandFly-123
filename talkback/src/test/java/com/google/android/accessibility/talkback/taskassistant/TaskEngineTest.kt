package com.google.android.accessibility.talkback.taskassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class TaskEngineTest {
  private class Host : TaskHost {
    var observation: ((Result<TaskScreen>) -> Unit)? = null
    var answer: ((Result<String>) -> Unit)? = null
    var confirmation: ((Boolean) -> Unit)? = null
    var execution: ((TaskExecution) -> Unit)? = null
    var question: ((String?) -> Unit)? = null
    var performed = 0
    var cancellations = 0
    var interruptions = 0
    var time = 0L
    override fun observe(done: (Result<TaskScreen>) -> Unit) { observation = done }
    override fun plan(prompt: String, done: (Result<String>) -> Unit) { answer = done }
    override fun execute(screen: TaskScreen, step: TaskStep, done: (TaskExecution) -> Unit) { performed++; execution = done }
    override fun confirm(screen: TaskScreen, step: TaskStep, done: (Boolean) -> Unit) { confirmation = done }
    override fun ask(question: String, done: (String?) -> Unit) { this.question = done }
    override fun interrupted() { interruptions++ }
    override fun changed(state: TaskState, message: String) { }
    override fun cancelOperations() { cancellations++ }
    override fun now() = time
    fun screen(value: TaskScreen) { val next = observation!!; observation = null; next(Result.success(value)) }
    fun reply(value: String) { val next = answer!!; answer = null; next(Result.success(value)) }
  }

  private val screen = TaskScreen(1, "player", "Cài đặt", "before", listOf(
    TaskNode("n0", "Phụ đề", "captions", "Switch", setOf("click"), false),
    TaskNode("n1", "Gửi", "send", "Button", setOf("click")),
  ))

  @Test fun completesOnlyAfterANewScreenAndFreshCompletionVerification() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    host.execution!!(TaskExecution.PERFORMED)
    val after = screen.copy(id = 2, fingerprint = "after", nodes = listOf(screen.nodes[0].copy(checked = true)))
    host.screen(after)
    host.reply("""{"snapshot":2,"action":"done","node":"n0","evidence":"Phụ đề","checked":true,"message":"Đã bật phụ đề"}""")
    assertEquals(TaskState.PLANNING, engine.state)
    host.screen(after.copy(id = 3))
    assertEquals(TaskState.FINISHED, engine.state)
    assertEquals(1, host.performed)
  }

  @Test fun changedScreenInvalidatesAiCompletion() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Đến phần phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"done","node":"n0","evidence":"Phụ đề","checked":false,"message":"Đã đến"}""")
    host.screen(screen.copy(id = 2, fingerprint = "different"))
    assertEquals(TaskState.OBSERVING, engine.state)
  }

  @Test fun userInterventionStopsTheLoopAndIgnoresTheLateAiReply() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    engine.interrupt()
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    assertEquals(0, host.performed)
    assertEquals(1, host.interruptions)
    assertEquals(TaskState.INTERRUPTED, engine.state)
    engine.pause()
    host.time = 3_600_000
    engine.resume()
    assertEquals(TaskState.OBSERVING, engine.state)
  }

  @Test fun cancellingAConfirmationNeverExecutesThePendingSend() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Gửi tin")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n1","sensitive":false}""")
    assertNotNull(host.confirmation)
    engine.cancel()
    host.confirmation!!(true)
    assertEquals(0, host.performed)
    assertEquals(TaskState.CANCELLED, engine.state)
  }

  @Test fun cancelledActionCallbacksCannotStartAnotherObservation() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    engine.interrupt()
    host.execution!!(TaskExecution.PERFORMED)
    assertEquals(TaskState.INTERRUPTED, engine.state)
    assertFalse(engine.busy)
  }

  @Test fun unchangedScreenDoesNotCauseRepeatedClicks() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    host.execution!!(TaskExecution.PERFORMED)
    repeat(3) { host.screen(screen.copy(id = it + 2L)) }
    assertEquals(1, host.performed)
    assertEquals(TaskState.FAILED, engine.state)
  }

  @Test fun anAiReplyAfterTheTimeBudgetCannotExecuteAnAction() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.time = TaskEngine.MAX_ACTIVE_MS + 1
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    assertEquals(TaskState.FAILED, engine.state)
    assertEquals(0, host.performed)
  }

  @Test fun theStepLimitStopsEvenWhenEveryScreenAndDecisionIsDifferent() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Chờ kết quả")
    repeat(TaskEngine.MAX_STEPS) { index ->
      val id = index + 1L
      host.screen(screen.copy(id = id, fingerprint = "screen-$index"))
      host.reply("""{"snapshot":$id,"action":"wait"}""")
      host.execution!!(TaskExecution.PERFORMED)
    }
    assertEquals(TaskState.FAILED, engine.state)
    assertEquals(TaskEngine.MAX_STEPS, host.performed)
  }

  @Test fun staleTargetsAreObservedAgainWithoutRepeatingTheOldDecision() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    host.execution!!(TaskExecution.STALE)
    assertEquals(TaskState.OBSERVING, engine.state)
    host.screen(screen.copy(id = 2, fingerprint = "new"))
    assertEquals(TaskState.PLANNING, engine.state)
    assertEquals(1, host.performed)
  }
}
