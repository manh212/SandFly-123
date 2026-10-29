package com.google.android.accessibility.talkback.subtitles

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SubtitleControllerTest {
  class TestService : AccessibilityService() {
    var foregroundPackage = "video.app"
    override fun getRootInActiveWindow(): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
      packageName = foregroundPackage
      setBoundsInScreen(Rect(0, 0, 1000, 1000))
      ReflectionHelpers.callInstanceMethod<Any>(
        this, "setSealed", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}
  }
  private class Speaker : SubtitleSpeaker {
    var busy = true
    val spoken = mutableListOf<Pair<String, Boolean>>()
    var stops = 0
    override fun isBusy() = busy
    override fun speak(text: String, interrupt: Boolean) { spoken += text to interrupt }
    override fun stop() { stops++ }
  }
  private lateinit var service: TestService
  private lateinit var controller: SubtitleController
  private lateinit var speaker: Speaker
  private val announcements = mutableListOf<String>()

  @Before fun setUp() {
    service = Robolectric.setupService(TestService::class.java)
    SharedPreferencesUtils.getSharedPreferences(service).edit().clear()
      .putString(SubtitleSettings.ENGINE, "mlkit").putString(SubtitleSettings.REGION, "full").commit()
    speaker = Speaker()
    controller = SubtitleController(service, { announcements += it }, speaker)
    controller.toggle()
    assertTrue(get("active") as Boolean)
    set("quietUntil", 0L)
    handlerRemoveCaptures()
  }

  @After fun tearDown() { controller.shutdown() }

  @Test fun changingAppsStopsCompletelyAndComingBackDoesNotRestart() {
    service.foregroundPackage = "other.app"
    controller.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
    assertFalse(get("active") as Boolean)
    assertEquals(1, speaker.stops)
    service.foregroundPackage = "video.app"
    controller.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
    assertFalse(get("active") as Boolean)
  }

  @Test fun aLateOcrResultCannotSpeakAfterStopOrAfterRestart() {
    val request = get("generation") as Int
    controller.stop(false)
    controller.toggle()
    set("quietUntil", 0L)
    frame(request, "Kết quả cũ không được đọc")
    frame(request, "Kết quả cũ không được đọc")
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(speaker.spoken.isEmpty())
  }

  @Test fun finishingModeKeepsOnlyTheNewestPendingSubtitle() {
    val request = get("generation") as Int
    repeat(2) { frame(request, "Phụ đề đầu tiên đang đợi") }
    repeat(2) { frame(request, "Nội dung mới thay thế câu cũ") }
    speaker.busy = false
    handlerRemoveCaptures()
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110))
    assertEquals(listOf("Nội dung mới thay thế câu cũ" to false), speaker.spoken)
  }

  @Test fun interruptModePassesTheSelectedPolicyAndTouchStopsOnlySubtitleSpeech() {
    SharedPreferencesUtils.getSharedPreferences(service).edit().putString(SubtitleSettings.SPEECH, "interrupt").commit()
    val request = get("generation") as Int
    repeat(2) { frame(request, "Câu mới cần đọc ngay") }
    assertEquals(listOf("Câu mới cần đọc ngay" to true), speaker.spoken)
    controller.onUserInput()
    assertEquals(1, speaker.stops)
    assertTrue(get("active") as Boolean)
  }

  @Test fun screenOffStopsTheSession() {
    service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(get("active") as Boolean)
  }

  private fun frame(request: Int, text: String) {
    val method = SubtitleController::class.java.getDeclaredMethod("complete", Int::class.javaPrimitiveType, List::class.java)
    method.isAccessible = true
    method.invoke(controller, request, listOf(SubtitleLine(text, Rect(100, 800, 900, 850))))
  }
  private fun get(name: String): Any? = SubtitleController::class.java.getDeclaredField(name).apply { isAccessible = true }.get(controller)
  private fun set(name: String, value: Any) { SubtitleController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value) }
  private fun handlerRemoveCaptures() {
    val handler = get("handler") as android.os.Handler
    handler.removeCallbacks(get("capture") as Runnable)
  }
}
