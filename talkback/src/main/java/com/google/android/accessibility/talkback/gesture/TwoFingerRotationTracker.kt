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

import android.util.Log
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class TwoFingerRotationTracker(

  private val decisionDistancePx: Float,

  private val minArcPx: Float,
  private val listener: Listener,
) {

  fun interface Listener {

    fun onRotationStep(clockwise: Boolean)
  }

  private enum class State {

    IDLE,

    TRACKING,

    ROTATING,

    REJECTED,
  }

  private var state = State.IDLE
  private var firstId = INVALID_ID
  private var secondId = INVALID_ID
  private var startX0 = 0f
  private var startY0 = 0f
  private var startX1 = 0f
  private var startY1 = 0f

  private var moved0X = 0f
  private var moved0Y = 0f
  private var moved1X = 0f
  private var moved1Y = 0f
  private var startMidX = 0f
  private var startMidY = 0f
  private var startDistance = 0f
  private var lastAngle = 0f

  private var accumulated = 0f

  private var anchor = 0f

  private var peak = 0f

  private var direction = 0

  private var acceptedTime = 0L

  private var rotatingFingersDown = false

  val isRotating: Boolean
    get() = state == State.ROTATING

  val isPossibleRotation: Boolean
    get() = state == State.TRACKING

  var isScroll = false
    private set

  fun clear() {
    state = State.IDLE
    isScroll = false
    firstId = INVALID_ID
    secondId = INVALID_ID
    rotatingFingersDown = false
  }

  fun onMotionEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> clear()
      MotionEvent.ACTION_POINTER_DOWN ->
        when (state) {
          State.IDLE -> if (event.pointerCount == 2) startTracking(event)
          State.TRACKING -> state = State.REJECTED
          else -> {}
        }
      MotionEvent.ACTION_MOVE ->
        when (state) {
          State.TRACKING -> onTrackingMove(event)
          State.ROTATING -> if (rotatingFingersDown) onRotatingMove(event)
          else -> {}
        }
      MotionEvent.ACTION_POINTER_UP ->
        when (state) {
          State.TRACKING -> state = State.REJECTED
          State.ROTATING -> {
            val id = event.getPointerId(event.actionIndex)
            if (id == firstId || id == secondId) rotatingFingersDown = false
            return true
          }
          else -> {}
        }
      MotionEvent.ACTION_UP,
      MotionEvent.ACTION_CANCEL -> {
        val wasRotating = state == State.ROTATING
        clear()
        return wasRotating
      }
      else -> {}
    }
    return state == State.ROTATING
  }

  private fun startTracking(event: MotionEvent) {
    firstId = event.getPointerId(0)
    secondId = event.getPointerId(1)
    val x0 = event.getX(0)
    val y0 = event.getY(0)
    val x1 = event.getX(1)
    val y1 = event.getY(1)
    startX0 = x0
    startY0 = y0
    startX1 = x1
    startY1 = y1
    startMidX = (x0 + x1) / 2
    startMidY = (y0 + y1) / 2
    startDistance = hypot(x1 - x0, y1 - y0)
    lastAngle = angleDegrees(x0, y0, x1, y1)
    accumulated = 0f
    state = State.TRACKING
    if (GestureLog.enabled) {
      Log.d(DEBUG_TAG, "Rotation tracking started: distance=%.0f".format(startDistance))
    }
  }

  private fun onTrackingMove(event: MotionEvent) {
    val firstIndex = event.findPointerIndex(firstId)
    val secondIndex = event.findPointerIndex(secondId)
    if (event.pointerCount != 2 || firstIndex < 0 || secondIndex < 0) {
      state = State.REJECTED
      return
    }
    val x0 = event.getX(firstIndex)
    val y0 = event.getY(firstIndex)
    val x1 = event.getX(secondIndex)
    val y1 = event.getY(secondIndex)
    accumulate(angleDegrees(x0, y0, x1, y1))

    val distance = hypot(x1 - x0, y1 - y0)

    val arc = (distance / 2) * Math.toRadians(abs(accumulated).toDouble()).toFloat()
    val translation = hypot((x0 + x1) / 2 - startMidX, (y0 + y1) / 2 - startMidY)
    val radial = abs(distance - startDistance) / 2
    val looksLikeRotation =
      translation <= arc * MAX_TRANSLATION_RATIO && radial <= arc * MAX_RADIAL_RATIO

    moved0X = x0 - startX0
    moved0Y = y0 - startY0
    moved1X = x1 - startX1
    moved1Y = y1 - startY1
    val moved0 = hypot(moved0X, moved0Y)
    val moved1 = hypot(moved1X, moved1Y)
    if (
      moved0 >= minArcPx &&
        moved1 >= minArcPx &&
        (moved0X * moved1X + moved0Y * moved1Y) / (moved0 * moved1) > SAME_DIRECTION_COSINE
    ) {

      debugLog("rejected as scroll, fingers moving the same way", arc, translation, radial)
      state = State.REJECTED
      isScroll = true
      return
    }

    val pivot = min(moved0, moved1) < max(moved0, moved1) * PIVOT_STILL_RATIO
    val commitDegrees = if (pivot) PIVOT_COMMIT_DEGREES else COMMIT_DEGREES

    if (looksLikeRotation && abs(accumulated) >= commitDegrees && arc >= minArcPx) {
      debugLog("accepted", arc, translation, radial)
      state = State.ROTATING
      rotatingFingersDown = true
      direction = if (accumulated > 0) 1 else -1

      anchor = direction * max(stepDegrees, abs(accumulated))
      acceptedTime = event.eventTime
      peak = accumulated
      listener.onRotationStep(direction > 0)
    } else if (translation > decisionDistancePx && abs(accumulated) < SCROLL_MAX_DEGREES) {

      debugLog("rejected as scroll", arc, translation, radial)
      state = State.REJECTED
      isScroll = true
    } else if (radial > decisionDistancePx && radial > arc) {

      debugLog("rejected as pinch", arc, translation, radial)
      state = State.REJECTED
    } else if (
      !looksLikeRotation &&
        max(arc, max(translation, radial)) > decisionDistancePx * GIVE_UP_DISTANCE_FACTOR
    ) {
      debugLog("rejected", arc, translation, radial)
      state = State.REJECTED
    }
  }

  private fun debugLog(result: String, arc: Float, translation: Float, radial: Float) {
    if (GestureLog.enabled) {
      Log.d(
        DEBUG_TAG,
        ("Rotation $result: angle=%.1f arc=%.0f translation=%.0f radial=%.0f slop=%.0f " +
            "finger1=(%.0f,%.0f) finger2=(%.0f,%.0f)")
          .format(
            accumulated,
            arc,
            translation,
            radial,
            decisionDistancePx,
            moved0X,
            moved0Y,
            moved1X,
            moved1Y,
          ),
      )
    }
  }

  private fun onRotatingMove(event: MotionEvent) {
    val firstIndex = event.findPointerIndex(firstId)
    val secondIndex = event.findPointerIndex(secondId)
    if (firstIndex < 0 || secondIndex < 0) {
      return
    }
    accumulate(
      angleDegrees(
        event.getX(firstIndex),
        event.getY(firstIndex),
        event.getX(secondIndex),
        event.getY(secondIndex),
      )
    )
    if ((accumulated - peak) * direction > 0) {
      peak = accumulated
    }
    if (event.eventTime - acceptedTime < FLICK_MILLIS) {

      if ((accumulated - anchor) * direction > 0) anchor = accumulated
      return
    }
    while (true) {
      val step = stepDegrees
      if ((accumulated - anchor) * direction >= step) {

        anchor += direction * step
        peak = anchor
        if ((accumulated - peak) * direction > 0) peak = accumulated
        listener.onRotationStep(direction > 0)
      } else if ((peak - accumulated) * direction >= step) {

        direction = -direction
        anchor = peak + direction * step
        peak = accumulated
        listener.onRotationStep(direction > 0)
      } else {
        break
      }
    }
  }

  private fun accumulate(angle: Float) {
    var delta = angle - lastAngle
    while (delta > 180f) delta -= 360f
    while (delta <= -180f) delta += 360f
    accumulated += delta
    lastAngle = angle
  }

  companion object {
    private const val INVALID_ID = -1

    private const val DEFAULT_STEP_DEGREES = 30f

    @Volatile private var stepDegrees = DEFAULT_STEP_DEGREES

    @JvmStatic
    fun setStepDegrees(degrees: Float) {
      // A step of 0 or less, such as from an imported settings file, would count steps forever.
      stepDegrees = if (degrees >= 1f) degrees else DEFAULT_STEP_DEGREES
    }

    private const val COMMIT_DEGREES = 15f

    private const val FLICK_MILLIS = 200L

    private const val PIVOT_COMMIT_DEGREES = 30f

    private const val PIVOT_STILL_RATIO = 0.35f

    private const val SAME_DIRECTION_COSINE = 0.5f

    private const val SCROLL_MAX_DEGREES = 8f

    private const val GIVE_UP_DISTANCE_FACTOR = 3f

    private const val MAX_TRANSLATION_RATIO = 2f

    private const val MAX_RADIAL_RATIO = 0.75f

    private const val DEBUG_TAG = "BacktalkGesture"

    private fun angleDegrees(x0: Float, y0: Float, x1: Float, y1: Float): Float =
      Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
  }
}
