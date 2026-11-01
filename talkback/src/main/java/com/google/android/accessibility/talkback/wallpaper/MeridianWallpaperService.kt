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

package com.google.android.accessibility.talkback.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.google.android.accessibility.talkback.ExitAfterTurnOff
import com.google.android.accessibility.talkback.TalkBackService

class MeridianWallpaperService : WallpaperService() {
  override fun onCreate() {
    super.onCreate()
    running = true
  }

  override fun onDestroy() {
    running = false
    super.onDestroy()
    if (TalkBackService.getInstance() == null) ExitAfterTurnOff.schedule(application)
  }

  override fun onCreateEngine(): Engine = BlackEngine()

  private inner class BlackEngine : Engine() {
    override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
      super.onSurfaceChanged(holder, format, width, height)
      draw()
    }

    override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
      super.onSurfaceRedrawNeeded(holder)
      draw()
    }

    override fun onVisibilityChanged(visible: Boolean) {
      super.onVisibilityChanged(visible)
      if (visible) draw()
    }

    private fun draw() {
      val holder = surfaceHolder ?: return
      val canvas = try { holder.lockCanvas() } catch (_: RuntimeException) { null } ?: return
      try {
        canvas.drawColor(Color.BLACK)
      } finally {
        try { holder.unlockCanvasAndPost(canvas) } catch (_: RuntimeException) { }
      }
    }
  }

  companion object {
    private var running = false

    @JvmStatic fun isRunning(): Boolean = running

    @JvmStatic
    fun intentToSet(context: Context): Intent =
      Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
        ComponentName(context, MeridianWallpaperService::class.java),
      )
  }
}
