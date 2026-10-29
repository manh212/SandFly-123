package com.google.android.accessibility.utils.output

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.google.android.accessibility.utils.LatencyExperiments
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class NavigationPcmCache(context: Context) {
  class PreparationNotifier(val flags: Int) : SpeechCacheManager.LoadSpeechResultNotifier {
    override fun onFinished(speechInfo: SpeechCachePlayer.SpeechInfo?, success: Boolean?) {}
  }

  private data class Identity(val engine: String, val voice: String, val locale: String,
    val outputRate: Int, val configuration: Long)
  private data class Key(val identity: Identity, val text: String, val pitch: Int, val rate: Int)
  private class Request(
    val key: Key, val voice: Voice, val params: Bundle,
    val foreground: TextToSpeech, val epoch: Long,
  ) {
    val id = "nav_pcm_" + nextId.incrementAndGet()
    val bytes = ByteArrayOutputStream()
    var file: File? = null
    var timeout: Runnable? = null
    var rate = 0
    var encoding = 0
    var channels = 0
  }

  private val appContext = context.applicationContext
  private val thread = HandlerThread("NavigationPcm").apply { start() }
  private val handler = Handler(thread.looper)
  private val cache = PreparedPcmStore<Key>(MAX_CACHE_BYTES, 8)
  private val queue = ArrayDeque<Request>()
  private val epoch = AtomicLong()
  private val configuration = AtomicLong()
  private val hits = AtomicLong()
  private val misses = AtomicLong()
  private val prepared = AtomicLong()
  private val cancelled = AtomicLong()
  @Volatile private var identity: Identity? = null
  @Volatile private var closed = false
  private var engine: TextToSpeech? = null
  private var engineName: String? = null
  private var ready = false
  private var active: Request? = null
  private val failures = AtomicLong()
  private var disabledUntil = 0L
  private var engineGeneration = 0L
  private val pump = Runnable { startNext() }

  fun prepare(
    foreground: TextToSpeech, engineName: String, text: CharSequence,
    locale: Locale, params: Bundle, outputRate: Int,
  ) {
    if (closed || !LatencyExperiments.preparePcm() || text.isEmpty() || text.length > 1200) return
    val requestedEpoch = epoch.get()
    val requestedConfiguration = configuration.get()
    val spoken = text.toString()
    val parameters = Bundle(params)
    handler.post {
      if (closed || requestedEpoch != epoch.get() || requestedConfiguration != configuration.get()
        || !LatencyExperiments.preparePcm()) return@post
      val voice = try { foreground.voice } catch (failure: RuntimeException) { null } ?: return@post
      if (voice.locale != locale) return@post
      val current = Identity(engineName, voice.name, locale.toLanguageTag(), outputRate, requestedConfiguration)
      if (identity != current) {
        cache.clear()
        identity = current
      }
      val key = Key(current, spoken, parameters.getInt("pitch", 100), parameters.getInt("rate", 100))
      if (cache.contains(key) ||
        active?.key == key || queue.any { it.key == key }) return@post
      if (queue.size >= 4) queue.removeFirst()
      queue.addLast(Request(key, voice, parameters, foreground, requestedEpoch))
      schedule(0)
    }
  }

  fun find(
    text: CharSequence, engineName: String, locale: Locale?, params: Bundle, outputRate: Int,
  ): FloatArray? {
    if (!LatencyExperiments.preparePcm() || closed) return null
    val current = identity
    if (current == null || current.engine != engineName ||
      current.outputRate != outputRate || current.configuration != configuration.get() || (locale != null && current.locale != locale.toLanguageTag())) {
      misses.incrementAndGet()
      return null
    }
    val key = Key(current, text.toString(), params.getInt("pitch", 100), params.getInt("rate", 100))
    val result = cache.get(key)
    if (result == null) misses.incrementAndGet() else hits.incrementAndGet()
    return result
  }

  fun cancelPreparation() {
    if (closed) return
    epoch.incrementAndGet()
    handler.post {
      queue.clear()
      handler.removeCallbacks(pump)
      val request = active
      if (request != null) {
        active = null
        cancelled.incrementAndGet()
        engine?.stop()
        request.timeout?.let { handler.removeCallbacks(it) }
        request.file?.delete()
      }
    }
  }

  fun invalidate() {
    configuration.incrementAndGet()
    identity = null
    cache.clear()
    cancelPreparation()
  }

  fun shutdown() {
    if (closed) return
    closed = true
    epoch.incrementAndGet()
    cache.clear()
    handler.post {
      handler.removeCallbacks(pump)
      active?.timeout?.let { handler.removeCallbacks(it) }
      active?.file?.delete()
      active = null
      queue.clear()
      engine?.stop()
      engine?.shutdown()
      engine = null
      thread.quitSafely()
    }
  }

  fun resetStatistics() {
    hits.set(0)
    misses.set(0)
    prepared.set(0)
    cancelled.set(0)
    failures.set(0)
  }

  fun summary(): String =
    "Navigation PCM cache: hits=${hits.get()}, misses=${misses.get()}, " +
      "prepared=${prepared.get()}, cancelled=${cancelled.get()}, failures=${failures.get()}, bytes=${cache.byteCount()}"

  private fun schedule(delay: Long) {
    handler.removeCallbacks(pump)
    handler.postDelayed(pump, delay)
  }

  private fun startNext() {
    if (closed || active != null || !LatencyExperiments.preparePcm()) return
    if (SystemClock.uptimeMillis() < disabledUntil) { queue.clear(); return }
    while (queue.peekFirst()?.let { it.epoch != epoch.get() } == true) queue.removeFirst()
    val request = queue.peekFirst() ?: return
    if (LatencyExperiments.inputIsActive(120)) { schedule(40); return }
    val foregroundBusy = try { request.foreground.isSpeaking } catch (failure: RuntimeException) { true }
    if (foregroundBusy) { schedule(60); return }
    if (engine == null || engineName != request.key.identity.engine) {
      engine?.shutdown()
      ready = false
      engineName = request.key.identity.engine
      val generation = ++engineGeneration
      engine = TextToSpeech(appContext, { status ->
        handler.post {
          if (closed || generation != engineGeneration) return@post
          ready = status == TextToSpeech.SUCCESS
          if (ready) {
            engine?.setOnUtteranceProgressListener(listener)
            schedule(0)
          } else {
            failures.incrementAndGet()
            disabledUntil = SystemClock.uptimeMillis() + 30_000
            queue.clear()
            engine?.shutdown()
            engine = null
          }
        }
      }, engineName)
      engine?.setOnUtteranceProgressListener(listener)
      return
    }
    if (!ready) return
    queue.removeFirst()
    if (engine?.setVoice(request.voice) != TextToSpeech.SUCCESS) { schedule(0); return }
    val file = try { File.createTempFile("nav-pcm-", ".wav", appContext.cacheDir) }
      catch (failure: java.io.IOException) { schedule(0); return }
    request.file = file
    active = request
    val params = Bundle(request.params).apply {
      putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
    }
    val result = engine?.synthesizeToFile(request.key.text, params, file, request.id)
    if (result != TextToSpeech.SUCCESS) {
      finish(request.id, false)
    } else {
      val timeout = Runnable {
        if (active === request) {
          engine?.stop()
          finish(request.id, false)
        }
      }
      request.timeout = timeout
      handler.postDelayed(timeout, 2500)
    }
  }

  private val listener = object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String?) {}
    override fun onBeginSynthesis(id: String, rate: Int, encoding: Int, channels: Int) {
      handler.post {
        active?.takeIf { it.id == id }?.let {
          it.rate = rate; it.encoding = encoding; it.channels = channels
        }
      }
    }
    override fun onAudioAvailable(id: String, audio: ByteArray) {
      handler.post {
        active?.takeIf { it.id == id && it.epoch == epoch.get() }?.let {
          if (it.bytes.size() + audio.size <= 2_000_000) it.bytes.write(audio)
          else { engine?.stop(); finish(id, false) }
        }
      }
    }
    override fun onDone(id: String) { handler.post { finish(id, true) } }
    override fun onError(id: String) { handler.post { finish(id, false) } }
  }

  private fun finish(id: String, success: Boolean) {
    val request = active?.takeIf { it.id == id } ?: return
    active = null
    request.timeout?.let { handler.removeCallbacks(it) }
    if (!success) failures.incrementAndGet()
    try {
      if (success && request.epoch == epoch.get() && !closed &&
        SpatialSoundPlayer.isSupportedFormat(request.rate, request.channels) &&
        AudioDecoder.isSupportedEncoding(request.encoding)) {
        val decoded = AudioDecoder.FloatList()
        AudioDecoder.toFloats(ByteBuffer.wrap(request.bytes.toByteArray()), request.encoding, decoded)
        val samples = decoded.toArray()
        val duration = samples.size.toLong() * 1000 / request.channels / request.rate
        if (duration <= 6000 && samples.any { it != 0f }) {
          val resampler = SincResampler(request.rate, request.key.identity.outputRate, request.channels)
          val converted = resampler.process(samples) + resampler.flush()
          val stereo = LowLatencyAudio.toStereo(converted, request.channels)
          val size = stereo.size * 4
          if (size <= MAX_CACHE_BYTES && request.epoch == epoch.get()) {
            if (request.key.identity.configuration == configuration.get() && cache.put(request.key, stereo)) {
              prepared.incrementAndGet()
            }
          }
        }
      }
    } catch (failure: RuntimeException) {
      failures.incrementAndGet()
    } finally {
      request.file?.delete()
      schedule(0)
    }
  }

  companion object {
    private val nextId = AtomicLong()
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
  }
}

