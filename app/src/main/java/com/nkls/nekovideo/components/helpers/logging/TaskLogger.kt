package com.nkls.nekovideo.components.helpers.logging

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.nkls.nekovideo.BuildConfig
import com.nkls.nekovideo.components.helpers.storage.StorageRoot
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务日志 / 运行诊断日志（第 8 轮）。
 *
 * ## 为什么要有它
 * "开启美颜后视频无法播放"至少有 6 条候选根因（特效压根没启用 / 输出 Surface 没接上新 renderer /
 * 着色器编译失败 / 重建后控制器断连 / HDR 阻断 / 反复重建），它们在**界面上表现完全一样**。
 * 这个类负责把运行过程记下来，让问题可以被**判定**，而不是靠猜。
 *
 * ## 记录范围（六个域，见 [Channel]）
 * `env` 环境 · `player` 播放器生命周期 · `render` **首帧/surface/解码器** ·
 * `beauty` 美颜决策链 · `gl` 自研着色器 · `ui` 界面动作 · `error` 异常
 *
 * ## 一行长什么样
 * ```
 * 18:42:01.123 I/beauty [main/2][+3.412s] setVideoEffects.start effects=3 [BeautyGlEffect, RgbAdjustment]...
 * ```
 * 四个必备要素：**毫秒时间**、**级别/域**、**线程名+id**（美颜跑在 Media3 的 GL 线程，
 * 与播放逻辑、界面线程的先后顺序是排查核心）、**相对进程启动的偏移秒数**
 * （一眼看出"打开视频后多久还没首帧"）。
 *
 * ## 三条铁律
 * 1. ★ **日志绝不能成为新的故障源** —— 本类所有公开方法内部全部 try/catch，
 *    任何失败都静默吞掉，绝不外泄给播放链路。
 * 2. ★ **写盘异步** —— UI / 播放线程只把行塞进队列，落盘由一条专用线程（[WRITER_THREAD_NAME]）做。
 * 3. ★ **绝不记录密码**。路径原样保留（`locked://` 本身就是 UUID 混淆名，不含原文件名）。
 *
 * ## 文件落盘（位置与滚动）
 * - 目录：`context.filesDir/logs/`
 * - 文件：`log-<yyyyMMdd-HHmmss>.txt`（**每次进程启动新建一个**），超 1 MB 自动转 `<同名>-p2.txt`
 * - 崩溃：另写 `crash-<yyyyMMdd-HHmmss>.txt`（见 [installCrashHandler]）
 * - 上限：普通日志保留最近 [KEEP_LOG_FILES] 个、崩溃文件保留最近 [KEEP_CRASH_FILES] 个（见 [LogExporter.cleanup]）
 *
 * ## ★ 事件名契约（[buildSummary] 靠这些词做自动判定，改名必须同步改）
 * | 事件名 | 记在哪 | 摘要里的作用 |
 * |---|---|---|
 * | `setVideoEffects.start` / `.done` | `MediaPlaybackService` | 出现 `.done` 即视作"特效已启用" |
 * | `.done` 的 `detail` 里要有 `effects=<n>` | 同上 | 摘要显示最后一次特效链 |
 * | `player.rebuild` | 同上 | 重建次数（≥3 判为 H6） |
 * | `onRenderedFirstFrame` | `Player.Listener` | ★ 首帧是否到达 |
 * | `onVideoSizeChanged` / `onSurfaceSizeChanged` | `Player.Listener` | ★ surface 是否有尺寸 |
 * | `decoderInitialized` + `name=<解码器>` | `AnalyticsListener` | 解码器是否起来 |
 * | `hdrBlocked=true` | `VideoPlayerOverlay` | HDR 阻断（H5） |
 * | `gl.error` / `build FAILED` | `BeautyShaderProgram` | 着色器问题（H3） |
 *
 * ## 包名注意
 * 本文件在 `helpers/logging/` 目录下，**package 是 `…components.helpers.logging`**
 * （与 `helpers/storage/` 同风格）。`helpers/private/`、`helpers/video/` 那两个是**反例**——
 * 它们的 package 是 `…components.helpers`（目录名不等于包名）。**加 import 前先看目标文件第一行。**
 */
object TaskLogger {

    // ───────────────────────────────────────────────────────────── 级别

    /** 日志级别。数值越大越啰嗦；[rank] 用于"要不要写"的比较。 */
    enum class Level(val letter: Char, val rank: Int) {
        ERROR('E', 0),
        WARN('W', 1),
        INFO('I', 2),
        DEBUG('D', 3),
        VERBOSE('V', 4);

        companion object {
            fun fromName(name: String?): Level =
                entries.firstOrNull { it.name == name } ?: DEBUG
        }
    }

    /**
     * 域标签。固定小写单词，便于 `grep beauty` 单看一路。
     * 用常量而不是散落的字符串字面量，避免拼错导致过滤失效。
     */
    object Channel {
        /** 环境：版本、设备、ABI、GL ES、存储根、权限。 */
        const val ENV = "env"

        /** 播放器生命周期：建/释放/重建、prepare、播放状态、媒体项切换。 */
        const val PLAYER = "player"

        /** 渲染：**首帧**、视频尺寸、surface 尺寸、解码器、输入格式、丢帧。 */
        const val RENDER = "render"

        /** 美颜决策链：参数解析、是否建管线、是否真的调了 `setVideoEffects`。 */
        const val BEAUTY = "beauty"

        /** 自研着色器：编译、配置、绘制、GL 错误。 */
        const val GL = "gl"

        /** 界面动作：打开/关闭播放器、开关、滑块提交、下发命令。 */
        const val UI = "ui"

        /** 异常：播放错误、未捕获异常。 */
        const val ERROR = "error"
    }

    // ───────────────────────────────────────────────────────────── 常量

    private const val PREFS_NAME = "nekovideo_settings"
    private const val KEY_LEVEL = "log_level"
    private const val KEY_LAST_ABNORMAL = "log_last_abnormal"
    private const val KEY_SESSION_COUNT = "log_session_count"

    /** 内存环形缓冲的行数上限（供崩溃现场与诊断摘要使用）。 */
    private const val MAX_MEMORY_LINES = 2000

    /** 写入队列容量。满了就丢并计数，绝不阻塞调用方。 */
    private const val QUEUE_CAPACITY = 4096

    /** 单个日志文件的字节上限，超出自动转分卷。 */
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024

    /** 普通日志保留份数。 */
    const val KEEP_LOG_FILES = 5

    /** 崩溃日志保留份数。 */
    const val KEEP_CRASH_FILES = 3

    private const val WRITER_THREAD_NAME = "MistVD-LogWriter"
    private const val LOGCAT_TAG = "MistVD"

    /** 单行里单个值的最长长度，超出截断（路径可能很长）。 */
    private const val MAX_VALUE_CHARS = 200

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val STAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val FULL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // ───────────────────────────────────────────────────────────── 状态

    private val initialized = AtomicBoolean(false)
    private val writerStarted = AtomicBoolean(false)

    /** 进程启动时刻，用于算相对时间偏移。 */
    private val processStartAt = System.currentTimeMillis()

    private val ringLock = Any()
    private val ring = ArrayDeque<String>()

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val droppedLines = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentFile: File? = null

    @Volatile
    private var currentBytes: Long = 0L

    @Volatile
    private var currentPart: Int = 0

    @Volatile
    private var sessionFile: File? = null

    @Volatile
    private var crashHandlerInstalled = false

    @Volatile
    private var currentLevel: Level = Level.DEBUG

    private val _version = MutableStateFlow(0)

    /** 每写一行自增，供界面按需刷新（不要每行都重组，界面自己控制节流）。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    // ───────────────────────────────────────────────────────────── 初始化

    /**
     * 初始化。**幂等**：同一进程内多次调用只有第一次生效（Activity 重建不会另开文件）。
     *
     * 必须在 `StorageRoot.init()` 之后调用（会话头要读存储根）。
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        try {
            val ctx = context.applicationContext
            appContext = ctx
            currentLevel = Level.fromName(readLevel(ctx))

            ensureWriter()
            writeSessionHeader(ctx)
            installCrashHandler()
        } catch (t: Throwable) {
            // 日志系统自己起不来时，绝不能连累 App。
            Log.w(LOGCAT_TAG, "TaskLogger.init failed", t)
        }
    }

    /** 由 `MainActivity.onDestroy`（真正结束、非配置变更）调用，用于标记"本次会话正常结束"。 */
    fun markCleanExit(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_LAST_ABNORMAL, false).commit()
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────────────────────────────────────────── 写日志

    fun e(channel: String, event: String, detail: String? = null) =
        log(Level.ERROR, channel, event, detail)

    fun w(channel: String, event: String, detail: String? = null) =
        log(Level.WARN, channel, event, detail)

    fun i(channel: String, event: String, detail: String? = null) =
        log(Level.INFO, channel, event, detail)

    fun d(channel: String, event: String, detail: String? = null) =
        log(Level.DEBUG, channel, event, detail)

    fun v(channel: String, event: String, detail: String? = null) =
        log(Level.VERBOSE, channel, event, detail)

    /** 记录一条错误对象（含类型与消息，不含堆栈 —— 堆栈交给 [logStackTrace]）。 */
    fun e(channel: String, event: String, throwable: Throwable) =
        log(Level.ERROR, channel, event, describe(throwable))

    /**
     * 记一条多行堆栈。**只在崩溃或 GL 编译失败这种真正需要定位的场合用**。
     * 每行单独成条、带 `  | ` 缩进前缀，保证"一行一条日志"的格式不被破坏。
     */
    fun logStackTrace(channel: String, event: String, throwable: Throwable?) {
        if (throwable == null) return
        log(Level.ERROR, channel, event, describe(throwable))
        throwable.stackTrace.take(30).forEach { frame ->
            log(Level.ERROR, channel, "$event.at", "  | $frame")
        }
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            log(Level.ERROR, channel, "$event.cause", "cause[$depth]: ${describe(cause)}")
            cause.stackTrace.take(10).forEach { frame ->
                log(Level.ERROR, channel, "$event.cause.at", "  | $frame")
            }
            cause = cause.cause
            depth++
        }
    }

    /** 核心写入。级别过滤在最前（不拼字符串），全程 try/catch。 */
    fun log(level: Level, channel: String, event: String, detail: String? = null) {
        if (level.rank > currentLevel.rank) return
        try {
            val line = format(level, channel, event, detail)

            synchronized(ringLock) {
                ring.addLast(line)
                while (ring.size > MAX_MEMORY_LINES) ring.removeFirst()
            }
            _version.value = _version.value + 1

            // 同步吐一份到 logcat：真机排查时用 adb 也能直接看
            Log.println(androidPriority(level), LOGCAT_TAG, line)

            // 落盘走队列；队列满就丢（绝不阻塞播放线程），并记下丢失数量
            if (!queue.offer(line)) droppedLines.incrementAndGet()
        } catch (_: Throwable) {
            // 铁律 1：日志自身的问题绝不允许冒泡
        }
    }

    // ───────────────────────────────────────────────────────────── 读取

    /** 内存里最近 [maxLines] 行（供界面显示）。 */
    fun snapshot(maxLines: Int = 1000): List<String> = try {
        val all = synchronized(ringLock) { ring.toList() }
        if (maxLines <= 0 || all.size <= maxLines) all else all.takeLast(maxLines)
    } catch (_: Throwable) {
        emptyList()
    }

    /** 内存里全部行文本（供"复制到剪贴板"用）。 */
    fun snapshotText(maxLines: Int = 0): String =
        snapshot(maxLines).joinToString("\n")

    /** 当前会话的日志文件（可能为 null：还没写过任何一行）。 */
    fun currentSessionFile(): File? = sessionFile

    fun logsDirectory(context: Context): File =
        File(context.applicationContext.filesDir, LOG_DIR_NAME)

    /**
     * 清空：内存环形缓冲立刻清空，日志文件删除（写入线程下次写时会自动新建）。
     */
    fun clear(context: Context) {
        try {
            synchronized(ringLock) { ring.clear() }
            currentFile = null
            sessionFile = null
            currentBytes = 0L
            currentPart = 0
            droppedLines.set(0)
            logsDirectory(context).listFiles()?.forEach { runCatching { it.delete() } }
            _version.value = _version.value + 1
            i(Channel.ENV, "log.cleared")
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────────────────────────────────────────── 级别设置

    fun getLevel(): Level = currentLevel

    fun setLevel(context: Context, level: Level) {
        try {
            currentLevel = level
            prefs(context).edit().putString(KEY_LEVEL, level.name).apply()
            i(Channel.ENV, "log.level", "level=${level.name}")
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────────────────────────────────────────── 诊断摘要

    /**
     * ★ 自动诊断摘要 —— 放在导出文件最前面，**给不想读全文的人看结论**。
     *
     * 把"能不能播"这件事的 8 条判定逐项跑一遍，最后给一句自动结论。
     * 依据是内存环形缓冲（= 当前会话最近 [MAX_MEMORY_LINES] 行）。
     */
    fun buildSummary(): String {
        val lines = try {
            synchronized(ringLock) { ring.toList() }
        } catch (_: Throwable) {
            emptyList()
        }

        fun count(needle: String): Int = lines.count { it.contains(needle) }
        fun has(needle: String): Boolean = lines.any { it.contains(needle) }
        fun lastWith(needle: String): String? = lines.lastOrNull { it.contains(needle) }
        fun value(line: String?, key: String): String {
            if (line == null) return "-"
            val idx = line.indexOf("$key=")
            if (idx < 0) return "-"
            val rest = line.substring(idx + key.length + 1)
            val end = rest.indexOfFirst { it == ' ' || it == ',' }
            return if (end < 0) rest else rest.substring(0, end)
        }

        val applyCount = count("setVideoEffects.done")
        val lastEffects = value(lastWith("setVideoEffects.done"), "effects")
        val pipelineOn = lastWith("setVideoEffects.done")?.contains("pipeline=on") == true ||
            has("pipeline=ON")
        val firstFrame = has("onRenderedFirstFrame")
        val videoSizeEvents = count("onVideoSizeChanged")
        val surfaceSizeEvents = count("onSurfaceSizeChanged")
        val decoderLine = lastWith("decoderInitialized")
        val decoder = value(decoderLine, "name")
        val errors = count("E/error ")
        val rebuilds = count("player.rebuild")
        val hdrBlocked = has("hdrBlocked=true")
        val glError = has("gl.error") || has("build FAILED")

        val sb = StringBuilder()
        sb.append("【诊断摘要】生成于 ")
        sb.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        sb.append('\n')
        sb.append("1. setVideoEffects 调用次数：").append(applyCount)
        if (lastEffects != "-") sb.append("  最后一次特效链：").append(lastEffects)
        sb.append('\n')
        sb.append("2. 特效管线（VideoGraph）：")
            .append(if (applyCount > 0) "已启用" else "未启用").append('\n')
        sb.append("3. onRenderedFirstFrame 首帧：")
            .append(if (firstFrame) "✔ 已到达" else "✘ 未到达").append('\n')
        sb.append("4. 视频尺寸事件：").append(videoSizeEvents)
            .append("   surface 尺寸事件：").append(surfaceSizeEvents).append('\n')
        sb.append("5. 解码器初始化：")
            .append(if (decoder != "-") "✔ $decoder" else "✘ 未见初始化记录").append('\n')
        sb.append("6. 错误条数：").append(errors)
            .append("   播放器重建次数：").append(rebuilds).append('\n')
        sb.append("7. HDR 是否被阻断：").append(if (hdrBlocked) "是" else "否")
            .append("   GL 报错：").append(if (glError) "有" else "无").append('\n')

        // 8. 自动结论 —— 把候选根因直接收敛到一条
        val verdict = when {
            applyCount == 0 && !hdrBlocked ->
                "特效从未启用 ⇒ 指向【H1：美颜开关或 COMMAND_SET_BEAUTY 未生效】"
            hdrBlocked ->
                "HDR 片源被阻断 ⇒ 指向【H5：HDR 判定】"
            glError ->
                "自分着色器/GL 有报错 ⇒ 指向【H3：着色器编译或运行失败】"
            applyCount > 0 && !firstFrame && surfaceSizeEvents == 0 ->
                "特效已启用、首帧与 surface 尺寸均未出现 ⇒ 指向【H2：输出 Surface 没接上新 renderer】"
            applyCount > 0 && !firstFrame ->
                "特效已启用但首帧未到达 ⇒ 指向【H2/H3：渲染输出或着色器】"
            rebuilds >= 3 ->
                "播放器重建 $rebuilds 次（偏多）⇒ 指向【H6：重建时机/反复重建】"
            firstFrame ->
                "已拿到首帧，本次会话**未见黑屏**（若业主仍看到黑屏，请确认是否发生在别的会话）"
            else -> "证据不足，请连同文件全文一并送检"
        }
        sb.append("8. 自动结论：").append(verdict).append('\n')

        val dropped = droppedLines.get()
        if (dropped > 0) {
            sb.append("提示：因写入队列满，本会话丢弃了 ").append(dropped).append(" 行日志（不影响判定，仅说明日志过密）\n")
        }
        return sb.toString()
    }

    // ───────────────────────────────────────────────────────────── 崩溃钩子

    /**
     * 安装全局未捕获异常钩子。
     *
     * ⚠️ **必须转发给原 handler** —— 吞掉异常会掩盖问题（真正的崩溃会变成静默退出，更难查）。
     * ⚠️ handler 里**同步**写文件：进程马上就要死了，不能走异步队列。
     */
    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        try {
            val ctx = appContext ?: return
            val original = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    handleCrash(ctx, thread, throwable)
                } catch (_: Throwable) {
                    // 崩溃现场写不进去也不能再抛
                }
                original?.uncaughtException(thread, throwable)
            }
        } catch (_: Throwable) {
        }
    }

    private fun handleCrash(ctx: Context, thread: Thread, throwable: Throwable) {
        // 1) 标记"上次会话异常退出"，下次启动会在会话头里点出来
        runCatching { prefs(ctx).edit().putBoolean(KEY_LAST_ABNORMAL, true).commit() }

        // 2) 拼现场：崩溃前内存里的全部行 + 堆栈
        val tail = synchronized(ringLock) { ring.toList() }
        val sb = StringBuilder(tail.size * 80 + 4096)
        sb.append("═══ CRASH ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
            .append(" ═══\n")
        sb.append("thread: ").append(thread.name).append('/').append(thread.id)
            .append(" (isMain=").append(thread == android.os.Looper.getMainLooper().thread).append(")\n")
        sb.append("exception: ").append(describe(throwable)).append('\n')
        throwable.stackTrace.take(60).forEach { sb.append("    at ").append(it).append('\n') }
        var cause = throwable.cause
        var depth = 0
        while (cause != null && depth < 5) {
            sb.append("caused by[").append(depth).append("]: ").append(describe(cause)).append('\n')
            cause.stackTrace.take(20).forEach { sb.append("    at ").append(it).append('\n') }
            cause = cause.cause
            depth++
        }
        sb.append("\n───── 崩溃前日志（内存缓冲 ").append(tail.size).append(" 行）─────\n")
        tail.forEach { sb.append(it).append('\n') }

        // 3) 同步落盘
        runCatching {
            val dir = logsDirectory(ctx).apply { mkdirs() }
            val f = File(dir, "crash-${LocalDateTime.now().format(STAMP_FMT)}.txt")
            FileOutputStream(f).use { it.write(sb.toString().toByteArray()) }
            Log.e(LOGCAT_TAG, "crash log written: ${f.absolutePath}")
        }
    }

    // ───────────────────────────────────────────────────────────── 内部：会话头

    private fun writeSessionHeader(ctx: Context) {
        val p = prefs(ctx)
        val sessionNo = p.getInt(KEY_SESSION_COUNT, 0) + 1
        val lastAbnormal = p.getBoolean(KEY_LAST_ABNORMAL, false)
        // 立刻把"本次正在运行"标上；正常结束时会由 markCleanExit 复位
        runCatching {
            p.edit()
                .putInt(KEY_SESSION_COUNT, sessionNo)
                .putBoolean(KEY_LAST_ABNORMAL, true)
                .apply()
        }

        val sb = StringBuilder()
        sb.append("═══ SESSION ").append(LocalDateTime.now().format(FULL_FMT))
            .append(" · 启动 #").append(sessionNo).append(" ═══\n")
        sb.append("App: ").append(BuildConfig.APPLICATION_ID)
            .append(' ').append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(')')
            .append("  ").append(BuildConfig.BUILD_TYPE).append('\n')
        sb.append("Device: ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL)
            .append("  Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Build: display=").append(Build.DISPLAY)
            .append("  hardware=").append(Build.HARDWARE)
            .append("  abis=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        sb.append("GLES: ").append(glEsVersion(ctx))
            .append("  (真实 GL vendor/renderer 会在着色器首次 configure 时记录)\n")
        sb.append("Storage: browseRoot=").append(StorageRoot.browseRoot)
            .append("  vaultRoot=").append(StorageRoot.vaultRoot).append('\n')
        sb.append("Storage perm: MANAGE_EXTERNAL_STORAGE=")
            .append(runCatching { Environment.isExternalStorageManager() }.getOrDefault(false))
            .append('\n')
        sb.append("Prev session: ")
            .append(if (lastAbnormal) "⚠ ABNORMAL EXIT（上次未正常结束，见 crash-*.txt）" else "clean")
            .append('\n')
        // 先把文件建出来，好让会话头里能写上真实文件名
        val f = newSessionFile(ctx)
        sessionFile = f
        currentFile = f
        currentBytes = 0L
        currentPart = 0

        sb.append("Log level: ").append(currentLevel.name)
            .append("   日志文件: ").append(f.name)
            .append('\n')
        sb.append("═══")

        val header = sb.toString()
        synchronized(ringLock) { ring.addLast(header) }
        _version.value = _version.value + 1
        Log.println(Log.INFO, LOGCAT_TAG, header)

        // 会话头必须同步落盘：保证它是文件的第一段，而不是被异步队列挤到后面
        runCatching { FileOutputStream(f, true).use { it.write((header + "\n").toByteArray()) } }
        currentBytes = header.length.toLong() + 1L
    }

    private fun glEsVersion(ctx: Context): String = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.deviceConfigurationInfo?.glEsVersion ?: "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    // ───────────────────────────────────────────────────────────── 内部：写盘

    private fun ensureWriter() {
        if (!writerStarted.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    val line = queue.take()
                    appendLine(line)
                } catch (_: InterruptedException) {
                    // 中断就退出，由 JVM 回收
                    return@Thread
                } catch (_: Throwable) {
                    // 单行写失败不能让整条流水线停掉
                }
            }
        }, WRITER_THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun appendLine(line: String) {
        val ctx = appContext ?: return
        try {
            var f = currentFile
            if (f == null || currentBytes > MAX_FILE_BYTES) {
                currentPart = if (f == null) 0 else currentPart + 1
                f = if (currentPart == 0) newSessionFile(ctx) else newPartFile(ctx, currentPart)
                currentFile = f
                currentBytes = 0L
                if (currentPart == 0) sessionFile = f
            }
            FileOutputStream(f, true).use { it.write((line + "\n").toByteArray()) }
            currentBytes += line.length.toLong() + 1
        } catch (_: Throwable) {
        }
    }

    private fun newSessionFile(ctx: Context): File {
        val dir = logsDirectory(ctx).apply { mkdirs() }
        return File(dir, "log-${LocalDateTime.now().format(STAMP_FMT)}.txt")
    }

    private fun newPartFile(ctx: Context, part: Int): File {
        val dir = logsDirectory(ctx).apply { mkdirs() }
        val base = sessionFile?.nameWithoutExtension ?: "log"
        return File(dir, "$base-p${part + 1}.txt")
    }

    // ───────────────────────────────────────────────────────────── 内部：格式化

    private fun format(level: Level, channel: String, event: String, detail: String?): String {
        val t = Thread.currentThread()
        val rel = (System.currentTimeMillis() - processStartAt) / 1000.0
        val sb = StringBuilder(160)
        sb.append(LocalTime.now().format(TIME_FMT))
        sb.append(' ').append(level.letter).append('/').append(channel)
        sb.append(" [").append(threadLabel(t)).append('/').append(t.id).append(']')
        sb.append('[').append(String.format(Locale.US, "+%.3fs", rel)).append(']')
        sb.append(' ').append(event)
        if (!detail.isNullOrBlank()) {
            sb.append(": ").append(clip(sanitize(detail)))
        }
        return sb.toString()
    }

    /** 线程名里可能有换行或奇怪字符，统一压平。 */
    private fun threadLabel(t: Thread): String {
        val n = t.name.ifBlank { "?" }
        return if (n == "main") "main" else n
    }

    /** 换行/制表转成可见转义，保证"一行一条日志"的格式不被破坏。 */
    private fun sanitize(s: String): String =
        s.replace("\r\n", "\\n").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    /** 超长值截断（路径可能很长），保留头部信息。 */
    private fun clip(s: String): String =
        if (s.length <= MAX_VALUE_CHARS) s
        else s.take(MAX_VALUE_CHARS - 20) + "…<省略 ${s.length - MAX_VALUE_CHARS + 20} 字符>"

    private fun describe(t: Throwable): String =
        "${t.javaClass.name}: ${t.message ?: "(no message)"}"

    private fun androidPriority(level: Level): Int = when (level) {
        Level.ERROR -> Log.ERROR
        Level.WARN -> Log.WARN
        Level.INFO -> Log.INFO
        Level.DEBUG -> Log.DEBUG
        Level.VERBOSE -> Log.VERBOSE
    }

    // ───────────────────────────────────────────────────────────── 内部：杂项

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readLevel(context: Context): String? =
        runCatching { prefs(context).getString(KEY_LEVEL, null) }.getOrNull()

    /** 落盘目录名（`filesDir/logs`）。公开给 [LogExporter]。 */
    const val LOG_DIR_NAME = "logs"

    /** 导出到公共目录时使用的子目录名（`<存储根>/MistVD/Logs`）。 */
    const val PUBLIC_DIR_NAME = "MistVD/Logs"
}
