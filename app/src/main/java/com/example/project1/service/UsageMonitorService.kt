package com.example.project1.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class UsageMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkUsageLimits()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    // Melacak waktu terakhir notifikasi dikirimkan per package agar tidak spam dan bisa re-alert
    private val lastNotifiedTimeMap = ConcurrentHashMap<String, Long>()
    private var lastOverlayLaunchTime = 0L
    private var lastOverlayPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SNOOZE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 5)
                if (!pkg.isNullOrEmpty()) {
                    PreferencesHelper.snoozeApp(this, pkg, minutes)
                    val notificationId = 2000 + (abs(pkg.hashCode()) % 100000)
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(notificationId)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this, "Batas waktu ditunda $minutes menit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            ACTION_CLEAR_COOLDOWN -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (!pkg.isNullOrEmpty()) {
                    lastNotifiedTimeMap.remove(pkg)
                } else {
                    lastNotifiedTimeMap.clear()
                }
            }
            ACTION_TEST_NOTIFICATION -> {
                serviceScope.launch {
                    sendLimitExceededNotification(
                        packageName = "com.google.android.youtube",
                        appName = "YouTube (Uji Coba)",
                        currentMinutes = 35,
                        limitMinutes = 30,
                        rawUsageMillis = 35 * 60 * 1000L
                    )
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
        handler.postDelayed(checkRunnable, 1000L)

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

        // Cek reset hari baru (pergantian hari)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (PreferencesHelper.isNewDay(this, today)) {
            lastNotifiedTimeMap.clear()
        }

        serviceScope.launch {
            // 1. Ambil data pemakaian media sosial terkini (real-time)
            val apps = UsageStatsHelper.getSocialAppsUsage(this@UsageMonitorService)

            // 2. Deteksi aplikasi yang sedang aktif di foreground
            val currentForegroundPkg = UsageStatsHelper.getForegroundPackage(this@UsageMonitorService)
            val now = System.currentTimeMillis()

            // 3. Periksa setiap aplikasi mandiri tanpa terblokir oleh status foreground
            for (app in apps) {
                if (app.isLimitExceeded) {
                    val isSnoozed = PreferencesHelper.isAppSnoozed(this@UsageMonitorService, app.packageName)
                    if (isSnoozed) {
                        // Sedang dalam masa tunda (snooze), lewati
                        continue
                    }

                    val lastNotified = lastNotifiedTimeMap[app.packageName] ?: 0L
                    val isForeground = (currentForegroundPkg == app.packageName)

                    // Kirim notifikasi jika belum pernah diberitahu sejak limit terlampaui,
                    // atau setelah interval cooldown (3 menit jika sedang aktif di layar, 10 menit jika di background)
                    val cooldownMs = if (isForeground) 3 * 60 * 1000L else 10 * 60 * 1000L
                    val shouldNotify = (lastNotified == 0L) || (now - lastNotified >= cooldownMs)

                    if (shouldNotify) {
                        sendLimitExceededNotification(
                            packageName = app.packageName,
                            appName = app.appName,
                            currentMinutes = app.usageMinutes,
                            limitMinutes = app.limitMinutes,
                            rawUsageMillis = app.rawUsageMillis
                        )
                        lastNotifiedTimeMap[app.packageName] = now
                    }

                    // 4. Jika aplikasi ini sedang aktif di layar dan bukan aplikasi pengaturan utama kita:
                    if (isForeground && currentForegroundPkg != packageName && !MainActivity.isAppInForeground) {
                        val isRecentOverlay = (now - lastOverlayLaunchTime < 10_000L) && (lastOverlayPackage == currentForegroundPkg)
                        if (!isRecentOverlay && PermissionHelper.hasOverlayPermission(this@UsageMonitorService)) {
                            lastOverlayLaunchTime = now
                            lastOverlayPackage = currentForegroundPkg
                            ReminderOverlayActivity.start(
                                context = this@UsageMonitorService,
                                packageName = app.packageName,
                                appName = app.appName,
                                usageMinutes = app.usageMinutes,
                                limitMinutes = app.limitMinutes,
                                rawUsageMillis = app.rawUsageMillis
                            )
                        }
                    }
                } else {
                    // Jika batas waktu diubah lebih besar atau durasi di-reset ke 0,
                    // bersihkan riwayat notifikasi agar jika terlampaui kembali bisa langsung berbunyi
                    lastNotifiedTimeMap.remove(app.packageName)
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
        createNotificationChannels()

        // Periksa izin notifikasi untuk Android 13+
        if (!PermissionHelper.hasNotificationPermission(this)) {
            return
        }

        val notificationId = 2000 + (abs(packageName.hashCode()) % 100000)

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
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, UsageMonitorService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_SNOOZE_MINUTES, 5)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this,
            notificationId + 1,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle("⚠️ Batas Waktu $appName Terlampaui!")
            .setContentText("Penggunaan: $currentMinutes menit (Batas: $limitMinutes menit). Istirahat sejenak!")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Anda telah menggunakan $appName selama $currentMinutes menit, melebihi batas harian $limitMinutes menit. Waktunya mengistirahatkan mata dan pikiran! 🌱"
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "⏳ Tunda 5 Menit", snoozePendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "Layanan Pemantauan Latar Belakang",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status layanan pemantau durasi di latar belakang"
                setShowBadge(false)
            }

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "Peringatan Batas Waktu",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(soundUri, audioAttributes)
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
        const val ACTION_CLEAR_COOLDOWN = "com.example.project1.ACTION_CLEAR_COOLDOWN"
        const val ACTION_TEST_NOTIFICATION = "com.example.project1.ACTION_TEST_NOTIFICATION"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"

        fun startService(context: Context) {
            PreferencesHelper.setMonitoringActive(context, true)
            val intent = Intent(context, UsageMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            PreferencesHelper.setMonitoringActive(context, false)
            val intent = Intent(context, UsageMonitorService::class.java)
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }

        fun clearNotificationCooldown(context: Context, packageName: String) {
            val intent = Intent(context, UsageMonitorService::class.java).apply {
                action = ACTION_CLEAR_COOLDOWN
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun sendTestNotification(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java).apply {
                action = ACTION_TEST_NOTIFICATION
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }
    }
}
