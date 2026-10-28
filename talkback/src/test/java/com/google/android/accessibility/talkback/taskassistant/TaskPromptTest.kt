package com.google.android.accessibility.talkback.taskassistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskPromptTest {
  @Test fun largeScreensAndAppCatalogsFitTheLocalBudgetWithoutChangingNodeIds() {
    val nodes = (0 until 300).map { TaskNode("n$it", "Cài đặt phụ đề ".repeat(25), "view-$it", "Button", setOf("click"), false) }
    val screen = TaskScreen(45, "example.player", "Trình phát", "fresh", nodes)
    val apps = (0 until 300).map { TaskApp("app.$it", "Ứng dụng $it") } + TaskApp("chat.zalo", "Zalo")
    val prompt = TaskPrompt.local("Mở Zalo và bật phụ đề", screen, apps, List(12) { "Lịch sử dài ".repeat(40) })
    assertTrue(prompt.toByteArray().size <= TaskPrompt.MAX_LOCAL_BYTES)
    assertTrue(prompt.contains("chat.zalo"))
    val visible = JSONObject(prompt.substringAfter("\nSCREEN: "))
    assertEquals(45L, visible.getLong("snapshot"))
    assertTrue(visible.getBoolean("truncated"))
    val target = visible.getJSONArray("nodes").getJSONObject(0)
    val step = TaskProtocol.parse(JSONObject().put("snapshot", 45).put("action", "click").put("node", target.getString("id")).toString(), screen, apps)
    assertTrue(nodes.any { it.id == step.node })
  }

  @Test(expected = IllegalArgumentException::class)
  fun goalsTooLargeForTheBudgetAreRejectedInsteadOfSilentlyTruncated() {
    TaskPrompt.local("Yêu cầu tiếng Việt ".repeat(1000), TaskScreen(1, "app", "", "fresh", emptyList()), emptyList(), emptyList())
  }
}
