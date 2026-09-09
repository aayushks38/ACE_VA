package com.ace.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class LocationRouteInfo(
    val distanceKm: Double,
    val distanceFormatted: String,
    val cardinalDirection: String,
    val drivingMinutes: Int,
    val drivingTimeFormatted: String,
    val walkingMinutes: Int,
    val walkingTimeFormatted: String,
    val destLat: Double,
    val destLng: Double,
    val destAddress: String
)

object LocationUtils {

    fun hasLocationPermission(context: Context): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission(context)) return@withContext null

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        // 1. Check last known location from providers (GPS, Network, Passive)
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.accuracy < bestLocation.accuracy || loc.time > bestLocation.time) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // If last known location is recent (within 5 minutes), use it
        if (bestLocation != null && (System.currentTimeMillis() - bestLocation.time) < 5 * 60 * 1000) {
            return@withContext bestLocation
        }

        // 2. Otherwise, request a single quick location update
        try {
            return@withContext suspendCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try {
                            locationManager.removeUpdates(this)
                        } catch (_: Exception) {}
                        continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                val targetProvider = when {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                if (targetProvider != null) {
                    try {
                        Looper.prepare()
                    } catch (_: Exception) {}
                    try {
                        locationManager.requestSingleUpdate(targetProvider, listener, Looper.getMainLooper())
                        // Timeout fallback after 3.5 seconds
                        android.os.Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                locationManager.removeUpdates(listener)
                            } catch (_: Exception) {}
                            if (bestLocation != null) {
                                continuation.resume(bestLocation)
                            } else {
                                continuation.resume(null)
                            }
                        }, 3500)
                    } catch (e: Exception) {
                        continuation.resume(bestLocation)
                    }
                } else {
                    continuation.resume(bestLocation)
                }
            }
        } catch (_: Exception) {
            return@withContext bestLocation
        }
    }

    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    return@withContext formatAddress(addr)
                }
            }
        } catch (_: Exception) {}

        // Fallback: OpenStreetMap Nominatim reverse geocoding
        try {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=18&addressdetails=1"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ACE-Agent/1.0")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(jsonText)
            val displayName = json.optString("display_name")
            if (displayName.isNotBlank()) {
                return@withContext displayName
            }
        } catch (_: Exception) {}

        return@withContext "Coordinates: ${String.format(Locale.US, "%.4f", lat)}, ${String.format(Locale.US, "%.4f", lng)}"
    }

    private fun formatAddress(addr: Address): String {
        val parts = mutableListOf<String>()
        if (!addr.featureName.isNullOrBlank() && addr.featureName != addr.subThoroughfare && addr.featureName != addr.thoroughfare) {
            parts.add(addr.featureName)
        }
        if (!addr.subThoroughfare.isNullOrBlank() || !addr.thoroughfare.isNullOrBlank()) {
            val street = listOfNotNull(addr.subThoroughfare, addr.thoroughfare).joinToString(" ")
            if (street.isNotBlank()) parts.add(street)
        }
        if (!addr.subLocality.isNullOrBlank()) parts.add(addr.subLocality)
        if (!addr.locality.isNullOrBlank()) parts.add(addr.locality)
        if (!addr.adminArea.isNullOrBlank()) parts.add(addr.adminArea)
        if (!addr.postalCode.isNullOrBlank()) parts.add(addr.postalCode)
        if (!addr.countryName.isNullOrBlank()) parts.add(addr.countryName)

        return if (parts.isNotEmpty()) parts.distinct().joinToString(", ") else "Unknown Location"
    }

    suspend fun geocodeDestination(context: Context, destination: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (destination.isBlank()) return@withContext null
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(destination, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    return@withContext Pair(addr.latitude, addr.longitude)
                }
            }
        } catch (_: Exception) {}

        // Fallback: OpenStreetMap Nominatim search
        try {
            val encodedQuery = Uri.encode(destination)
            val urlStr = "https://nominatim.openstreetmap.org/search?format=json&q=$encodedQuery&limit=1"
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ACE-Agent/1.0")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val jsonArray = JSONArray(jsonText)
            if (jsonArray.length() > 0) {
                val first = jsonArray.getJSONObject(0)
                val lat = first.getDouble("lat")
                val lon = first.getDouble("lon")
                return@withContext Pair(lat, lon)
            }
        } catch (_: Exception) {}

        return@withContext null
    }

    fun calculateCardinalDirection(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLonRad = Math.toRadians(lon2 - lon1)

        val y = Math.sin(deltaLonRad) * Math.cos(lat2Rad)
        val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(deltaLonRad)

        var bearing = Math.toDegrees(Math.atan2(y, x))
        bearing = (bearing + 360) % 360

        return when {
            bearing >= 337.5 || bearing < 22.5 -> "North"
            bearing >= 22.5 && bearing < 67.5 -> "North-East"
            bearing >= 67.5 && bearing < 112.5 -> "East"
            bearing >= 112.5 && bearing < 157.5 -> "South-East"
            bearing >= 157.5 && bearing < 202.5 -> "South"
            bearing >= 202.5 && bearing < 247.5 -> "South-West"
            bearing >= 247.5 && bearing < 292.5 -> "West"
            bearing >= 292.5 && bearing < 337.5 -> "North-West"
            else -> "North"
        }
    }

    fun calculateRoute(
        startLat: Double,
        startLng: Double,
        destLat: Double,
        destLng: Double,
        destName: String
    ): LocationRouteInfo {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLng, destLat, destLng, results)
        val distanceMeters = results[0]
        val distanceKm = distanceMeters / 1000.0

        val cardinal = calculateCardinalDirection(startLat, startLng, destLat, destLng)

        // Estimated speeds: driving ~ 35 km/h in city traffic, walking ~ 5.0 km/h
        val drivingHours = distanceKm / 35.0
        val drivingMins = (drivingHours * 60).toInt().coerceAtLeast(1)

        val walkingHours = distanceKm / 5.0
        val walkingMins = (walkingHours * 60).toInt().coerceAtLeast(1)

        val distFormatted = if (distanceKm < 1.0) {
            "${distanceMeters.toInt()} meters"
        } else {
            String.format(Locale.US, "%.1f km", distanceKm)
        }

        val drivingFormatted = if (drivingMins >= 60) {
            val hrs = drivingMins / 60
            val mins = drivingMins % 60
            "$hrs hr $mins mins"
        } else {
            "$drivingMins mins"
        }

        val walkingFormatted = if (walkingMins >= 60) {
            val hrs = walkingMins / 60
            val mins = walkingMins % 60
            "$hrs hr $mins mins"
        } else {
            "$walkingMins mins"
        }

        return LocationRouteInfo(
            distanceKm = distanceKm,
            distanceFormatted = distFormatted,
            cardinalDirection = cardinal,
            drivingMinutes = drivingMins,
            drivingTimeFormatted = drivingFormatted,
            walkingMinutes = walkingMins,
            walkingTimeFormatted = walkingFormatted,
            destLat = destLat,
            destLng = destLng,
            destAddress = destName
        )
    }
}
