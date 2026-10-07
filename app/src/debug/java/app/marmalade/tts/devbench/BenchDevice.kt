package app.marmalade.tts.devbench

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import app.marmalade.tts.R
import java.util.Locale

/**
 * Android-side helpers the adb-driven bench services share: the thermal
 * summary every log line carries, and the minimal foreground notification
 * `am start-foreground-service` requires.
 */
internal object BenchDevice {

    /** Thermal state for the log: status (0 none … 6 shutdown), 10 s headroom, battery °C. */
    fun thermal(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) pm.getThermalHeadroom(10) else Float.NaN
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) ?: -10) / 10.0
        return String.format(Locale.ROOT, "thermalStatus=%d headroom=%.2f batteryC=%.1f", pm.currentThermalStatus, headroom, tempC)
    }

    // Developer-only, debug-build-only notification: English on purpose (the
    // 8-locale rule covers user-visible strings, and no user build has this).
    fun startForeground(service: Service, notificationId: Int, title: String) {
        val mgr = service.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Engine benchmark (debug)", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = Notification.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_marmalade)
            .setContentTitle(title)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(notificationId, notification)
        }
    }

    private const val CHANNEL_ID = "marmalade_debug_bench"
}
