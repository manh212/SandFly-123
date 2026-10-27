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
    assertFalse(TaskProtocol.needsConfirmation(step, screen))
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

  @Test fun nativePolicyRequiresSendConfirmationEvenWhenAiSaysItIsSafe() {
    val step = parse("""{"snapshot":7,"action":"click","node":"n1","sensitive":false}""")
    assertTrue(TaskProtocol.needsConfirmation(step, screen))
  }

  @Test fun unknownButtonsAndPermissionSwitchesNeedConfirmation() {
    val unknown = screen.copy(nodes = listOf(TaskNode("n0", "Tiến hành", "", "Button", setOf("click"))))
    assertTrue(TaskProtocol.needsConfirmation(TaskStep(7, TaskOperation.CLICK, "n0"), unknown))
    assertTrue(TaskProtocol.needsConfirmation(TaskStep(7, TaskOperation.CLICK, "n0"), screen.copy(title = "Quyền truy cập")))
  }

  @Test fun requiresObservableCompletionEvidenceAndTheRequestedCheckedState() {
    reject("""{"snapshot":7,"action":"done","message":"Đã bật"}""")
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"Phụ đề","message":"Đã bật"}""")
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"Phụ đề","checked":true,"message":"Đã bật"}""")
    reject("""{"snapshot":7,"action":"done","node":"n0","evidence":"Wi-Fi","message":"Đã bật"}""")
    val step = parse("""{"snapshot":7,"action":"done","node":"n0","evidence":"Phụ đề","checked":false,"message":"Phụ đề đang tắt"}""")
    assertEquals(TaskOperation.DONE, step.operation)
  }

  @Test fun vietnameseToneMarksDoNotHideDangerousButtonLabels() {
    val dangerous = screen.copy(nodes = listOf(TaskNode("n0", "XÓA dữ liệu", "", "Button", setOf("click"))))
    assertTrue(TaskProtocol.needsConfirmation(TaskStep(7, TaskOperation.CLICK, "n0"), dangerous))
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

  @Test fun imeSubmissionAlwaysRequiresConfirmationAndMustBeExposed() {
    reject("""{"snapshot":7,"action":"press_enter","node":"n2"}""")
    val editable = screen.copy(nodes = listOf(screen.nodes[2].copy(actions = setOf("press_enter"))))
    val step = TaskProtocol.parse("""{"snapshot":7,"action":"press_enter","node":"n2"}""", editable, apps)
    assertTrue(TaskProtocol.needsConfirmation(step, editable))
  }

  @Test fun installerNavigationLabelsCannotBypassConfirmation() {
    val installer = screen.copy(packageName = "com.android.packageinstaller", nodes =
      listOf(TaskNode("n0", "Cài đặt", "", "Button", setOf("click"))))
    assertTrue(TaskProtocol.needsConfirmation(TaskStep(7, TaskOperation.CLICK, "n0"), installer))
  }

  @Test fun credentialQuestionsAreRecognizedInBothLanguages() {
    assertTrue(TaskProtocol.isCredentialRequest("Nhập mật khẩu của bạn"))
    assertTrue(TaskProtocol.isCredentialRequest("Enter verification code"))
    assertFalse(TaskProtocol.isCredentialRequest("Bạn muốn dùng ứng dụng nào?"))
  }

  private fun parse(json: String) = TaskProtocol.parse(json, screen, apps)

  private fun reject(json: String) {
    try { parse(json); fail("The response must not be executable") } catch (_: IllegalArgumentException) { }
  }
}
