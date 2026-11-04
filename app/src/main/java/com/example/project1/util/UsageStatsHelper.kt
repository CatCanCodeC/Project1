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
     * Mengambil daftar aplikasi media sosial beserta durasi penggunaannya hari ini secara asinkron (IO Thread),
     * termasuk durasi sesi aktif yang sedang berjalan saat ini (real-time).
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

        // Kueri agregat statistik harian
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        // Buat map packageName -> totalTimeInForeground
        val usageMap = usageStatsList.groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }

        // Periksa sesi aktif foreground saat ini agar durasi bertambah secara real-time
        val (currentFgPkg, ongoingDurationMillis) = getActiveSessionInfo(usageStatsManager, startTime, endTime)

        val packageManager = context.packageManager
        val savedLimits = if (customLimits.isEmpty()) PreferencesHelper.getAllAppLimits(context) else customLimits

        DEFAULT_SOCIAL_MEDIA_APPS.map { (pkg, defaultName) ->
            val appName = getAppName(packageManager, pkg) ?: defaultName
            val baseUsage = usageMap[pkg] ?: 0L
            // Jika aplikasi ini sedang aktif dipakai di layar, tambahkan durasi sesi terkini
            val rawUsageMillis = if (pkg == currentFgPkg && ongoingDurationMillis > 0L) {
                baseUsage + ongoingDurationMillis
            } else {
                baseUsage
            }

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
     * Membaca riwayat event dan mengembalikan paket yang saat ini sedang aktif (ACTIVITY_RESUMED tanpa PAUSED)
     * beserta durasi sesi berjalan (dalam milidetik).
     */
    fun getActiveSessionInfo(
        usageStatsManager: UsageStatsManager,
        startTime: Long,
        endTime: Long
    ): Pair<String?, Long> {
        return try {
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            var currentForegroundPackage: String? = null
            var lastResumeTime = 0L

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        currentForegroundPackage = event.packageName
                        lastResumeTime = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (event.packageName == currentForegroundPackage) {
                            currentForegroundPackage = null
                            lastResumeTime = 0L
                        }
                    }
                }
            }

            val ongoingMillis = if (currentForegroundPackage != null && lastResumeTime > 0L) {
                (endTime - lastResumeTime).coerceAtLeast(0L)
            } else {
                0L
            }

            Pair(currentForegroundPackage, ongoingMillis)
        } catch (_: Exception) {
            Pair(null, 0L)
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
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        val baseUsage = stats.filter { it.packageName == packageName }
            .sumOf { it.totalTimeInForeground }

        val (fgPkg, ongoingMillis) = getActiveSessionInfo(usageStatsManager, startTime, endTime)
        val currentTotalMillis = if (packageName == fgPkg) baseUsage + ongoingMillis else baseUsage

        PreferencesHelper.setUsageOffset(context, packageName, currentTotalMillis)
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
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: emptyList()

        val usageMap = stats.groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }

        val (fgPkg, ongoingMillis) = getActiveSessionInfo(usageStatsManager, startTime, endTime)

        DEFAULT_SOCIAL_MEDIA_APPS.forEach { (pkg, _) ->
            val base = usageMap[pkg] ?: 0L
            val currentTotal = if (pkg == fgPkg) base + ongoingMillis else base
            PreferencesHelper.setUsageOffset(context, pkg, currentTotal)
            PreferencesHelper.clearSnooze(context, pkg)
        }
    }

    /**
     * Mendapatkan nama package aplikasi yang saat ini sedang aktif di layar depan (foreground).
     * Memeriksa rentang waktu yang cukup (hingga 2 jam ke belakang) untuk menangkap sesi aktif yang sedang berlangsung.
     */
    fun getForegroundPackage(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val startTime = now - (2 * 60 * 60 * 1000L) // Periksa 2 jam terakhir
        val (currentFgPkg, _) = getActiveSessionInfo(usageStatsManager, startTime, now)

        if (!currentFgPkg.isNullOrEmpty()) {
            return currentFgPkg
        }

        // Fallback: periksa queryUsageStats untuk aplikasi dengan lastTimeUsed terbaru dalam 60 detik terakhir
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 60_000L,
            now
        )
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
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
