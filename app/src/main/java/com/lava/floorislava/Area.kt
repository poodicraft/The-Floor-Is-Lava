package com.lava.floorislava

import android.content.Context
import android.location.Geocoder
import java.util.Locale
import kotlin.math.roundToInt

/** The player's home area for the area leaderboard, e.g. key "il|tel aviv-yafo", name "Tel Aviv-Yafo, IL". */
data class AreaInfo(val key: String, val name: String)

object Area {

    /**
     * Resolves a GPS position to a city-level area using the phone's built-in
     * geocoder. Falls back to a ~11 km grid square when the geocoder has no
     * answer (no network, or no geocoder on the device).
     *
     * Blocking — call it off the main thread.
     */
    fun resolve(context: Context, latitude: Double, longitude: Double): AreaInfo {
        try {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val address = Geocoder(context, Locale.ENGLISH)
                    .getFromLocation(latitude, longitude, 1)
                    ?.firstOrNull()
                if (address != null) {
                    val place = address.locality ?: address.subAdminArea ?: address.adminArea
                    val country = address.countryCode ?: address.countryName
                    if (!place.isNullOrBlank() && !country.isNullOrBlank()) {
                        return AreaInfo(
                            key = "$country|$place".lowercase(Locale.ROOT),
                            name = "$place, $country"
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Geocoder is best effort; fall through to the grid square.
        }

        val gridLat = (latitude * 10).roundToInt()
        val gridLng = (longitude * 10).roundToInt()
        return AreaInfo(
            key = "grid|$gridLat|$gridLng",
            name = String.format(Locale.US, "Around %.1f, %.1f", gridLat / 10.0, gridLng / 10.0)
        )
    }
}
