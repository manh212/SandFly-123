/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.gesture

import android.graphics.Rect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Role
import java.util.ArrayDeque
import java.util.Locale

object SendButtonFinder {

  private const val MAX_NODES = 500

  private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 3

  private const val MAX_LABEL_LENGTH = 50

  private val SEND_ID_WORDS = setOf("send", "toot")

  @JvmStatic
  fun find(
    field: AccessibilityNodeInfoCompat,
    sendWords: Collection<String>,
  ): AccessibilityNodeInfoCompat? {
    var root = field.parent ?: return null
    repeat(2) { root.parent?.let { parent -> root = parent } }
    val fieldBounds = Rect().also { field.getBoundsInScreen(it) }
    var best: Candidate? = null
    var ambiguous = false
    val queue = ArrayDeque<AccessibilityNodeInfoCompat>().apply { add(root) }
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_NODES) {
      val node = queue.removeFirst()
      visited++
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { queue.add(it) }
      }
      val strength = matchStrength(node, sendWords)
      if (strength != Strength.STRONG || node.isEditable || !node.isVisibleToUser) {
        continue
      }
      val target = clickTarget(node) ?: continue
      if (!target.isEnabled || target == field) {
        continue
      }
      val bounds = Rect().also { target.getBoundsInScreen(it) }

      if (bounds.contains(fieldBounds)) {
        continue
      }
      val sameRow = bounds.top < fieldBounds.bottom && bounds.bottom > fieldBounds.top
      if (!sameRow || bounds.isEmpty || fieldBounds.isEmpty) continue
      val candidate = Candidate(target, sameRow, strength, distance(bounds, fieldBounds))
      val previous = best
      if (previous == null || candidate.isBetterThan(previous)) {
        best = candidate
        ambiguous = false
      } else if (!previous.isBetterThan(candidate) && previous.node != candidate.node) {
        ambiguous = true
      }
    }
    return if (ambiguous) null else best?.node
  }

  @JvmStatic
  fun isSendViewId(viewId: String?): Boolean {
    if (viewId.isNullOrEmpty()) {
      return false
    }

    val name = viewId.substringAfter(":id/")
    return name
      .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
      .lowercase(Locale.ROOT)
      .split(Regex("[^a-z0-9]+"))
      .any { it in SEND_ID_WORDS }
  }

  private fun isButton(node: AccessibilityNodeInfoCompat): Boolean {
    val role = Role.getRole(node)
    return role == Role.ROLE_BUTTON || role == Role.ROLE_IMAGE_BUTTON
  }

  @JvmStatic
  fun labelStrength(label: CharSequence?, sendWords: Collection<String>): Strength {
    if (label.isNullOrBlank() || label.length > MAX_LABEL_LENGTH) {
      return Strength.NONE
    }
    val text = label.toString().trim().lowercase()
    var strength = Strength.NONE
    for (word in sendWords) {
      val sendWord = word.trim().lowercase()
      if (sendWord.isEmpty()) {
        continue
      }
      if (text == sendWord) {
        return Strength.STRONG
      }
      if (text.startsWith("$sendWord ")) {
        strength = Strength.WEAK
      }
    }
    return strength
  }

  private fun matchStrength(
    node: AccessibilityNodeInfoCompat,
    sendWords: Collection<String>,
  ): Strength {
    if (isSendViewId(node.viewIdResourceName)) {
      return Strength.STRONG
    }
    return maxOf(
      labelStrength(node.contentDescription, sendWords),
      labelStrength(node.text, sendWords),
      labelStrength(node.tooltipText, sendWords),
    )
  }

  private fun clickTarget(node: AccessibilityNodeInfoCompat): AccessibilityNodeInfoCompat? {
    var current: AccessibilityNodeInfoCompat? = node
    for (depth in 0..MAX_CLICKABLE_ANCESTOR_DEPTH) {
      val candidate = current ?: return null
      if (candidate.isClickable) {
        return candidate
      }
      current = candidate.parent
    }
    return null
  }

  private fun distance(a: Rect, b: Rect): Long {
    val dx = maxOf(0, maxOf(a.left, b.left) - minOf(a.right, b.right)).toLong()
    val dy = maxOf(0, maxOf(a.top, b.top) - minOf(a.bottom, b.bottom)).toLong()
    return dx * dx + dy * dy
  }

  enum class Strength {
    NONE,
    WEAK,
    STRONG,
  }

  private class Candidate(
    val node: AccessibilityNodeInfoCompat,
    val sameRow: Boolean,
    val strength: Strength,
    val distance: Long,
  ) {
    fun isBetterThan(other: Candidate): Boolean {
      if (sameRow != other.sameRow) {
        return sameRow
      }
      if (distance != other.distance) {
        return distance < other.distance
      }
      return strength > other.strength
    }
  }
}
