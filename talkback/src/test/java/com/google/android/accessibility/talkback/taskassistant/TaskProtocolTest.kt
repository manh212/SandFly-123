package com.google.android.accessibility.talkback.taskassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskProtocolTest {
  private val apps = listOf(TaskApp("example.player", "Trình phát"))
  private val screen = TaskScreen(7, "example.player", "Cài đặt", "a", listOf(
    TaskNode("n0", "Phụ đề", "captions", "Switch", setOf("click"), false),
    TaskNode("n1", "Gửi", "send", "Button", setOf("click")),
    TaskNode("n2", "Nội dung", "input", "EditText", setOf("set_text")),
  ))

  @Test fun acceptsAnActionExposedByTheCurrentScreen() {
    val step = parse("""{"snapshot":7,"action":"click","node":"n0"}""")
    assertEquals(TaskOperation.CLICK, step.operation)
  }

  @Test fun rejectsStaleOrInventedNodesAndUnsupportedActions() {
    reject("""{"snapshot":6,"action":"click","node":"n0"}""")
    reject("""{"snapshot":7,"action":"click","node":"n9"}""")
    reject("""{"snapshot":7,"action":"set_text","node":"n1","text":"x"}""")
    reject("""{"snapshot":7,"action":"shell","text":"anything"}""")
    reject("""{"snapshot":"7","action":"click","node":"n0"}""")
  }

  @Test fun cannotLaunchAnUnlistedPackageOrAnIntentUri() {
    reject("""{"snapshot":7,"action":"open_app","package":"other.app"}""")
    reject("""{"snapshot":7,"action":"open_app","package":"intent://example"}""")
    assertEquals("example.player", parse("""{"snapshot":7,"action":"open_app","package":"example.player"}""").packageName)
  }

  @Test fun requiresObservableCompletionEvidenceAndTheRequestedCheckedState() {
    reject("""{"snapshot":7,"action":"done","message":"Đã bật"}""")
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"Phụ đề","message":"Đã bật"}""")
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"Phụ đề","checked":true,"message":"Đã bật"}""")
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"Wi-Fi","message":"Đã bật"}""")
    val step = parse("""{"snapshot":7,"action":"done","node":"n0","evidence":"Phụ đề","checked":false,"message":"Phụ đề đang tắt"}""")
    assertEquals(TaskOperation.DONE, step.operation)
  }

  @Test fun sequenceStepsAreMatchedByLabelAndOpenAppPackagesAreValidated() {
    val plan = TaskProtocol.parsePlan(
      """{"snapshot":7,"steps":[{"action":"open_app","package":"example.player"},{"action":"click","label":"Phụ đề"}]}""", screen, apps)
    assertTrue(plan is TaskProtocol.TaskPlan.Sequence)
    val steps = (plan as TaskProtocol.TaskPlan.Sequence).steps
    assertEquals(2, steps.size)
    assertEquals(TaskOperation.OPEN_APP, steps[0].operation)
    assertEquals("example.player", steps[0].packageName)
    assertEquals(TaskOperation.CLICK, steps[1].operation)
    assertEquals("Phụ đề", steps[1].label)
    assertTrue(TaskProtocol.parsePlan("""{"snapshot":7,"action":"click","node":"n0"}""", screen, apps) is TaskProtocol.TaskPlan.Single)
  }

  @Test fun sequenceRejectsDoneAskMissingLabelsAndUnlistedPackages() {
    rejectPlan("""{"snapshot":7,"steps":[{"action":"done","node":"n0","evidence":"Phụ đề","message":"x"}]}""")
    rejectPlan("""{"snapshot":7,"steps":[{"action":"ask","message":"?"}]}""")
    rejectPlan("""{"snapshot":7,"steps":[{"action":"click"}]}""")
    rejectPlan("""{"snapshot":7,"steps":[{"action":"open_app","package":"other.app"}]}""")
    rejectPlan("""{"snapshot":7,"steps":[]}""")
    rejectPlan("""{"snapshot":6,"steps":[{"action":"click","label":"Phụ đề"}]}""")
  }

  @Test fun plannerSeesStateAndScopeWithoutReceivingExecutableTools() {
    val prompt = TaskProtocol.prompt("Bật phụ đề", screen, apps, emptyList())
    assertTrue(prompt.contains("untrusted data"))
    assertTrue(prompt.contains("NEW snapshot"))
    assertTrue(prompt.contains("example.player"))
    assertTrue(prompt.contains("\"checked\":false"))
  }

  @Test fun blankNormalizedEvidenceCannotProveCompletion() {
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"\u0301","message":"Đã xong"}""")
  }

  @Test fun credentialInputsAreNotExecutableEvenWhenNotMarkedAsPassword() {
    val privateScreen = screen.copy(nodes = listOf(screen.nodes[2].copy(label = "Mã xác minh")))
    try {
      TaskProtocol.parse("""{"snapshot":7,"action":"set_text","node":"n2","text":"123456"}""", privateScreen, apps)
      fail("Credential entry must stay private")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun imeSubmissionMustBeExposed() {
    reject("""{"snapshot":7,"action":"press_enter","node":"n2"}""")
    val editable = screen.copy(nodes = listOf(screen.nodes[2].copy(actions = setOf("press_enter"))))
    val step = TaskProtocol.parse("""{"snapshot":7,"action":"press_enter","node":"n2"}""", editable, apps)
    assertEquals(TaskOperation.PRESS_ENTER, step.operation)
  }

  @Test fun credentialQuestionsAreRecognizedInBothLanguages() {
    assertTrue(TaskProtocol.isCredentialRequest("Nhập mật khẩu của bạn"))
    assertTrue(TaskProtocol.isCredentialRequest("Enter verification code"))
    assertTrue(TaskProtocol.isCredentialRequest("Nhập mã xác nhận bạn vừa nhận được"))
    assertTrue(TaskProtocol.isCredentialRequest("Nhập mật mã của bạn"))
    assertFalse(TaskProtocol.isCredentialRequest("Bạn muốn dùng ứng dụng nào?"))
    assertFalse(TaskProtocol.isCredentialRequest("Bạn muốn xác nhận đơn hàng này không?"))
  }

  @Test fun confirmationCodeInputsAreNotExecutable() {
    val privateScreen = screen.copy(nodes = listOf(screen.nodes[2].copy(label = "Mã xác nhận")))
    try {
      TaskProtocol.parse("""{"snapshot":7,"action":"set_text","node":"n2","text":"123456"}""", privateScreen, apps)
      fail("Credential entry must stay private")
    } catch (_: IllegalArgumentException) { }
  }

  private fun parse(json: String) = TaskProtocol.parse(json, screen, apps)

  private fun rejectPlan(json: String) {
    try { TaskProtocol.parsePlan(json, screen, apps); fail("The sequence must not be executable") } catch (_: IllegalArgumentException) { }
  }

  private fun reject(json: String) {
    try { parse(json); fail("The response must not be executable") } catch (_: IllegalArgumentException) { }
  }
}
