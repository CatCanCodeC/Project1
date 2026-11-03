package com.example.project1.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.project1.MainActivity
import com.example.project1.ui.theme.Project1Theme
import com.example.project1.util.PreferencesHelper
import com.example.project1.util.UsageStatsHelper

class ReminderOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Memastikan aktivitas dapat tampil saat layar terkunci / menyala
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Media Sosial"
        val usageMinutes = intent.getIntExtra(EXTRA_USAGE_MINUTES, 0)
        val limitMinutes = intent.getIntExtra(EXTRA_LIMIT_MINUTES, 30)
        val rawUsageMillis = intent.getLongExtra(EXTRA_RAW_USAGE_MILLIS, (usageMinutes * 60 * 1000L))

        setContent {
            Project1Theme {
                BackHandler {
                    returnToHomeScreen()
                }

                ReminderOverlayDialog(
                    packageName = packageName,
                    appName = appName,
                    usageMinutes = usageMinutes,
                    limitMinutes = limitMinutes,
                    onExitApp = { returnToHomeScreen() },
                    onSnooze = { minutes ->
                        PreferencesHelper.snoozeApp(this, packageName, minutes)
                        Toast.makeText(this, "Batas ditunda selama $minutes menit", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onIncreaseLimit = { addedMinutes ->
                        val newLimit = usageMinutes + addedMinutes
                        PreferencesHelper.setAppLimit(this, packageName, newLimit)
                        PreferencesHelper.snoozeApp(this, packageName, 5)
                        Toast.makeText(this, "Batas $appName dinaikkan ke $newLimit menit", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onResetUsage = {
                        PreferencesHelper.setUsageOffset(this, packageName, rawUsageMillis)
                        PreferencesHelper.clearSnooze(this, packageName)
                        Toast.makeText(this, "Durasi $appName berhasil di-reset ke 0 menit!", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onOpenSettings = {
                        // Tunda otomatis 5 menit agar pengguna leluasa mengatur slider di MainActivity
                        PreferencesHelper.snoozeApp(this, packageName, 5)
                        val settingsIntent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(settingsIntent)
                        finish()
                    }
                )
            }
        }
    }

    private fun returnToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_USAGE_MINUTES = "extra_usage_minutes"
        const val EXTRA_LIMIT_MINUTES = "extra_limit_minutes"
        const val EXTRA_RAW_USAGE_MILLIS = "extra_raw_usage_millis"

        fun start(
            context: Context,
            packageName: String,
            appName: String,
            usageMinutes: Int,
            limitMinutes: Int,
            rawUsageMillis: Long = 0L
        ) {
            val intent = Intent(context, ReminderOverlayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_USAGE_MINUTES, usageMinutes)
                putExtra(EXTRA_LIMIT_MINUTES, limitMinutes)
                putExtra(EXTRA_RAW_USAGE_MILLIS, rawUsageMillis)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun ReminderOverlayDialog(
    packageName: String,
    appName: String,
    usageMinutes: Int,
    limitMinutes: Int,
    onExitApp: () -> Unit,
    onSnooze: (Int) -> Unit,
    onIncreaseLimit: (Int) -> Unit,
    onResetUsage: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val appIconDrawable = remember(packageName) {
        if (packageName.isNotEmpty()) UsageStatsHelper.getAppIcon(context, packageName) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Sentuhan di luar dialog tidak menutup sembarangan
            },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Baris Atas: Tombol Tutup/Tunda Cepat (✕)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = { onSnooze(5) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    }
                }

                // Icon Header / App Icon
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFEBEE)),
                    contentAlignment = Alignment.Center
                ) {
                    if (appIconDrawable != null) {
                        val bitmap = remember(appIconDrawable) {
                            UsageStatsHelper.drawableToBitmap(appIconDrawable)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = appName,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Text("⏳", fontSize = 32.sp)
                        }
                    } else {
                        Text("⏳", fontSize = 32.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Judul
                Text(
                    text = "Waktu Habis!",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFD32F2F)
                )

                Text(
                    text = "Batas bermain $appName telah tercapai",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Kotak Rincian Penggunaan
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Durasi Hari Ini:", fontSize = 13.sp, color = Color.DarkGray)
                            Text(
                                "$usageMinutes menit",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Batas yang Ditentukan:", fontSize = 13.sp, color = Color.DarkGray)
                            Text(
                                "$limitMinutes menit",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val overMinutes = (usageMinutes - limitMinutes).coerceAtLeast(0)
                        if (overMinutes > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Kelebihan Waktu:", fontSize = 13.sp, color = Color.DarkGray)
                                Text(
                                    "+$overMinutes menit",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "🌱 Istirahatkan matamu sejenak dan lakukan peregangan ringan!",
                    fontSize = 12.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Tombol Aksi Utama: Keluar ke Beranda
                Button(
                    onClick = onExitApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        "Keluar & Kembali ke Beranda",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tombol Aksi Kedua: Tunda 5 Menit (Snooze)
                OutlinedButton(
                    onClick = { onSnooze(5) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        "⏳ Tunda 5 Menit (Snooze)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Opsi Cepat: Tambah Batas & Reset Waktu langsung dari dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onIncreaseLimit(15) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("+15m Batas", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = onResetUsage,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🔄 Reset 0m", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Opsi Menuju Pengaturan
                TextButton(onClick = onOpenSettings) {
                    Text(
                        "Ubah Batas Waktu di Pengaturan",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
