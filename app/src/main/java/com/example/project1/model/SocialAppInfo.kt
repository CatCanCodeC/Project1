package com.example.project1.model

import android.graphics.Bitmap

data class SocialAppInfo(
    val packageName: String,
    val appName: String,
    val usageTimeMillis: Long = 0L,
    val rawUsageMillis: Long = usageTimeMillis,
    val limitMinutes: Int = 30, // Batas default 30 menit
    val isMonitoringEnabled: Boolean = true,
    val iconBitmap: Bitmap? = null
) {
    val usageMinutes: Int
        get() = (usageTimeMillis / 1000 / 60).toInt()

    val progress: Float
        get() = if (limitMinutes > 0) (usageMinutes.toFloat() / limitMinutes.toFloat()).coerceAtMost(1f) else 0f

    val isLimitExceeded: Boolean
        get() = usageMinutes >= limitMinutes && limitMinutes > 0
}
