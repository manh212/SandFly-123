package com.google.android.accessibility.talkback.calls

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.google.android.accessibility.utils.SharedPreferencesUtils
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [CallLimitControllerTest.TelecomShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CallLimitControllerTest {
  @Implements(TelecomManager::class)
  class TelecomShadow {
    @Implementation fun endCall(): Boolean {
      if (rejectByPermission) throw SecurityException("Permission revoked")
      ended++
      return true
    }
    companion object {
      var ended = 0
      var rejectByPermission = false
    }
  }

  private var warnings = 0
  private val app = RuntimeEnvironment.getApplication()
  private val prefs = SharedPreferencesUtils.getSharedPreferences(app)

  @Before fun setUp() {
    CallLimitController.detach()
    TelecomShadow.ended = 0
    TelecomShadow.rejectByPermission = false
    warnings = 0
    shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
    shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
      "android:manage_ongoing_calls", Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
    prefs.edit().clear().putBoolean(CallLimitSettings.ENABLED, true)
      .putString(CallLimitSettings.MINUTES, "1").commit()
    CallLimitController.attach(app, Runnable { warnings++ })
  }

  @After fun tearDown() {
    CallLimitController.detach()
    prefs.edit().clear().commit()
  }

  private fun state(value: String) {
    app.sendBroadcast(Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
      .putExtra(TelephonyManager.EXTRA_STATE, value))
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

  @Test fun ordinaryPermissionFallbackAutomaticallyEndsOutgoingCall() {
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(59)
    assertEquals(0, TelecomShadow.ended)
    advance(1)
    assertEquals(1, TelecomShadow.ended)
    advance(60)
    assertEquals(1, TelecomShadow.ended)
  }

  @Test fun outgoingOnlyDoesNotEndAnIncomingCall() {
    state(TelephonyManager.EXTRA_STATE_RINGING)
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(120)
    assertEquals(0, TelecomShadow.ended)
  }

  @Test fun bothScopeStartsIncomingLimitWhenAnswered() {
    prefs.edit().putString(CallLimitSettings.SCOPE, "BOTH").commit()
    shadowOf(Looper.getMainLooper()).idle()
    state(TelephonyManager.EXTRA_STATE_RINGING)
    advance(90)
    assertEquals(0, TelecomShadow.ended)
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(60)
    assertEquals(1, TelecomShadow.ended)
  }

  @Test fun callWaitingCancelsTheLegacyTimer() {
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(20)
    state(TelephonyManager.EXTRA_STATE_RINGING)
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(100)
    assertEquals(0, TelecomShadow.ended)
  }

  @Test fun switchingOffCancelsAnActiveLimit() {
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(20)
    prefs.edit().putBoolean(CallLimitSettings.ENABLED, false).commit()
    shadowOf(Looper.getMainLooper()).idle()
    advance(100)
    assertEquals(0, TelecomShadow.ended)
  }

  @Test fun previousCallTimerCannotAffectTheNextCall() {
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(30)
    state(TelephonyManager.EXTRA_STATE_IDLE)
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(30)
    assertEquals(0, TelecomShadow.ended)
    advance(30)
    assertEquals(1, TelecomShadow.ended)
  }

  @Test fun revokedPermissionPreventsDisconnect() {
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    shadowOf(app).denyPermissions(Manifest.permission.ANSWER_PHONE_CALLS)
    advance(60)
    assertEquals(0, TelecomShadow.ended)
  }
  @Test fun warningIsDeliveredBeforeEndingAndNotAtTheOneMinuteLimit() {
    prefs.edit().putString(CallLimitSettings.MINUTES, "2").commit()
    shadowOf(Looper.getMainLooper()).idle()
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(59)
    assertEquals(0, warnings)
    advance(1)
    assertEquals(1, warnings)
    assertEquals(0, TelecomShadow.ended)
    advance(60)
    assertEquals(1, TelecomShadow.ended)
    state(TelephonyManager.EXTRA_STATE_IDLE)
    prefs.edit().putString(CallLimitSettings.MINUTES, "1").commit()
    shadowOf(Looper.getMainLooper()).idle()
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    advance(60)
    assertEquals(1, warnings)
    assertEquals(2, TelecomShadow.ended)
  }
  @Test fun permissionRejectionAtDisconnectIsHandledWithoutCrashing() {
    state(TelephonyManager.EXTRA_STATE_OFFHOOK)
    TelecomShadow.rejectByPermission = true
    advance(60)
    assertEquals(0, TelecomShadow.ended)
    advance(60)
    assertEquals(0, TelecomShadow.ended)
  }
}
