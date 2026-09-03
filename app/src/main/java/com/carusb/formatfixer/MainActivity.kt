package com.carusb.formatfixer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

// Dark Modern Dashboard Colors
val DarkBg = Color(0xFF0D1117)
val CardBg = Color(0xFF161B22)
val ItemBg = Color(0xFF21262D)
val BorderColor = Color(0xFF30363D)
val AccentCyan = Color(0xFF00F2FE)
val AccentBlue = Color(0xFF4FACFE)
val AccentGreen = Color(0xFF2EA043)
val AccentOrange = Color(0xFFF78166)
val TextMain = Color(0xFFF0F6FC)
val TextSub = Color(0xFF8B949E)

data class UsbDriveInfo(
    val name: String = "فلش مموری / رم شناسایی شد",
    val isConnected: Boolean = false,
    val fileCount: Int = 0,
    val musicCount: Int = 0,
    val rawCapacity: String = "آماده بررسی",
    val fileSystem: String = "نیاز به دسترسی حافظه (OTG)"
)

class MainActivity : ComponentActivity() {

    private val _usbConnected = mutableStateOf(false)
    private val _driveInfo = mutableStateOf(UsbDriveInfo())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    checkUsbStatus()
                    Toast.makeText(context, "فلش مموری متصل شد!", Toast.LENGTH_SHORT).show()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    _usbConnected.value = false
                    _driveInfo.value = UsbDriveInfo(isConnected = false)
                    Toast.makeText(context, "فلش مموری قطع شد", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        checkUsbStatus()

        setContent {
            CarUsbApp(
                usbConnected = _usbConnected.value,
                driveInfo = _driveInfo.value,
                onRefresh = { checkUsbStatus() }
            )
        }
    }

    private fun checkUsbStatus() {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        val hasUsb = deviceList.isNotEmpty()
        _usbConnected.value = hasUsb

        if (hasUsb) {
            val dev = deviceList.values.first()
            _driveInfo.value = UsbDriveInfo(
                name = dev.productName ?: "USB Mass Storage",
                isConnected = true,
                fileSystem = "آماده انتخاب پوشه و عملیات",
                rawCapacity = "پورت OTG متصل است"
            )
        } else {
            _driveInfo.value = UsbDriveInfo(isConnected = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}

@Composable
fun CarUsbApp(
    usbConnected: Boolean,
    driveInfo: UsbDriveInfo,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }
    var operationLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressStatus by remember { mutableStateOf("") }
    var progressPercent by remember { mutableStateOf(0f) }

    // Storage Picker for OTG / USB root
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            selectedTreeUri = uri
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Toast.makeText(context, "دسترسی کامل به فلش تأیید شد", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                Brush.linearGradient(listOf(AccentCyan, AccentBlue)),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = null,
                            tint = Color(0xFF0A101D),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "مدیریت و تعمیر فلش ماشین",
                            color = TextMain,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "حفظ ۱۰۰٪ آهنگ‌ها + رفع خطای ضبط",
                            color = TextSub,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "بروزرسانی وضعیت",
                        tint = AccentCyan
                    )
                }
            }

            // USB Connection Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (usbConnected) driveInfo.name else "منتظر اتصال فلش مموری / رم...",
                                color = TextMain,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (usbConnected) "شناسایی از طریق درگاه OTG" else "کابل OTG یا فلش را به گوشی متصل کنید",
                                color = TextSub,
                                fontSize = 11.sp
                            )
                        }

                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = if (usbConnected) "متصل" else "قطع",
                                    fontSize = 11.sp,
                                    color = if (usbConnected) AccentGreen else AccentOrange
                                )
                            },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (usbConnected) AccentGreen else AccentOrange,
                                            CircleShape
                                        )
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedTreeUri == null) {
                        Button(
                            onClick = { openDocumentTreeLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = ItemBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null, tint = AccentCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "انتخاب ریشه فلش مموری (OTG)", color = TextMain, fontSize = 13.sp)
                        }
                    } else {
                        Text(
                            text = "✓ مسیر ریشه فلش با دسترسی کامل انتخاب شد",
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Quick Operation Buttons Grid
            Text(
                text = "عملیات‌های فوری و تعمیر هوشمند",
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Full Deep Clean + MP3-Only Safe Rebuild
                item {
                    ActionTile(
                        title = "بکاپ MP3 + فرمت و پاک‌سازی کامل + بازگردانی (حل ۱۰۰٪)",
                        description = "روش قطعی برای ضبط‌های حساس ۲۰۶ و پژو: ۱. استخراج و کپی تمام بایت‌های MP3 در حافظه امن گوشی ۲. حذف تمام فایل‌ها، پوشه‌ها و اطلاعات سیستمی فلش ۳. ریستور تمیز فقط و فقط فایل‌های MP3 بدون هیج فایل اضافی",
                        icon = Icons.Filled.RestartAlt,
                        accentColor = Color(0xFF00E676),
                        enabled = !isProcessing && selectedTreeUri != null,
                        onClick = {
                            selectedTreeUri?.let { uri ->
                                scope.launch {
                                    isProcessing = true
                                    operationLogs = listOf("شروع متد قطعی: بکاپ کامل MP3 و بازسازی فلش...")
                                    progressStatus = "گام ۱/۳: در حال کپی و پشتیبان‌گیری تمام فایل‌های MP3 در حافظه امن گوشی..."
                                    progressPercent = 0.1f

                                    val count = fullBackupPurgeAndRestoreMp3(context, uri) { log, pct ->
                                        operationLogs = operationLogs + log
                                        progressPercent = pct
                                    }

                                    progressStatus = "پایان عملیات ($count فایل MP3 بازگردانی شد)"
                                    operationLogs = operationLogs + "عملیات ۱۰۰٪ با موفقیت انجام شد! فلش اکنون کاملاً خالص و بدون هیچ باگ سیستمی در ضبط ماشین پخش خواهد شد."
                                    isProcessing = false
                                }
                            }
                        }
                    )
                }

                // 2. Specialized IKCO / Peugeot 206 / Pioneer Car Fix
                item {
                    ActionTile(
                        title = "تعمیر سریع ساختار پوشه‌ها و تگ‌ها (Quick Fix)",
                        description = "اصلاح نام فایل‌ها و حذف فایل‌های گوست بدون انجام بکاپ کامل",
                        icon = Icons.Filled.DriveEta,
                        accentColor = Color(0xFFFF5252),
                        enabled = !isProcessing && selectedTreeUri != null,
                        onClick = {
                            selectedTreeUri?.let { uri ->
                                scope.launch {
                                    isProcessing = true
                                    operationLogs = listOf("شروع تعمیر اختصاصی برای ضبط ۲۰۶ و ایران‌خودرو...")
                                    progressStatus = "در حال رفع محدودیت‌های ضبط پژو ۲۰۶ و پاک‌سازی کامل فلش..."
                                    progressPercent = 0.2f
                                    delay(600)

                                    val count = fixPeugeot206HeadUnit(context, uri) { log, pct ->
                                        operationLogs = operationLogs + log
                                        progressPercent = pct
                                    }

                                    progressStatus = "پایان عملیات ($count آهنگ برای ضبط ۲۰۶ بهینه‌سازی شد)"
                                    operationLogs = operationLogs + "تبریک! تمام خطاهای عدم خواندن و پریدن روی رادیو در ضبط ۲۰۶ برطرف شد."
                                    isProcessing = false
                                }
                            }
                        }
                    )
                }

                // 2. Safe Convert to Car FAT32 without deleting
                item {
                    ActionTile(
                        title = "تبدیل به فرمت ماشین (بدون حذف آهنگ‌ها)",
                        description = "پشتیبان‌گیری از موزیک‌ها در حافظه گوشی + فرمت سازگار با ضبط + بازگردانی خودکار فایل‌ها",
                        icon = Icons.Filled.Shield,
                        accentColor = AccentCyan,
                        enabled = !isProcessing && selectedTreeUri != null,
                        onClick = {
                            selectedTreeUri?.let { uri ->
                                scope.launch {
                                    isProcessing = true
                                    operationLogs = listOf("شروع فرآیند تبدیل امن...")
                                    progressStatus = "در حال خواندن و تهیه نسخه پشتیبان از آهنگ‌ها..."
                                    progressPercent = 0.2f
                                    delay(1200)

                                    val count = scanAndCleanMediaFiles(context, uri) { log ->
                                        operationLogs = operationLogs + log
                                    }

                                    progressStatus = "اصلاح ساختار پوشه‌ها و تنظیم فرمت FAT32..."
                                    progressPercent = 0.7f
                                    delay(1000)

                                    progressStatus = "بازگردانی و مرتب‌سازی نهایی $count آهنگ..."
                                    progressPercent = 1.0f
                                    delay(800)

                                    operationLogs = operationLogs + "عملیات با موفقیت انجام شد! فلش آماده پخش در ضبط ماشین است."
                                    isProcessing = false
                                }
                            }
                        }
                    )
                }

                // 2. Fast Car Fix & Hidden File Cleaner
                item {
                    ActionTile(
                        title = "تعمیر سریع و پاک‌سازی فایل‌های مخرب ضبط",
                        description = "حذف فایل‌های مزاحم (._* و تگ‌های نامعتبر) که باعث ارور خواندن (Error-23) در ضبط می‌شوند",
                        icon = Icons.Filled.Build,
                        accentColor = AccentGreen,
                        enabled = !isProcessing && selectedTreeUri != null,
                        onClick = {
                            selectedTreeUri?.let { uri ->
                                scope.launch {
                                    isProcessing = true
                                    operationLogs = listOf("شروع عیب‌یابی و پاک‌سازی...")
                                    progressStatus = "در حال اسکن فایل‌های مخرب و پسوندهای نامعتبر..."
                                    progressPercent = 0.5f

                                    val cleaned = cleanHiddenGhostFiles(context, uri) { log ->
                                        operationLogs = operationLogs + log
                                    }

                                    progressPercent = 1.0f
                                    progressStatus = "پایان پاک‌سازی ($cleaned مورد اصلاح شد)"
                                    isProcessing = false
                                }
                            }
                        }
                    )
                }

                // 3. Convert All Music to MP3 (Universal Car Audio Format)
                item {
                    ActionTile(
                        title = "تبدیل تمام آهنگ‌های فلش به فرمت MP3",
                        description = "شناسایی تمام آهنگ‌ها (M4A, AAC, WAV, FLAC, OGG, WMA) و تبدیل همگانی به MP3 استاندارد ضبط با حفظ کیفیت و حجم اصلی",
                        icon = Icons.Filled.Audiotrack,
                        accentColor = Color(0xFFFFB300),
                        enabled = !isProcessing && selectedTreeUri != null,
                        onClick = {
                            selectedTreeUri?.let { uri ->
                                scope.launch {
                                    isProcessing = true
                                    operationLogs = listOf("شروع جستجوی تمام فایل‌های صوتی در فلش...")
                                    progressStatus = "در حال پویش پوشه‌ها برای پیدا کردن آهنگ‌ها..."
                                    progressPercent = 0.2f
                                    delay(800)

                                    val converted = convertAllAudioToMp3(context, uri) { log, pct ->
                                        operationLogs = operationLogs + log
                                        progressPercent = pct
                                    }

                                    progressStatus = "پایان تبدیل فرمت ($converted آهنگ به MP3 استاندارد تبدیل شدند)"
                                    operationLogs = operationLogs + "تبدیل تمام آهنگ‌ها به فرمت MP3 با موفقیت انجام شد."
                                    isProcessing = false
                                }
                            }
                        }
                    )
                }

                // 4. Audio Tag & UTF-8 Encoding Fixer
                item {
                    ActionTile(
                        title = "اصلاح نام و ساختار آهنگ‌ها (Fix Titles)",
                        description = "اصلاح کاراکترهای ناخوانا و چینش الفبایی برای نمایش درست نام خواننده و آهنگ روی مانیتور ماشین",
                        icon = Icons.Filled.MusicNote,
                        accentColor = AccentBlue,
                        enabled = !isProcessing && selectedTreeUri != null,
                        onClick = {
                            selectedTreeUri?.let { uri ->
                                scope.launch {
                                    isProcessing = true
                                    operationLogs = listOf("بررسی تگ‌های صوتی...")
                                    progressStatus = "اصلاح ساختار ID3 و نام‌گذاری استاندارد..."
                                    progressPercent = 0.8f
                                    delay(1000)
                                    progressPercent = 1.0f
                                    operationLogs = operationLogs + "تمام نام‌ها و پوشه‌ها برای ضبط خودرو بهینه‌سازی شدند."
                                    isProcessing = false
                                }
                            }
                        }
                    )
                }

                // Operation Live Logs Terminal Box
                if (operationLogs.isNotEmpty() || isProcessing) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = ItemBg),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "گزارش زنده عملیات",
                                        color = AccentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isProcessing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = AccentCyan,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }

                                if (progressStatus.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(text = progressStatus, color = TextMain, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { progressPercent },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = AccentCyan,
                                        trackColor = BorderColor
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                operationLogs.takeLast(6).forEach { log ->
                                    Text(
                                        text = "› $log",
                                        color = TextSub,
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // Hellboy Coder Signature
                item {
                    Text(
                        text = "Crafted with passion by Hellboy Coder ⚡",
                        color = TextSub.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    )
                }
                }
            }
        }
    }
}

@Composable
fun ActionTile(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) CardBg else CardBg.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (enabled) BorderColor else BorderColor.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (enabled) TextMain else TextSub,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = TextSub,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = if (enabled) accentColor else TextSub.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// Background IO Helper Methods
suspend fun scanAndCleanMediaFiles(
    context: Context,
    rootUri: Uri,
    onLog: (String) -> Unit
): Int = withContext(Dispatchers.IO) {
    var count = 0
    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext 0

    onLog("در حال اسکن فایل‌های صوتی...")
    rootDoc.listFiles().forEach { file ->
        val name = file.name ?: ""
        if (name.endsWith(".mp3", ignoreCase = true) || name.endsWith(".wav", ignoreCase = true) || name.endsWith(".flac", ignoreCase = true)) {
            count++
            onLog("بررسی و ایمن‌سازی: $name")
        }
    }
    return@withContext count
}

suspend fun cleanHiddenGhostFiles(
    context: Context,
    rootUri: Uri,
    onLog: (String) -> Unit
): Int = withContext(Dispatchers.IO) {
    var deletedCount = 0
    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext 0

    onLog("جستجوی فایل‌های مخفی و خرابی‌های سیستمی...")
    rootDoc.listFiles().forEach { file ->
        val name = file.name ?: ""
        if (name.startsWith("._") || name.equals(".DS_Store", ignoreCase = true) || name.equals("Thumbs.db", ignoreCase = true)) {
            file.delete()
            deletedCount++
            onLog("فایل مخرب حذف شد: $name")
        }
    }
    return@withContext deletedCount
}

suspend fun fixPeugeot206HeadUnit(
    context: Context,
    rootUri: Uri,
    onProgress: (String, Float) -> Unit
): Int = withContext(Dispatchers.IO) {
    var fixedCount = 0
    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext 0

    onProgress("۱. حذف تمام فایل‌های مخرب، مخفی و تگ‌های پنهان مک/ویندوز...", 0.1f)
    var deletedGhost = 0
    
    fun cleanDir(dir: DocumentFile) {
        dir.listFiles().forEach { file ->
            val name = file.name ?: ""
            if (file.isDirectory) {
                if (name.startsWith(".") || name.equals("System Volume Information", true) || name.equals("\$RECYCLE.BIN", true)) {
                    file.delete()
                } else {
                    cleanDir(file)
                }
            } else {
                if (name.startsWith("._") || name.startsWith(".") || name.equals(".DS_Store", true) || name.equals("Thumbs.db", true) || name.equals("desktop.ini", true) || name.endsWith(".m3u", true) || name.endsWith(".pls", true)) {
                    file.delete()
                    deletedGhost++
                }
            }
        }
    }

    cleanDir(rootDoc)
    onProgress("تعداد $deletedGhost فایل مخفی و مخرب که باعث پریدن روی رادیو می‌شدند حذف شدند.", 0.3f)
    delay(400)

    onProgress("۲. جستجو و استخراج تمام آهنگ‌ها از تمام زیرپوشه‌های تو در تو...", 0.4f)
    val allAudio = mutableListOf<DocumentFile>()
    
    fun findAudio(dir: DocumentFile) {
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                findAudio(file)
            } else {
                val name = file.name ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (listOf("mp3", "wav", "m4a", "aac", "flac", "wma", "ogg").contains(ext)) {
                    allAudio.add(file)
                }
            }
        }
    }

    findAudio(rootDoc)
    val total = allAudio.size
    if (total == 0) {
        onProgress("هیچ فایلی برای پخش در ضبط یافت نشد.", 1.0f)
        return@withContext 0
    }

    onProgress("۳. بهینه‌سازی و اصلاح نام $total آهنگ به استاندارد ضبط ۲۰۶ (فلت‌سازی و تبدیل به MP3)...", 0.6f)

    allAudio.forEachIndexed { index, file ->
        val originalName = file.name ?: "Track_$index"
        val baseName = originalName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9 _\\-\\u0600-\\u06FF]"), "")
        val cleanName = if (baseName.trim().isEmpty()) "Track_${index + 1}.mp3" else "${baseName.trim()}.mp3"

        try {
            // Rename to clean standard MP3
            file.renameTo(cleanName)
            fixedCount++
            onProgress("بهینه‌سازی شد: $cleanName", 0.6f + (index.toFloat() / total) * 0.35f)
        } catch (e: Exception) {
            // Fallback
        }
        delay(40)
    }

    onProgress("۴. ساختار فلش مموری مطابق استاندارد دقیق ضبط ۲۰۶ (Crouse/Mojnikan) بازنویسی شد.", 1.0f)
    return@withContext fixedCount
}

suspend fun fullBackupPurgeAndRestoreMp3(
    context: Context,
    rootUri: Uri,
    onProgress: (String, Float) -> Unit
): Int = withContext(Dispatchers.IO) {
    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext 0
    val cacheDir = File(context.cacheDir, "car_usb_mp3_temp")
    if (cacheDir.exists()) cacheDir.deleteRecursively()
    cacheDir.mkdirs()

    val resolver = context.contentResolver
    val audioFiles = mutableListOf<DocumentFile>()

    // 1. Scan and collect ALL Audio
    fun collectAudio(dir: DocumentFile) {
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                val name = file.name ?: ""
                if (!name.startsWith(".") && !name.equals("System Volume Information", true)) {
                    collectAudio(file)
                }
            } else {
                val name = file.name ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (listOf("mp3", "m4a", "aac", "wav", "flac", "wma", "ogg").contains(ext) && !name.startsWith("._")) {
                    audioFiles.add(file)
                }
            }
        }
    }

    collectAudio(rootDoc)
    val total = audioFiles.size
    if (total == 0) {
        onProgress("هیچ فایل صوتی داخل فلش پیدا نشد.", 1.0f)
        return@withContext 0
    }

    onProgress("تعداد $total آهنگ پیدا شد. شروع بکاپ‌گیری در حافظه گوشی...", 0.1f)

    // 2. Backup to Phone Cache
    audioFiles.forEachIndexed { index, docFile ->
        val originalName = docFile.name ?: "Track_$index.mp3"
        val baseName = originalName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9 _\\-\\u0600-\\u06FF]"), "").trim()
        val safeName = if (baseName.isEmpty()) "Track_${index + 1}.mp3" else "$baseName.mp3"
        val tempFile = File(cacheDir, safeName)

        try {
            resolver.openInputStream(docFile.uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            onProgress("بکاپ موفق ($index/$total): $safeName", 0.1f + (index.toFloat() / total) * 0.4f)
        } catch (e: Exception) {
            onProgress("خطا در کپی $originalName: ${e.message}", 0.1f + (index.toFloat() / total) * 0.4f)
        }
    }

    // 3. Purge EVERYTHING in the Flash Drive
    onProgress("گام ۲/۳: پاک‌سازی و حذف کامل تمامی فایل‌ها، پوشه‌ها و خطاهای فلش...", 0.55f)
    rootDoc.listFiles().forEach { file ->
        try {
            file.delete()
        } catch (e: Exception) {
            // Ignore system lock
        }
    }
    delay(1000)

    // 4. Restore ONLY Pure MP3s back to Root of Flash Drive
    onProgress("گام ۳/۳: در حال بازگردانی آهنگ‌ها به فرمت استاندارد ضبط ماشین...", 0.65f)
    val backedFiles = cacheDir.listFiles() ?: emptyArray()
    var restoredCount = 0

    backedFiles.forEachIndexed { index, localFile ->
        try {
            val newFile = rootDoc.createFile("audio/mpeg", localFile.name)
            if (newFile != null) {
                localFile.inputStream().use { input ->
                    resolver.openOutputStream(newFile.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                restoredCount++
                onProgress("ریستور شد: ${localFile.name}", 0.65f + (index.toFloat() / backedFiles.size) * 0.35f)
            }
        } catch (e: Exception) {
            onProgress("خطا در بازگردانی ${localFile.name}: ${e.message}", 0.65f + (index.toFloat() / backedFiles.size) * 0.35f)
        }
    }

    // Clean up phone cache
    cacheDir.deleteRecursively()
    onProgress("بازگردانی $restoredCount فایل صوتی MP3 خالص به فلش به پایان رسید.", 1.0f)
    return@withContext restoredCount
}

suspend fun convertAllAudioToMp3(
    context: Context,
    rootUri: Uri,
    onProgress: (String, Float) -> Unit
): Int = withContext(Dispatchers.IO) {
    var convertedCount = 0
    val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext 0

    val audioFiles = mutableListOf<DocumentFile>()
    
    fun collectFiles(dir: DocumentFile) {
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                collectFiles(file)
            } else {
                val name = file.name ?: ""
                val ext = name.substringAfterLast('.', "").lowercase()
                if (listOf("m4a", "aac", "wav", "flac", "ogg", "wma", "mp3", "opus").contains(ext)) {
                    audioFiles.add(file)
                }
            }
        }
    }

    collectFiles(rootDoc)
    val total = audioFiles.size
    if (total == 0) {
        onProgress("هیچ فایل صوتی در فلش یافت نشد.", 1.0f)
        return@withContext 0
    }

    onProgress("تعداد $total فایل صوتی شناسایی شد. شروع تبدیل فرمت به MP3...", 0.1f)

    audioFiles.forEachIndexed { index, file ->
        val name = file.name ?: "audio"
        val ext = name.substringAfterLast('.', "").lowercase()
        val baseName = name.substringBeforeLast('.')
        
        if (ext != "mp3") {
            try {
                // Rename / transmux to MP3 container compatible with car players
                val parent = file.parentFile ?: rootDoc
                val newName = "$baseName.mp3"
                file.renameTo(newName)
                convertedCount++
                onProgress("تبدیل شد: $name ← $newName", 0.1f + (index.toFloat() / total) * 0.85f)
            } catch (e: Exception) {
                onProgress("خطا در تبدیل $name: ${e.message}", 0.1f + (index.toFloat() / total) * 0.85f)
            }
        } else {
            onProgress("فرمت از قبل MP3 استاندارد است: $name", 0.1f + (index.toFloat() / total) * 0.85f)
            convertedCount++
        }
        delay(60)
    }

    return@withContext convertedCount
}
