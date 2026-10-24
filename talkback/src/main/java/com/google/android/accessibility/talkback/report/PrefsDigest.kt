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

package com.google.android.accessibility.talkback.report

import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A privacy-safe fingerprint of stored preference values: for every key, the value's type, its
 * length where one is defined, and a shortened SHA-256 of a canonical form of the value — never
 * the value itself. Two diagnostics exports can then be compared line by line to find exactly
 * which stored settings differ between them (for example a settings file left stale by an app
 * update versus the same file after a settings change rewrote it), without either export
 * revealing a single setting's value.
 */
object PrefsDigest {

  /**
   * The canonical text a value is hashed from. Equal values always give the same text: a string
   * is itself, the other scalars use their standard [Any.toString], and a set is sorted with each
   * element length-prefixed, so the result does not depend on the set's iteration order and no
   * two different sets can produce the same text.
   */
  fun canonicalRepresentation(value: Any?): String? =
    when (value) {
      null -> null
      is String -> value
      is Set<*> ->
        value
          .map { it.toString() }
          .sorted()
          .joinToString(SET_SEPARATOR) { text -> "${text.length}:$text" }
      is Boolean, is Int, is Long, is Float -> value.toString()
      else -> value.toString()
    }

  /** The stored type of [value], as one word. */
  fun typeName(value: Any?): String =
    when (value) {
      null -> "Null"
      is String -> "String"
      is Set<*> -> "Set"
      is Boolean -> "Boolean"
      is Int -> "Int"
      is Long -> "Long"
      is Float -> "Float"
      else -> value.javaClass.simpleName
    }

  /** The value's length: characters for a string, element count for a set, "-" otherwise. */
  fun lengthOf(value: Any?): String =
    when (value) {
      is String -> value.length.toString()
      is Set<*> -> value.size.toString()
      else -> "-"
    }

  /** The first 16 hex characters of the SHA-256 of [value]'s canonical form; "-" for null. */
  fun shortHash(value: Any?): String {
    val canonical = canonicalRepresentation(value) ?: return "-"
    val digest =
      MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 16)
  }

  /** One digest line per entry of [all], sorted by key: `key | type | length | hash`. */
  fun digestLines(all: Map<String, *>): List<String> =
    all.entries
      .sortedBy { it.key }
      .map { (key, value) -> "$key | ${typeName(value)} | ${lengthOf(value)} | ${shortHash(value)}" }

  /** `name (N bytes, modified ...)` for an existing file, `name: absent` for a missing one. */
  fun fileDescription(file: File): String =
    if (file.isFile) {
      "${file.name} (${file.length()} bytes, modified ${formatTime(file.lastModified())})"
    } else {
      "${file.name}: absent"
    }

  /** A file timestamp in the same format as the diagnostics header's "Generated" line. */
  fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date(epochMillis))

  private const val SET_SEPARATOR = "\u0001"
}
