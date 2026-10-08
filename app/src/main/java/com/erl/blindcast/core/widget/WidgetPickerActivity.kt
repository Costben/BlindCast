package com.erl.blindcast.core.widget

import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * widget 绑定入口（Desktop-Widget-1 · 手机侧）。
 *
 * ## 为什么必须有这个界面
 * 把桌面小部件交给 BlindCast 渲染，需要本应用先成为系统的 widget host——
 * 而「允许某个应用承载 widget」是 Android 的安全模型，**无法静默完成**：
 * - 首次绑定走 `ACTION_APPWIDGET_BIND`，由用户在系统弹窗里点允许；
 * - 一旦允许，本应用进入系统 appwidget 白名单，之后新增 widget 不再弹窗
 *   （见 [WidgetHostManager.bindIfAllowed]）。
 *
 * ## 交互
 * - 上半区：已绑定 widget（点右侧「移除」解绑并释放）；
 * - 下半区：本机全部可用 provider（点条目绑定，走上面的授权流程）。
 * 绑定完成后无需在此预览——打开 Web 控制台的桌面面板即可看到渲染结果。
 *
 * ## 入口
 * 当前以 `exported=true` + 独立 taskAffinity 暴露，可由
 * `adb shell am start -n com.erl.blindcast/.core.widget.WidgetPickerActivity` 直达，
 * 后续接入设置页入口后可改为 `exported=false`。
 */
class WidgetPickerActivity : ComponentActivity() {

    private var pendingId: Int = -1
    private var pendingInfo: AppWidgetProviderInfo? = null

    /** 列表刷新信号（每次绑定/解绑/授权返回自增，触发 Compose 重组重读）。 */
    private var revision = mutableIntStateOf(0)

    private val bindLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val id = pendingId
        val info = pendingInfo
        pendingId = -1
        pendingInfo = null
        if (id >= 0) {
            if (result.resultCode == RESULT_OK && info != null) {
                persist(id, info)
            } else {
                // 用户拒绝授权：释放刚分配的 id，避免系统侧残留空绑定。
                WidgetHostManager.delete(id)
            }
        }
        revision.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetHostManager.init(applicationContext)
        setContent {
            MaterialTheme {
                WidgetPickerScreen(
                    revision = revision.intValue,
                    onAdd = ::startBind,
                    onRemove = ::unbind,
                )
            }
        }
    }

    // ------------------------------------------------------------------

    private fun startBind(info: AppWidgetProviderInfo) {
        val id = WidgetHostManager.allocate() ?: return
        val provider = info.provider ?: run {
            WidgetHostManager.delete(id)
            return
        }
        if (WidgetHostManager.bindIfAllowed(id, provider)) {
            persist(id, info)
            revision.intValue++
        } else {
            pendingId = id
            pendingInfo = info
            runCatching { bindLauncher.launch(WidgetHostManager.buildBindIntent(id, provider)) }
                .onFailure {
                    WidgetHostManager.delete(id)
                    pendingId = -1
                    pendingInfo = null
                }
        }
    }

    private fun persist(id: Int, info: AppWidgetProviderInfo) {
        WidgetStore.add(
            WidgetStore.Record(
                id = id,
                provider = WidgetHostManager.componentString(info),
                label = WidgetHostManager.labelOf(info),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun unbind(id: Int) {
        WidgetRenderer.release(id)
        WidgetHostManager.delete(id)
        WidgetStore.remove(id)
        revision.intValue++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetPickerScreen(
    revision: Int,
    onAdd: (AppWidgetProviderInfo) -> Unit,
    onRemove: (Int) -> Unit,
) {
    // revision 参与 key，绑定/解绑后重读系统状态。
    val bound = remember(revision) { loadBound() }
    val available = remember(revision) { WidgetHostManager.availableProviders() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("桌面小部件") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "这些部件会渲染到 Web 控制台的桌面面板，可在浏览器里点击、拖动与缩放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader("已绑定（${bound.size}）") }
            if (bound.isEmpty()) {
                item { EmptyHint("还没有绑定任何小部件") }
            } else {
                items(bound, key = { it.first }) { (id, label) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label.ifEmpty { "widget #$id" }, fontWeight = FontWeight.Medium)
                                Text(
                                    text = "id $id",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onRemove(id) }) { Text("移除") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("可添加（${available.size}）") }
            if (available.isEmpty()) {
                item { EmptyHint("本机没有可用的桌面小部件") }
            } else {
                items(available, key = { it.provider?.flattenToString() ?: it.toString() }) { info ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAdd(info) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = WidgetHostManager.labelOf(info).ifEmpty { "未命名部件" },
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = info.provider?.packageName.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { onAdd(info) }) { Text("添加") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 已绑定 widget 的 (id, 展示名) 列表；provider 卸载后回退到 [WidgetStore] 里记的名字。 */
private fun loadBound(): List<Pair<Int, String>> {
    val host = WidgetHostManager.hostOrNull() ?: return emptyList()
    val ids = runCatching { host.appWidgetIds?.toList().orEmpty() }.getOrDefault(emptyList())
    return ids.map { id ->
        val info = WidgetHostManager.providerInfo(id)
        val label = WidgetHostManager.labelOf(info).ifEmpty {
            WidgetStore.find(id)?.label.orEmpty()
        }
        id to label
    }
}
