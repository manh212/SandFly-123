package com.google.android.accessibility.talkback.calls

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.speakerphone.SpeakerphoneSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils

object CallLimitController {
  private val main = Handler(Looper.getMainLooper())
  private var context: Context? = null
  private var prefs: SharedPreferences? = null
  private var warningFeedback: Runnable? = null
  private val tones = mutableSetOf<ToneGenerator>()
  private var armedAt = 0L
  private var phoneState = TelephonyManager.CALL_STATE_IDLE
  private var phoneIncoming = false
  private var phoneAmbiguous = false
  private var phoneStartedAt = 0L
  private var phoneGeneration = 0L
  private val telecomCalls = mutableMapOf<Call, Int>()
  private val finished = mutableSetOf<Call>()
  private const val PHONE = "legacyPhone"

  private val engine = CallLimitEngine(
    { SystemClock.elapsedRealtime() },
    { settings() },
    { delay, action -> schedule(delay, action) },
    { context?.let { Toast.makeText(it, R.string.call_limit_failed, Toast.LENGTH_LONG).show() } },
    { warnBeforeEnding() },
  )

  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key in CallLimitSettings.KEYS) {
      engine.cancelAll()
      armedAt = System.currentTimeMillis()
      phoneStartedAt = 0L
      finished.addAll(telecomCalls.keys)
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
        TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
        TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
        TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
        else -> return
      }
      onPhoneState(state)
    }
  }

  @JvmStatic
  @JvmOverloads
  fun attach(context: Context, warningFeedback: Runnable = Runnable {}) {
    detach()
    this.context = context
    this.warningFeedback = warningFeedback
    prefs = SharedPreferencesUtils.getSharedPreferences(context)
    armedAt = System.currentTimeMillis()
    prefs?.registerOnSharedPreferenceChangeListener(listener)
    ContextCompat.registerReceiver(context, receiver,
      IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    if (has(context, Manifest.permission.READ_PHONE_STATE)) {
      @Suppress("DEPRECATION")
      val current = runCatching { context.getSystemService(TelephonyManager::class.java)?.callState }
        .getOrNull() ?: TelephonyManager.CALL_STATE_IDLE
      if (current != TelephonyManager.CALL_STATE_IDLE) {
        phoneState = current
        phoneAmbiguous = true
      }
    }
  }

  @JvmStatic
  fun detach() {
    context?.let { runCatching { it.unregisterReceiver(receiver) } }
    prefs?.unregisterOnSharedPreferenceChangeListener(listener)
    engine.cancelAll()
    tones.toList().forEach { runCatching { it.release() } }
    tones.clear()
    warningFeedback = null
    telecomCalls.clear()
    finished.clear()
    context = null
    prefs = null
    phoneState = TelephonyManager.CALL_STATE_IDLE
    phoneIncoming = false
    phoneAmbiguous = false
    phoneStartedAt = 0L
    phoneGeneration++
  }

  private fun settings() = prefs?.let { CallLimitSettings.read(it) } ?: CallLimitSettings()

  fun hasManagedAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeakerphoneSettings.canControlCalls(context)

  fun canUseLegacy(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      has(context, Manifest.permission.READ_PHONE_STATE) && has(context, Manifest.permission.ANSWER_PHONE_CALLS)

  private fun has(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  private fun onPhoneState(state: Int) {
    val context = context ?: return
    if (!has(context, Manifest.permission.READ_PHONE_STATE) || state == phoneState) return
    val previous = phoneState
    phoneState = state
    if (state == TelephonyManager.CALL_STATE_IDLE) {
      engine.cancel(PHONE)
      phoneIncoming = false
      phoneAmbiguous = false
      phoneStartedAt = 0L
      phoneGeneration++
      return
    }
    if (state == TelephonyManager.CALL_STATE_RINGING) {
      if (previous == TelephonyManager.CALL_STATE_OFFHOOK) {
        phoneAmbiguous = true
        engine.cancel(PHONE)
      } else phoneIncoming = true
      return
    }
    if (hasManagedAccess(context) || !canUseLegacy(context) || phoneAmbiguous) return
    if (phoneStartedAt != 0L || !settings().appliesTo(phoneIncoming)) return
    phoneStartedAt = SystemClock.elapsedRealtime()
    val generation = ++phoneGeneration
    engine.start(PHONE, phoneIncoming, phoneStartedAt,
      { this.context === context && phoneGeneration == generation &&
        phoneState == TelephonyManager.CALL_STATE_OFFHOOK && !phoneAmbiguous &&
        !hasManagedAccess(context) && canUseLegacy(context) },
      { endLegacy(context) })
  }

  @Suppress("DEPRECATION")
  private fun endLegacy(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !canUseLegacy(context)) return false
    return try {
      context.getSystemService(TelecomManager::class.java)?.endCall() == true
    } catch (_: SecurityException) {
      false
    } catch (_: RuntimeException) {
      false
    }
  }

  @JvmStatic
  @RequiresApi(Build.VERSION_CODES.S)
  fun onTelecomCall(call: Call, state: Int) {
    val context = context ?: return
    telecomCalls[call] = state
    if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
      engine.cancel(call)
      finished += call
      return
    }
    if (!hasManagedAccess(context) || !settings().enabled) return
    engine.cancel(PHONE)
    if (state != Call.STATE_ACTIVE || call in finished) return
    val details = call.details
    if (details.creationTimeMillis < armedAt || details.connectTimeMillis <= 0) return
    val incoming = when (details.callDirection) {
      Call.Details.DIRECTION_INCOMING -> true
      Call.Details.DIRECTION_OUTGOING -> false
      else -> return
    }
    if (!safeToEnd(context, call)) return
    val elapsed = (System.currentTimeMillis() - details.connectTimeMillis).coerceAtLeast(0)
    engine.start(call, incoming, SystemClock.elapsedRealtime() - elapsed,
      { this.context === context && telecomCalls[call] in setOf(Call.STATE_ACTIVE, Call.STATE_HOLDING) &&
        hasManagedAccess(context) && safeToEnd(context, call) },
      {
        finished += call
        runCatching { call.disconnect(); true }.getOrDefault(false)
      })
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun safeToEnd(context: Context, call: Call): Boolean {
    val details = call.details
    val excluded = Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE or
      Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL or Call.Details.PROPERTY_CONFERENCE or
      Call.Details.PROPERTY_SELF_MANAGED or Call.Details.PROPERTY_IS_EXTERNAL_CALL
    if (details.callProperties and excluded != 0 || call.parent != null || call.children.isNotEmpty()) return false
    val number = details.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: return false
    return runCatching {
      context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(number) == false
    }.getOrDefault(false)
  }

  @JvmStatic
  fun onTelecomRemoved(call: Call) {
    telecomCalls.remove(call)
    finished.remove(call)
    engine.cancel(call)
  }

  @JvmStatic
  fun onTelecomDisconnected() {
    telecomCalls.keys.toList().forEach(engine::cancel)
    telecomCalls.clear()
    finished.clear()
  }

  private fun warnBeforeEnding() {
    val context = context ?: return
    val vibrator = context.getSystemService(Vibrator::class.java)
    if (vibrator?.hasVibrator() == true) {
      runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1)) }
    }
    runCatching { ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 100) }.getOrNull()?.let { tone ->
      tones += tone
      runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
      main.postDelayed({
        if (tones.remove(tone)) runCatching { tone.release() }
      }, 600L)
    }
    warningFeedback?.run()
  }

  private fun schedule(delay: Long, action: () -> Unit): () -> Unit {
    val context = context ?: return {}
    val alarms = context.getSystemService(AlarmManager::class.java)
    var cancelled = false
    val runnable = Runnable {
      if (!cancelled) {
        cancelled = true
        action()
      }
    }
    val listener = AlarmManager.OnAlarmListener { runnable.run() }
    main.postDelayed(runnable, delay)
    runCatching {
      alarms?.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + delay, "MeridianCallLimit", listener, main)
    }
    return {
      cancelled = true
      main.removeCallbacks(runnable)
      alarms?.cancel(listener)
    }
  }
}
