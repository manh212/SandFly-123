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
package com.google.android.accessibility.talkback.review

class ReviewItem(
  val text: String,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val source: Any? = null,
) {
  val centerY: Int
    get() = (top + bottom) / 2

  fun splitLines(): List<ReviewItem> {
    val lines = text.split('\n').map { it.trim() }
    if (lines.size == 1) {
      return if (lines[0] == text) listOf(this)
      else listOf(ReviewItem(lines[0], left, top, right, bottom, source))
    }
    val height = (bottom - top).coerceAtLeast(lines.size)
    return lines.mapIndexedNotNull { index, line ->
      if (line.isEmpty()) {
        null
      } else {
        ReviewItem(
          line,
          left,
          top + height * index / lines.size,
          right,
          top + height * (index + 1) / lines.size,
          source,
        )
      }
    }
  }
}

class ReviewWord(
  val text: String,

  val start: Int,
  val item: ReviewItem,
  val left: Int,
  val right: Int,
) {
  val end: Int
    get() = start + text.length

  val centerX: Int
    get() = (left + right) / 2

  val centerY: Int
    get() = item.centerY
}

class ReviewLine(val items: List<ReviewItem>) {
  val text: String
  val words: List<ReviewWord>

  init {
    val builder = StringBuilder()
    val words = mutableListOf<ReviewWord>()
    for (item in items) {
      if (builder.isNotEmpty()) builder.append(' ')
      val itemStart = builder.length
      builder.append(item.text)
      val width = item.right - item.left
      val length = item.text.length.coerceAtLeast(1)
      for (match in WORD.findAll(item.text)) {
        val range = match.range
        words.add(
          ReviewWord(
            match.value,
            itemStart + range.first,
            item,
            item.left + width * range.first / length,
            item.left + width * (range.last + 1) / length,
          )
        )
      }
    }
    text = builder.toString()
    this.words = words
  }

  fun wordAt(offset: Int): ReviewWord? =
    words.firstOrNull { offset < it.end } ?: words.lastOrNull()

  private companion object {
    val WORD = Regex("\\S+")
  }
}

class ReviewText(items: List<ReviewItem>) {
  val lines: List<ReviewLine>

  init {
    val sorted =
      items
        .flatMap { it.splitLines() }
        .filter { it.text.isNotBlank() }
        .sortedWith(compareBy({ it.top }, { it.left }))
    val lines = mutableListOf<MutableList<ReviewItem>>()
    for (item in sorted) {

      val band = lines.lastOrNull()?.minByOrNull { it.bottom - it.top }
      if (band != null && item.centerY in band.top..band.bottom && band.centerY in item.top..item.bottom) {
        lines.last().add(item)
      } else {
        lines.add(mutableListOf(item))
      }
    }
    this.lines = lines.map { line -> ReviewLine(line.sortedBy { it.left }) }
  }

  val isEmpty: Boolean
    get() = lines.isEmpty()
}

class ReviewCursor(val text: ReviewText, line: Int = 0, offset: Int = 0) {
  var line: Int = line.coerceIn(0, (text.lines.size - 1).coerceAtLeast(0))
    private set

  var offset: Int = offset
    private set

  val currentLine: ReviewLine?
    get() = text.lines.getOrNull(line)

  val currentWord: ReviewWord?
    get() = currentLine?.wordAt(offset)

  val currentCharacter: Char?
    get() = currentLine?.text?.getOrNull(offset)

  fun moveLine(delta: Int): Boolean {
    val target = line + delta
    if (target !in text.lines.indices) return false
    line = target
    offset = 0
    return true
  }

  fun moveToTop(): Boolean = moveToLine(0)

  fun moveToBottom(): Boolean = moveToLine(text.lines.size - 1)

  private fun moveToLine(target: Int): Boolean {
    if (target !in text.lines.indices) return false
    line = target
    offset = 0
    return true
  }

  fun moveToLineStart() {
    offset = 0
  }

  fun moveToLineEnd() {
    offset = ((currentLine?.text?.length ?: 0) - 1).coerceAtLeast(0)
  }

  fun moveCharacter(forward: Boolean): Boolean {
    val length = currentLine?.text?.length ?: return false
    if (forward) {
      if (offset + 1 < length) {
        offset++
        return true
      }
      return moveLine(1)
    }
    if (offset > 0) {
      offset--
      return true
    }
    if (!moveLine(-1)) return false
    moveToLineEnd()
    return true
  }

  fun moveWord(forward: Boolean): Boolean {
    val words = currentLine?.words ?: return false
    if (forward) {
      words.firstOrNull { it.start > offset }?.let {
        offset = it.start
        return true
      }
      var target = line + 1
      while (target in text.lines.indices && text.lines[target].words.isEmpty()) target++
      if (target !in text.lines.indices) return false
      line = target
      offset = text.lines[target].words.first().start
      return true
    }

    val anchor = words.firstOrNull { offset in it.start until it.end }?.start ?: offset
    words.lastOrNull { it.start < anchor }?.let {
      offset = it.start
      return true
    }
    var target = line - 1
    while (target >= 0 && text.lines[target].words.isEmpty()) target--
    if (target < 0) return false
    line = target
    offset = text.lines[target].words.last().start
    return true
  }

  fun moveNear(x: Int, y: Int): Boolean {
    var best: Pair<Int, ReviewWord>? = null
    var bestDistance = Long.MAX_VALUE
    text.lines.forEachIndexed { index, reviewLine ->
      for (word in reviewLine.words) {
        val dx = (word.centerX - x).toLong()
        val dy = (word.centerY - y).toLong()

        val distance = dy * dy * 4 + dx * dx
        if (distance < bestDistance) {
          bestDistance = distance
          best = index to word
        }
      }
    }
    val (index, word) = best ?: return false
    line = index
    offset = word.start
    return true
  }

  fun moveToSource(matches: (Any?) -> Boolean): Boolean {
    text.lines.forEachIndexed { index, reviewLine ->
      reviewLine.words.firstOrNull { matches(it.item.source) }?.let {
        line = index
        offset = it.start
        return true
      }
    }
    return false
  }
}
