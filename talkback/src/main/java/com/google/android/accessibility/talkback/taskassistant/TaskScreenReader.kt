package com.google.android.accessibility.talkback.taskassistant

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class CapturedTaskScreen(val screen: TaskScreen, val windowId: Int, val handles: Map<String, AccessibilityNodeInfoCompat>)

class TaskScreenReader(private val service: AccessibilityService) {
  private val sequence = AtomicLong()

  fun read(): CapturedTaskScreen {
    val windows = service.windows.orEmpty().filter {
      it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }
    val window = windows.firstOrNull { it.isActive } ?: windows.firstOrNull { it.isFocused }
      ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
      ?: error("Không có cửa sổ ứng dụng")
    val root = window.root?.let(AccessibilityNodeInfoCompat::wrap) ?: error("Ứng dụng chưa cung cấp nội dung màn hình")
    val nodes = mutableListOf<TaskNode>()
    val handles = LinkedHashMap<String, AccessibilityNodeInfoCompat>()
    val pending = ArrayDeque(listOf(root))
    var visited = 0
    val deadline = SystemClock.uptimeMillis() + 2500
    while (pending.isNotEmpty() && visited++ < 1800 && nodes.size < 300 && SystemClock.uptimeMillis() < deadline) {
      val node = pending.removeFirst()
      if (!node.isVisibleToUser) continue
      if (!isPrivateInput(node)) {
        val actions = linkedSetOf<String>()
        val supported = node.actionList.map { it.id }.toSet()
        if (node.isEnabled) {
          if (AccessibilityNodeInfo.ACTION_CLICK in supported || node.isClickable) actions += "click"
          if (AccessibilityNodeInfo.ACTION_LONG_CLICK in supported || node.isLongClickable) actions += "long_click"
          if (AccessibilityNodeInfo.ACTION_SET_TEXT in supported && node.isEditable) actions += "set_text"
          if (AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_IME_ENTER.id in supported) actions += "press_enter"
          if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in supported) actions += "scroll_forward"
          if (AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in supported) actions += "scroll_backward"
        }
        val label = label(node, actions.isNotEmpty())
        if (label.isNotBlank() || actions.isNotEmpty()) {
          val id = "n${nodes.size}"
          nodes += TaskNode(id, label, node.viewIdResourceName.orEmpty(), node.className?.toString()?.substringAfterLast('.').orEmpty(),
            actions, if (node.isCheckable) node.isChecked else null)
          handles[id] = node
        }
      }
      if (!isPrivateInput(node)) for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
    }
    val packageName = root.packageName?.toString() ?: error("Không xác định được ứng dụng")
    val title = window.title?.toString().orEmpty()
    val signature = "$packageName:${window.id}:$title:${nodes.joinToString { it.json().toString() }}"
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    return CapturedTaskScreen(TaskScreen(sequence.incrementAndGet(), packageName, title, fingerprint, nodes, pending.isNotEmpty()), window.id, handles)
  }

  private fun isPrivateInput(node: AccessibilityNodeInfoCompat): Boolean = node.isPassword ||
    (node.isEditable && TaskProtocol.isCredentialRequest(listOfNotNull(node.hintText, node.contentDescription,
      node.viewIdResourceName).joinToString(" ")))

  private fun label(node: AccessibilityNodeInfoCompat, includeDescendants: Boolean): String =
    listOfNotNull(node.text?.toString(), node.contentDescription?.toString(), node.stateDescription?.toString(), node.hintText?.toString())
      .filter { it.isNotBlank() }.distinct().joinToString("; ").take(400)
      .ifEmpty { if (includeDescendants) descendantLabel(node) else "" }

  private fun descendantLabel(root: AccessibilityNodeInfoCompat): String {
    val parts = mutableListOf<String>()
    val queue = ArrayDeque<AccessibilityNodeInfoCompat>()
    for (i in 0 until root.childCount.coerceAtMost(12)) root.getChild(i)?.let(queue::addLast)
    var count = 0
    while (queue.isNotEmpty() && count++ < 24) {
      val item = queue.removeFirst()
      if (!item.isVisibleToUser || isPrivateInput(item)) continue
      (item.text ?: item.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(parts::add)
      for (i in 0 until item.childCount.coerceAtMost(8)) item.getChild(i)?.let(queue::addLast)
    }
    return parts.distinct().joinToString("; ").take(400)
  }

  fun perform(capture: CapturedTaskScreen, step: TaskStep): Boolean {
    val node = capture.handles[step.node] ?: return false
    if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || isPrivateInput(node) || node.windowId != capture.windowId ||
      node.packageName?.toString() != capture.screen.packageName) return false
    val expected = capture.screen.nodes.firstOrNull { it.id == step.node } ?: return false
    if (label(node, expected.actions.isNotEmpty()) != expected.label ||
      node.viewIdResourceName.orEmpty() != expected.viewId ||
      (if (node.isCheckable) node.isChecked else null) != expected.checked) return false
    val id = when (step.operation) {
      TaskOperation.CLICK -> AccessibilityNodeInfo.ACTION_CLICK
      TaskOperation.LONG_CLICK -> AccessibilityNodeInfo.ACTION_LONG_CLICK
      TaskOperation.SET_TEXT -> AccessibilityNodeInfo.ACTION_SET_TEXT
      TaskOperation.PRESS_ENTER -> AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_IME_ENTER.id
      TaskOperation.SCROLL_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
      TaskOperation.SCROLL_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
      else -> return false
    }
    val args = if (step.operation == TaskOperation.SET_TEXT) android.os.Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text)
    } else null
    return node.performAction(id, args)
  }
}
