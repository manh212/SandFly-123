package com.google.android.accessibility.talkback.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import kotlin.math.roundToInt

internal data class CurrencyPrediction(val denomination: Int, val confidence: Float)

internal data class CurrencyInputLayout(val width: Int, val height: Int, val channelsFirst: Boolean) {
  companion object {
    fun from(shape: IntArray): CurrencyInputLayout {
      require(shape.size == 4 && shape[0] == 1)
      val channelsFirst = shape[1] == 3 && shape[3] != 3
      val channelsLast = shape[3] == 3 && shape[1] != 3
      require(channelsFirst || channelsLast)
      val width = if (channelsFirst) shape[3] else shape[2]
      val height = if (channelsFirst) shape[2] else shape[1]
      require(width in 160..1024 && height in 160..1024)
      return CurrencyInputLayout(width, height, channelsFirst)
    }
  }
}

internal object CurrencyModelOutput {
  val denominations = intArrayOf(1000, 10000, 100000, 2000, 20000, 200000, 5000, 50000, 500000)
  private const val FEATURES = 13
  private const val MIN_CONFIDENCE = 0.78f

  fun best(values: FloatArray, dimensions: IntArray): CurrencyPrediction? {
    require(dimensions.size == 3 && dimensions[0] == 1)
    val channelsFirst = dimensions[1] == FEATURES
    val channelsLast = dimensions[2] == FEATURES
    require(channelsFirst || channelsLast)
    val count = if (channelsFirst) dimensions[2] else dimensions[1]
    require(count > 0 && values.size == FEATURES * count)
    fun value(candidate: Int, feature: Int): Float =
      if (channelsFirst) values[feature * count + candidate] else values[candidate * FEATURES + feature]
    var best: CurrencyPrediction? = null
    for (candidate in 0 until count) {
      val width = value(candidate, 2)
      val height = value(candidate, 3)
      if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) continue
      for (index in denominations.indices) {
        val score = value(candidate, index + 4)
        if (score.isFinite() && score >= MIN_CONFIDENCE && score > (best?.confidence ?: 0f)) {
          best = CurrencyPrediction(denominations[index], score)
        }
      }
    }
    return best
  }

  fun speak(denomination: Int): String = when (denomination) {
    1000 -> "Một nghìn đồng"
    2000 -> "Hai nghìn đồng"
    5000 -> "Năm nghìn đồng"
    10000 -> "Mười nghìn đồng"
    20000 -> "Hai mươi nghìn đồng"
    50000 -> "Năm mươi nghìn đồng"
    100000 -> "Một trăm nghìn đồng"
    200000 -> "Hai trăm nghìn đồng"
    500000 -> "Năm trăm nghìn đồng"
    else -> throw IllegalArgumentException("Unrecognized denomination: $denomination")
  }
}

internal class CurrencyTracker {
  private var candidate: Int? = null
  private var consecutive = 0
  private var missing = 0
  private var spoken: Int? = null
  private var lastSpokenAtMs = Long.MIN_VALUE

  /** Repeat a stable denomination while it remains in the camera view. */
  fun observe(prediction: CurrencyPrediction?, nowMs: Long): String? {
    if (prediction == null || prediction.confidence < 0.78f) {
      missing++
      candidate = null
      consecutive = 0
      if (missing >= 3) {
        spoken = null
        lastSpokenAtMs = Long.MIN_VALUE
      }
      return null
    }
    missing = 0
    if (candidate != prediction.denomination) {
      candidate = prediction.denomination
      consecutive = 1
    } else {
      consecutive++
    }
    if (consecutive < 2) return null
    if (spoken == candidate && lastSpokenAtMs != Long.MIN_VALUE &&
        nowMs - lastSpokenAtMs < REPEAT_INTERVAL_MS) return null
    spoken = candidate
    lastSpokenAtMs = nowMs
    return CurrencyModelOutput.speak(prediction.denomination)
  }

  fun reset() {
    candidate = null
    consecutive = 0
    missing = 0
    spoken = null
    lastSpokenAtMs = Long.MIN_VALUE
  }

  companion object {
    const val REPEAT_INTERVAL_MS = 1_000L
  }
}

internal class CurrencyRecognizer(private val context: Context) : Closeable {
  private val interpreter: Interpreter
  private val width: Int
  private val height: Int
  private val channelsFirst: Boolean
  private val floatInput: FloatArray?
  private val inputType: DataType
  private val outputType: DataType
  private val outputShape: IntArray
  private val inputScale: Float
  private val inputZeroPoint: Int
  private val outputScale: Float
  private val outputZeroPoint: Int
  private val inputBuffer: ByteBuffer
  private val outputBuffer: ByteBuffer

  init {
    val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
    val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
    model.put(bytes).rewind()
    interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    val input = interpreter.getInputTensor(0)
    val layout = CurrencyInputLayout.from(input.shape())
    height = layout.height
    width = layout.width
    channelsFirst = layout.channelsFirst
    inputType = input.dataType()
    floatInput = if (inputType == DataType.FLOAT32) FloatArray(width * height * 3) else null
    require(inputType == DataType.FLOAT32 || inputType == DataType.UINT8)
    inputScale = input.quantizationParams().scale
    inputZeroPoint = input.quantizationParams().zeroPoint
    if (inputType == DataType.UINT8) require(inputScale > 0f)
    val output = interpreter.getOutputTensor(0)
    outputType = output.dataType()
    require(outputType == DataType.FLOAT32 || outputType == DataType.UINT8)
    outputScale = output.quantizationParams().scale
    outputZeroPoint = output.quantizationParams().zeroPoint
    if (outputType == DataType.UINT8) require(outputScale > 0f)
    outputShape = output.shape()
    require(outputShape.size == 3 && outputShape[0] == 1 &&
      (outputShape[1] == 13 || outputShape[2] == 13))
    inputBuffer = ByteBuffer.allocateDirect(width * height * 3 * inputType.byteSize()).order(ByteOrder.nativeOrder())
    outputBuffer = ByteBuffer.allocateDirect(outputShape.reduce(Int::times) * outputType.byteSize()).order(ByteOrder.nativeOrder())
  }

  fun detect(bitmap: Bitmap): CurrencyPrediction? {
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    try {
      val pixels = IntArray(width * height)
      scaled.getPixels(pixels, 0, width, 0, 0, width, height)
      inputBuffer.clear()
      if (inputType == DataType.FLOAT32) {
        val floats = requireNotNull(floatInput)
        if (channelsFirst) {
          val planeSize = pixels.size
          for (index in pixels.indices) {
            val pixel = pixels[index]
            floats[index] = Color.red(pixel) / 255f
            floats[planeSize + index] = Color.green(pixel) / 255f
            floats[2 * planeSize + index] = Color.blue(pixel) / 255f
          }
        } else {
          var index = 0
          for (pixel in pixels) {
            floats[index++] = Color.red(pixel) / 255f
            floats[index++] = Color.green(pixel) / 255f
            floats[index++] = Color.blue(pixel) / 255f
          }
        }
        inputBuffer.asFloatBuffer().put(floats)
      } else if (channelsFirst) {
        for (pixel in pixels) putChannel(Color.red(pixel))
        for (pixel in pixels) putChannel(Color.green(pixel))
        for (pixel in pixels) putChannel(Color.blue(pixel))
      } else {
        for (pixel in pixels) {
          putChannel(Color.red(pixel))
          putChannel(Color.green(pixel))
          putChannel(Color.blue(pixel))
        }
      }
      inputBuffer.rewind()
      outputBuffer.clear()
      interpreter.run(inputBuffer, outputBuffer)
      outputBuffer.rewind()
      val scores = FloatArray(outputShape.reduce(Int::times))
      if (outputType == DataType.FLOAT32) {
        outputBuffer.asFloatBuffer().get(scores)
      } else {
        for (index in scores.indices) {
          scores[index] = (outputBuffer.get().toInt() and 0xff).minus(outputZeroPoint) * outputScale
        }
      }
      return CurrencyModelOutput.best(scores, outputShape)
    } finally {
      if (scaled !== bitmap) scaled.recycle()
    }
  }

  private fun putChannel(channel: Int) {
    val normalized = channel / 255f
    if (inputType == DataType.FLOAT32) inputBuffer.putFloat(normalized)
    else inputBuffer.put((normalized / inputScale + inputZeroPoint).roundToInt().coerceIn(0, 255).toByte())
  }

  override fun close() = interpreter.close()

  companion object {
    const val MODEL_ASSET = "vn_currency_yolo11n.tflite"
    fun isAvailable(context: Context): Boolean =
      context.assets.list("")?.contains(MODEL_ASSET) == true
  }
}
