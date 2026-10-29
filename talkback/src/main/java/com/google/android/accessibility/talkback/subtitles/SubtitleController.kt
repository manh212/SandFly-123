package com.google.android.accessibility.talkback.subtitles

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.imagecaption.TesseractOcr
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.screencapture.ScreenshotCapture
import com.google.android.libraries.accessibility.utils.screencapture.ScreenCaptureController.CaptureListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

internal interface SubtitleSpeaker {
  fun isBusy(): Boolean
  fun speak(text: String, interrupt: Boolean)
  fun stop()
}

class SubtitleController internal constructor(
  private val service: AccessibilityService,
  private val announce: (String) -> Unit,
  private val speaker: SubtitleSpeaker,
) {
  constructor(service: TalkBackService, announce: (String) -> Unit) : this(service, announce, object : SubtitleSpeaker {
    override fun isBusy(): Boolean = service.speechController.isSpeakingOrSpeechQueued
    override fun stop() = service.speechController.stopUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES)
    override fun speak(text: String, interrupt: Boolean) {
      var flags = FeedbackItem.FLAG_FORCE_FEEDBACK_EVEN_IF_AUDIO_PLAYBACK_ACTIVE or
        FeedbackItem.FLAG_CLEAR_QUEUED_UTTERANCES_WITH_SAME_UTTERANCE_GROUP
      if (interrupt) flags = flags or FeedbackItem.FLAG_INTERRUPT_CURRENT_UTTERANCE_WITH_SAME_UTTERANCE_GROUP
      service.speechController.speak(text, Performance.EVENT_ID_UNTRACKED,
        SpeechController.SpeakOptions.create().setQueueMode(SpeechController.QUEUE_MODE_QUEUE)
          .setUtteranceGroup(SpeechController.UTTERANCE_GROUP_SUBTITLES).setFlags(flags))
    }
  })
  private val handler = Handler(Looper.getMainLooper())
  private val prefs = SharedPreferencesUtils.getSharedPreferences(service)
  private val worker = Executors.newSingleThreadExecutor { task ->
    Thread(task, "subtitle-ocr").apply { isDaemon = true }
  }
  private var recognizer: TextRecognizer? = null
  private var active = false
  private var generation = 0
  private var owner = ""
  private var viewport = Rect()
  private var focus: Rect? = null
  private var tracker = SubtitleTracker(true)
  private var capturePending = false
  private var failures = 0
  private var pendingText: String? = null
  private var pendingAt = 0L
  private var quietUntil = 0L
  private var receiverRegistered = false
  private var closed = false
  private val capture = Runnable { captureFrame() }
  private val speakPending = object : Runnable {
    override fun run() {
      if (!active || pendingText == null) return
      val now = SystemClock.uptimeMillis()
      if (now - pendingAt > 4000L || !tracker.visible) {
        pendingText = null
        return
      }
      if (now < quietUntil || speaker.isBusy()) {
        handler.postDelayed(this, 100L)
        return
      }
      deliverPending()
    }
  }
  private val screenReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_SCREEN_OFF) stop(false)
    }
  }

  fun toggle() {
    if (active) { stop(true); return }
    if (closed) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      announce(service.getString(R.string.subtitle_requires_android))
      return
    }
    if (SubtitleSettings.engine(prefs) == "tesseract" && !TesseractOcr.isDataDownloaded(service)) {
      announce(service.getString(R.string.subtitle_model_missing))
      return
    }
    val root = service.rootInActiveWindow
    if (root == null) { announce(service.getString(R.string.subtitle_no_window)); return }
    try {
      owner = root.packageName?.toString().orEmpty()
      root.getBoundsInScreen(viewport)
      focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
        try { Rect().also { node.getBoundsInScreen(it) } } finally { node.recycle() }
      }
    } finally { root.recycle() }
    if (owner.isEmpty() || viewport.isEmpty) {
      announce(service.getString(R.string.subtitle_no_window))
      return
    }
    if (SubtitleSettings.bounds(prefs, viewport, focus) == null) {
      announce(service.getString(R.string.subtitle_no_focus))
      return
    }
    tracker = SubtitleTracker(SubtitleSettings.region(prefs) == "auto")
    active = true
    generation++
    failures = 0
    quietUntil = SystemClock.uptimeMillis() + 1000L
    ContextCompat.registerReceiver(service, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    receiverRegistered = true
    announce(service.getString(R.string.subtitle_started))
    schedule()
  }

  fun findAgain() {
    if (!active) { announce(service.getString(R.string.subtitle_not_running)); return }
    generation++
    tracker.reset()
    pendingText = null
    handler.removeCallbacks(speakPending)
    speaker.stop()
    announce(service.getString(R.string.subtitle_searching))
    schedule()
  }

  fun stop(speak: Boolean) {
    val wasActive = active
    active = false
    generation++
    handler.removeCallbacks(capture)
    handler.removeCallbacks(speakPending)
    pendingText = null
    tracker.reset()
    if (receiverRegistered) {
      service.unregisterReceiver(screenReceiver)
      receiverRegistered = false
    }
    if (wasActive) {
      speaker.stop()
      if (speak) announce(service.getString(R.string.subtitle_stopped))
    }
  }

  fun shutdown() {
    if (closed) return
    stop(false)
    closed = true
    worker.execute { recognizer?.close(); recognizer = null }
    worker.shutdown()
  }

  fun onUserInput() {
    if (!active) return
    quietUntil = SystemClock.uptimeMillis() + 1200L
    pendingText = null
    handler.removeCallbacks(speakPending)
    speaker.stop()
  }

  fun onAccessibilityEvent(event: AccessibilityEvent) {
    if (!active) return
    if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) onUserInput()
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      if (!sameApplication()) stop(false)
    }
  }

  private fun sameApplication(): Boolean {
    val root = service.rootInActiveWindow ?: return false
    return try {
      if (root.packageName?.toString() != owner) false else {
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        if (!bounds.isEmpty && bounds != viewport) {
          generation++
          tracker.resize(viewport, bounds)
          focus = focus?.let { SubtitleTracker.resizeBounds(it, viewport, bounds) }
          viewport = bounds
        }
        true
      }
    } finally { root.recycle() }
  }

  private fun schedule() {
    handler.removeCallbacks(capture)
    if (active && !capturePending) handler.postDelayed(capture, SubtitleSettings.interval(prefs))
  }

  private fun captureFrame() {
    if (!active || closed || capturePending) return
    if (service.getSystemService(PowerManager::class.java)?.isInteractive != true || !sameApplication()) {
      stop(false)
      return
    }
    val request = generation
    capturePending = true
    try {
      ScreenshotCapture.takeScreenshot(service, object : CaptureListener {
        override fun onScreenCaptureFinished(bitmap: Bitmap?, isFormatSupported: Boolean) {
          if (!active || request != generation || !sameApplication()) {
            bitmap?.recycle()
            capturePending = false
            if (active && request == generation) stop(false) else schedule()
            return
          }
          if (request != generation) {
            bitmap?.recycle()
            capturePending = false
            schedule()
            return
          }
          if (bitmap == null || !isFormatSupported) {
            bitmap?.recycle()
            complete(request, null)
            return
          }
          val region = Rect(tracker.lockedBounds ?: SubtitleSettings.bounds(prefs, viewport, focus) ?: viewport)
          if (!region.intersect(0, 0, bitmap.width, bitmap.height) || region.isEmpty) {
            bitmap.recycle()
            complete(request, null)
            return
          }
          val cropped = try { Bitmap.createBitmap(bitmap, region.left, region.top, region.width(), region.height()) }
            catch (e: RuntimeException) { bitmap.recycle(); complete(request, null); return }
          if (cropped !== bitmap) bitmap.recycle()
          val controls = if (SubtitleSettings.region(prefs) == "auto" && tracker.lockedBounds == null) controlBounds() else emptyList()
          worker.execute { recognize(cropped, region, controls, request) }
        }
      })
    } catch (e: RuntimeException) {
      complete(request, null)
    }
  }

  private fun recognize(bitmap: Bitmap, region: Rect, controls: List<Rect>, request: Int) {
    if (SubtitleSettings.engine(prefs) == "tesseract") {
      val lines = try {
        TesseractOcr.recognizeSegments(service, bitmap)?.map { SubtitleLine(it.text, Rect(it.bounds).apply { offset(region.left, region.top) }) }
      } catch (e: RuntimeException) { null }
      finally { bitmap.recycle() }
      handler.post { complete(request, lines?.filterNot { line -> controls.any { it.contains(line.bounds.centerX(), line.bounds.centerY()) } }) }
    } else {
      try {
        val engine = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        engine.process(InputImage.fromBitmap(bitmap, 0))
          .addOnSuccessListener { text ->
            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
              line.boundingBox?.let { SubtitleLine(line.text, Rect(it).apply { offset(region.left, region.top) }) }
            }.filterNot { line -> controls.any { it.contains(line.bounds.centerX(), line.bounds.centerY()) } }
            complete(request, lines)
          }
          .addOnFailureListener { complete(request, null) }
          .addOnCompleteListener { bitmap.recycle() }
      } catch (e: RuntimeException) {
        bitmap.recycle()
        handler.post { complete(request, null) }
      }
    }
  }

  private fun complete(request: Int, lines: List<SubtitleLine>?) {
    capturePending = false
    if (!active || request != generation) { schedule(); return }
    if (!sameApplication()) { stop(false); return }
    if (request != generation) { schedule(); return }
    if (lines == null) {
      if (++failures >= 3) {
        stop(false)
        announce(service.getString(R.string.subtitle_failed))
        return
      }
    } else {
      failures = 0
      val text = tracker.observe(lines, viewport)
      if (!tracker.visible) {
        pendingText = null
        if (SubtitleSettings.interrupt(prefs)) speaker.stop()
      }
      val pending = pendingText
      if (pending != null && SubtitleTracker.normalize(lines.joinToString(" ") { it.text }).contains(SubtitleTracker.normalize(pending))) {
        pendingAt = SystemClock.uptimeMillis()
      }
      if (text != null) {
        pendingText = text
        pendingAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(speakPending)
        if (SubtitleSettings.interrupt(prefs) && SystemClock.uptimeMillis() >= quietUntil) {
          deliverPending()
        } else handler.post(speakPending)
      }
    }
    schedule()
  }

  private fun deliverPending() {
    val text = pendingText ?: return
    pendingText = null
    if (!active || !sameApplication()) { stop(false); return }
    speaker.speak(text, SubtitleSettings.interrupt(prefs))
  }

  private fun controlBounds(): List<Rect> {
    val root = service.rootInActiveWindow ?: return emptyList()
    val boxes = mutableListOf<Rect>()
    val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var visited = 0
    val deadline = SystemClock.uptimeMillis() + 30L
    try {
      while (queue.isNotEmpty() && visited++ < 250 && SystemClock.uptimeMillis() < deadline) {
        val node = queue.removeFirst()
        try {
          if (node.isVisibleToUser && (node.isClickable || node.isEditable)) {
            val box = Rect().also { node.getBoundsInScreen(it) }
            if (box.width().toLong() * box.height() < viewport.width().toLong() * viewport.height() / 10) boxes.add(box)
          }
          for (index in 0 until minOf(node.childCount, 250 - visited - queue.size)) node.getChild(index)?.let { queue.add(it) }
        } finally { node.recycle() }
      }
    } finally { while (queue.isNotEmpty()) queue.removeFirst().recycle() }
    return boxes
  }
}
