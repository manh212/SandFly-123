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

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PrefsDigestTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  @Test
  fun canonicalRepresentation_scalars() {
    assertThat(PrefsDigest.canonicalRepresentation("abc")).isEqualTo("abc")
    assertThat(PrefsDigest.canonicalRepresentation(true)).isEqualTo("true")
    assertThat(PrefsDigest.canonicalRepresentation(42)).isEqualTo("42")
    assertThat(PrefsDigest.canonicalRepresentation(42L)).isEqualTo("42")
    assertThat(PrefsDigest.canonicalRepresentation(1.5f)).isEqualTo("1.5")
    assertThat(PrefsDigest.canonicalRepresentation(null)).isNull()
  }

  @Test
  fun canonicalRepresentation_setDoesNotDependOnElementOrder() {
    val first = linkedSetOf("beta", "alpha", "gamma")
    val second = linkedSetOf("gamma", "alpha", "beta")
    assertThat(PrefsDigest.canonicalRepresentation(first))
      .isEqualTo(PrefsDigest.canonicalRepresentation(second))
    assertThat(PrefsDigest.canonicalRepresentation(first))
      .isEqualTo("5:alpha\u00014:beta\u00015:gamma")
  }

  @Test
  fun canonicalRepresentation_setsWithAmbiguousJoinsDiffer() {
    // Without length prefixes these two sets would join to the same text.
    val first = setOf("a\u0001b")
    val second = setOf("a", "b")
    assertThat(PrefsDigest.canonicalRepresentation(first))
      .isNotEqualTo(PrefsDigest.canonicalRepresentation(second))
  }

  @Test
  fun shortHash_isStableAnd16HexChars() {
    val hash = PrefsDigest.shortHash("some value")
    assertThat(hash.length).isEqualTo(16)
    assertThat(hash).matches("[0-9a-f]{16}")
    assertThat(PrefsDigest.shortHash("some value")).isEqualTo(hash)
    assertThat(PrefsDigest.shortHash("other value")).isNotEqualTo(hash)
    assertThat(PrefsDigest.shortHash(null)).isEqualTo("-")
  }

  @Test
  fun digestLines_sortedByKeyWithTypeLengthAndHash() {
    val all =
      mapOf<String, Any?>(
        "z_string" to "hello",
        "a_bool" to true,
        "m_set" to setOf("x", "y"),
        "b_int" to 7,
      )
    val lines = PrefsDigest.digestLines(all)
    assertThat(lines).hasSize(4)
    assertThat(lines[0]).startsWith("a_bool | Boolean | - | ")
    assertThat(lines[1]).startsWith("b_int | Int | - | ")
    assertThat(lines[2]).startsWith("m_set | Set | 2 | ")
    assertThat(lines[3]).startsWith("z_string | String | 5 | ")
    for (line in lines) {
      assertThat(line.substringAfterLast(" | ")).matches("[0-9a-f]{16}")
    }
  }

  @Test
  fun digestLines_emptyPreferencesGiveNoLines() {
    assertThat(PrefsDigest.digestLines(emptyMap<String, Any?>())).isEmpty()
  }

  @Test
  fun fileDescription_existingAndAbsentFiles() {
    val dir = tempFolder.newFolder("shared_prefs")
    val file = File(dir, "app_preferences.xml")
    file.writeText("<map/>")
    val present = PrefsDigest.fileDescription(file)
    assertThat(present).startsWith("app_preferences.xml (6 bytes, modified ")
    assertThat(PrefsDigest.fileDescription(File(dir, "app_preferences.xml.bak")))
      .isEqualTo("app_preferences.xml.bak: absent")
  }

  @Test
  fun fileDescription_backupFilePresent() {
    val dir = tempFolder.newFolder("shared_prefs")
    val bak = File(dir, "app_preferences.xml.bak")
    bak.writeText("x")
    assertThat(PrefsDigest.fileDescription(bak))
      .startsWith("app_preferences.xml.bak (1 bytes, modified ")
  }
}
