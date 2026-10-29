package com.google.android.accessibility.talkback.subtitles

import android.graphics.Rect
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SubtitleLine(val text: String, val bounds: Rect)

internal class SubtitleTracker(private val automatic: Boolean) {
  var lockedBounds: Rect? = null
    private set
  var visible = false
    private set
  private var candidateBounds: Rect? = null
  private var candidateFrames = 0
  private var candidateText = ""
  private var textFrames = 0
  private var previousText = ""
  private var previousLines = emptyList<String>()
  private var blankFrames = 0

  fun resize(previous: Rect, next: Rect) {
    lockedBounds = lockedBounds?.let { resizeBounds(it, previous, next) }
    candidateBounds = null
    candidateFrames = 0
  }

  fun reset() {
    lockedBounds = null
    candidateBounds = null
    candidateFrames = 0
    candidateText = ""
    textFrames = 0
    previousText = ""
    previousLines = emptyList()
    blankFrames = 0
    visible = false
  }

  fun observe(input: List<SubtitleLine>, viewport: Rect): String? {
    var lines = input.filter { it.text.isNotBlank() && !it.bounds.isEmpty }
      .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    if (automatic && lockedBounds == null) {
      val group = selectCandidate(lines, viewport)
      if (group.isEmpty()) return emptyFrame()
      val box = union(group)
      val previous = candidateBounds
      candidateFrames = if (previous != null && near(previous, box)) candidateFrames + 1 else 1
      candidateBounds = box
      if (candidateFrames < 2) return null
      val height = max(viewport.height() * 18 / 100, box.height() * 3)
      lockedBounds = Rect(viewport.left + viewport.width() * 4 / 100,
        max(viewport.top, box.centerY() - height / 2),
        viewport.right - viewport.width() * 4 / 100,
        min(viewport.bottom, box.centerY() + height / 2))
    }
    lockedBounds?.let { box -> lines = lines.filter { box.contains(it.bounds.centerX(), it.bounds.centerY()) } }
    if (lines.isEmpty()) return emptyFrame()
    blankFrames = 0
    visible = true
    val text = lines.joinToString("\n") { it.text.trim() }
    val key = normalize(text)
    if (key.isEmpty()) return null
    if (similar(candidateText, key)) {
      textFrames++
    } else {
      candidateText = key
      textFrames = 1
    }
    if (textFrames < 2 || similar(previousText, key)) return null
    val keys = lines.map { normalize(it.text) }
    val fresh = lines.filterIndexed { index, _ -> keys[index] !in previousLines }
    val spoken = if (fresh.isEmpty()) "" else fresh.joinToString(" ") { it.text.trim() }
    val previousWords = previousText.split(' ').filter { it.isNotEmpty() }
    val words = normalize(spoken).split(' ').filter { it.isNotEmpty() }
    val rawWords = spoken.split(Regex("\\s+"))
    var overlap = 0
    if (fresh.size == lines.size) {
      for (count in 2..min(previousWords.size, words.size)) {
        if (previousWords.takeLast(count) == words.take(count)) overlap = count
      }
    }
    previousText = key
    previousLines = keys
    return (if (overlap > 0 && rawWords.size == words.size) rawWords.drop(overlap).joinToString(" ") else spoken)
      .takeIf { it.isNotBlank() }
  }

  private fun emptyFrame(): String? {
    visible = false
    candidateText = ""
    textFrames = 0
    candidateFrames = 0
    candidateBounds = null
    if (++blankFrames >= 2) {
      previousText = ""
      previousLines = emptyList()
    }
    return null
  }

  private fun selectCandidate(lines: List<SubtitleLine>, screen: Rect): List<SubtitleLine> {
    val eligible = lines.filter { line ->
      val text = normalize(line.text)
      text.length >= 5 && text.any { it.isLetter() } &&
        line.bounds.height() <= screen.height() / 8 &&
        abs(line.bounds.centerX() - screen.centerX()) < screen.width() * 35 / 100
    }
    val groups = mutableListOf<MutableList<SubtitleLine>>()
    for (line in eligible) {
      val group = groups.lastOrNull()
      val last = group?.lastOrNull()
      if (group != null && last != null && line.bounds.top - last.bounds.bottom <= max(line.bounds.height(), last.bounds.height()) &&
        union(group).height() + line.bounds.height() <= screen.height() / 4) {
        group.add(line)
      } else {
        groups.add(mutableListOf(line))
      }
    }
    return groups.maxByOrNull { group ->
      val box = union(group)
      val lower = if (box.centerY() > screen.top + screen.height() * 55 / 100) 30 else 0
      lower + min(40, group.sumOf { it.text.length }) - abs(box.centerX() - screen.centerX()) * 20 / max(1, screen.width())
    } ?: emptyList()
  }

  private fun union(lines: List<SubtitleLine>): Rect = Rect(lines.first().bounds).also { box ->
    lines.drop(1).forEach { box.union(it.bounds) }
  }

  private fun near(a: Rect, b: Rect): Boolean =
    abs(a.centerY() - b.centerY()) <= max(a.height(), b.height()) &&
      abs(a.centerX() - b.centerX()) <= max(a.width(), b.width()) / 2

  companion object {
    internal fun resizeBounds(box: Rect, old: Rect, next: Rect): Rect = Rect(
      next.left + (box.left - old.left) * next.width() / max(1, old.width()),
      next.top + (box.top - old.top) * next.height() / max(1, old.height()),
      next.left + (box.right - old.left) * next.width() / max(1, old.width()),
      next.top + (box.bottom - old.top) * next.height() / max(1, old.height()))

    internal fun normalize(text: String): String =
      Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    internal fun similar(a: String, b: String): Boolean {
      if (a == b) return true
      if (a.length < 24 || b.length < 24 || abs(a.length - b.length) > 1) return false
      var left = 0
      var right = 0
      var errors = 0
      while (left < a.length && right < b.length) {
        if (a[left] == b[right]) { left++; right++; continue }
        if (++errors > 1) return false
        if (a.length >= b.length) left++
        if (b.length >= a.length) right++
      }
      return errors + (a.length - left) + (b.length - right) <= 1
    }
  }
}
