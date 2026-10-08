package com.erl.blindcast.ui.screen.pairing

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.erl.blindcast.R
import com.erl.blindcast.core.server.BlindCastServer
import com.erl.blindcast.core.server.auth.CredentialStore
import com.erl.blindcast.core.server.auth.PairingManager
import com.erl.blindcast.data.repository.SettingsRepository
import com.erl.blindcast.data.repository.SettingsRepositoryImpl
import com.erl.blindcast.ui.LocalUiMode
import com.erl.blindcast.ui.UiMode
import com.erl.blindcast.ui.component.dialog.rememberConfirmDialog
import com.erl.blindcast.ui.screen.home.QrCodeImage
import com.erl.blindcast.ui.screen.home.formatPairingRemaining
import com.erl.blindcast.ui.screen.home.readLanIp
import com.erl.blindcast.ui.screen.home.resolveLanPort
import com.erl.blindcast.ui.theme.TemplateTheme
import kotlinx.coroutines.delay

/**
 * 配对与设备管理页（Phase A · 手机侧）。
 *
 * ## 为什么本页要自己补 master token
 * 一旦存在有效配对凭据，服务端就要求鉴权
 * （[com.erl.blindcast.core.server.auth.TokenAuthenticator.isAuthRequired]），
 * 而手机自己「本机浏览器打开」的链接要靠 master token 才能免密直达。
 * 所以首次生成配对码时若访问口令仍为空，就顺手签发一个随机口令：
 * 保证手机自身入口始终可用（口令可在设置页查看 / 修改）。
 *
 * ## 为什么进来先 init 凭据表
 * 凭据表由前台服务启动时载入内存；服务没跑时本页也要能列出已配对设备，
 * 故进入页面补一次 [CredentialStore.init]（幂等，只是读一次落盘文件）。
 *
 * ## 主题
 * 本页只有 Material 一套实现，故显式提供 [UiMode.Material]，
 * 与项目自带的确认对话框组件（Material 分支）保持一致。
 */
class PairingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CredentialStore.init(applicationContext)
        setContent {
            CompositionLocalProvider(LocalUiMode provides UiMode.Material) {
                TemplateTheme(uiMode = UiMode.Material) {
                    PairingScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val repo = remember { SettingsRepositoryImpl() }

    var ticket by remember { mutableStateOf(PairingManager.current()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 凭据表不在 Compose 状态里，吊销后靠这个信号重读列表。
    var revision by remember { mutableIntStateOf(0) }

    // 每秒重读当前码：倒计时随 now 走动；过期后 current() 返回 null，自动退回「生成配对码」态。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            now = System.currentTimeMillis()
            ticket = PairingManager.current(now)
        }
    }

    // 二维码内容只在码变化时重算，避免每秒重组都去枚举网卡。
    val qrContent = remember(ticket?.display) { buildPairingUrl(context, ticket) }
    // 列表随每秒 tick 重读：浏览器在另一头配对成功时，本页 1s 内就能看到新设备。
    val clients = remember(revision, now) { CredentialStore.list() }
    val activeCount = remember(revision, now) { CredentialStore.activeCount() }

    val issueCode: () -> Unit = {
        ensureMasterToken(repo)
        ticket = PairingManager.issue()
        now = System.currentTimeMillis()
    }

    val revokeAllDialog = rememberConfirmDialog(onConfirm = {
        CredentialStore.revokeAll()
        revision++
    })
    val revokeAllTitle = stringResource(R.string.blindcast_pairing_revoke_all_confirm_title)
    val revokeAllText = stringResource(R.string.blindcast_pairing_revoke_all_confirm_text)
    val revokeAllAction = stringResource(R.string.blindcast_pairing_revoke_all)
    val cancelAction = stringResource(R.string.blindcast_pairing_cancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blindcast_pairing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PairingCodeCard(
                    ticket = ticket,
                    now = now,
                    qrContent = qrContent,
                    onIssue = issueCode,
                    onCopy = {
                        ticket?.let { code ->
                            clipboard.setText(AnnotatedString(code.display))
                            Toast.makeText(
                                context,
                                context.getString(R.string.blindcast_pairing_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.blindcast_pairing_devices_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.blindcast_pairing_devices_active, activeCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (clients.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.blindcast_pairing_devices_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(clients, key = { it.id }) { client ->
                    DeviceCard(
                        client = client,
                        now = now,
                        onRevoke = {
                            CredentialStore.revoke(client.id)
                            revision++
                        },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = {
                            revokeAllDialog.showConfirm(
                                title = revokeAllTitle,
                                content = revokeAllText,
                                confirm = revokeAllAction,
                                dismiss = cancelAction,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.blindcast_pairing_revoke_all))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PairingCodeCard(
    ticket: PairingManager.Ticket?,
    now: Long,
    qrContent: String,
    onIssue: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ticket == null) {
                Button(onClick = onIssue) {
                    Text(stringResource(R.string.blindcast_pairing_generate))
                }
                Text(
                    text = stringResource(R.string.blindcast_pairing_generate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 大号等宽大字：手抄不易看错（0/O、1/I/L 已从字母表剔除）。
                Text(
                    text = ticket.display,
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = stringResource(
                        R.string.blindcast_pairing_remaining,
                        formatPairingRemaining(ticket.remainingMs(now)),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (qrContent.isNotEmpty()) {
                    QrCodeImage(content = qrContent, size = 220.dp)
                    Text(
                        text = stringResource(R.string.blindcast_pairing_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.blindcast_home_no_ip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.blindcast_pairing_copy))
                    }
                    Button(onClick = onIssue, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.blindcast_pairing_refresh))
                    }
                }
            }
            Text(
                text = stringResource(R.string.blindcast_pairing_master_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    client: CredentialStore.Client,
    now: Long,
    onRevoke: () -> Unit,
) {
    val revoked = client.revoked
    // 已吊销条目整体置灰，右侧只留「已吊销」标记（不再给吊销按钮）。
    val titleColor = if (revoked) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metaColor = MaterialTheme.colorScheme.outline
    val source = when (client.addedVia) {
        PairingManager.ADDED_VIA -> stringResource(R.string.blindcast_pairing_added_via_pairing)
        else -> client.addedVia
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = client.name.ifEmpty { stringResource(R.string.blindcast_pairing_device_unnamed) },
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                )
                Text(
                    text = stringResource(
                        R.string.blindcast_pairing_device_meta,
                        source,
                        relativeTimeText(client.createdAt, now),
                        relativeTimeText(client.lastSeenAt, now),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = metaColor,
                )
            }
            if (revoked) {
                Text(
                    text = stringResource(R.string.blindcast_pairing_revoked),
                    style = MaterialTheme.typography.bodySmall,
                    color = metaColor,
                )
            } else {
                TextButton(onClick = onRevoke) {
                    Text(stringResource(R.string.blindcast_pairing_revoke))
                }
            }
        }
    }
}

/** 相对时间文案：刚刚 / N 分钟前 / N 小时前 / N 天前（未来时间戳按「刚刚」处理）。 */
@Composable
private fun relativeTimeText(timeMs: Long, now: Long): String {
    val delta = (now - timeMs).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> stringResource(R.string.blindcast_pairing_time_just_now)
        delta < 3_600_000L -> stringResource(R.string.blindcast_pairing_time_minutes, (delta / 60_000L).toInt())
        delta < 86_400_000L -> stringResource(R.string.blindcast_pairing_time_hours, (delta / 3_600_000L).toInt())
        else -> stringResource(R.string.blindcast_pairing_time_days, (delta / 86_400_000L).toInt())
    }
}

/**
 * 首次生成配对码时补齐 master token（设计约定，见 [PairingActivity] 类注释）。
 * 同时写偏好（持久）与运行时（当前跑着的服务立刻生效，不必重启）。
 */
private fun ensureMasterToken(repo: SettingsRepository) {
    if (repo.streamToken.isNotBlank()) return
    val token = PairingManager.newMasterToken()
    repo.streamToken = token
    BlindCastServer.setToken(token)
}

/** 二维码内容：局域网地址 + `?pair=` 展示码（浏览器打开即预填配对码）；无 IP 时返回空串。 */
private fun buildPairingUrl(context: Context, ticket: PairingManager.Ticket?): String {
    if (ticket == null) return ""
    val ip = readLanIp(context) ?: return ""
    return "http://$ip:${resolveLanPort(context)}/?pair=${ticket.display}"
}
