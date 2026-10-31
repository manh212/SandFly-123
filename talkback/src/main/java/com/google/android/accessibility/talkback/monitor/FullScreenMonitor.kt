/*
 * Copyright 2026 SandFly contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.android.accessibility.talkback.monitor

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import java.util.ArrayDeque

/**
 * An opt-in spoken monitor of changes Android exposes through its accessibility tree.
 * Foreground app changes only. Does not record, upload, or store captured message content.
 */
object FullScreenMonitor {
  const val KEY_APPS = "pref_full_screen_monitor_apps"
  const val KEY_MANUAL = "pref_full_screen_monitor_manual"

  private const val MAX_SNAPSHOT = 100
  private const val MAX_RECENT = 200
  private val recentByApp = mutableMapOf<String, LinkedHashSet<String>>()

  @JvmStatic
  fun enabled(prefs: SharedPreferences, packageName: String): Boolean =
    prefs.getBoolean(KEY_MANUAL, false) ||
      prefs.getStringSet(KEY_APPS, emptySet()).orEmpty().contains(packageName)

  /** Temporarily enable or disable monitoring in all apps; per-app opt-ins remain in effect. */
  @JvmStatic
  fun toggle(context: Context): Boolean {
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val enabled = !prefs.getBoolean(KEY_MANUAL, false)
    prefs.edit().putBoolean(KEY_MANUAL, enabled).apply()
    recentByApp.clear()
    return enabled
  }

  @JvmStatic
  fun onEvent(context: Context, event: AccessibilityEvent, pipeline: Pipeline.FeedbackReturner) {
    val pkg = event.packageName?.toString() ?: return
    if (pkg == context.packageName || pkg == "com.android.systemui") return
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    if (!enabled(prefs, pkg)) return
    if (event.isPassword) return
    val eventType = event.eventType
    if (
      eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
        eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
        eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
        eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
        eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    ) return
    val source = event.source
    if (source?.isPassword == true || source?.isEditable == true) return

    val texts = ArrayList<String>()
    for (part in event.text) {
      val value = part?.toString()?.trim().orEmpty()
      if (value.isNotEmpty()) texts.add(value)
    }
    if (source != null) {
      collectAccessibleText(source, texts)
    }
    val latest = recentByApp.getOrPut(pkg) { LinkedHashSet() }
    val unseen = texts.distinct().filter { it.isNotBlank() && it !in latest }
    texts.forEach {
      if (it.isNotBlank()) latest.add(it)
    }
    while (latest.size > MAX_RECENT) {
      val oldest = latest.firstOrNull() ?: break
      latest.remove(oldest)
    }
    if (unseen.isEmpty()) return
    val priority =
      eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
        (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && unseen.size > 1) ||
        unseen.any {
          it.contains("comment", ignoreCase = true) ||
            it.contains("bình luận", ignoreCase = true) ||
            it.contains("tin nhắn", ignoreCase = true) ||
            it.contains("message", ignoreCase = true)
        }
    val spoken = unseen.takeLast(if (priority) 3 else 2).joinToString(". ").take(300)
    if (spoken.isBlank()) return
    pipeline.returnFeedback(
      EVENT_ID_UNTRACKED,
      Feedback.speech(
        spoken,
        SpeakOptions.create()
          .setQueueMode(if (priority) SpeechController.QUEUE_MODE_INTERRUPT else SpeechController.QUEUE_MODE_QUEUE)
          .setFlags(
            FeedbackItem.FLAG_NO_HISTORY or
              FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE
          ),
      ),
    )
  }

  /** Extract leaf text in visual order. Avoid echoing typed input and password fields. */
  private fun collectAccessibleText(root: AccessibilityNodeInfo, output: MutableList<String>) {
    val nodes = ArrayDeque<AccessibilityNodeInfo>()
    nodes.add(root)
    var processed = 0
    while (nodes.isNotEmpty() && processed++ < MAX_SNAPSHOT) {
      val node = nodes.removeFirst()
      if (node.isPassword || node.isEditable || !node.isVisibleToUser) continue
      if (node.childCount == 0) {
        val content = node.contentDescription?.toString()?.trim().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val value = if (content.isNotBlank()) content else text
        if (value.isNotBlank() && value.length <= 300) output.add(value)
      } else {
        for (i in 0 until node.childCount) {
          node.getChild(i)?.let { nodes.add(it) }
        }
      }
    }
  }
}
