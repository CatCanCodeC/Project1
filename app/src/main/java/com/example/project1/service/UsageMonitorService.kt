package com.example.project1.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.project1.MainActivity
import com.example.project1.R
import com.example.project1.ui.ReminderOverlayActivity
import com.example.project1.util.PermissionHelper
import com.example.project1.util.PreferencesHelper
import com.example.project1.util.UsageStatsHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

class UsageMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkUsageLimits()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private val notifiedAppsToday = mutableSetOf<String>()
    private var lastOverlayLaunchTime = 0L
    private var lastOverlayPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Tangani aksi tunda (snooze) dari notifikasi
        if (intent?.action == ACTION_SNOOZE) {
            val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)
            if (!pkg.isNullOrEmpty()) {
                PreferencesHelper.snoozeApp(this, pkg, minutes)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(pkg.hashCode())
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Batas waktu ditunda $minutes menit", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val notification = createForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification)
        }

        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 3000L)

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkUsageLimits() {
        if (!PermissionHelper.hasUsageStatsPermission(this)) return

        // JANGAN memunculkan pop-up jika pengguna sedang membuka aplikasi kita sendiri (MainActivity)
        if (MainActivity.isAppInForeground) {
            return
        }

        // Cek reset hari baru (pergantian hari)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (PreferencesHelper.isNewDay(this, today)) {
            notifiedAppsToday.clear()
        }

        serviceScope.launch {
            // 1. Ambil data penggunaan seluruh aplikasi media sosial
            val apps = UsageStatsHelper.getSocialAppsUsage(this@UsageMonitorService)
            val appMap = apps.associateBy { it.packageName }

            // 2. Deteksi aplikasi yang sedang dibuka di layar pengguna (foreground)
            val currentForegroundPkg = UsageStatsHelper.getForegroundPackage(this@UsageMonitorService)

            // Pastikan foreground bukan aplikasi kita sendiri ataupun kosong
            if (currentForegroundPkg == packageName || currentForegroundPkg.isNullOrEmpty()) {
                return@launch
            }

            // Jika aplikasi yang sedang dibuka adalah medsos yang sudah melampaui batas waktu:
            if (appMap.containsKey(currentForegroundPkg)) {
                val currentApp = appMap[currentForegroundPkg]!!
                val isSnoozed = PreferencesHelper.isAppSnoozed(this@UsageMonitorService, currentApp.packageName)

                if (currentApp.isLimitExceeded && !isSnoozed) {
                    val now = System.currentTimeMillis()
                    // Cegah pembukaan berulang terlalu cepat (debounce 5 detik) jika aplikasi sama
                    val isRecentLaunch = (now - lastOverlayLaunchTime < 5_000L) && (lastOverlayPackage == currentForegroundPkg)

                    if (!isRecentLaunch) {
                        lastOverlayLaunchTime = now
                        lastOverlayPackage = currentForegroundPkg

                        // Tampilkan overlay jika izin overlay tersedia
                        if (PermissionHelper.hasOverlayPermission(this@UsageMonitorService)) {
                            ReminderOverlayActivity.start(
                                context = this@UsageMonitorService,
                                packageName = currentApp.packageName,
                                appName = currentApp.appName,
                                usageMinutes = currentApp.usageMinutes,
                                limitMinutes = currentApp.limitMinutes,
                                rawUsageMillis = currentApp.rawUsageMillis
                            )
                        }

                        // Kirim atau perbarui notifikasi peringatan
                        sendLimitExceededNotification(
                            packageName = currentApp.packageName,
                            appName = currentApp.appName,
                            currentMinutes = currentApp.usageMinutes,
                            limitMinutes = currentApp.limitMinutes,
                            rawUsageMillis = currentApp.rawUsageMillis
                        )
                        notifiedAppsToday.add(currentApp.packageName)
                    }
                }
            }

            // 3. Untuk aplikasi lain yang sudah lewat batas tapi belum pernah diberitahukan hari ini:
            for (app in apps) {
                val isSnoozed = PreferencesHelper.isAppSnoozed(this@UsageMonitorService, app.packageName)
                if (app.isLimitExceeded && !isSnoozed && !notifiedAppsToday.contains(app.packageName)) {
                    sendLimitExceededNotification(
                        packageName = app.packageName,
                        appName = app.appName,
                        currentMinutes = app.usageMinutes,
                        limitMinutes = app.limitMinutes,
                        rawUsageMillis = app.rawUsageMillis
                    )
                    notifiedAppsToday.add(app.packageName)
                }
            }
        }
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setContentTitle("Pemantau Media Sosial Aktif")
            .setContentText("Aplikasi sedang memantau batas waktu bermain media sosial Anda.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun sendLimitExceededNotification(
        packageName: String,
        appName: String,
        currentMinutes: Int,
        limitMinutes: Int,
        rawUsageMillis: Long
    ) {
        val contentIntent = Intent(this, ReminderOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReminderOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(ReminderOverlayActivity.EXTRA_APP_NAME, appName)
            putExtra(ReminderOverlayActivity.EXTRA_USAGE_MINUTES, currentMinutes)
            putExtra(ReminderOverlayActivity.EXTRA_LIMIT_MINUTES, limitMinutes)
            putExtra(ReminderOverlayActivity.EXTRA_RAW_USAGE_MILLIS, rawUsageMillis)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            packageName.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action Intent untuk 'Tunda 5 Menit' langsung dari drawer notifikasi
        val snoozeIntent = Intent(this, UsageMonitorService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_SNOOZE_MINUTES, 5)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this,
            packageName.hashCode() + 1,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle("⚠️ Batas Waktu $appName Terlampaui!")
            .setContentText("Penggunaan: $currentMinutes menit (Batas: $limitMinutes menit). Istirahat sejenak!")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Anda telah menggunakan $appName selama $currentMinutes menit, melebihi batas harian $limitMinutes menit. Waktunya mengistirahatkan mata dan pikiran! 🌱"
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "⏳ Tunda 5 Menit", snoozePendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(packageName.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Layanan Pemantauan Latar Belakang",
                NotificationManager.IMPORTANCE_LOW
            )
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "Peringatan Batas Waktu",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                description = "Pemberitahuan saat durasi penggunaan media sosial telah mencapai batas"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        private const val NOTIFICATION_ID_SERVICE = 1001
        private const val CHANNEL_ID_SERVICE = "channel_usage_service"
        private const val CHANNEL_ID_ALERT = "channel_usage_alert"
        private const val CHECK_INTERVAL_MS = 15_000L

        const val ACTION_SNOOZE = "com.example.project1.ACTION_SNOOZE"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"

        fun startService(context: Context) {
            PreferencesHelper.setMonitoringActive(context, true)
            val intent = Intent(context, UsageMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            PreferencesHelper.setMonitoringActive(context, false)
            val intent = Intent(context, UsageMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
