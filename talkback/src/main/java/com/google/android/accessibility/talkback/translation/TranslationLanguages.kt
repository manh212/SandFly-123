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

import android.icu.util.ULocale
import java.util.Locale

object TranslationLanguages {
  const val CHINESE_SIMPLIFIED = "zh-Hans"
  const val CHINESE_TRADITIONAL = "zh-Hant"

  val ALL: List<String> =
    listOf(
      "af", "ak", "am", "ar", "as", "ay", "az", "be", "bg", "bho", "bm", "bn", "bs", "ca", "ceb",
      "ckb", "co", "cs", "cy", "da", "de", "doi", "dv", "ee", "el", "en", "eo", "es", "et", "eu",
      "fa", "fi", "fil", "fr", "fy", "ga", "gd", "gl", "gn", "gom", "gu", "ha", "haw", "he", "hi",
      "hmn", "hr", "ht", "hu", "hy", "id", "ig", "ilo", "is", "it", "ja", "jv", "ka", "kk", "km",
      "kn", "ko", "kri", "ku", "ky", "la", "lb", "lg", "ln", "lo", "lt", "lus", "lv", "mai", "mg",
      "mi", "mk", "ml", "mn", "mni", "mr", "ms", "mt", "my", "ne", "nl", "no", "nso", "ny", "om",
      "or", "pa", "pl", "ps", "pt", "qu", "ro", "ru", "rw", "sa", "sd", "si", "sk", "sl", "sm",
      "sn", "so", "sq", "sr", "st", "su", "sv", "sw", "ta", "te", "tg", "th", "ti", "tk", "tr",
      "ts", "tt", "ug", "uk", "ur", "uz", "vi", "xh", "yi", "yo", CHINESE_SIMPLIFIED,
      CHINESE_TRADITIONAL, "zu",
    )

  private val ALIASES =
    mapOf(
      "iw" to "he",
      "in" to "id",
      "ji" to "yi",
      "jw" to "jv",
      "tl" to "fil",
      "nb" to "no",
      "nn" to "no",
      "zh" to CHINESE_SIMPLIFIED,
      "zh-cn" to CHINESE_SIMPLIFIED,
      "zh-sg" to CHINESE_SIMPLIFIED,
      "zh-hans" to CHINESE_SIMPLIFIED,
      "zh-tw" to CHINESE_TRADITIONAL,
      "zh-hk" to CHINESE_TRADITIONAL,
      "zh-mo" to CHINESE_TRADITIONAL,
      "zh-hant" to CHINESE_TRADITIONAL,
      "zt" to CHINESE_TRADITIONAL,
      "mni-mtei" to "mni",
    )

  @JvmStatic
  fun normalize(code: String?): String? {
    val lower = code?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)
    if (lower.isNullOrEmpty() || lower == "und" || lower == "auto") return null
    ALIASES[lower]?.let { return it }
    ALL.firstOrNull { it.lowercase(Locale.ROOT) == lower }?.let { return it }
    val base = lower.substringBefore('-')
    if (base == "zh") {

      return if ("hant" in lower || lower.endsWith("-tw") || lower.endsWith("-hk")) CHINESE_TRADITIONAL
      else CHINESE_SIMPLIFIED
    }
    return ALIASES[base] ?: ALL.firstOrNull { it == base }
  }

  @JvmStatic
  fun of(locale: Locale): String =
    normalize(if (locale.language == "zh") chineseTag(locale) else locale.language) ?: "en"

  private fun chineseTag(locale: Locale): String =
    when {
      locale.script.isNotEmpty() -> "zh-${locale.script}"
      locale.country.isNotEmpty() -> "zh-${locale.country}"
      else -> "zh"
    }

  @JvmStatic
  fun name(code: String, inLocale: Locale = Locale.getDefault()): String {

    val name =
      if (code == CHINESE_SIMPLIFIED || code == CHINESE_TRADITIONAL) {
        ULocale.forLanguageTag(code).getDisplayName(ULocale.forLocale(inLocale))
      } else {
        Locale.forLanguageTag(code).getDisplayName(inLocale)
      }
    return if (name.isEmpty() || name.equals(code, ignoreCase = true)) code
    else name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(inLocale) else it.toString() }
  }

  fun sorted(only: Set<String>? = null): List<String> {
    val locale = Locale.getDefault()
    return ALL.filter { only == null || it in only }.sortedBy { name(it, locale).lowercase(locale) }
  }

  @JvmStatic
  fun same(a: String?, b: String?): Boolean = a != null && normalize(a) == normalize(b)
}
