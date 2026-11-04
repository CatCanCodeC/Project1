package com.example.project1.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.project1.util.PermissionHelper
import com.example.project1.util.PreferencesHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (PreferencesHelper.isMonitoringActive(context) && PermissionHelper.hasUsageStatsPermission(context)) {
                UsageMonitorService.startService(context)
            }
        }
    }
}
