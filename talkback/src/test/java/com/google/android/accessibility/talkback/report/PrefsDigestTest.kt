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
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsDigestTest {

  @Test
  fun canonicalRepresentation_scalars() {
    assertEquals("abc", PrefsDigest.canonicalRepresentation("abc"))
    assertEquals("true", PrefsDigest.canonicalRepresentation(true))
    assertEquals("42", PrefsDigest.canonicalRepresentation(42))
    assertEquals("42", PrefsDigest.canonicalRepresentation(42L))
    assertEquals("1.5", PrefsDigest.canonicalRepresentation(1.5f))
    assertNull(PrefsDigest.canonicalRepresentation(null))
  }

  @Test
  fun canonicalRepresentation_setDoesNotDependOnElementOrder() {
    val first = linkedSetOf("beta", "alpha", "gamma")
    val second = linkedSetOf("gamma", "alpha", "beta")
    assertEquals(
      PrefsDigest.canonicalRepresentation(first),
      PrefsDigest.canonicalRepresentation(second),
    )
    assertEquals("5:alpha\u00014:beta\u00015:gamma", PrefsDigest.canonicalRepresentation(first))
  }

  @Test
  fun canonicalRepresentation_setsWithAmbiguousJoinsDiffer() {
    // Without length prefixes these two sets would join to the same text.
    val first = setOf("a\u0001b")
    val second = setOf("a", "b")
    assertNotEquals(
      PrefsDigest.canonicalRepresentation(first),
      PrefsDigest.canonicalRepresentation(second),
    )
  }

  @Test
  fun shortHash_isStableAnd16HexChars() {
    val hash = PrefsDigest.shortHash("some value")
    assertEquals(16, hash.length)
    assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    assertEquals(hash, PrefsDigest.shortHash("some value"))
    assertNotEquals(hash, PrefsDigest.shortHash("other value"))
    assertEquals("-", PrefsDigest.shortHash(null))
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
    assertEquals(4, lines.size)
    assertTrue(lines[0].startsWith("a_bool | Boolean | - | "))
    assertTrue(lines[1].startsWith("b_int | Int | - | "))
    assertTrue(lines[2].startsWith("m_set | Set | 2 | "))
    assertTrue(lines[3].startsWith("z_string | String | 5 | "))
    for (line in lines) {
      val hash = line.substringAfterLast(" | ")
      assertEquals(16, hash.length)
      assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
  }

  @Test
  fun digestLines_emptyPreferencesGiveNoLines() {
    assertTrue(PrefsDigest.digestLines(emptyMap<String, Any?>()).isEmpty())
  }

  @Test
  fun fileDescription_existingAndAbsentFiles() {
    val dir = Files.createTempDirectory("prefs-digest-test").toFile()
    try {
      val file = File(dir, "app_preferences.xml")
      file.writeText("<map/>")
      assertTrue(
        PrefsDigest.fileDescription(file).startsWith("app_preferences.xml (6 bytes, modified ")
      )
      assertEquals(
        "app_preferences.xml.bak: absent",
        PrefsDigest.fileDescription(File(dir, "app_preferences.xml.bak")),
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun fileDescription_backupFilePresent() {
    val dir = Files.createTempDirectory("prefs-digest-test").toFile()
    try {
      val bak = File(dir, "app_preferences.xml.bak")
      bak.writeText("x")
      assertTrue(
        PrefsDigest.fileDescription(bak).startsWith("app_preferences.xml.bak (1 bytes, modified ")
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun digest_neverContainsTheValueItself() {
    val secret = "a-very-recognisable-value"
    val lines = PrefsDigest.digestLines(mapOf("some_key" to secret))
    assertFalse(lines.single().contains(secret))
    assertFalse(lines.single().contains("recognisable"))
  }
}
