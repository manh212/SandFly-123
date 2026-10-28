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

package com.google.android.accessibility.talkback.migration

import com.google.android.accessibility.talkback.migration.AppIdMove.InstallKind
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdMoveTest {
  private val old = AppIdMove.OLD_PACKAGE
  private val new = AppIdMove.NEW_PACKAGE

  private fun kind(apk: String?, own: String, same: Boolean = true, newKey: Boolean = true) =
    AppIdMove.installKind(apk, own, sameSigner = same, newSigner = newKey)

  @Test
  fun samePackageUpdates() {
    assertEquals(InstallKind.UPDATE, kind(old, old))
    assertEquals(InstallKind.UPDATE, kind(new, new))
  }

  @Test
  fun samePackageWithAnotherKeyIsRefused() {
    assertEquals(InstallKind.REFUSE, kind(old, old, same = false))
  }

  @Test
  fun meridianMoves() {
    assertEquals(InstallKind.MOVE, kind(new, old, same = false))
  }

  @Test
  fun meridianWithoutItsKeyIsRefused() {
    assertEquals(InstallKind.REFUSE, kind(new, old, newKey = false))
  }

  @Test
  fun otherPackagesAreRefused() {
    assertEquals(InstallKind.REFUSE, kind("com.example.app", old))
    assertEquals(InstallKind.REFUSE, kind(old, new))
    assertEquals(InstallKind.REFUSE, kind("com.example.app", "com.example.other"))
    assertEquals(InstallKind.REFUSE, kind(null, old))
    assertEquals(InstallKind.REFUSE, kind("", old))
  }

  @Test
  fun authorityAndServiceFollowThePackage() {
    assertEquals("io.github.aaron_gh.sandfly.migration", AppIdMove.authority(old))
    assertEquals(
      "io.github.aaron_gh.meridian/com.google.android.marvin.talkback.TalkBackService",
      AppIdMove.serviceName(new),
    )
  }
}
