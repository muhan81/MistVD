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

    /**
     * ★ 第 10 轮：导出**最近 N 个会话**（含当前），而不是只导当前这一个。
     *
     * 原因：跨会话的多步实验（"开美颜播一次 / 关美颜播一次 / 只美白播一次"）会分别落在
     * 不同的 `log-*.txt` 里，只导最新一个 ⇒ **前几步的证据全丢**。
     * 实测：业主按 E1~E4 做了 4 组实验，回传的文件里只有"启动 #4"，**"启动 #3"整份都没拿到**。
     */
    private const val MAX_SESSIONS = 3

    /** ★ 第 10 轮：附带最近的 `crash-*.txt`（未捕获异常现场，含崩溃前内存日志）。 */
    private const val MAX_CRASH_ATTACH = 2

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
        val sessions = recentSessions(context)

        sb.append("MistVD 运行日志导出\n")
        sb.append("生成时间：").append(LocalDateTime.now().format(FULL_FMT)).append('\n')
        sb.append("包含会话（旧→新，最多 ").append(MAX_SESSIONS).append(" 个）：")
            .append(
                if (sessions.isEmpty()) "(本次会话尚未落盘，以下为内存缓冲)"
                else sessions.joinToString("、") { it.name }
            ).append('\n')
        sb.append("说明：本文件含文件路径等信息，对外分享前请自行确认。\n")
        sb.append("═".repeat(60)).append('\n')

        // ① 诊断摘要（针对**当前会话**）
        sb.append(TaskLogger.buildSummary())
        sb.append("═".repeat(60)).append('\n')

        // ② 日志正文（最近 MAX_SESSIONS 个会话，按时间升序拼在一起）
        sb.append("【日志正文】\n")
        val body = readBody(sessions)
        sb.append(body)
        if (!body.endsWith("\n")) sb.append('\n')

        // ③ 崩溃现场 —— 以前只在会话头里写一句"见 crash-*.txt"，却从不把文件带出来，
        //    等于让业主自己去文件管理器里翻。
        sb.append("═".repeat(60)).append('\n')
        appendCrashes(sb, context)

        return sb.toString()
    }

    /**
     * 取正文：把 [sessions]（旧→新）的**全部分卷**按顺序拼起来；一个文件都没有时退回内存快照。
     *
     * ★ 第 9 轮修的是"只读主卷、丢了分卷"，★ 第 10 轮修的是"只看当前会话、丢了前几次" ——
     * 同一条"证据别丢"的思路。
     */
    private fun readBody(sessions: List<File>): String {
        try {
            val sb = StringBuilder(256 * 1024)
            var emitted = 0
            outer@ for (f in sessions) {
                // 日志超过 1 MB 会自动分卷（`log-<stamp>-p2.txt` …），按序号升序全部拼上
                for (part in sessionParts(f)) {
                    for (line in part.readLines()) {
                        if (emitted >= MAX_BODY_LINES) break@outer
                        sb.append(line).append('\n')
                        emitted++
                    }
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        } catch (_: Throwable) {
        }
        // 退路：内存缓冲（例如文件还没被写入线程创建出来）
        val mem = TaskLogger.snapshot(maxLines = 0)
        return if (mem.isEmpty()) "(本次会话暂无日志)\n" else mem.joinToString("\n")
    }

    /**
     * ★ 第 10 轮：最近 [MAX_SESSIONS] 个会话的**主卷**，按时间**升序**（旧→新）返回。
     *
     * 会话主卷名是 `log-yyyyMMdd-HHmmss.txt` ⇒ 字典序 = 时间序，直接按名排序即可，
     * 不必读文件时间戳。分卷 `-pN` 必须排除：它们由 [sessionParts] 逐会话带出来，
     * 否则会被当成独立会话重复计入。
     */
    private fun recentSessions(context: Context): List<File> {
        val dir = TaskLogger.logsDirectory(context)
        if (!dir.isDirectory) return emptyList()
        val mains = dir.listFiles { f ->
            f.isFile && f.name.startsWith("log-") && f.name.endsWith(".txt") &&
                !f.name.substringBeforeLast('.').contains("-p")
        } ?: return emptyList()
        return mains.sortedByDescending { it.name }
            .take(MAX_SESSIONS)
            .sortedBy { it.name }
    }

    /** ★ 第 10 轮：把最近的 `crash-*.txt` 附在导出文件末尾（每个最多 2500 行）。 */
    private fun appendCrashes(sb: StringBuilder, context: Context) {
        val dir = TaskLogger.logsDirectory(context)
        val crashes = if (dir.isDirectory) {
            dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.name }
                ?.take(MAX_CRASH_ATTACH)
                ?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }

        sb.append("【崩溃现场】")
        if (crashes.isEmpty()) {
            sb.append("本机没有 crash-*.txt（= 没有发生过未捕获异常）\n")
            return
        }
        sb.append("最近 ").append(crashes.size).append(" 个 crash-*.txt（旧→新）\n")
        for (f in crashes) {
            sb.append('\n').append("───── ").append(f.name).append(" ─────\n")
            val text = try {
                f.readText()
            } catch (_: Throwable) {
                "(读取失败)\n"
            }
            sb.append(text.lines().take(2500).joinToString("\n")).append('\n')
        }
    }

    /**
     * 同一会话的全部分卷，按序号升序：`log-<stamp>.txt` 在前，`log-<stamp>-p2.txt` … 在后。
     *
     * 主卷文件名由 [TaskLogger] 用 `yyyyMMdd-HHmmss` 生成，分卷在其后追加 `-pN`，
     * 所以用"前缀匹配 + 解析 N 排序"即可，不必额外记录会话 id。
     */
    private fun sessionParts(sessionFile: File?): List<File> {
        val main = sessionFile ?: return emptyList()
        if (!main.exists() || main.length() == 0L) return emptyList()
        val dir = main.parentFile ?: return listOf(main)
        val base = main.nameWithoutExtension
        val extra = dir.listFiles { f ->
            f.isFile && f.name.startsWith("$base-p") && f.name.endsWith(".txt")
        }?.sortedBy {
            it.name.substringAfterLast("-p").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
        } ?: emptyList()
        return listOf(main) + extra
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
