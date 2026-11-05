package com.google.android.accessibility.talkback.status

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.accessibility.talkback.R
import java.util.Locale
import java.util.concurrent.Executors
import java.util.function.Consumer

class CurrentLocationReader(private val context: Context, private val announce: Consumer<String>) {
  private val handler = Handler(Looper.getMainLooper())
  private val manager = context.getSystemService(LocationManager::class.java)
  private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "current-address").apply { isDaemon = true } }
  private var listener: LocationListener? = null
  private var generation = 0
  private var foundLocation = false
  private val timeout = Runnable {
    finish(generation, context.getString(if (foundLocation) R.string.location_address_unavailable else R.string.location_unavailable))
  }

  @SuppressLint("MissingPermission")
  fun read(): Boolean {
    cancel()
    val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
      .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (!granted) {
      announce.accept(context.getString(R.string.location_permission_needed))
      return false
    }
    val manager = manager ?: return unavailable(R.string.location_unavailable)
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
      .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return unavailable(R.string.location_disabled)
    val request = generation
    val started = SystemClock.elapsedRealtime()
    foundLocation = false
    val callback = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        if (request != generation || foundLocation || location.elapsedRealtimeNanos / 1_000_000 < started - 2_000) return
        foundLocation = true
        stopUpdates()
        if (!Geocoder.isPresent()) {
          finish(request, context.getString(R.string.location_address_unavailable))
          return
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
          try {
            geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
              override fun onGeocode(addresses: MutableList<Address>) = result(request, addresses.firstOrNull())
              override fun onError(errorMessage: String?) = result(request, null)
            })
          } catch (_: RuntimeException) { result(request, null) }
        } else {
          worker.execute {
            @Suppress("DEPRECATION")
            val address = runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
            result(request, address)
          }
        }
      }

      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
      override fun onProviderEnabled(provider: String) {}
      override fun onProviderDisabled(provider: String) {}
    }
    listener = callback
    var registered = false
    for (provider in providers) {
      try {
        manager.requestLocationUpdates(provider, 0L, 0f, callback, Looper.getMainLooper())
        registered = true
      } catch (_: SecurityException) {
      } catch (_: IllegalArgumentException) {
      }
    }
    if (!registered) {
      cancel()
      return unavailable(R.string.location_unavailable)
    }
    handler.postDelayed(timeout, 15_000)
    return true
  }

  private fun result(request: Int, address: Address?) {
    val text = addressText(address).ifBlank { context.getString(R.string.location_address_unavailable) }
    handler.post { finish(request, text) }
  }

  private fun finish(request: Int, text: String) {
    if (request != generation) return
    cancel()
    announce.accept(text)
  }

  private fun unavailable(message: Int): Boolean {
    announce.accept(context.getString(message))
    return false
  }

  private fun stopUpdates() {
    listener?.let { runCatching { manager?.removeUpdates(it) } }
    listener = null
  }

  fun cancel() {
    generation++
    handler.removeCallbacks(timeout)
    stopUpdates()
  }

  fun shutdown() {
    cancel()
    worker.shutdownNow()
  }

  companion object {
    internal fun addressText(address: Address?): String {
      if (address == null) return ""
      val parts = listOf(address.thoroughfare, address.subLocality, address.locality, address.subAdminArea, address.adminArea)
        .filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
      return if (parts.isNotEmpty()) parts.joinToString(", ") else address.getAddressLine(0).orEmpty().trim()
    }
  }
}
