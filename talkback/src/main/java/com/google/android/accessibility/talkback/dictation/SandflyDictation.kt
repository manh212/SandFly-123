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

package com.google.android.accessibility.talkback.dictation

object SandflyDictation {

  @Volatile internal var instance: AiDictation? = null

  @JvmStatic
  val isAvailable: Boolean
    get() = instance != null

  @JvmStatic
  val isListening: Boolean
    get() = instance?.isListening == true

  @JvmStatic
  fun listen(onText: (String?) -> Unit): Boolean {
    val dictation = instance
    if (dictation == null) {
      onText(null)
      return false
    }
    return dictation.listen(onText)
  }

  @JvmStatic
  fun cancel() {
    instance?.cancelListening()
  }

  @JvmStatic
  fun stop() {
    instance?.takeIf { it.isListening }?.start(null)
  }
}
