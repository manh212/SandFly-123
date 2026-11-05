package com.google.android.accessibility.talkback.status

import android.location.Address
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CurrentLocationReaderTest {
  @Test fun readsAvailableAddressPartsWithoutInventingMissingStreet() {
    val address = Address(Locale.forLanguageTag("vi")).apply {
      subLocality = "Phường A"
      locality = "Thành phố B"
      adminArea = "Thành phố B"
    }
    assertEquals("Phường A, Thành phố B", CurrentLocationReader.addressText(address))
  }

  @Test fun missingAddressNeverBecomesCoordinates() {
    val address = Address(Locale.ROOT).apply { latitude = 10.0; longitude = 106.0 }
    assertEquals("", CurrentLocationReader.addressText(address))
    assertEquals("", CurrentLocationReader.addressText(null))
  }
}
