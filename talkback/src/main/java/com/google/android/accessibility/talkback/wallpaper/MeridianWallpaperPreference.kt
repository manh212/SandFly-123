package com.google.android.accessibility.talkback.wallpaper

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import com.google.android.accessibility.material.preference.AccessibilitySuitePreference
import com.google.android.accessibility.talkback.R

class MeridianWallpaperPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
  AccessibilitySuitePreference(context, attrs) {
  override fun onClick() {
    try {
      context.startActivity(MeridianWallpaperService.intentToSet(context))
    } catch (_: ActivityNotFoundException) {
      Toast.makeText(context, R.string.meridian_wallpaper_unavailable, Toast.LENGTH_SHORT).show()
    }
  }
}
