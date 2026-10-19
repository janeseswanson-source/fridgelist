package com.fridgelist.app.location

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Dev debug screen only (docs/test-geofence.md). On an emulator nothing else asks for location, so
 * `adb emu geo fix` positions never reach the geofencing engine. These background updates keep GPS
 * running the way other apps do on a real phone. Never used in production code paths.
 */
object EmulatorGps {
    @SuppressLint("MissingPermission") // the debug screen is used after detection is set up
    fun start(context: Context) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()
        runCatching { LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request, pendingIntent(context)) }
    }

    fun stop(context: Context) {
        runCatching { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).setAction(GeofenceBroadcastReceiver.ACTION_GPS)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }
}
