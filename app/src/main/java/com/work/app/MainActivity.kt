package com.work.app

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.work.app.calendar.DeviceCalendarSync
import com.work.app.data.*
import com.work.app.notification.PinnedTaskNotification
import com.work.app.sync.FirebaseSync
import com.work.app.update.OtaUpdater
import com.work.app.wallpaper.LockScreenRenderer
import com.work.app.worker.DailyResetWorker
import java.io.File
import java.util.concurrent.Executors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DailyResetWorker.scheduleDaily(this)

        val perms = mutableListOf(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            PinnedTaskNotification.showOrUpdate(this)
        }.launch(perms.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WorkAppScreen()
            }
        }
    }
}

@Composable
fun WorkAppScreen() {
    val ctx = LocalContext.current
    val repo = remember { WorkRepository(ctx) }
    var tab by remember { mutableStateOf(0) } // 0=Việc, 1=Lịch, 2=Màn hình khóa, 3=Cài đặt
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var tasks by remember { mutableStateOf(repo.getTasksForDate(selectedDate)) }
    var doneIds by remember { mutableStateOf(repo.getCompletedIds(selectedDate)) }
    var viewMatrix by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var presetQuad by remember { mutableStateOf(Quadrant.Q1) }
    var wallpapers by remember { mutableStateOf(repo.getWallpapers()) }
    var activeWallPath by remember { mutableStateOf(repo.getActiveWallpaperPath()) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val dest = File(ctx.filesDir, "wall_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(it)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            repo.addWallpaper(SavedWallpaper(dest.name, "Ảnh của bạn", dest.absolutePath))
            wallpapers = repo.getWallpapers()
            activeWallPath = dest.absolutePath
            LockScreenRenderer.renderAndApplyLockScreen(ctx)
            statusBanner = "Đã thêm ảnh vào kho & cập nhật màn hình khóa"
        }
    }

    val bgExecutor = remember { Executors.newSingleThreadExecutor() }

    fun refreshLocalOnly() {
        tasks = repo.getTasksForDate(selectedDate)
        doneIds = repo.getCompletedIds(selectedDate)
        bgExecutor.execute {
            PinnedTaskNotification.showOrUpdate(ctx)
            LockScreenRenderer.renderAndApplyLockScreen(ctx)
        }
    }

    fun refreshAll() {
        refreshLocalOnly()
        if (repo.getSyncRoomCode().isNotBlank()) {
            FirebaseSync.pushToCloud(ctx)
        }
    }

    LaunchedEffect(Unit) {
        if (repo.getSyncRoomCode().isNotBlank()) {
            FirebaseSync.pullFromCloud(ctx) { ok, _ ->
                if (ok) refreshLocalOnly()
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF12151C),
        floatingActionButton = {
            if (tab == 0 || tab == 1) {
                FloatingActionButton(
                    onClick = {
                        presetQuad = Quadrant.Q1
                        showAddDialog = true
                    },
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    shape = CircleShape
                ) {
                    Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161A23)) {
                val items = listOf("Việc" to "⊞", "Lịch" to "📅", "Màn hình khóa" to "🔒", "Cài đặt" to "⚙")
                items.forEachIndexed { idx, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Text(icon, fontSize = 18.sp) },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            statusBanner?.let { msg ->
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { statusBanner = null }
                ) {
                    Text(msg, color = Color(0xFF34D399), fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }

            when (tab) {
                0 -> TasksTabView(
                    tasks = tasks,
                    doneIds = doneIds,
                    viewMatrix = viewMatrix,
                    onToggleView = { viewMatrix = it },
                    onToggleTask = { id ->
                        repo.toggleTaskDone(id, selectedDate)
                        refreshAll()
                    },
                    onAddInQuad = { q ->
                        presetQuad = q
                        showAddDialog = true
                    },
                    onDeleteTask = { id ->
                        val updated = repo.getTasks().filterNot { it.id == id }
                        repo.saveTasks(updated)
                        refreshAll()
                    }
                )
                1 -> CalendarTabView(
                    selectedDate = selectedDate,
                    onSelectDate = { d ->
                        selectedDate = d
                        tasks = repo.getTasksForDate(d)
                        doneIds = repo.getCompletedIds(d)
                    },
                    tasks = tasks,
                    doneIds = doneIds,
                    onToggleTask = { id ->
                        repo.toggleTaskDone(id, selectedDate)
                        refreshAll()
                    }
                )
                2 -> LockScreenTabView(
                    wallpapers = wallpapers,
                    activeWallPath = activeWallPath,
                    onPickNewImage = { imagePicker.launch("image/*") },
                    onSelectWallpaper = { path ->
                        repo.setActiveWallpaperPath(path)
                        activeWallPath = path
                        bgExecutor.execute { LockScreenRenderer.renderAndApplyLockScreen(ctx) }
                        statusBanner = "Đã đổi hình nền màn hình khóa"
                    },
                    onApplyNow = {
                        bgExecutor.execute { LockScreenRenderer.renderAndApplyLockScreen(ctx) }
                        statusBanner = "Đã cập nhật màn hình khóa!"
                    }
                )
                else -> SettingsTabView(
                    repo = repo,
                    onPickNewImage = { imagePicker.launch("image/*") },
                    onShowNotif = { PinnedTaskNotification.showOrUpdate(ctx) },
                    onSyncPulled = { refreshLocalOnly() }
                )
            }
        }

        if (showAddDialog) {
            AddTaskDialog(
                initialQuad = presetQuad,
                initialDate = selectedDate,
                onDismiss = { showAddDialog = false },
                onSave = { newTask ->
                    val list = repo.getTasks().toMutableList()
                    list.add(0, newTask)
                    repo.saveTasks(list)
                    showAddDialog = false
                    refreshAll()
                }
            )
        }
    }
}

@Composable
fun TasksTabView(
    tasks: List<WorkTask>,
    doneIds: Set<String>,
    viewMatrix: Boolean,
    onToggleView: (Boolean) -> Unit,
    onToggleTask: (String) -> Unit,
    onAddInQuad: (Quadrant) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Chào bạn! 👋", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("Hôm nay làm gì? (${tasks.size} việc)", fontSize = 12.sp, color = Color(0xFF94A3B8))
            }
            Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(50)) {
                Text("✓ ${doneIds.size}/${tasks.size}", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = viewMatrix, onClick = { onToggleView(true) }, label = { Text("Ma trận 4 ô") })
            FilterChip(selected = !viewMatrix, onClick = { onToggleView(false) }, label = { Text("Danh sách") })
        }
        Spacer(Modifier.height(10.dp))

        if (viewMatrix) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    QuadrantCard(Quadrant.Q1, "🔥", tasks.filter { it.quadrant == Quadrant.Q1 }, doneIds, onToggleTask, onAddInQuad, onDeleteTask, Modifier.weight(1f).fillMaxHeight())
                    QuadrantCard(Quadrant.Q2, "📅", tasks.filter { it.quadrant == Quadrant.Q2 }, doneIds, onToggleTask, onAddInQuad, onDeleteTask, Modifier.weight(1f).fillMaxHeight())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    QuadrantCard(Quadrant.Q3, "⚡", tasks.filter { it.quadrant == Quadrant.Q3 }, doneIds, onToggleTask, onAddInQuad, onDeleteTask, Modifier.weight(1f).fillMaxHeight())
                    QuadrantCard(Quadrant.Q4, "☰", tasks.filter { it.quadrant == Quadrant.Q4 }, doneIds, onToggleTask, onAddInQuad, onDeleteTask, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(tasks, key = { it.id }) { t ->
                    TaskRowCard(t, doneIds.contains(t.id), { onToggleTask(t.id) }, { onDeleteTask(t.id) })
                }
            }
        }
    }
}

@Composable
fun QuadrantCard(
    quad: Quadrant,
    icon: String,
    items: List<WorkTask>,
    doneIds: Set<String>,
    onToggleTask: (String) -> Unit,
    onAddInQuad: (Quadrant) -> Unit,
    onDeleteTask: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = Color(quad.colorHex)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1A1F29))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .clickable { onAddInQuad(quad) }
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onAddInQuad(quad) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(icon, fontSize = 14.sp)
                Spacer(Modifier.width(5.dp))
                Text(quad.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF262D3B)).clickable { onAddInQuad(quad) },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(items, key = { it.id }) { t ->
                val done = doneIds.contains(t.id)
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF232936)).clickable { onToggleTask(t.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape)
                            .border(2.dp, if (done) Color(0xFF34D399) else accent, CircleShape)
                            .background(if (done) Color(0xFF34D399) else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        if (done) Text("✓", color = Color(0xFF0F172A), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (done) Color.Gray else Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None)
                        val rem = NoteReminderParser.computeReminderTime(t)
                        Text("${t.time ?: "Cả ngày"} · 🔔 ${rem ?: "--"}", fontSize = 10.sp, color = Color(0xFF94A3B8))
                    }
                    Text("✕", color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.clickable { onDeleteTask(t.id) }.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
                }
            }
        }
    }
}

@Composable
fun TaskRowCard(t: WorkTask, isDone: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val accent = Color(t.quadrant.colorHex)
    val rem = NoteReminderParser.computeReminderTime(t)
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1A1F29)).clickable { onToggle() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape)
                .border(2.dp, if (isDone) Color(0xFF34D399) else accent, CircleShape)
                .background(if (isDone) Color(0xFF34D399) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) Text("✓", color = Color(0xFF0F172A), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (isDone) Color.Gray else Color.White, fontWeight = FontWeight.SemiBold, textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None)
            Text("${t.time ?: "Cả ngày"} · ${t.repeat.name} · 🔔 ${rem ?: "--"}", fontSize = 12.sp, color = Color(0xFF94A3B8))
        }
        Text("✕", color = Color(0xFF64748B), modifier = Modifier.clickable { onDelete() }.padding(6.dp))
    }
}

@Composable
fun CalendarTabView(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    tasks: List<WorkTask>,
    doneIds: Set<String>,
    onToggleTask: (String) -> Unit
) {
    val ctx = LocalContext.current
    val events = remember(selectedDate) { DeviceCalendarSync.getEventsForDate(ctx, selectedDate) }
    val monday = selectedDate.minusDays(((selectedDate.dayOfWeek.value - 1).toLong()))
    val weekDates = (0..6).map { monday.plusDays(it.toLong()) }
    val labels = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

    Column(modifier = Modifier.fillMaxSize()) {
        Text(selectedDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale("vi", "VN"))), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1A1F29)).padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            weekDates.forEachIndexed { i, d ->
                val sel = d == selectedDate
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Color(0xFFF59E0B) else Color.Transparent)
                        .clickable { onSelectDate(d) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(labels[i], fontSize = 10.sp, color = if (sel) Color.Black else Color(0xFF94A3B8))
                    Text("${d.dayOfMonth}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (sel) Color.Black else Color.White)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { ev ->
                Surface(color = Color(0xFF451A03), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("📅 ${ev.title} (Lịch máy/Google)", color = Color(0xFFFDE68A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
                }
            }
            items(tasks, key = { it.id }) { t ->
                TaskRowCard(t, doneIds.contains(t.id), { onToggleTask(t.id) }, {})
            }
        }
    }
}

@Composable
fun LockScreenTabView(
    wallpapers: List<SavedWallpaper>,
    activeWallPath: String?,
    onPickNewImage: () -> Unit,
    onSelectWallpaper: (String) -> Unit,
    onApplyNow: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Màn hình khóa", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onApplyNow, modifier = Modifier.fillMaxWidth()) {
            Text("Áp dụng lên Màn hình khóa ngay")
        }
        Spacer(Modifier.height(10.dp))
        Text("Kho hình nền của bạn (${wallpapers.size} ảnh)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
        Spacer(Modifier.height(8.dp))

        LazyVerticalGrid(columns = GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box(
                    modifier = Modifier.aspectRatio(9f / 14f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1F29)).border(1.dp, Color(0xFF475569), RoundedCornerShape(14.dp)).clickable { onPickNewImage() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("+ Ảnh của bạn", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            items(wallpapers, key = { it.id }) { w ->
                val bmp = remember(w.filePath) { runCatching { BitmapFactory.decodeFile(w.filePath) }.getOrNull() }
                val isActive = w.filePath == activeWallPath
                Box(
                    modifier = Modifier.aspectRatio(9f / 14f).clip(RoundedCornerShape(14.dp))
                        .border(2.dp, if (isActive) Color(0xFFFBBF24) else Color(0xFF334155), RoundedCornerShape(14.dp))
                        .clickable { onSelectWallpaper(w.filePath) }
                ) {
                    if (bmp != null) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = w.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTabView(
    repo: WorkRepository,
    onPickNewImage: () -> Unit,
    onShowNotif: () -> Unit,
    onSyncPulled: () -> Unit
) {
    val ctx = LocalContext.current
    var syncCal by remember { mutableStateOf(repo.isSyncCalendarEnabled()) }
    var notifOn by remember { mutableStateOf(repo.isNotificationsEnabled()) }
    var beforeMin by remember { mutableStateOf(repo.getDefaultBeforeMin()) }
    var roomCode by remember { mutableStateOf(repo.getSyncRoomCode()) }
    var syncStatus by remember { mutableStateOf(if (roomCode.isBlank()) "Nhập mã phòng giống nhau trên 2 máy" else "Phòng: $roomCode") }
    val otaStatus = OtaUpdater.statusMessage.ifBlank { "Phiên bản hiện tại: v${OtaUpdater.getCurrentVersion(ctx)}" }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cài đặt", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)

        // LỊCH & ĐỒNG BỘ
        Text("LỊCH & ĐỒNG BỘ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1F29)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Liên kết Lịch Google / Lịch máy", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Hiện sinh nhật & ngày quan trọng lên Lịch và màn hình khóa", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Switch(checked = syncCal, onCheckedChange = {
                    syncCal = it
                    repo.setSyncCalendarEnabled(it)
                    LockScreenRenderer.renderAndApplyLockScreen(ctx)
                })
            }
        }

        // THÔNG BÁO & LỜI NHẮC
        Text("THÔNG BÁO & LỜI NHẮC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1F29)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nhắc việc và ghi chú", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Tự đọc giờ trong ghi chú (vd: 'nhắc trước 30 phút')", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Switch(checked = notifOn, onCheckedChange = {
                    notifOn = it
                    repo.setNotificationsEnabled(it)
                    if (it) onShowNotif()
                })
            }
            Text("Nhắc mặc định cho việc có giờ:", color = Color(0xFFCBD5E1), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "Đúng giờ", 15 to "Trước 15p", 30 to "Trước 30p").forEach { (m, lbl) ->
                    FilterChip(selected = beforeMin == m, onClick = {
                        beforeMin = m
                        repo.setDefaultBeforeMin(m)
                    }, label = { Text(lbl, fontSize = 11.sp) })
                }
            }
            OutlinedButton(onClick = onShowNotif, modifier = Modifier.fillMaxWidth()) {
                Text("🔔 Cập nhật thông báo ghim trên thanh trạng thái", fontSize = 12.sp)
            }
        }

        // HÌNH NỀN & ĐỒNG BỘ 2 MÁY
        Text("HÌNH NỀN & ĐỒNG BỘ 2 MÁY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1F29)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPickNewImage, modifier = Modifier.fillMaxWidth()) {
                Text("📁 Tải ảnh nền của bạn lên")
            }
            OutlinedTextField(
                value = roomCode,
                onValueChange = { roomCode = it },
                label = { Text("Mã phòng đồng bộ Firebase (vd: minhduc-2026)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(syncStatus, fontSize = 11.sp, color = Color(0xFF94A3B8))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    repo.setSyncRoomCode(roomCode)
                    FirebaseSync.pullFromCloud(ctx) { ok, msg ->
                        syncStatus = msg
                        if (ok) onSyncPulled()
                    }
                }, modifier = Modifier.weight(1f)) { Text("⬇ Kéo về") }
                OutlinedButton(onClick = {
                    repo.setSyncRoomCode(roomCode)
                    FirebaseSync.pushToCloud(ctx) { msg -> syncStatus = msg }
                }, modifier = Modifier.weight(1f)) { Text("⬆ Đẩy lên") }
            }
        }

        // CẬP NHẬT OTA
        Text("CẬP NHẬT ỨNG DỤNG (GITHUB OTA)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1F29)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(otaStatus, fontSize = 12.sp, color = Color(0xFF94A3B8))
            if (OtaUpdater.readyApkFile != null) {
                Button(onClick = { OtaUpdater.installDownloadedApk(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("📦 Cài đặt bản đã tải ngay")
                }
            }
            Button(
                onClick = { OtaUpdater.checkAndInstallUpdate(ctx) },
                enabled = !OtaUpdater.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (OtaUpdater.isBusy) "⏳ Đang tải bản cập nhật..." else "🔄 Kiểm tra & Cập nhật ngay")
            }
        }
    }
}

@Composable
fun AddTaskDialog(
    initialQuad: Quadrant,
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (WorkTask) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("20:59") }
    var repeat by remember { mutableStateOf(RepeatType.DAILY) }
    var quad by remember { mutableStateOf(initialQuad) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo công việc mới") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Tiêu đề") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Ghi chú (vd: nhắc trước 30 phút)") })
                OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Giờ (để trống nếu cả ngày)") }, singleLine = true)

                Text("Lặp lại (Miễn phí)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(RepeatType.DAILY to "Hàng ngày", RepeatType.WEEKDAYS to "T2–T6", RepeatType.NONE to "1 lần").forEach { (r, lbl) ->
                        FilterChip(selected = repeat == r, onClick = { repeat = r }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }

                Text("Ma trận 4 ô", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(Quadrant.Q1 to "🔥 Gấp", Quadrant.Q2 to "📅 Q.Trọng", Quadrant.Q3 to "⚡ Gấp", Quadrant.Q4 to "☰ Sau").forEach { (q, lbl) ->
                        FilterChip(selected = quad == q, onClick = { quad = q }, label = { Text(lbl, fontSize = 10.sp) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) {
                    onSave(
                        WorkTask(
                            id = "t_${System.currentTimeMillis()}",
                            title = title.trim(),
                            note = note.trim(),
                            startDate = initialDate.toString(),
                            time = time.trim().ifBlank { null },
                            repeat = repeat,
                            quadrant = quad,
                            highPriority = quad == Quadrant.Q1
                        )
                    )
                }
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}
