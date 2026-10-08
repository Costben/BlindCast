package com.erl.blindcast

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.erl.blindcast.core.priv.DesktopTaskController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fusion 虚拟桌面专属宿主 Home (SECONDARY_HOME)。
 *
 * 核心设计约束：
 * 1. 独立进程 (:fusionhome) 与独立 taskAffinity (com.erl.blindcast.fusionhome)，与物理主屏彻底隔离；
 * 2. 界面显著位置产品化展示（如「独立桌面」），技术 ID 仅作为诊断小字；
 * 3. 严格绑定当前 Display：displayId <= 0 时安全拦截，杜绝触碰物理主屏；
 * 4. 任务复用与隔离：
 *    - 同屏已有 Task 时：优先调用 DesktopTaskController.switchTask 恢复前台，复用原 TaskId 不新建实例；
 *    - 同屏无 Task 时：必须使用 FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK，确保副屏
 *      强制创建全新独立 Task，坚决杜绝系统跨屏复用/迁移物理主屏同包 Task 导致主屏被夺权；
 * 5. 启动失败原样显示具体可读错误，绝不静默 fallback 到物理主屏。
 */
class FusionHomeActivity : ComponentActivity() {

    private data class FusionAppItem(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: ImageBitmap?,
    )

    private var currentDisplayIdState = mutableIntStateOf(Display.DEFAULT_DISPLAY)
    private var allAppsState = mutableStateListOf<FusionAppItem>()
    private var recentAppsState = mutableStateListOf<FusionAppItem>()
    private var errorMessageState = mutableStateOf<String?>(null)
    private var isLoadingState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateCurrentDisplayId()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121316)
                ) {
                    FusionHomeScreen(
                        displayId = currentDisplayIdState.intValue,
                        allApps = allAppsState,
                        recentApps = recentAppsState,
                        errorMessage = errorMessageState.value,
                        isLoading = isLoadingState.value,
                        onDismissError = { errorMessageState.value = null },
                        onLaunchApp = { app -> launchAppOnCurrentDisplay(app) }
                    )
                }
            }
        }

        loadAppsAsync()
    }

    override fun onResume() {
        super.onResume()
        updateCurrentDisplayId()
        syncRunningTasksAsync()
    }

    private fun syncRunningTasksAsync() {
        val did = currentDisplayIdState.intValue
        if (did <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            val tasks = DesktopTaskController.listTasks(did)
            if (tasks.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    val currentAll = allAppsState.toList()
                    for (task in tasks.reversed()) {
                        val matched = currentAll.firstOrNull { it.packageName == task.packageName }
                        if (matched != null) {
                            recordRecentApp(matched)
                        }
                    }
                }
            }
        }
    }

    private fun updateCurrentDisplayId() {
        val did = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY
        }
        currentDisplayIdState.intValue = did
    }

    private fun loadAppsAsync() {
        isLoadingState.value = true
        val pm = packageManager
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val list = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
                resolveList.mapNotNull { resolve ->
                    val pkg = resolve.activityInfo.packageName
                    val act = resolve.activityInfo.name
                    if (pkg == packageName && act == FusionHomeActivity::class.java.name) {
                        null
                    } else {
                        val label = resolve.loadLabel(pm).toString()
                        val iconBmp = runCatching {
                            val drawable = resolve.loadIcon(pm)
                            drawableToBitmap(drawable)?.asImageBitmap()
                        }.getOrNull()
                        FusionAppItem(pkg, act, label, iconBmp)
                    }
                }.sortedBy { it.label }
            }
            allAppsState.clear()
            allAppsState.addAll(list)
            isLoadingState.value = false
        }
    }

    private fun launchAppOnCurrentDisplay(app: FusionAppItem) {
        val targetDisplayId = currentDisplayIdState.intValue
        if (targetDisplayId <= 0) {
            errorMessageState.value =
                "安全防护拦截：当前处于物理主屏！" +
                "Fusion 独立桌面仅允许在虚拟副屏上启动应用，已阻止执行以保护物理主屏前台。"
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 1. 同屏任务复用检查：副屏上是否已有该应用 Task？
            val existingTask = DesktopTaskController.findTaskByPackage(app.packageName, targetDisplayId)
            if (existingTask != null) {
                // 已有任务：调用 switchTask 恢复至前台（严禁新开实例与跨屏拖动）
                val switchRes = DesktopTaskController.switchTask(existingTask.taskId, targetDisplayId)
                if (switchRes.ok) {
                    withContext(Dispatchers.Main) {
                        errorMessageState.value = null
                        recordRecentApp(app)
                    }
                    return@launch
                }
            }

            // 2. 无同屏已有任务时：在副屏新建独立 Task
            // 必须组合使用 FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_MULTIPLE_TASK：
            // 确保强制在副屏新建独立 Task，绝对防止 Android AMS 跨屏复用并迁移物理主屏(Display 0)已有的同应用 Task！
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.activityName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            }

            val options = ActivityOptions.makeBasic().apply {
                setLaunchDisplayId(targetDisplayId)
            }

            withContext(Dispatchers.Main) {
                try {
                    startActivity(launchIntent, options.toBundle())
                    errorMessageState.value = null
                    recordRecentApp(app)
                } catch (t: Throwable) {
                    errorMessageState.value =
                        "启动「${app.label}」失败: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }
    }

    private fun recordRecentApp(app: FusionAppItem) {
        recentAppsState.removeAll { it.packageName == app.packageName && it.activityName == app.activityName }
        recentAppsState.add(0, app)
        if (recentAppsState.size > 8) {
            recentAppsState.removeLast()
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    @Composable
    private fun FusionHomeScreen(
        displayId: Int,
        allApps: List<FusionAppItem>,
        recentApps: List<FusionAppItem>,
        errorMessage: String?,
        isLoading: Boolean,
        onDismissError: () -> Unit,
        onLaunchApp: (FusionAppItem) -> Unit,
    ) {
        var query by remember { mutableStateOf("") }
        val filteredApps = remember(query, allApps) {
            if (query.isBlank()) allApps
            else allApps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 顶栏：去技术化产品文案（技术 displayId 仅以微小诊断字样留存）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "独立桌面",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (displayId > 0) "已与物理主屏完全隔离" else "主屏防护模式",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8E9099)
                    )
                }

                // 状态指示徽标：文案自然友好
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (displayId > 0) Color(0xFF1E3A2B) else Color(0xFF3A1E1E))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (displayId > 0) "独立桌面 (运行中)" else "物理主屏 (防护拦截)",
                        color = if (displayId > 0) Color(0xFF4ADE80) else Color(0xFFF87171),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 错误提示
            AnimatedVisibility(visible = errorMessage != null) {
                if (errorMessage != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1616)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "提示",
                                tint = Color(0xFFF87171),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage,
                                color = Color(0xFFFCA5A5),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 已开应用快速切回
            if (recentApps.isNotEmpty()) {
                Text(
                    text = "已开应用 (点击切回)",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFA1A4B2),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(recentApps, key = { "recent_${it.packageName}_${it.activityName}" }) { app ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF20232B))
                                .clickable { onLaunchApp(app) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color.Gray, CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = app.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                placeholder = { Text("搜索应用...", color = Color(0xFF6B7280)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = Color(0xFF9CA3AF)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF3B82F6),
                    unfocusedBorderColor = Color(0xFF2D3139),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1A1D24),
                    unfocusedContainerColor = Color(0xFF1A1D24)
                )
            )

            // 应用网格
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF3B82F6))
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (query.isBlank()) "无可用启动器应用" else "未找到相关应用",
                        color = Color(0xFF6B7280),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 76.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(filteredApps, key = { "${it.packageName}_${it.activityName}" }) { app ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onLaunchApp(app) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(48.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color(0xFF374151), CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = app.label,
                                color = Color(0xFFE5E7EB),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
