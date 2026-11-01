/*
 * Copyright 2026 SandFly contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.compositor

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.compositor.rule.EventTypeViewClickedFeedbackRule
import com.google.android.accessibility.talkback.scripting.Scripts
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EventId

class ClickFeedback(
  private val compositor: Compositor,
  private val context: Context,
  private val globalVariables: GlobalVariables,
) {

  fun click(target: AccessibilityNodeInfoCompat, eventId: EventId?, click: () -> Boolean): Boolean {
    compositor.giveClickCues(eventId)

    val items = itemsWithState(target)
    val before = items?.let { stateOf(it) }
    val start = SystemClock.uptimeMillis()
    val success = click()
    val sent = SENT.add(start, SystemClock.uptimeMillis(), target.packageName?.toString())
    if (!success || items == null) {
      return success
    }

    if (!items.all { it.refresh() }) {
      return true
    }
    val after = stateOf(items) ?: return true
    if (TextUtils.equals(after.text, before?.text) || Scripts.isSilenced(after.node)) {
      return true
    }
    sent.said = after.text.toString()
    compositor.sayClickState(after.node, after.text, eventId)
    return true
  }

  fun filter(
    type: Int,
    event: AccessibilityEvent,
    source: AccessibilityNodeInfoCompat?,
    feedback: EventFeedback,
  ): EventFeedback {
    if (
      (type != Compositor.EVENT_TYPE_VIEW_CLICKED &&
        type != Compositor.EVENT_TYPE_WINDOW_CONTENT_CHANGED) || SENT.isEmpty()
    ) {
      return feedback
    }
    val time = event.eventTime
    val cuesGiven =
      type == Compositor.EVENT_TYPE_VIEW_CLICKED &&
        SENT.takeClickEvent(time, event.packageName?.toString())
    val tts = feedback.ttsOutput().orElse(null)
    val said =
      !TextUtils.isEmpty(tts) &&
        (SENT.isSaid(time, tts.toString()) ||
          (source != null && SENT.isSaid(time, stateDescriptionOf(source))))
    if (!cuesGiven && !said) {
      return feedback
    }
    val builder = feedback.toBuilder()
    if (cuesGiven) {
      builder.setEarcon(-1).setSecondEarcon(-1).setHaptic(-1)
    }
    if (said) {
      builder.setTtsOutput(java.util.Optional.of(""))
    }
    return builder.build()
  }

  private fun itemsWithState(target: AccessibilityNodeInfoCompat): List<AccessibilityNodeInfoCompat>? {
    val items = ArrayList<AccessibilityNodeInfoCompat>()
    items += copy(target)
    var any = hasState(target)
    for (i in 0 until minOf(target.childCount, MAX_CHILDREN)) {
      val child = target.getChild(i) ?: continue
      if (child.isCheckable || !TextUtils.isEmpty(AccessibilityNodeInfoUtils.getState(child))) {
        items += child
        any = true
      }
    }
    return if (any) items else null
  }

  private class State(val node: AccessibilityNodeInfoCompat, val text: CharSequence)

  private fun stateOf(items: List<AccessibilityNodeInfoCompat>): State? {
    for (node in items) {
      val checked =
        EventTypeViewClickedFeedbackRule.getNodeCheckedStateText(node, context, globalVariables)
      if (!TextUtils.isEmpty(checked)) {
        return State(node, checked)
      }
    }
    val opened = AccessibilityNodeFeedbackUtils.getCollapsedOrExpandedStateText(items[0], context)
    if (!TextUtils.isEmpty(opened)) {
      return State(items[0], opened)
    }
    for (node in items) {
      val description = stateDescriptionOf(node)
      if (!TextUtils.isEmpty(description)) {
        return State(node, description)
      }
    }
    return null
  }

  private fun stateDescriptionOf(node: AccessibilityNodeInfoCompat): String =
    AccessibilityNodeFeedbackUtils.getNodeStateDescription(node, context, globalVariables)
      .toString()

  private fun hasState(node: AccessibilityNodeInfoCompat): Boolean =
    node.isCheckable ||
      AccessibilityNodeInfoUtils.isExpandable(node) ||
      AccessibilityNodeInfoUtils.isCollapsible(node) ||
      !TextUtils.isEmpty(AccessibilityNodeInfoUtils.getState(node))

  @Suppress("DEPRECATION")
  private fun copy(node: AccessibilityNodeInfoCompat) = AccessibilityNodeInfoCompat.obtain(node)

  companion object {

    private const val MAX_CHILDREN = 8

    private val SENT = SentClicks(SystemClock::uptimeMillis)

    @JvmStatic
    fun isSaid(eventTime: Long, stateDescription: CharSequence?): Boolean =
      !TextUtils.isEmpty(stateDescription) && SENT.isSaid(eventTime, stateDescription.toString())

    @JvmStatic
    fun clear() = SENT.clear()
  }
}

class SentClicks(private val clock: () -> Long) {
  class Click(val start: Long, val end: Long, val packageName: String?) {

    var said: String? = null

    var eventTaken = false
  }

  private val clicks = RecentItems<Click>(MAX_CLICKS, MAX_AGE_MS, clock)

  fun add(start: Long, end: Long, packageName: String? = null): Click =
    Click(start, end, packageName).also { clicks.add(it) }

  fun isEmpty(): Boolean = clicks.isEmpty()

  fun takeClickEvent(eventTime: Long, packageName: String?): Boolean {
    val click =
      clicks.find {
        !it.eventTaken &&
          (it.packageName == null || packageName == null || it.packageName == packageName) &&
          eventTime >= it.start - CLOCK_SLACK_MS &&
          eventTime <= it.end + CLICK_EVENT_MS
      } ?: return false
    click.eventTaken = true
    return true
  }

  fun isSaid(eventTime: Long, text: String): Boolean =
    clicks.find {
      val said = it.said
      said != null &&
        eventTime >= it.start - CLOCK_SLACK_MS &&
        eventTime <= it.end + REPORT_MS &&
        said.trim().equals(text.trim(), ignoreCase = true)
    } != null

  fun clear() = clicks.removeAll { true }

  private companion object {

    const val CLOCK_SLACK_MS = 1L

    const val CLICK_EVENT_MS = 1_000L

    const val REPORT_MS = 600L
    const val MAX_CLICKS = 8

    const val MAX_AGE_MS = 10_000L
  }
}
