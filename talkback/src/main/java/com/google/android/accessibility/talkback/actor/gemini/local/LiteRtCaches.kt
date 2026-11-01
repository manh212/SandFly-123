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

package com.google.android.accessibility.talkback.actor.gemini.local

import android.util.Log
import java.io.File

object LiteRtCaches {
  private const val TAG = "LiteRtCaches"
  private const val XNNPACK_SUFFIX = ".xnnpack_cache"
  private const val MLDRIFT_MARKER = "_mldrift_"
  private const val MODEL_SUFFIX = ".litertlm"

  fun isCache(name: String): Boolean = name.endsWith(XNNPACK_SUFFIX) || name.contains(MLDRIFT_MARKER)

  fun belongsTo(name: String, model: LocalModel): Boolean {
    val stem = model.localFileName.removeSuffix(MODEL_SUFFIX)
    return name.length > stem.length && name.startsWith(stem) && name[stem.length] in "._"
  }

  fun deleteUnused(cacheDir: File, installed: Collection<LocalModel>): Long =
    deleteWhere(cacheDir) { name -> installed.none { belongsTo(name, it) } }

  fun deleteAll(cacheDir: File): Long = deleteWhere(cacheDir) { true }

  private fun deleteWhere(cacheDir: File, test: (String) -> Boolean): Long {
    var freed = 0L
    cacheDir.listFiles()?.forEach { file ->
      if (file.isFile && isCache(file.name) && test(file.name)) {
        val bytes = file.length()
        if (file.delete()) {
          freed += bytes
          Log.i(TAG, "Deleted ${file.name}, $bytes bytes")
        }
      }
    }
    return freed
  }
}
