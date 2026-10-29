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

package com.google.android.accessibility.talkback.translation

object AllLanguageDownloads {

  data class Progress(val total: Int, val done: Int, val failed: Int)

  var progress: Progress? = null
    private set

  private val queue = ArrayDeque<String>()
  private val listeners = mutableSetOf<() -> Unit>()
  private var onFinished: ((Progress) -> Unit)? = null

  private var run = 0

  val isRunning: Boolean
    get() = progress != null

  fun start(
    models: OnDeviceTranslation,
    languages: List<String>,
    wifiOnly: Boolean,
    onFinished: (Progress) -> Unit,
  ) {
    if (isRunning || languages.isEmpty()) return
    queue.clear()
    queue.addAll(languages)
    run++
    progress = Progress(languages.size, 0, 0)
    this.onFinished = onFinished
    next(models, wifiOnly)
  }

  fun stop() {
    queue.clear()
    run++
    progress = null
    onFinished = null
    changed()
  }

  fun addListener(listener: () -> Unit) {
    listeners += listener
  }

  fun removeListener(listener: () -> Unit) {
    listeners -= listener
  }

  private fun next(models: OnDeviceTranslation, wifiOnly: Boolean) {
    val language = queue.removeFirstOrNull()
    val current = progress ?: return
    if (language == null) {
      val finished = onFinished
      progress = null
      onFinished = null
      changed()
      finished?.invoke(current)
      return
    }
    changed()
    val thisRun = run
    models.download(language, wifiOnly) { ok ->

      if (run != thisRun) return@download
      val now = progress ?: return@download
      progress = now.copy(done = now.done + 1, failed = now.failed + if (ok) 0 else 1)
      next(models, wifiOnly)
    }
  }

  private fun changed() = listeners.toList().forEach { it() }
}
