package com.example.project1.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.example.project1.model.SocialAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object UsageStatsHelper {

    // Cache icon agar PackageManager tidak perlu diakses berulang-ulang
    private val iconCache = ConcurrentHashMap<String, Bitmap>()

    // Daftar package name aplikasi media sosial umum
    val DEFAULT_SOCIAL_MEDIA_APPS = listOf(
        "com.instagram.android" to "Instagram",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.google.android.youtube" to "YouTube",
        "com.twitter.android" to "X (Twitter)",
        "com.facebook.katana" to "Facebook",
        "com.whatsapp" to "WhatsApp"
    )

    /**
     * Mengambil daftar aplikasi media sosial beserta durasi penggunaannya hari ini secara asinkron (IO Thread).
     * Melakukan kueri UsageStatsManager 1 kali saja untuk mengoptimalkan performa.
     */
    suspend fun getSocialAppsUsage(
        context: Context,
        customLimits: Map<String, Int> = emptyMap()
    ): List<SocialAppInfo> = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@withContext emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        // Kueri 1 kali saja untuk seluruh statistik
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        // Buat map packageName -> totalTimeInForeground
        val usageMap = usageStatsList.groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }

        val packageManager = context.packageManager
        val savedLimits = if (customLimits.isEmpty()) PreferencesHelper.getAllAppLimits(context) else customLimits

        DEFAULT_SOCIAL_MEDIA_APPS.map { (pkg, defaultName) ->
            val appName = getAppName(packageManager, pkg) ?: defaultName
            val rawUsageMillis = usageMap[pkg] ?: 0L
            val offsetMillis = PreferencesHelper.getUsageOffset(context, pkg)
            val effectiveUsageMillis = (rawUsageMillis - offsetMillis).coerceAtLeast(0L)
            val limitMinutes = savedLimits[pkg] ?: PreferencesHelper.getAppLimit(context, pkg)

            // Ambil icon dari cache atau load di background thread IO
            val iconBitmap = iconCache[pkg] ?: run {
                val iconDrawable = getAppIcon(context, pkg)
                val bmp = iconDrawable?.let { drawableToBitmap(it) }
                if (bmp != null) iconCache[pkg] = bmp
                bmp
            }

            SocialAppInfo(
                packageName = pkg,
                appName = appName,
                usageTimeMillis = effectiveUsageMillis,
                rawUsageMillis = rawUsageMillis,
                limitMinutes = limitMinutes,
                iconBitmap = iconBitmap
            )
        }
    }

    /**
     * Reset durasi penggunaan aplikasi tertentu ke 0 menit dengan menetapkan offset waktu.
     */
    suspend fun resetAppUsage(context: Context, packageName: String) = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@withContext
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            calendar.timeInMillis,
            System.currentTimeMillis()
        ) ?: emptyList()

        val currentRawMillis = stats.filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }

        PreferencesHelper.setUsageOffset(context, packageName, currentRawMillis)
        PreferencesHelper.clearSnooze(context, packageName)
    }

    /**
     * Reset durasi seluruh aplikasi media sosial yang dipantau ke 0 menit.
     */
    suspend fun resetAllAppsUsage(context: Context) = withContext(Dispatchers.IO) {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@withContext
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            calendar.timeInMillis,
            System.currentTimeMillis()
        ) ?: emptyList()

        val usageMap = stats.groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }

        DEFAULT_SOCIAL_MEDIA_APPS.forEach { (pkg, _) ->
            val rawMillis = usageMap[pkg] ?: 0L
            PreferencesHelper.setUsageOffset(context, pkg, rawMillis)
            PreferencesHelper.clearSnooze(context, pkg)
        }
    }

    /**
     * Mendapatkan nama package aplikasi yang saat ini sedang aktif di layar depan (foreground).
     * Telah dioptimalkan agar tidak salah mendeteksi aplikasi yang baru saja ditutup.
     */
    fun getForegroundPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val startTime = now - 15_000L // Cukup periksa 15 detik terakhir

        // 1. Coba deteksi menggunakan UsageEvents dengan menangani RESUMED dan PAUSED/STOPPED
        val usageEvents = usageStatsManager.queryEvents(startTime, now)
        val event = UsageEvents.Event()
        var currentForegroundPackage: String? = null

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    currentForegroundPackage = event.packageName
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (event.packageName == currentForegroundPackage) {
                        currentForegroundPackage = null
                    }
                }
            }
        }

        if (!currentForegroundPackage.isNullOrEmpty()) {
            return currentForegroundPackage
        }

        // 2. Fallback: hanya periksa jika aplikasi aktif kurang dari 5 detik lalu
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 5_000L,
            now
        )
        return stats?.filter { it.lastTimeUsed > (now - 5_000L) }
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    /**
     * Mengambil nama label aplikasi dari PackageManager.
     */
    fun getAppName(packageManager: PackageManager, packageName: String): String? {
        return try {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mengambil icon aplikasi dari PackageManager.
     */
    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mengonversi Android Drawable menjadi Bitmap dengan aman untuk Jetpack Compose.
     */
    fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
