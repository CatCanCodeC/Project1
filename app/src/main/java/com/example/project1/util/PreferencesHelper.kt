package com.example.project1.util

import android.content.Context
import android.content.SharedPreferences

object PreferencesHelper {

    private const val PREFS_NAME = "project1_usage_prefs"
    private const val KEY_MONITORING_ACTIVE = "key_monitoring_active"
    private const val KEY_LAST_CHECKED_DAY = "key_last_checked_day"
    private const val PREFIX_LIMIT = "limit_"
    private const val PREFIX_SNOOZE = "snooze_"
    private const val PREFIX_USAGE_OFFSET = "usage_offset_"
    private const val DEFAULT_LIMIT_MINUTES = 30

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Status aktif/tidaknya layanan pemantauan latar belakang (default: true).
     */
    fun isMonitoringActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MONITORING_ACTIVE, true)
    }

    fun setMonitoringActive(context: Context, active: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MONITORING_ACTIVE, active).apply()
    }

    /**
     * Mengambil batas waktu harian untuk aplikasi tertentu (dalam menit).
     */
    fun getAppLimit(context: Context, packageName: String): Int {
        return getPrefs(context).getInt(PREFIX_LIMIT + packageName, DEFAULT_LIMIT_MINUTES)
    }

    /**
     * Menyimpan batas waktu harian untuk aplikasi tertentu (dalam menit).
     */
    fun setAppLimit(context: Context, packageName: String, limitMinutes: Int) {
        getPrefs(context).edit().putInt(PREFIX_LIMIT + packageName, limitMinutes).apply()
    }

    /**
     * Mengembalikan batas ke nilai default (30 menit).
     */
    fun resetAppLimitToDefault(context: Context, packageName: String) {
        getPrefs(context).edit().putInt(PREFIX_LIMIT + packageName, DEFAULT_LIMIT_MINUTES).apply()
    }

    /**
     * Mengambil semua batas waktu aplikasi yang tersimpan.
     */
    fun getAllAppLimits(context: Context): Map<String, Int> {
        val prefs = getPrefs(context)
        val result = mutableMapOf<String, Int>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(PREFIX_LIMIT) && value is Int) {
                val pkg = key.removePrefix(PREFIX_LIMIT)
                result[pkg] = value
            }
        }
        return result
    }

    /**
     * Menunda (snooze) peringatan untuk aplikasi tertentu selama sejumlah menit (default 5 menit).
     */
    fun snoozeApp(context: Context, packageName: String, minutes: Int = 5) {
        val untilMillis = System.currentTimeMillis() + (minutes * 60 * 1000L)
        getPrefs(context).edit().putLong(PREFIX_SNOOZE + packageName, untilMillis).apply()
    }

    /**
     * Memeriksa apakah aplikasi sedang dalam masa penundaan (snooze).
     */
    fun isAppSnoozed(context: Context, packageName: String): Boolean {
        val untilMillis = getPrefs(context).getLong(PREFIX_SNOOZE + packageName, 0L)
        return System.currentTimeMillis() < untilMillis
    }

    /**
     * Mengambil sisa waktu penundaan (dalam menit).
     */
    fun getSnoozeRemainingMinutes(context: Context, packageName: String): Int {
        val untilMillis = getPrefs(context).getLong(PREFIX_SNOOZE + packageName, 0L)
        val remaining = untilMillis - System.currentTimeMillis()
        return if (remaining > 0) ((remaining / 1000 / 60) + 1).toInt() else 0
    }

    /**
     * Menghapus status penundaan untuk aplikasi tertentu.
     */
    fun clearSnooze(context: Context, packageName: String) {
        getPrefs(context).edit().remove(PREFIX_SNOOZE + packageName).apply()
    }

    /**
     * Mengambil offset pengurangan waktu penggunaan (digunakan saat reset manual).
     */
    fun getUsageOffset(context: Context, packageName: String): Long {
        return getPrefs(context).getLong(PREFIX_USAGE_OFFSET + packageName, 0L)
    }

    /**
     * Menyimpan offset waktu penggunaan saat ini agar durasi terbaca menjadi 0 menit.
     */
    fun setUsageOffset(context: Context, packageName: String, currentRawUsageMillis: Long) {
        getPrefs(context).edit().putLong(PREFIX_USAGE_OFFSET + packageName, currentRawUsageMillis).apply()
    }

    /**
     * Menghapus offset penggunaan aplikasi tertentu (kembali ke durasi asli sistem).
     */
    fun clearUsageOffset(context: Context, packageName: String) {
        getPrefs(context).edit().remove(PREFIX_USAGE_OFFSET + packageName).apply()
    }

    /**
     * Menghapus seluruh offset penggunaan yang tersimpan.
     */
    fun clearAllUsageOffsets(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(PREFIX_USAGE_OFFSET)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    /**
     * Memeriksa dan memperbarui tanggal terakhir untuk mendeteksi pergantian hari.
     */
    fun isNewDay(context: Context, currentDayOfYear: Int): Boolean {
        val prefs = getPrefs(context)
        val lastDay = prefs.getInt(KEY_LAST_CHECKED_DAY, -1)
        if (lastDay != currentDayOfYear) {
            prefs.edit().putInt(KEY_LAST_CHECKED_DAY, currentDayOfYear).apply()
            // Reset offset saat pergantian hari baru
            clearAllUsageOffsets(context)
            return true
        }
        return false
    }
}
