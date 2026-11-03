package com.example.project1

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.project1.model.SocialAppInfo
import com.example.project1.service.UsageMonitorService
import com.example.project1.ui.ReminderOverlayActivity
import com.example.project1.ui.theme.Project1Theme
import com.example.project1.util.PermissionHelper
import com.example.project1.util.PreferencesHelper
import com.example.project1.util.UsageStatsHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Project1Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
    }

    companion object {
        @Volatile
        var isAppInForeground: Boolean = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var hasUsagePermission by remember { mutableStateOf(PermissionHelper.hasUsageStatsPermission(context)) }
    var hasNotificationPermission by remember { mutableStateOf(PermissionHelper.hasNotificationPermission(context)) }
    var hasOverlayPermission by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(context)) }
    var isMonitoringActive by remember { mutableStateOf(PreferencesHelper.isMonitoringActive(context)) }

    // Map untuk menyimpan batas waktu per package secara persisten
    val appLimits = remember {
        mutableStateMapOf<String, Int>().apply {
            putAll(PreferencesHelper.getAllAppLimits(context))
        }
    }
    var socialApps by remember { mutableStateOf(emptyList<SocialAppInfo>()) }

    fun refreshUsageData() {
        hasUsagePermission = PermissionHelper.hasUsageStatsPermission(context)
        hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
        hasOverlayPermission = PermissionHelper.hasOverlayPermission(context)
        isMonitoringActive = PreferencesHelper.isMonitoringActive(context)

        if (hasUsagePermission) {
            coroutineScope.launch {
                socialApps = UsageStatsHelper.getSocialAppsUsage(context, appLimits)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshUsageData()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Izin notifikasi diberikan!", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pengingat Media Sosial", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { refreshUsageData() }) {
                        Text("🔄", fontSize = 18.sp)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Ringkasan Penggunaan Hari Ini & Tombol Reset Keseluruhan
            if (hasUsagePermission && socialApps.isNotEmpty()) {
                val totalMinutes = socialApps.sumOf { it.usageMinutes }
                val exceededCount = socialApps.count { it.isLimitExceeded }
                val hours = totalMinutes / 60
                val remainingMins = totalMinutes % 60

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (exceededCount > 0) Color(0xFFFBE9E7) else MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Total Durasi Media Sosial Hari Ini",
                                        fontSize = 13.sp,
                                        color = Color.DarkGray
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (hours > 0) "$hours jam $remainingMins menit" else "$remainingMins menit",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (exceededCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            UsageStatsHelper.resetAllAppsUsage(context)
                                            refreshUsageData()
                                            Toast.makeText(context, "Semua durasi penggunaan berhasil di-reset ke 0!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("🔄 Reset Semua", fontSize = 12.sp)
                                }
                            }

                            if (exceededCount > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "⚠️ $exceededCount aplikasi telah melampaui batas harian!",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFD32F2F)
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Status Izin Sistem
            item {
                Text(
                    text = "Status Izin Sistem",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                PermissionCard(
                    title = "Akses Statistik Penggunaan (Usage Access)",
                    description = "Diperlukan untuk membaca durasi aplikasi media sosial.",
                    isGranted = hasUsagePermission,
                    onRequestPermission = { PermissionHelper.openUsageAccessSettings(context) }
                )
            }

            item {
                PermissionCard(
                    title = "Izin Notifikasi",
                    description = "Diperlukan untuk mengirim peringatan saat waktu habis.",
                    isGranted = hasNotificationPermission,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            item {
                PermissionCard(
                    title = "Izin Tampilan Overlay (Draw over apps)",
                    description = "Penting: Untuk memunculkan pop-up peringatan interaktif di atas aplikasi saat waktu habis.",
                    isGranted = hasOverlayPermission,
                    onRequestPermission = { PermissionHelper.openOverlaySettings(context) }
                )
            }

            // Section 3: Toggle Layanan Pemantauan
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMonitoringActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Layanan Pemantauan Latar Belakang",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = if (isMonitoringActive) "Pemantauan aktif & siap memunculkan pop-up batas waktu" else "Pemantauan dinonaktifkan",
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = isMonitoringActive,
                            onCheckedChange = { active ->
                                if (active && !hasUsagePermission) {
                                    Toast.makeText(context, "Berikan izin Usage Access terlebih dahulu!", Toast.LENGTH_LONG).show()
                                    return@Switch
                                }
                                isMonitoringActive = active
                                if (active) {
                                    UsageMonitorService.startService(context)
                                    Toast.makeText(context, "Layanan pemantau dimulai", Toast.LENGTH_SHORT).show()
                                } else {
                                    UsageMonitorService.stopService(context)
                                    Toast.makeText(context, "Layanan pemantau dihentikan", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            // Section 4: Daftar Aplikasi & Batas Waktu
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Penggunaan Hari Ini & Batas Waktu",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { refreshUsageData() }) {
                        Text("Perbarui")
                    }
                }
            }

            if (!hasUsagePermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = "Silakan berikan izin 'Akses Statistik Penggunaan' di atas untuk melihat durasi bermain media sosial.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            } else if (socialApps.isEmpty()) {
                item {
                    Text("Memuat data penggunaan...", modifier = Modifier.padding(16.dp))
                }
            } else {
                items(socialApps) { app ->
                    SocialAppItem(
                        appInfo = app,
                        onLimitChanged = { newLimit ->
                            PreferencesHelper.setAppLimit(context, app.packageName, newLimit)
                            appLimits[app.packageName] = newLimit
                            refreshUsageData()
                        },
                        onResetUsage = {
                            coroutineScope.launch {
                                UsageStatsHelper.resetAppUsage(context, app.packageName)
                                refreshUsageData()
                                Toast.makeText(context, "Durasi ${app.appName} di-reset ke 0 menit!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onResetDefaultLimit = {
                            PreferencesHelper.resetAppLimitToDefault(context, app.packageName)
                            appLimits[app.packageName] = 30
                            refreshUsageData()
                            Toast.makeText(context, "Batas ${app.appName} dikembalikan ke 30 menit", Toast.LENGTH_SHORT).show()
                        },
                        onRefreshRequested = { refreshUsageData() }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isGranted) "✅" else "⚠️",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(text = description, fontSize = 12.sp, color = Color.DarkGray)
            }
            if (!isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
                ) {
                    Text("Izinkan", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SocialAppItem(
    appInfo: SocialAppInfo,
    onLimitChanged: (Int) -> Unit,
    onResetUsage: () -> Unit,
    onResetDefaultLimit: () -> Unit,
    onRefreshRequested: () -> Unit
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    // State lokal untuk slider agar pergerakan slider halus tanpa lag
    var localSliderValue by remember(appInfo.limitMinutes) {
        mutableFloatStateOf(appInfo.limitMinutes.toFloat())
    }

    val isSnoozed = PreferencesHelper.isAppSnoozed(context, appInfo.packageName)
    val snoozeRemaining = if (isSnoozed) PreferencesHelper.getSnoozeRemainingMinutes(context, appInfo.packageName) else 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon Aplikasi (Langsung menggunakan Bitmap yang di-cache di IO Thread)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (appInfo.iconBitmap != null) {
                        Image(
                            bitmap = appInfo.iconBitmap.asImageBitmap(),
                            contentDescription = appInfo.appName,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Text("📱", fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = appInfo.appName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Penggunaan: ${appInfo.usageMinutes} m / Batas: ${appInfo.limitMinutes} m",
                        fontSize = 13.sp,
                        color = if (appInfo.isLimitExceeded) MaterialTheme.colorScheme.error else Color.Gray
                    )
                }

                // Status Badge
                Column(horizontalAlignment = Alignment.End) {
                    if (appInfo.isLimitExceeded) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Batas Habis",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (isSnoozed) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = Color(0xFF0288D1),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "⏳ Ditunda ($snoozeRemaining m)",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar Penggunaan
            LinearProgressIndicator(
                progress = { appInfo.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (appInfo.isLimitExceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = Color.LightGray.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { isExpanded = !isExpanded }) {
                    Text(if (isExpanded) "Sembunyikan Pengaturan" else "Atur Batas & Opsi Reset")
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Batas Waktu Harian: ${localSliderValue.toInt()} menit",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = onResetDefaultLimit) {
                            Text("Reset Batas (30m)", fontSize = 11.sp)
                        }
                    }

                    Slider(
                        value = localSliderValue,
                        onValueChange = { localSliderValue = it },
                        onValueChangeFinished = { onLimitChanged(localSliderValue.toInt()) },
                        valueRange = 5f..180f,
                        steps = 34 // Kelipatan 5 menit
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Baris Tombol Aksi: Reset Waktu & Uji Coba Overlay
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onResetUsage,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("🔄 Reset Waktu (0m)", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                ReminderOverlayActivity.start(
                                    context = context,
                                    packageName = appInfo.packageName,
                                    appName = appInfo.appName,
                                    usageMinutes = (appInfo.usageMinutes).coerceAtLeast(appInfo.limitMinutes + 5),
                                    limitMinutes = appInfo.limitMinutes,
                                    rawUsageMillis = appInfo.rawUsageMillis
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🧪 Uji Coba Pop-up", fontSize = 11.sp)
                        }
                    }

                    if (isSnoozed) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                PreferencesHelper.clearSnooze(context, appInfo.packageName)
                                Toast.makeText(context, "Penundaan dibatalkan", Toast.LENGTH_SHORT).show()
                                onRefreshRequested()
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Batalkan Penundaan (Hapus Snooze)", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
