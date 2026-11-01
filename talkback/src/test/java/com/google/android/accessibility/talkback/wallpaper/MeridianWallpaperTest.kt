package com.google.android.accessibility.talkback.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import com.google.android.accessibility.talkback.ExitAfterTurnOff
import com.google.android.accessibility.talkback.TalkBackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MeridianWallpaperTest {
  @Test
  fun lifecycleKeepsWallpaperUseSeparateFromTheScreenReader() {
    val controller = Robolectric.buildService(MeridianWallpaperService::class.java).create()
    assertTrue(MeridianWallpaperService.isRunning())
    assertNull(TalkBackService.getInstance())
    controller.destroy()
    assertFalse(MeridianWallpaperService.isRunning())
    assertNull(TalkBackService.getInstance())
    ExitAfterTurnOff.cancel()
  }

  @Test
  fun activationUsesTheSystemChooserForMeridianOnly() {
    val context: Context = RuntimeEnvironment.getApplication()
    val intent = MeridianWallpaperService.intentToSet(context)
    assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, intent.action)
    assertEquals(ComponentName(context, MeridianWallpaperService::class.java),
      intent.getParcelableExtra<android.os.Parcelable>(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT))
  }
}
