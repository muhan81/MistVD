package com.nkls.nekovideo.components.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.SortRowMessageCenter
import com.nkls.nekovideo.components.helpers.logging.LogExporter
import com.nkls.nekovideo.components.helpers.logging.TaskLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 运行日志（第 8 轮）。
 *
 * 三个入口共用这一个页面：设置主页卡片、关于页快捷项、**九宫格工具箱**。
 *
 * ## 这一页是干什么的
 * "开启美颜后视频无法播放"这类问题，光看界面完全分不出原因（黑屏 / 转圈长得一样）。
 * 这一页把运行过程摊开给人看，并给出**一键导出**。
 *
 * ## 界面上的四件事
 * 1. **级别**：切了之后**只影响新产生的日志**（历史行已按当时的级别过滤过，不会追补）——
 *    这是日志系统的常规行为，不是 bug。
 * 2. **导出到文件**：写到 `<存储根>/MistVD/Logs/`，用系统文件管理器就能看到。
 * 3. **分享**：系统分享面板（微信 / 邮件 / 蓝牙）。
 * 4. **复制**：整段复制到剪贴板，直接粘给 AI 分析最方便。
 *
 * ## 性能取舍
 * 界面只渲染**最近 500 行**（日志文件里是全量）。播放时日志持续增长，
 * 每行都实时重组会在低端机上卡顿，所以这里 1 秒轮询一次、且限制行数。
 */
@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var level by remember { mutableStateOf(TaskLogger.getLevel()) }
    var lines by remember { mutableStateOf(TaskLogger.snapshot(MAX_UI_LINES)) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // ★ 第 9 轮：点某一行 → 弹窗看**全文**。
    //   原来每行只显示 2 行就省略号（界面里的第二层截断），业主在界面上看不到全貌，
    //   会误以为"日志还是坏的"（其实导出文件是完整的）。
    var selectedLine by remember { mutableStateOf<String?>(null) }

    // 日志在播放时是持续增长的 → 定时刷新。用 1 秒轮询而不是"每行触发重组"，
    // 因为高频重组这条链路本身会把播放拖慢（那就本末倒置了）。
    LaunchedEffect(Unit) {
        while (true) {
            lines = TaskLogger.snapshot(MAX_UI_LINES)
            delay(1000L)
        }
    }

    fun exportToPublic() {
        if (busy) return
        scope.launch {
            busy = true
            val file = withContext(Dispatchers.IO) { LogExporter.exportToPublic(context) }
            busy = false
            if (file != null) {
                SortRowMessageCenter.showSuccess(
                    context.getString(
                        R.string.logs_export_ok,
                        "${TaskLogger.PUBLIC_DIR_NAME}/${file.name}"
                    )
                )
            } else {
                SortRowMessageCenter.showError(context.getString(R.string.logs_export_fail))
            }
        }
    }

    fun share() {
        val intent = LogExporter.shareIntent(context)
        if (intent == null) {
            SortRowMessageCenter.showError(context.getString(R.string.logs_export_fail))
            return
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.logs_share))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            SortRowMessageCenter.showError(context.getString(R.string.logs_export_fail))
        }
    }

    fun copyAll() {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("MistVD log", TaskLogger.snapshotText())
            )
            SortRowMessageCenter.showSuccess(context.getString(R.string.logs_copied))
        }.onFailure {
            SortRowMessageCenter.showError(context.getString(R.string.logs_export_fail))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ① 隐私提示 —— 日志含文件路径，分享前要提醒一句
        Text(
            text = stringResource(R.string.logs_privacy_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ② 级别（5 档）。切换只影响此后新产生的行。
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.logs_level),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
            ) {
                TaskLogger.Level.entries.forEach { item ->
                    FilterChip(
                        selected = item == level,
                        onClick = {
                            level = item
                            TaskLogger.setLevel(context, item)
                        },
                        label = { Text(item.name, fontSize = 11.sp) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ③ 四个动作
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            OutlinedButton(onClick = { exportToPublic() }, enabled = !busy) {
                Text(stringResource(R.string.logs_export), fontSize = 12.sp)
            }
            OutlinedButton(onClick = { share() }) {
                Text(stringResource(R.string.logs_share), fontSize = 12.sp)
            }
            OutlinedButton(onClick = { copyAll() }) {
                Text(stringResource(R.string.logs_copy), fontSize = 12.sp)
            }
            OutlinedButton(onClick = { showClearConfirm = true }) {
                Text(stringResource(R.string.logs_clear), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        // ④ 正文
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(lines) { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        // ★ 第 9 轮：2 → 6，并且**点一下看全文**（见下方 selectedLine 弹窗）。
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLine = line },
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.logs_clear_confirm_title)) },
            text = { Text(stringResource(R.string.logs_clear_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    TaskLogger.clear(context)
                    lines = TaskLogger.snapshot(MAX_UI_LINES)
                }) {
                    Text(stringResource(R.string.logs_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // ★ 第 9 轮：单行全文弹窗（长行、会话头这类内容在列表里看不全时用）
    selectedLine?.let { full ->
        AlertDialog(
            onDismissRequest = { selectedLine = null },
            title = { Text(stringResource(R.string.logs_row_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(full, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("MistVD log line", full))
                    }
                    SortRowMessageCenter.showSuccess(context.getString(R.string.logs_copied))
                    selectedLine = null
                }) {
                    Text(stringResource(R.string.logs_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedLine = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** 界面最多渲染多少行。全量在日志文件里，这里只为"看得见、不卡顿"。 */
private const val MAX_UI_LINES = 500
