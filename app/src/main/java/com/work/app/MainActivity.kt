package com.work.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.work.app.data.*
import com.work.app.update.OtaUpdater
import com.work.app.sync.FirebaseSync
import com.work.app.notification.PinnedTaskNotification
import com.work.app.wallpaper.LockScreenRenderer
import com.work.app.worker.DailyResetWorker
import java.io.File
import java.time.LocalDate

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
    var tab by remember { mutableStateOf(0) }
    var tasks by remember { mutableStateOf(repo.getTasksForDate()) }
    var doneIds by remember { mutableStateOf(repo.getCompletedIds()) }
    var showAddDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val dest = File(ctx.filesDir, "wall_${System.currentTimeMillis()}.jpg")
            ctx.contentResolver.openInputStream(it)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            repo.addWallpaper(SavedWallpaper(dest.name, "Ảnh của bạn", dest.absolutePath))
            LockScreenRenderer.renderAndApplyLockScreen(ctx)
        }
    }

    fun refreshLocalOnly() {
        tasks = repo.getTasksForDate()
        doneIds = repo.getCompletedIds()
        PinnedTaskNotification.showOrUpdate(ctx)
        LockScreenRenderer.renderAndApplyLockScreen(ctx)
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
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = Color.White, contentColor = Color.Black) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161A23)) {
                listOf("Việc", "Màn hình khóa", "Cài đặt").forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Text(if (idx == 0) "⊞" else if (idx == 1) "🔒" else "⚙") },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            Text("Work", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(tasks) { t ->
                            val isDone = doneIds.contains(t.id)
                            val rem = NoteReminderParser.computeReminderTime(t)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF1A1F29))
                                    .clickable {
                                        repo.toggleTaskDone(t.id)
                                        refreshAll()
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (isDone) Color(0xFF34D399) else Color(t.quadrant.colorHex))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.title,
                                        color = if (isDone) Color.Gray else Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
                                    )
                                    Text(
                                        "${t.time ?: "Cả ngày"} · ${t.repeat.name} · 🔔 ${rem ?: "--"}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Button(
                        onClick = { LockScreenRenderer.renderAndApplyLockScreen(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cập nhật màn hình khóa ngay") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ Tải ảnh của bạn vào kho hình nền") }
                }
                else -> {
                    var roomCode by remember { mutableStateOf(repo.getSyncRoomCode()) }
                    var syncStatus by remember { mutableStateOf(if (roomCode.isBlank()) "Nhập mã phòng giống nhau trên 2 máy để đồng bộ" else "Đang kết nối phòng: $roomCode") }
                    var otaStatus by remember { mutableStateOf("Phiên bản hiện tại: v${OtaUpdater.getCurrentVersion(ctx)}") }

                    Text("Đồng bộ 2 điện thoại (Firebase)", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { roomCode = it },
                        label = { Text("Mã phòng đồng bộ (vd: minhduc-2026)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(syncStatus, fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                repo.setSyncRoomCode(roomCode)
                                FirebaseSync.pullFromCloud(ctx) { ok, msg ->
                                    syncStatus = msg
                                    if (ok) refreshLocalOnly()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("⬇ Kéo về") }
                        OutlinedButton(
                            onClick = {
                                repo.setSyncRoomCode(roomCode)
                                FirebaseSync.pushToCloud(ctx) { msg -> syncStatus = msg }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("⬆ Đẩy lên") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tải ảnh nền màn hình khóa") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { PinnedTaskNotification.showOrUpdate(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Bật thông báo ghim trên thanh trạng thái") }
                    Spacer(Modifier.height(16.dp))
                    Text("Cập nhật ứng dụng (GitHub OTA)", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(otaStatus, fontSize = 12.sp, color = Color(0xFF94A3B8))
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { OtaUpdater.checkAndInstallUpdate(ctx) { msg -> otaStatus = msg } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🔄 Kiểm tra & Cập nhật ngay") }
                }
            }
        }

        if (showAddDialog) {
            var title by remember { mutableStateOf("") }
            var note by remember { mutableStateOf("") }
            var time by remember { mutableStateOf("20:59") }
            var repeat by remember { mutableStateOf(RepeatType.DAILY) }

            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Tạo công việc mới") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Tiêu đề") })
                        OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Ghi chú (vd: nhắc trước 30 phút)") })
                        OutlinedTextField(value = time, onValueChange = { time = it }, label = { Text("Giờ (để trống nếu cả ngày)") })
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(RepeatType.DAILY to "Hàng ngày", RepeatType.NONE to "1 lần").forEach { (r, lbl) ->
                                FilterChip(selected = repeat == r, onClick = { repeat = r }, label = { Text(lbl) })
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (title.isNotBlank()) {
                            val list = repo.getTasks().toMutableList()
                            list.add(0, WorkTask(
                                id = "t_${System.currentTimeMillis()}",
                                title = title.trim(),
                                note = note.trim(),
                                startDate = LocalDate.now().toString(),
                                time = time.trim().ifBlank { null },
                                repeat = repeat,
                                quadrant = Quadrant.Q1
                            ))
                            repo.saveTasks(list)
                            showAddDialog = false
                            refreshAll()
                        }
                    }) { Text("Lưu") }
                },
                dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Hủy") } }
            )
        }
    }
}
