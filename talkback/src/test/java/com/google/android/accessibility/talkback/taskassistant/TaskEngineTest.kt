package com.google.android.accessibility.talkback.taskassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEngineTest {
  private class Host : TaskHost {
    var observation: ((Result<TaskScreen>) -> Unit)? = null
    var answer: ((Result<String>) -> Unit)? = null
    var lastPrompt = ""
    var execution: ((TaskExecution) -> Unit)? = null
    var performed = 0
    var cancellations = 0
    override fun observe(done: (Result<TaskScreen>) -> Unit) { observation = done }
    override fun plan(prompt: String, done: (Result<String>) -> Unit) { lastPrompt = prompt; answer = done }
    override fun execute(screen: TaskScreen, step: TaskStep, done: (TaskExecution) -> Unit) { performed++; execution = done }
    override fun changed(state: TaskState, message: String) { }
    override fun cancelOperations() { cancellations++ }
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

  @Test fun userCancellationStopsTheLoopAndIgnoresTheLateAiReply() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    engine.cancel()
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    assertEquals(0, host.performed)
    assertEquals(TaskState.CANCELLED, engine.state)
  }

  @Test fun aRiskySingleStepExecutesImmediatelyWithoutAsking() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Gửi tin")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n1","sensitive":false}""")
    assertEquals(1, host.performed)
    assertEquals(TaskState.EXECUTING, engine.state)
  }

  @Test fun cancelledActionCallbacksCannotStartAnotherObservation() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    engine.cancel()
    host.execution!!(TaskExecution.PERFORMED)
    assertEquals(TaskState.CANCELLED, engine.state)
    assertFalse(engine.busy)
  }

  @Test fun unchangedScreenIsReportedForReplanningInsteadOfFailing() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Bật phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    host.execution!!(TaskExecution.PERFORMED)
    repeat(3) { host.screen(screen.copy(id = it + 2L)) }
    assertEquals(1, host.performed)
    assertEquals(TaskState.PLANNING, engine.state)
    host.reply("""{"snapshot":4,"action":"wait"}""")
    host.execution!!(TaskExecution.PERFORMED)
    assertEquals(2, host.performed)
    assertEquals(TaskState.OBSERVING, engine.state)
  }

  @Test fun theLoopContinuesPastTheOldStepLimit() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Chờ kết quả")
    repeat(45) { index ->
      val id = index + 1L
      host.screen(screen.copy(id = id, fingerprint = "screen-$index"))
      host.reply("""{"snapshot":$id,"action":"wait"}""")
      host.execution!!(TaskExecution.PERFORMED)
    }
    assertEquals(45, host.performed)
    assertEquals(TaskState.OBSERVING, engine.state)
  }

  @Test fun aSequenceExecutesItsStepsInOrderThenReplanningContinues() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Mở phần phụ đề")
    host.screen(screen)
    host.reply("""{"snapshot":1,"steps":[{"action":"click","label":"Phụ đề"},{"action":"click","label":"Cài đặt"}]}""")
    assertEquals(1, host.performed)
    host.execution!!(TaskExecution.PERFORMED)
    host.screen(screen.copy(id = 2, fingerprint = "after-1"))
    assertEquals(2, host.performed)
    host.execution!!(TaskExecution.PERFORMED)
    host.screen(screen.copy(id = 3, fingerprint = "after-2"))
    assertEquals(TaskState.PLANNING, engine.state)
  }

  @Test fun aRiskySequenceStepExecutesImmediatelyWithoutAsking() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Gửi tin")
    host.screen(screen)
    host.reply("""{"snapshot":1,"steps":[{"action":"click","label":"Gửi"}]}""")
    assertEquals(1, host.performed)
    host.execution!!(TaskExecution.PERFORMED)
    host.screen(screen.copy(id = 2, fingerprint = "after"))
    assertEquals(TaskState.PLANNING, engine.state)
  }

  @Test fun anAskReplyFailsWithItsMessageInsteadOfAsking() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Làm gì đó")
    host.screen(screen)
    host.reply("""{"snapshot":1,"action":"ask","message":"Bạn muốn gì?"}""")
    assertEquals(TaskState.FAILED, engine.state)
    assertEquals("Bạn muốn gì?", engine.message)
  }

  @Test fun invalidReplyIsRetriedOnceWithFormatFeedbackThenExecutes() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Mở YouTube tìm kiếm")
    host.screen(screen)
    host.reply("""{"snapshot":1,"steps":[{"action":"click","label":"Phụ đề"},{"action":"set_text","text":"x"}]}""")
    assertEquals(TaskState.PLANNING, engine.state)
    assertTrue(host.lastPrompt.contains("nhãn hiển thị"))
    host.reply("""{"snapshot":1,"action":"click","node":"n0"}""")
    assertEquals(1, host.performed)
  }

  @Test fun twoInvalidRepliesInARowFailTheTask() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Mở YouTube tìm kiếm")
    host.screen(screen)
    host.reply("""{"snapshot":1,"steps":[{"action":"set_text","text":"x"}]}""")
    assertEquals(TaskState.PLANNING, engine.state)
    host.reply("""{"snapshot":1,"steps":[{"action":"set_text","text":"x"}]}""")
    assertEquals(TaskState.FAILED, engine.state)
    assertEquals("AI trả lời thiếu tên mục cần ấn nên không thực hiện được. Hãy giao lại việc.", engine.message)
  }

  @Test fun truncatedSequenceExecutesItsPrefixThenReplans() {
    val host = Host()
    val engine = TaskEngine(host, emptyList())
    engine.start("Mở YouTube tìm kiếm")
    host.screen(screen)
    host.reply("""{"snapshot":1,"steps":[{"action":"click","label":"Phụ đề"},{"action":"set_text","text":"x"}]}""")
    assertEquals(1, host.performed)
    host.execution!!(TaskExecution.PERFORMED)
    host.screen(screen.copy(id = 2, fingerprint = "after"))
    assertEquals(TaskState.PLANNING, engine.state)
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
