package com.nkls.nekovideo.components.helpers.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nkls.nekovideo.components.helpers.storage.StorageRoot
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 日志导出 / 分享 / 清理（第 8 轮）。
 *
 * 与 [TaskLogger] 的分工：TaskLogger 只管**采集与落盘**，本文件管**把日志交到业主手里**。
 *
 * ## 三条通道（对应"0 代码基础也能拿到日志"）
 * 1. **应用内查看** —— `LogViewerScreen`（直接读 [TaskLogger.snapshot]）
 * 2. **导出到公共目录** —— [exportToPublic] → `<存储根>/MistVD/Logs/MistVD-log-<时间戳>.txt`，
 *    走项目**已有**的「所有文件访问」权限，用系统文件管理器就能看到、能拷到电脑
 * 3. **分享** —— [shareIntent] → 系统分享面板（微信/邮件/蓝牙）
 *
 * ## 导出文件长什么样
 * ```
 * MistVD 运行日志导出
 * 生成时间：…
 * ────────────────────────────
 * 【诊断摘要】…       ← 自动给出的结论（TaskLogger.buildSummary）
 * ────────────────────────────
 * 【日志正文】…       ← 本次会话全文
 * ```
 *
 * 分享用的是固定名 `export-latest.txt`（每次覆盖），所以**分享与导出内容完全一致**，
 * 也不会在应用里堆积导出文件。
 *
 * ## 包名
 * 本文件在 `helpers/logging/` 下，package 是 `…components.helpers.logging`。
 */
object LogExporter {

    private val STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val FULL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 固定名导出文件（供分享），每次覆盖。 */
    private const val EXPORT_LATEST = "export-latest.txt"

    /** 单次导出的正文行数上限（防止极端长会话生成一个几十 MB 的文件）。 */
    private const val MAX_BODY_LINES = 20000

    // ───────────────────────────────────────────────────────────── 导出：公共目录

    /**
     * 导出到公共目录 `<存储根>/MistVD/Logs/`。
     *
     * @return 落地文件；存储根不可用（如存储卡被拔）时返回 `null`，调用方据此提示"改用分享"。
     */
    fun exportToPublic(context: Context): File? {
        return try {
            val root = StorageRoot.browseRoot
            if (!StorageRoot.isPathAvailable(root)) return null

            val dir = File(root, TaskLogger.PUBLIC_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) return null

            val dst = File(dir, "MistVD-log-${LocalDateTime.now().format(STAMP_FMT)}.txt")
            dst.writeText(buildExportText(context))
            dst
        } catch (_: Throwable) {
            null
        }
    }

    // ───────────────────────────────────────────────────────────── 导出：内部固定文件

    /**
     * 在应用内部生成（覆盖）`filesDir/logs/export-latest.txt`，供 [shareIntent] 使用。
     * 内容与 [exportToPublic] 完全一致。
     */
    fun buildExportFile(context: Context): File? {
        return try {
            val dir = TaskLogger.logsDirectory(context).apply { mkdirs() }
            val f = File(dir, EXPORT_LATEST)
            f.writeText(buildExportText(context))
            f
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 拼导出正文 = 文档头 + **诊断摘要** + 本次会话全文。
     *
     * 摘要在最前面是刻意的：**不想读全文的人看前 20 行就能知道结论**。
     */
    fun buildExportText(context: Context): String {
        val sb = StringBuilder(64 * 1024)
        val sessionFile = TaskLogger.currentSessionFile()

        sb.append("MistVD 运行日志导出\n")
        sb.append("生成时间：").append(LocalDateTime.now().format(FULL_FMT)).append('\n')
        sb.append("来源文件：").append(sessionFile?.name ?: "(本次会话尚未落盘，以下为内存缓冲)").append('\n')
        sb.append("说明：本文件含文件路径等信息，对外分享前请自行确认。\n")
        sb.append("═".repeat(60)).append('\n')

        // ① 诊断摘要
        sb.append(TaskLogger.buildSummary())
        sb.append("═".repeat(60)).append('\n')

        // ② 日志正文
        sb.append("【日志正文】\n")
        val body = readBody(context, sessionFile)
        sb.append(body)
        if (!body.endsWith("\n")) sb.append('\n')

        return sb.toString()
    }

    /**
     * 取正文：优先读**文件**（比内存缓冲全），读不到再退回内存快照。
     */
    private fun readBody(context: Context, sessionFile: File?): String {
        try {
            if (sessionFile != null && sessionFile.exists() && sessionFile.length() > 0) {
                val lines = sessionFile.readLines()
                if (lines.isNotEmpty()) {
                    return if (lines.size <= MAX_BODY_LINES) {
                        lines.joinToString("\n")
                    } else {
                        lines.takeLast(MAX_BODY_LINES).joinToString("\n")
                    }
                }
            }
        } catch (_: Throwable) {
        }
        // 退路：内存缓冲（例如文件还没被写入线程创建出来）
        val mem = TaskLogger.snapshot(maxLines = 0)
        return if (mem.isEmpty()) "(本次会话暂无日志)\n" else mem.joinToString("\n")
    }

    // ───────────────────────────────────────────────────────────── 分享

    /**
     * 构造分享用的 Intent（`text/plain`）。
     *
     * ⚠️ 走的是已有的 `${packageName}.provider`，授权路径依赖
     * `res/xml/file_paths.xml` 里的 `<files-path name="logs" path="logs/" />` ——
     * **那条路径必须存在**，否则 `getUriForFile` 会抛 `IllegalArgumentException`。
     */
    fun shareIntent(context: Context): Intent? {
        val f = buildExportFile(context) ?: return null
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                f
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MistVD 运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ───────────────────────────────────────────────────────────── 清理

    /**
     * 清理旧日志：`log-*` 保留最近 [TaskLogger.KEEP_LOG_FILES] 个、
     * `crash-*` 保留最近 [TaskLogger.KEEP_CRASH_FILES] 个。`export-latest.txt` 不参与（固定名、每次覆盖）。
     *
     * 内部起一条线程做 IO，调用方（`MainActivity.onCreate`）不必自己开线程。
     */
    fun cleanup(context: Context) {
        val appCtx = context.applicationContext
        Thread({
            try {
                val dir = TaskLogger.logsDirectory(appCtx)
                if (!dir.isDirectory) return@Thread

                trim(dir, "log-", TaskLogger.KEEP_LOG_FILES)
                trim(dir, "crash-", TaskLogger.KEEP_CRASH_FILES)
            } catch (_: Throwable) {
            }
        }, "MistVD-LogCleanup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** 按前缀筛出文件，按最后修改时间倒序保留 [keep] 个，其余删除。 */
    private fun trim(dir: File, prefix: String, keep: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { runCatching { it.delete() } }
    }
}
