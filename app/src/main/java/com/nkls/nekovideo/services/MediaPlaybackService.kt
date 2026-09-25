package com.nkls.nekovideo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.io.File
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import com.nkls.nekovideo.components.OptimizedThumbnailManager
import kotlinx.coroutines.*
import android.net.Uri
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.nkls.nekovideo.components.helpers.HybridDataSourceFactory
import com.nkls.nekovideo.components.helpers.LockedPlaybackSession
import com.nkls.nekovideo.components.helpers.PlaylistManager
import com.nkls.nekovideo.components.helpers.VideoProgressStore
import com.nkls.nekovideo.components.helpers.PlaylistNavigator
import com.nkls.nekovideo.components.helpers.ContinueWatchingStore
import com.nkls.nekovideo.components.helpers.ContinueWatchingEntry
import com.nkls.nekovideo.components.helpers.ContinueWatchingTrackPreference
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nkls.nekovideo.components.helpers.BeautySettingsStore
import com.nkls.nekovideo.components.helpers.logging.TaskLogger
import com.nkls.nekovideo.components.player.beauty.BeautyEffects
import com.nkls.nekovideo.components.player.beauty.BeautyParams
// 第 8 轮：渲染侧观测（AnalyticsListener 是排查"黑屏/转圈"的关键，见本文件 analyticsListener）
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    companion object {
        private const val DISABLE_PLAYBACK_ARTWORK_REFRESH_FOR_DEBUG = false
        const val COMMAND_APPLY_EXTERNAL_SUBTITLE = "nekovideo.apply_external_subtitle"
        // ===== 第 6 轮美颜：界面 → 服务的参数下发通道（照抄外部字幕那套范式）=====
        const val COMMAND_SET_BEAUTY = "nekovideo.set_beauty"
        const val EXTRA_BEAUTY_SMOOTH = "beauty_smooth"
        const val EXTRA_BEAUTY_WHITEN = "beauty_whiten"
        const val EXTRA_BEAUTY_ROSY = "beauty_rosy"
        const val EXTRA_BEAUTY_SHARPEN = "beauty_sharpen"
        const val EXTRA_BEAUTY_BRIGHTNESS = "beauty_brightness"
        const val EXTRA_BEAUTY_CONTRAST = "beauty_contrast"
        const val EXTRA_BEAUTY_SATURATION = "beauty_saturation"
        const val COMMAND_CLEAR_EXTERNAL_SUBTITLE = "nekovideo.clear_external_subtitle"
        const val EXTRA_SUBTITLE_URI = "subtitle_uri"
        const val EXTRA_SUBTITLE_NAME = "subtitle_name"
        const val ACTION_START_SLEEP_TIMER = "nekovideo.action.START_SLEEP_TIMER"
        const val ACTION_CLEAR_SLEEP_TIMER = "nekovideo.action.CLEAR_SLEEP_TIMER"
        const val ACTION_REQUEST_SLEEP_TIMER_STATE = "nekovideo.action.REQUEST_SLEEP_TIMER_STATE"
        const val ACTION_REFRESH_CURRENT_ARTWORK = "nekovideo.action.REFRESH_CURRENT_ARTWORK"
        /**
         * ★ 第 7 轮：请服务按"当前正在播放的视频"重新解析一遍美颜参数并立即应用。
         * 用途：在**设置页**改了总开关 / 全局参数之后，若此时迷你播放器或后台播放还在跑，
         * 需要一条即时生效的通路（播放器内的「仅此视频」面板有 MediaController，不走这条）。
         */
        const val ACTION_REFRESH_BEAUTY = "nekovideo.action.REFRESH_BEAUTY"
        const val ACTION_PERSIST_CONTINUE_WATCHING = "nekovideo.action.PERSIST_CONTINUE_WATCHING"
        const val ACTION_PAUSE_FOR_BACKGROUND = "nekovideo.action.PAUSE_FOR_BACKGROUND"
        const val EXTRA_SLEEP_TIMER_DURATION_MS = "sleep_timer_duration_ms"
        private const val PROGRESS_PERSIST_INTERVAL_MS = 10_000L
        private const val PLAYBACK_ARTWORK_REFRESH_DELAY_MS = 2_500L
        const val BROADCAST_SLEEP_TIMER_STATE_CHANGED = "nekovideo.broadcast.SLEEP_TIMER_STATE_CHANGED"
        const val EXTRA_SLEEP_TIMER_ACTIVE = "sleep_timer_active"
        const val EXTRA_SLEEP_TIMER_END_AT_MS = "sleep_timer_end_at_ms"

        fun startWithPlaylist(
            context: Context,
            playlist: List<String>,
            initialIndex: Int = 0,
            initialPositionMs: Long = 0L
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_PLAYLIST"
                putStringArrayListExtra("PLAYLIST", ArrayList(playlist))
                putExtra("INITIAL_INDEX", initialIndex)
                putExtra("INITIAL_POSITION_MS", initialPositionMs)
            }
            context.startService(intent)
        }

        fun refreshPlayer(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "REFRESH_PLAYER"
            }
            context.startService(intent)
        }

        fun refreshCurrentArtwork(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_REFRESH_CURRENT_ARTWORK
            }
            context.startService(intent)
        }

        fun updatePlayerWindow(context: Context, window: List<String>, currentIndexInWindow: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_WINDOW"
                putStringArrayListExtra("WINDOW", ArrayList(window))
                putExtra("CURRENT_INDEX_IN_WINDOW", currentIndexInWindow)
            }
            context.startService(intent)
        }

        fun seekToPlaylistIndex(context: Context, playlistIndex: Int, autoPlay: Boolean = false) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "SEEK_TO_PLAYLIST_INDEX"
                putExtra("PLAYLIST_INDEX", playlistIndex)
                putExtra("AUTO_PLAY", autoPlay)
            }
            context.startService(intent)
        }

        fun removePlaylistItem(context: Context, removeIndex: Int, nextIndex: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "REMOVE_PLAYLIST_ITEM"
                putExtra("REMOVE_INDEX", removeIndex)
                putExtra("NEXT_INDEX", nextIndex)
            }
            context.startService(intent)
        }

        fun resumeLocalPlayback(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "RESUME_LOCAL_PLAYBACK"
            }
            context.startService(intent)
        }

        fun updatePlaylistAfterDeletion(context: Context, playlist: List<String>, nextIndex: Int) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "UPDATE_PLAYLIST_AFTER_DELETION"
                putStringArrayListExtra("PLAYLIST", ArrayList(playlist))
                putExtra("NEXT_INDEX", nextIndex)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = "STOP_SERVICE"
            }
            context.startService(intent)
        }

        fun persistContinueWatching(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_PERSIST_CONTINUE_WATCHING
            }
            context.startService(intent)
        }

        fun pauseForBackground(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_PAUSE_FOR_BACKGROUND
            }
            context.startService(intent)
        }

        fun startSleepTimer(context: Context, durationMs: Long) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_START_SLEEP_TIMER
                putExtra(EXTRA_SLEEP_TIMER_DURATION_MS, durationMs)
            }
            context.startService(intent)
        }

        fun clearSleepTimer(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_CLEAR_SLEEP_TIMER
            }
            context.startService(intent)
        }

        fun requestSleepTimerState(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_REQUEST_SLEEP_TIMER_STATE
            }
            context.startService(intent)
        }
    }

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    // AudioFocus
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    private val preloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentPlaybackProcessingJob: Job? = null
    private var progressPersistenceJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var isSleepTimerActive = false
    private var sleepTimerEndAtMs = 0L

    // Flag para evitar recursão na atualização de metadados
    private var isUpdatingMetadata = false
    private var lastAppliedArtworkKey: String? = null

    // ✅ NOVO: Timestamp da última atualização de window para ignorar eventos assíncronos
    private var lastWindowUpdateTime = 0L
    private val WINDOW_UPDATE_COOLDOWN_MS = 500L // Ignora eventos por 500ms após atualização
    private var pendingContinueWatchingRestore: ContinueWatchingEntry? = null
    private var pendingSeekIndex: Int? = null
    private var activeSeekIndex: Int? = null
    private var trackedMediaItemUri: String? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // ★ 第 7 轮：美颜管线的"是否需要"状态（v1.19 黑屏 / 转圈回归的修复核心）
    //
    // 两个关键事实（读 Media3 1.7.1 源码实证，别凭记忆）：
    //  ① MediaCodecVideoRenderer.onEnabled() 的判定是 `videoEffects != null`，**不是** `!isEmpty()`
    //     ⇒ 传入**空列表**同样算"有特效"，照样把整条渲染切到 VideoGraph 管线
    //       （getSurfaceForCodec() 改走 videoSink.getInputSurface()，解码器不再直连 PlayerView 的
    //        Surface）。本机拿不到输出画面 ⇒ 黑屏 / 首帧永不到达（转圈）。
    //  ② `hasSetVideoSink` 只在 renderer.onReset() 里复位，`setVideoEffects()` 方法体仅 3 行、
    //     完全不碰它 ⇒ 一旦启用过，"关掉"也回不到非 VideoGraph 状态。
    //
    // ⇒ 两条铁律：**不需要特效时坚决不调 setVideoEffects**；**跨"启用/未启用"边界只能重建 player**。
    // ─────────────────────────────────────────────────────────────────────────────

    /** 建 player 时该套用的美颜参数。由 [prepareBeautyForPlaylist] 按"即将播放谁"算出。 */
    private var pendingBeautyParams: BeautyParams = BeautyParams.DEFAULT

    /** 当前 player 的 renderer 里是否**已经建了 VideoGraph**（= 曾用非空列表调过 setVideoEffects）。 */
    private var beautyPipelineActive = false

    /**
     * ★ 第 9 轮（修复 R1-a）：**当前管线里实际装着的那一份参数**。
     *
     * 由 [createConfiguredPlayer]（build 阶段装上后）与 [applyBeautyEffects]（动态下发成功后）写入。
     * 有了它才能识别"界面把同一份参数又下发了一遍"这种**无变化的重下发** ——
     * 而那种重下发会重建 `VideoFrameProcessor`，且恰好落在 prepare 窗口里（详见 [applyBeautyEffects]）。
     */
    private var appliedBeautyParams: BeautyParams = BeautyParams.DEFAULT

    /**
     * ★ 第 9 轮（修复 R1-c）：因播放器还没 `STATE_READY` 而**推迟**的特效更新。
     * 到 `onPlaybackStateChanged(STATE_READY)` 时补发（见 playerListener）。
     */
    private var pendingDynamicApply: BeautyParams? = null

    // ─────────────────────────────────────────────────────────────────────────────
    // 第 8 轮：日志辅助
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 美颜参数的"日志文本"。
     *
     * **刻意不用 data class 自带的 `toString()`** —— 虽然 Kotlin 生成的 toString 里字段名是
     * 字符串字面量（R8 不会改），但一旦将来有人覆写 toString 或改用反射，日志格式就会在
     * release 包里悄悄变样（类名被混淆）。这里显式拼一份，保证**任何构建类型下日志格式稳定**，
     * 否则"日志看不懂"会把排查带进沟里。
     */
    private fun BeautyParams.logText(): String =
        "smooth=$smooth,whiten=$whiten,rosy=$rosy,sharpen=$sharpen," +
            "bright=$brightness,contrast=$contrast,sat=$saturation"

    /** 数一数异常链有多深（只用于摘要行，不展开内容）。 */
    private fun causeChainDepth(t: Throwable?): Int {
        var c = t?.cause
        var n = 0
        while (c != null && n < 16) {
            n++
            c = c.cause
        }
        return n
    }

    /** 播放状态常量转可读名 —— 日志里写数字等于没写。 */
    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?#$state"
    }

    private fun createConfiguredPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Keep the custom datasource on every player recreation so locked:// URIs
        // continue working after refreshes triggered by cast disconnects.
        val dataSourceFactory = HybridDataSourceFactory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setAudioAttributes(audioAttributes, true)
                addListener(playerListener)
                // ★ 第 8 轮：渲染侧观测（解码器 / 输入格式 / 丢帧）。
                //   放在这里而不是 onCreate —— 重建出来的 player 也必须带上这两组监听，
                //   否则"重建之后的那次播放"就是观测盲区，恰好是美颜场景最需要看的一段。
                addAnalyticsListener(analyticsListener)
                // ★ 第 7 轮修正：**只在真正需要特效时才调 setVideoEffects**。
                // v1.19 在这里无条件调了一次；总开关关闭时传进去的是**空列表**，
                // 而"空列表 ≠ 无特效"（见上方注释①）⇒ 未开美颜也切进了 VideoGraph ⇒ 黑屏。
                // setVideoEffects 必须在 prepare() 之前调用才能建起管线，所以放在 build() 里。
                beautyPipelineActive = false
                // ★ 第 9 轮（R1-e）：重建时 build 阶段会把参数装好，所以推迟队列必须清空，
                //   否则 READY 之后会把同一份参数再重装一次（正是我们要消灭的那次）。
                pendingDynamicApply = null
                if (!pendingBeautyParams.isDefault) {
                    val effects = runCatching { BeautyEffects.build(pendingBeautyParams) }
                        .getOrElse { emptyList() }
                    TaskLogger.i(
                        TaskLogger.Channel.BEAUTY, "setVideoEffects.start",
                        "at=build effects=${effects.size} params=${pendingBeautyParams.logText()}"
                    )
                    runCatching {
                        setVideoEffects(effects)
                        beautyPipelineActive = true
                    }.onFailure {
                        beautyPipelineActive = false
                        TaskLogger.e(TaskLogger.Channel.BEAUTY, "setVideoEffects.failed", it)
                    }
                    // ★ 第 9 轮（R1-a）：记下"管线里现在装的就是这一份"。装失败则回到默认，
                    //   否则会把"没装上"误判成"已一致"而永远不再下发。
                    appliedBeautyParams =
                        if (beautyPipelineActive) BeautyParams.sanitize(pendingBeautyParams)
                        else BeautyParams.DEFAULT
                    TaskLogger.i(
                        TaskLogger.Channel.BEAUTY, "setVideoEffects.done",
                        "at=build ok=$beautyPipelineActive effects=${effects.size} " +
                            "pipeline=${if (beautyPipelineActive) "on" else "off"} " +
                            "params=${pendingBeautyParams.logText()}"
                    )
                } else {
                    // "什么都没做"也必须留证据：否则日志里"没出现 setVideoEffects"
                    // 无法区分"故意跳过"与"代码没跑到"。
                    appliedBeautyParams = BeautyParams.DEFAULT
                    TaskLogger.i(
                        TaskLogger.Channel.BEAUTY, "setVideoEffects.skip",
                        "at=build reason=paramsDefault (★ 未启用就一次 API 都不调，避免切进 VideoGraph)"
                    )
                }
                TaskLogger.d(
                    TaskLogger.Channel.PLAYER, "player.created",
                    "pipeline=${if (beautyPipelineActive) "on" else "off"} " +
                        "params=${pendingBeautyParams.logText()}"
                )
            }
    }

    /**
     * ★ 第 7 轮：打开 / 切换视频前的"按需启用美颜"。
     *
     * **必须**在 `player?.run { clearMediaItems(); setMediaItems(...); prepare() }` **之前**调用：
     * renderer 只在首次 `onEnabled()`（prepare 之后）决定要不要建 VideoGraph，所以要提前把
     * `pendingBeautyParams` 与 player 都准备好。
     *
     * 只在**跨"启用 / 未启用"边界**时才重建 player；同边界内仅更新参数（零开销）。
     * 本函数是"是否启用美颜"的**唯一权威**（见 [applyBeautyEffects] 的说明）。
     */
    private fun prepareBeautyForPlaylist(target: String?) {
        val incoming = BeautySettingsStore.resolve(this, target)
        pendingBeautyParams = incoming
        val need = !incoming.isDefault
        // ★ 第 8 轮：这是"要不要建特效管线"的决策点，四个值一起记 —— 缺任何一个都无法判定。
        TaskLogger.i(
            TaskLogger.Channel.BEAUTY, "prepareForPlaylist",
            "target=${target ?: "<global>"} need=$need active=$beautyPipelineActive " +
                "masterEnabled=${BeautySettingsStore.isEnabled(this)} params=${incoming.logText()}"
        )
        if (need == beautyPipelineActive) {
            TaskLogger.d(
                TaskLogger.Channel.BEAUTY, "prepareForPlaylist.noRebuild",
                "need==active=$need ⇒ 同边界内不重建，仅参数生效"
            )
            return
        }

        TaskLogger.w(
            TaskLogger.Channel.BEAUTY, "player.rebuild",
            "trigger=prepareBeautyForPlaylist $beautyPipelineActive->$need " +
                "target=${target ?: "<global>"}"
        )
        player?.release()
        player = createConfiguredPlayer()
        // ⚠️ 必须同步换掉 MediaSession 持有的 player，否则已连接的 MediaController 会绑在
        //    已 release 的旧实例上，UI 直接失灵。refreshPlayerWithCurrentState() 里同此处理。
        mediaSession?.player = player!!
        TaskLogger.d(
            TaskLogger.Channel.PLAYER, "session.playerSwapped",
            "owner=prepareBeautyForPlaylist"
        )
    }

    /**
     * 应用美颜参数（第 6 轮功能，**第 7 轮重写**）。
     *
     * 之所以必须在这里做、而不是在界面层：`setVideoEffects` 是 **ExoPlayer 独有的方法**，
     * `MediaController` 上没有；而全项目只有这里持有真正的 ExoPlayer 实例。
     * 好处是主播放器 / 迷你播放器 / 画中画**一次全部生效**，不必逐个改。
     *
     * 三分支（关键：**未启用时绝不调 API** —— 这正是 v1.19 的病根）：
     *  - 两边都"未启用" → 直接 return，**一次 API 都不调**；
     *  - 两边都"已启用" → 动态改数值，播放不中断；
     *  - 跨边界 → 只能重建 player（`hasSetVideoSink` 无法复位）。
     *
     * 失败只记日志、不抛 —— 特效装不上不应该影响正常播放。
     */
    private fun applyBeautyEffects(params: BeautyParams) {
        val p = BeautyParams.sanitize(params)
        val need = !p.isDefault
        TaskLogger.i(
            TaskLogger.Channel.BEAUTY, "apply",
            "need=$need active=$beautyPipelineActive params=${p.logText()}"
        )

        if (need == beautyPipelineActive) {
            if (!beautyPipelineActive) {
                // ★ 未启用 ⇒ 绝不调 setVideoEffects（这正是 v1.19 的病根）。
                //   "什么都没做"也要留证据，否则日志里无法区分"故意没调"与"代码没跑到"。
                TaskLogger.d(
                    TaskLogger.Channel.BEAUTY, "setVideoEffects.skip",
                    "at=apply reason=bothDisabled (★ 一次 API 都不调)"
                )
                return
            }
            val currentPlayer = player
            if (currentPlayer == null) {
                TaskLogger.w(TaskLogger.Channel.BEAUTY, "apply.noPlayer", "player==null，参数已存待下次生效")
                return
            }
            pendingBeautyParams = p

            // ── ★ 第 9 轮（修复 R1-b）：参数与"管线里实际装着的那份"完全一致 ⇒ 一次 API 都不调。
            //   为什么这条能治病：Media3 的 setVideoEffects 内部是发 MSG_SET_VIDEO_EFFECTS，
            //   它会**重建 VideoFrameProcessor**（一整条 OpenGL 后台管线）。而原来的代码在
            //   "刚 updatePlaylist、renderer 还在 prepare"的窗口里把同一份特效又装了一遍 ——
            //   v1.21 真机日志：at=build 39.316 → at=apply-dynamic 39.425，参数完全相同。
            //   那一刻输出面还不有效（onSurfaceSizeChanged 报 -1x-1 / 0x0、onVideoSizeChanged 报 0x0），
            //   新管线接不上画面：要么永远不出首帧（视频 A），要么 44 ms 后 7001（视频 B）。
            if (p == appliedBeautyParams) {
                TaskLogger.i(
                    TaskLogger.Channel.BEAUTY, "setVideoEffects.skip",
                    "at=apply-dynamic reason=paramsUnchanged " +
                        "(★ 管线里已经是这份参数，重下发会重建 VideoFrameProcessor)"
                )
                return
            }

            // ── ★ 第 9 轮（修复 R1-c）：播放器还没 READY（= 正在 prepare）时不下发。
            //   "prepare 进行到一半"是特效管线最脆的窗口：renderer 正要在 onEnabled() 里
            //   初始化 VideoFrameProcessor，输出面尚未绑定。记下来，等 STATE_READY 再补发。
            if (currentPlayer.playbackState != Player.STATE_READY) {
                pendingDynamicApply = p
                TaskLogger.i(
                    TaskLogger.Channel.BEAUTY, "setVideoEffects.deferred",
                    "state=${stateName(currentPlayer.playbackState)} " +
                        "(★ prepare 期间改特效会让 VideoFrameProcessor 重建 ⇒ 推迟到 STATE_READY)"
                )
                return
            }

            val effects = runCatching { BeautyEffects.build(p) }.getOrElse { emptyList() }
            TaskLogger.i(
                TaskLogger.Channel.BEAUTY, "setVideoEffects.start",
                "at=apply-dynamic effects=${effects.size} params=${p.logText()}"
            )
            runCatching {
                currentPlayer.setVideoEffects(effects)
                appliedBeautyParams = p
            }.onFailure {
                TaskLogger.e(TaskLogger.Channel.BEAUTY, "setVideoEffects.failed", it)
            }
            TaskLogger.i(
                TaskLogger.Channel.BEAUTY, "setVideoEffects.done",
                "at=apply-dynamic effects=${effects.size} pipeline=on params=${p.logText()}"
            )
            return
        }

        // 跨边界：只记参数不起作用，必须重建 player 才能让 renderer 重新 onEnabled。
        pendingBeautyParams = p
        if (player == null || mediaSession == null || (player?.mediaItemCount ?: 0) == 0) {
            // 空闲 / 未初始化：不白重建，等下一次 updatePlaylist 带着新参数建。
            TaskLogger.i(
                TaskLogger.Channel.BEAUTY, "apply.deferred",
                "boundary $beautyPipelineActive->$need 但 player 空闲，推迟到下次 updatePlaylist"
            )
            return
        }
        TaskLogger.w(
            TaskLogger.Channel.BEAUTY, "player.rebuild",
            "trigger=applyBeautyEffects(crossing) $beautyPipelineActive->$need"
        )
        refreshPlayerWithCurrentState()
    }


    override fun onCreate() {
        super.onCreate()

        TaskLogger.i(TaskLogger.Channel.PLAYER, "service.onCreate")

        player = createConfiguredPlayer()

        mediaSession = MediaSession.Builder(this, player!!)
            .setSessionActivity(createOpenPlayerPendingIntent())
            .setCallback(mediaSessionCallback)
            .build()
    }

    private fun createOpenPlayerPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER"
            putExtra("OPEN_PLAYER", true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            // ✅ Sempre habilitar next/previous
            val availableCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(COMMAND_APPLY_EXTERNAL_SUBTITLE, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_CLEAR_EXTERNAL_SUBTITLE, Bundle.EMPTY))
                        .add(SessionCommand(COMMAND_SET_BEAUTY, Bundle.EMPTY))
                        .build()
                )
                .setAvailablePlayerCommands(availableCommands)
                .build()
        }

        // ✅ INTERCEPTAR NEXT/PREVIOUS - Bloqueia comando do ExoPlayer e usa PlaylistNavigator
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int
        ): Int {
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT -> {
                    Log.d("MediaPlaybackService", "onPlayerCommandRequest: SEEK_TO_NEXT recebido")
                    // ✅ CENTRALIZADO: Usa PlaylistNavigator (não deixa ExoPlayer navegar)
                    PlaylistNavigator.next(this@MediaPlaybackService)
                    // Retorna RESULT_SUCCESS para "consumir" o comando e evitar que ExoPlayer faça sua própria navegação
                    return SessionResult.RESULT_SUCCESS
                }

                Player.COMMAND_SEEK_TO_PREVIOUS -> {
                    Log.d("MediaPlaybackService", "onPlayerCommandRequest: SEEK_TO_PREVIOUS recebido")
                    // ✅ CENTRALIZADO: Usa PlaylistNavigator (não deixa ExoPlayer navegar)
                    PlaylistNavigator.previous(this@MediaPlaybackService)
                    // Retorna RESULT_SUCCESS para "consumir" o comando e evitar que ExoPlayer faça sua própria navegação
                    return SessionResult.RESULT_SUCCESS
                }
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            try {
                session.setSessionActivity(createOpenPlayerPendingIntent())
            } catch (e: Exception) {
                Log.e("MediaPlaybackService", "Erro ao configurar intent: ${e.message}")
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_APPLY_EXTERNAL_SUBTITLE -> {
                    val subtitleUri = args.getString(EXTRA_SUBTITLE_URI)?.let(Uri::parse)
                    val subtitleName = args.getString(EXTRA_SUBTITLE_NAME)

                    return if (subtitleUri != null && applyExternalSubtitleToCurrentItem(subtitleUri, subtitleName)) {
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                    }
                }

                COMMAND_CLEAR_EXTERNAL_SUBTITLE -> {
                    return if (clearExternalSubtitleFromCurrentItem()) {
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } else {
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
                    }
                }

                // 第 6 轮美颜：界面把 7 个参数打包下发，这里直接重建特效链。
                // 界面侧已保证"松手才提交一次"，所以此处不必再做防抖。
                COMMAND_SET_BEAUTY -> {
                    val incoming = BeautyParams(
                        smooth = args.getFloat(EXTRA_BEAUTY_SMOOTH, 0f),
                        whiten = args.getFloat(EXTRA_BEAUTY_WHITEN, 0f),
                        rosy = args.getFloat(EXTRA_BEAUTY_ROSY, 0f),
                        sharpen = args.getFloat(EXTRA_BEAUTY_SHARPEN, 0f),
                        brightness = args.getFloat(EXTRA_BEAUTY_BRIGHTNESS, 0f),
                        contrast = args.getFloat(EXTRA_BEAUTY_CONTRAST, 0f),
                        saturation = args.getFloat(EXTRA_BEAUTY_SATURATION, 0f)
                    )
                    // ★ 第 8 轮：命令**到达**这件事本身是最重要的证据之一 ——
                    //   日志里没有这一行，就说明问题在界面侧（压根没下发），不必再往渲染层查。
                    TaskLogger.i(
                        TaskLogger.Channel.UI, "command.setBeauty",
                        "params=${incoming.logText()}"
                    )
                    applyBeautyEffects(incoming)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }

            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    // ✅ REMOVIDO: handleNext() e handlePrevious() - agora usa PlaylistNavigator centralizado

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // ★ 第 9 轮改造：以前把 msg + cause 拼在**一行**里，而 msg 本身就有 400 字符左右，
            //   结果最关键的 cause= 总是被挤掉（v1.21 真机日志实测丢了 225 字符）。
            //   现在拆成两层，从**结构上**摆脱"单行长度"这个约束：
            //     ① 短摘要行：只放永远看得见的字段，且把 cause 提到前面
            //     ② 完整因果链 + 堆栈：逐帧独立成行（logStackTrace），多长都不会被截
            val root = error.cause
            TaskLogger.e(
                TaskLogger.Channel.ERROR, "onPlayerError",
                "code=${error.errorCode} name=${error.errorCodeName} " +
                    "cause0=${root?.javaClass?.name ?: "<无>"} causeDepth=${causeChainDepth(error)}"
            )
            TaskLogger.logStackTrace(TaskLogger.Channel.ERROR, "onPlayerError", error)
            Log.e("MediaPlaybackService", "Player error: ${error.message}")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isUpdatingMetadata) {
                return
            }

            // 随机模式（第 6 轮修复）：ExoPlayer 的自动前进永远按**列表顺序**走 —— 那不是我们要的随机。
            // 这里拦下它，改由 PlaylistManager 的洗牌袋抽签 + seek。
            // ⚠️ 必须直接 return：下面那段会把播放器的物理索引无条件写回 PlaylistManager
            //（`confirmCurrentIndex`），那会覆盖掉刚抽好的签 —— 这正是本缺陷的第二层根因。
            if (PlaylistManager.isShuffleEnabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                currentPlaybackProcessingJob?.cancel()

                // 保住元数据追踪，否则下一条的"继续观看"清理与缩略图会错位。
                val autoUri = mediaItem?.localConfiguration?.uri?.toString()
                val autoPreviousUri = trackedMediaItemUri
                if (autoPreviousUri != null && autoPreviousUri != autoUri) {
                    clearSavedProgressForUri(autoPreviousUri)
                }
                trackedMediaItemUri = autoUri

                Log.d("MediaPlaybackService", "onMediaItemTransition(AUTO) em modo aleatório → sorteia")
                PlaylistNavigator.next(this@MediaPlaybackService, force = true)
                return
            }

            currentPlaybackProcessingJob?.cancel()

            val currentUri = mediaItem?.localConfiguration?.uri?.toString()
            val previousUri = trackedMediaItemUri
            if (
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                previousUri != null &&
                previousUri != currentUri
            ) {
                clearSavedProgressForUri(previousUri)
            }
            trackedMediaItemUri = currentUri

            player?.let { currentPlayer ->
                val currentPlaylistIndex = currentPlayer.currentMediaItemIndex
                if (currentPlaylistIndex != PlaylistManager.getCurrentIndex()) {
                    PlaylistManager.confirmCurrentIndex(currentPlaylistIndex)
                }

                if (activeSeekIndex == currentPlaylistIndex) {
                    activeSeekIndex = null
                }

                processPendingSeekIfNeeded(currentPlayer)
            }

            scheduleCurrentPlaybackProcessing()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // ★ 第 8 轮：以前这里只发广播、不进日志 —— 排查时看不到"卡在 BUFFERING"这类关键事实。
            TaskLogger.i(
                TaskLogger.Channel.PLAYER, "playbackState",
                "state=${stateName(playbackState)} index=${player?.currentMediaItemIndex ?: -1} " +
                    "pipeline=${if (beautyPipelineActive) "on" else "off"}"
            )
            val isPlaying = player?.isPlaying ?: false
            val intent = Intent("PLAYBACK_STATE_CHANGED")
            intent.putExtra("IS_PLAYING", isPlaying)
            sendBroadcast(intent)

            if (playbackState == Player.STATE_ENDED) {
                player?.let { currentPlayer ->
                    if (currentPlayer.repeatMode != Player.REPEAT_MODE_ONE) {
                        clearSavedProgressForCurrentItem(currentPlayer)
                    }
                    if (PlaylistManager.isShuffleEnabled) {
                        // 随机模式（第 6 轮修复）：列表末尾播完也必须继续抽签，绝不能停住。
                        // 非末尾的自动前进走 onMediaItemTransition(AUTO)，这里是**最后一条**的出口。
                        Log.d("MediaPlaybackService", "STATE_ENDED em modo aleatório → sorteia")
                        PlaylistNavigator.next(this@MediaPlaybackService, force = true)
                    } else {
                        handleEndOfPlaylistIfNeeded(currentPlayer)
                    }
                }
            }

            if (playbackState == Player.STATE_READY) {
                scheduleCurrentPlaybackProcessing()
                // ★ 第 9 轮（修复 R1-d）：把 prepare 期间被推迟的特效更新补上。
                //   先取出并清空，再调用 —— 避免 applyBeautyEffects 内部再入时重复应用。
                pendingDynamicApply?.let { queued ->
                    pendingDynamicApply = null
                    TaskLogger.i(
                        TaskLogger.Channel.BEAUTY, "setVideoEffects.resume",
                        "state=READY ⇒ 补上 prepare 期间被推迟的特效更新"
                    )
                    applyBeautyEffects(queued)
                }
            } else {
                currentPlaybackProcessingJob?.cancel()
            }

            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_IDLE) {
                player?.let(::processPendingSeekIfNeeded)
            }

            // ✅ REMOVIDO: handleNext() não é mais necessário aqui
            // O ExoPlayer já navega automaticamente dentro da window
            // e o onMediaItemTransition sincroniza o PlaylistManager
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            TaskLogger.d(TaskLogger.Channel.PLAYER, "isPlayingChanged", "isPlaying=$isPlaying")
            if (isPlaying) {
                scheduleProgressPersistence()
            } else {
                persistContinueWatchingState()
                cancelProgressPersistence()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            applyPendingContinueWatchingRestore(tracks)
        }

        // ─────────────────────────────────────────────────────────────────
        // ★ 第 8 轮新增：这三个回调是「黑屏 / 转圈」的**唯一直接判据**。
        //   以前整个项目一个都没注册 —— 所以"到底有没有画面"根本无从判断，
        //   这正是"开启美颜后无法播放"排查中最大的观测盲区。
        // ─────────────────────────────────────────────────────────────────

        /** ★ 首帧到达。**日志里没有这一行 = 就是黑屏/转圈。** */
        override fun onRenderedFirstFrame() {
            TaskLogger.i(TaskLogger.Channel.RENDER, "onRenderedFirstFrame", "★ 首帧已到达")
        }

        /** 视频自身尺寸。VideoGraph 接不上时，这个事件往往也不出现。 */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            TaskLogger.i(
                TaskLogger.Channel.RENDER, "onVideoSizeChanged",
                "w=${videoSize.width} h=${videoSize.height} " +
                    "par=${videoSize.pixelWidthHeightRatio} " +
                    "rotation=${videoSize.unappliedRotationDegrees}"
            )
        }

        /** 输出 surface 尺寸。一直是 0x0 / 一直不出现 ⇒ 解码结果没有可写的目标面。 */
        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            TaskLogger.i(TaskLogger.Channel.RENDER, "onSurfaceSizeChanged", "w=$width h=$height")
        }
    }

    /**
     * ★ 第 8 轮新增：渲染侧观测（`AnalyticsListener`）。
     *
     * `Player.Listener` 只能告诉你"首帧到没到"，但**不告诉你解码器是谁、输入格式是什么、丢没丢帧**。
     * 排查黑屏/转圈时，这三样决定了问题出在**解码器**还是**特效链**：
     * 解码器正常起来、格式也识别了，却始终没有首帧 ⇒ 嫌疑就集中到 VideoGraph / 输出 surface 那一段。
     *
     * ⚠️ `AnalyticsListener` 是 `@UnstableApi`；本类已带 `@OptIn(UnstableApi::class)`，无需额外注解。
     */
    private val analyticsListener = object : AnalyticsListener {

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            TaskLogger.i(
                TaskLogger.Channel.RENDER, "decoderInitialized",
                "name=$decoderName initMs=$initializationDurationMs"
            )
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            TaskLogger.i(
                TaskLogger.Channel.RENDER, "videoInputFormat",
                "mime=${format.sampleMimeType} ${format.width}x${format.height} " +
                    "colorTransfer=${format.colorInfo?.colorTransfer} " +
                    "colorSpace=${format.colorInfo?.colorSpace} " +
                    "hdrStaticInfo=${if (format.colorInfo?.hdrStaticInfo != null) "yes" else "no"}"
            )
        }

        override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            TaskLogger.i(TaskLogger.Channel.RENDER, "decoderReleased", "name=$decoderName")
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long
        ) {
            TaskLogger.d(
                TaskLogger.Channel.RENDER, "droppedFrames",
                "dropped=$droppedFrames elapsedMs=$elapsedMs"
            )
        }
    }

    // MediaPlaybackService.kt
    private var lastMediaItemIndex = 0 // ✅ ADICIONAR esta variável

    private fun updateNotificationIntent() {
        try {
            mediaSession?.setSessionActivity(createOpenPlayerPendingIntent())
        } catch (e: Exception) {
            Log.e("MediaPlaybackService", "Erro ao atualizar intent: ${e.message}")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * Check if any URI in a playlist uses the locked:// scheme
     */
    private fun hasLockedUris(playlist: List<String>): Boolean {
        return playlist.any { it.startsWith("locked://") }
    }

    // Keep playlist creation cheap for large queues. Artwork is refreshed only
    // for the current item after thumbnail generation completes.
    private fun createMediaItemWithMetadata(uri: String): MediaItem {
        val isLocked = uri.startsWith("locked://")
        val filePath = if (isLocked) uri.removePrefix("locked://") else uri.removePrefix("file://")
        val file = File(filePath)
        // For locked files, try to get original name from session
        val title = if (isLocked) {
            LockedPlaybackSession.getOriginalName(file.name)?.substringBeforeLast(".") ?: file.nameWithoutExtension
        } else {
            file.nameWithoutExtension
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist("MistVD")

        // Keep locked:// URI so HybridDataSource can detect and handle it
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun subtitleMimeType(uri: Uri, displayName: String?): String? {
        val source = (displayName ?: uri.lastPathSegment).orEmpty().lowercase()
        return when {
            source.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            source.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            source.endsWith(".ssa") || source.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> null
        }
    }

    private fun applyExternalSubtitleToCurrentItem(subtitleUri: Uri, displayName: String?): Boolean {
        val currentPlayer = player ?: return false
        val currentIndex = currentPlayer.currentMediaItemIndex
        val currentItem = currentPlayer.currentMediaItem ?: return false
        if (currentIndex == -1) return false

        val mimeType = subtitleMimeType(subtitleUri, displayName)
        if (mimeType == null) {
            return false
        }

        val currentPosition = currentPlayer.currentPosition
        val playWhenReady = currentPlayer.playWhenReady
        val updatedItem = currentItem.buildUpon()
            .setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                        .setMimeType(mimeType)
                        .setLanguage("und")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
                        .setLabel(displayName ?: "External Subtitle")
                        .build()
                )
            )
            .build()

        val updatedItems = List(currentPlayer.mediaItemCount) { index ->
            if (index == currentIndex) updatedItem else currentPlayer.getMediaItemAt(index)
        }

        currentPlayer.setMediaItems(updatedItems, currentIndex, currentPosition)
        currentPlayer.prepare()
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        if (playWhenReady) currentPlayer.play()
        return true
    }

    private fun clearExternalSubtitleFromCurrentItem(): Boolean {
        val currentPlayer = player ?: return false
        val currentIndex = currentPlayer.currentMediaItemIndex
        val currentItem = currentPlayer.currentMediaItem ?: return false
        if (currentIndex == -1) return false

        val currentPosition = currentPlayer.currentPosition
        val playWhenReady = currentPlayer.playWhenReady
        val updatedItem = currentItem.buildUpon()
            .setSubtitleConfigurations(emptyList())
            .build()

        val updatedItems = List(currentPlayer.mediaItemCount) { index ->
            if (index == currentIndex) updatedItem else currentPlayer.getMediaItemAt(index)
        }

        currentPlayer.setMediaItems(updatedItems, currentIndex, currentPosition)
        currentPlayer.prepare()
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        if (playWhenReady) currentPlayer.play()
        return true
    }

    // Busca somente thumbnails já existentes em RAM/disco.
    private fun loadThumbnailWithContext(videoPath: String): Bitmap? {
        val key = videoPath.hashCode().toString()

        // 1. Tenta RAM primeiro
        val ramBitmap = OptimizedThumbnailManager.thumbnailCache.get(key)
        if (ramBitmap != null && !ramBitmap.isRecycled) {
            return ramBitmap
        }

        // 2. Busca do disco
        val diskBitmap = OptimizedThumbnailManager.loadThumbnailFromDiskSync(this, videoPath)
        if (diskBitmap != null) {
            OptimizedThumbnailManager.thumbnailCache.put(key, diskBitmap)
            return diskBitmap
        }

        // 3. Não existe - retorna null (será gerada em background)
        // Não geramos aqui para não bloquear a thread principal
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "UPDATE_PLAYLIST" -> {
                val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
                val initialIndex = intent.getIntExtra("INITIAL_INDEX", 0)
                val initialPositionMs = intent.getLongExtra("INITIAL_POSITION_MS", 0L)
                updatePlaylist(playlist, initialIndex, initialPositionMs)
            }
            "UPDATE_WINDOW" -> {
                val window = intent.getStringArrayListExtra("WINDOW") ?: emptyList()
                val currentIndexInWindow = intent.getIntExtra("CURRENT_INDEX_IN_WINDOW", 0)
                updateWindow(window, currentIndexInWindow)
            }
            "SEEK_TO_PLAYLIST_INDEX" -> {
                val playlistIndex = intent.getIntExtra("PLAYLIST_INDEX", 0)
                val autoPlay = intent.getBooleanExtra("AUTO_PLAY", false)
                seekToPlaylistIndex(playlistIndex, autoPlay)
            }
            "REMOVE_PLAYLIST_ITEM" -> {
                val removeIndex = intent.getIntExtra("REMOVE_INDEX", -1)
                val nextIndex = intent.getIntExtra("NEXT_INDEX", 0)
                removePlaylistItem(removeIndex, nextIndex)
            }
            "REFRESH_PLAYER" -> {
                refreshPlayerWithCurrentState()
            }
            "UPDATE_PLAYLIST_AFTER_DELETION" -> {
                val playlist = intent.getStringArrayListExtra("PLAYLIST") ?: emptyList()
                val nextIndex = intent.getIntExtra("NEXT_INDEX", 0)
                updatePlaylistAfterDeletion(playlist, nextIndex)
            }
            "RESUME_LOCAL_PLAYBACK" -> {
                resumeLocalPlayback()
            }
            "STOP_SERVICE" -> {
                cancelSleepTimer()
                pendingSeekIndex = null
                activeSeekIndex = null
                persistContinueWatchingState()
                ContinueWatchingStore.setPlaybackActive(false)
                PlaylistManager.clear()
                player?.run {
                    pause()
                    clearMediaItems()
                    trackedMediaItemUri = null
                    stop()
                }

                val intent = Intent("PLAYER_CLOSED")
                sendBroadcast(intent)

                stopSelf()
            }
            ACTION_START_SLEEP_TIMER -> {
                val durationMs = intent.getLongExtra(EXTRA_SLEEP_TIMER_DURATION_MS, 0L)
                startSleepTimer(durationMs)
            }
            ACTION_CLEAR_SLEEP_TIMER -> {
                cancelSleepTimer()
            }
            ACTION_REQUEST_SLEEP_TIMER_STATE -> {
                broadcastSleepTimerState(isActive = isSleepTimerActive)
            }
            ACTION_REFRESH_CURRENT_ARTWORK -> {
                scheduleCurrentPlaybackProcessing()
            }
            ACTION_REFRESH_BEAUTY -> {
                // ★ 第 7 轮：设置页改了总开关 / 全局参数后，让"当前正在播放的视频"立刻跟上。
                // 路径取自 trackedMediaItemUri（updatePlaylist 里记录），拿不到再读当前媒体项。
                // 若此刻没有在播的视频 ⇒ videoPath = null ⇒ resolve 回落全局参数，同样正确。
                val uriStr = trackedMediaItemUri
                    ?: player?.currentMediaItem?.localConfiguration?.uri?.toString()
                val videoPath = uriStr
                    ?.removePrefix("locked://")
                    ?.removePrefix("file://")
                applyBeautyEffects(BeautySettingsStore.resolve(this, videoPath))
            }
            ACTION_PERSIST_CONTINUE_WATCHING -> {
                persistContinueWatchingState()
            }
            ACTION_PAUSE_FOR_BACKGROUND -> {
                persistContinueWatchingState()
                ContinueWatchingStore.setPlaybackActive(false)
                player?.run {
                    playWhenReady = false
                    pause()
                }
                abandonAudioFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startSleepTimer(durationMs: Long) {
        if (durationMs <= 0L) {
            cancelSleepTimer()
            return
        }

        sleepTimerJob?.cancel()
        isSleepTimerActive = true
        sleepTimerEndAtMs = System.currentTimeMillis() + durationMs
        broadcastSleepTimerState(isActive = true)

        sleepTimerJob = preloadScope.launch {
            delay(durationMs)
            withContext(Dispatchers.Main) {
                player?.let { currentPlayer ->
                    currentPlayer.playWhenReady = false
                    currentPlayer.pause()
                }
                sleepTimerJob = null
                isSleepTimerActive = false
                sleepTimerEndAtMs = 0L
                broadcastSleepTimerState(isActive = false)
            }
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null

        if (!isSleepTimerActive) {
            return
        }

        isSleepTimerActive = false
        sleepTimerEndAtMs = 0L
        broadcastSleepTimerState(isActive = false)
    }

    private fun broadcastSleepTimerState(isActive: Boolean) {
        sendBroadcast(
            Intent(BROADCAST_SLEEP_TIMER_STATE_CHANGED).apply {
                putExtra(EXTRA_SLEEP_TIMER_ACTIVE, isActive)
                putExtra(EXTRA_SLEEP_TIMER_END_AT_MS, sleepTimerEndAtMs)
            }
        )
    }

    private fun updatePlaylist(playlist: List<String>, initialIndex: Int, initialPositionMs: Long = 0L) {
        // ★ 第 8 轮：这才是"打开视频"的**真正入口**（不是 createConfiguredPlayer）。
        //   记下来才能判断"美颜管线到底在哪一步被决定、是不是每次开视频都重建"。
        TaskLogger.i(
            TaskLogger.Channel.PLAYER, "updatePlaylist",
            "size=${playlist.size} index=$initialIndex posMs=$initialPositionMs " +
                "target=${playlist.getOrNull(initialIndex)}"
        )
        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition
        pendingSeekIndex = null
        activeSeekIndex = null
        ContinueWatchingStore.setPlaybackActive(playlist.isNotEmpty())
        pendingContinueWatchingRestore = buildPendingContinueWatchingRestore(
            playlist = playlist,
            initialIndex = initialIndex,
            initialPositionMs = initialPositionMs
        )

        // ★ 第 7 轮：按需启用美颜 —— 必须早于 setMediaItems/prepare（见 prepareBeautyForPlaylist 注释）。
        // 放在 isUpdatingMetadata = true 的区间内：重建 player 会触发 onMediaItemTransition，
        // 跑到区间外会引出意外的元数据流程（会动到"继续观看"）。
        if (playlist.isNotEmpty()) {
            prepareBeautyForPlaylist(playlist.getOrNull(initialIndex))
        }

        player?.run {
            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) },
                initialIndex,
                initialPositionMs.coerceAtLeast(0L)
            )
            trackedMediaItemUri = currentMediaItem?.localConfiguration?.uri?.toString()
            prepare()
            playWhenReady = true
        }

        PlaylistManager.syncLoadedWindow(initialIndex)

        isUpdatingMetadata = false

        updateNotificationIntent()

    }

    private fun updatePlaylistAfterDeletion(playlist: List<String>, nextIndex: Int) {
        TaskLogger.i(
            TaskLogger.Channel.PLAYER, "updatePlaylistAfterDeletion",
            "size=${playlist.size} nextIndex=$nextIndex target=${playlist.getOrNull(nextIndex)}"
        )
        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition
        pendingSeekIndex = null
        activeSeekIndex = null
        ContinueWatchingStore.setPlaybackActive(playlist.isNotEmpty())

        // ★ 第 7 轮：删除后重排也会换"当前视频"，同样按需启用美颜
        // （playlist 为空时下面 player?.run{} 内部会早退，这里就不折腾了）。
        if (playlist.isNotEmpty()) {
            prepareBeautyForPlaylist(playlist.getOrNull(nextIndex))
        }

        player?.run {
            if (playlist.isEmpty()) {
                pause()
                clearMediaItems()
                abandonAudioFocus()
                ContinueWatchingStore.setPlaybackActive(false)
                isUpdatingMetadata = false
                return
            }

            clearMediaItems()
            setMediaItems(
                playlist.map { createMediaItemWithMetadata(it) },
                nextIndex,
                0L
            )
            prepare()
            playWhenReady = true
        }

        PlaylistManager.syncLoadedWindow(nextIndex)

        isUpdatingMetadata = false

        updateNotificationIntent()

    }

    private fun scheduleCurrentPlaybackProcessing() {
        currentPlaybackProcessingJob?.cancel()

        if (DISABLE_PLAYBACK_ARTWORK_REFRESH_FOR_DEBUG) {
            return
        }

        currentPlaybackProcessingJob = preloadScope.launch {
            delay(PLAYBACK_ARTWORK_REFRESH_DELAY_MS)

            if (activeSeekIndex != null || pendingSeekIndex != null) {
                return@launch
            }

            val artworkTarget = withContext(Dispatchers.Main) { getCurrentArtworkTarget() } ?: return@launch
            ensureThumbnailAvailable(artworkTarget)
            refreshCurrentMediaItemMetadata()
        }
    }

    private data class ArtworkUpdate(
        val mediaUri: String,
        val title: String,
        val artworkData: ByteArray
    )

    private data class CurrentArtworkTarget(
        val mediaUri: String,
        val videoPath: String,
        val title: String,
        val isLocked: Boolean
    )

    private suspend fun ensureThumbnailAvailable(target: CurrentArtworkTarget): Boolean = withContext(Dispatchers.IO) {
        try {
            OptimizedThumbnailManager.getCachedThumbnail(target.videoPath)
                ?: OptimizedThumbnailManager.loadThumbnailFromDiskSync(this@MediaPlaybackService, target.videoPath)
                ?: OptimizedThumbnailManager.generateThumbnailSync(this@MediaPlaybackService, target.videoPath)
        } catch (e: Exception) {
            Log.w("MediaPlaybackService", "Falha ao garantir thumbnail: ${e.message}")
            null
        } != null
    }

    /**
     * Atualiza os metadados do MediaItem atual para refletir thumbnails recém-geradas
     */
    private suspend fun refreshCurrentMediaItemMetadata() {
        if (isUpdatingMetadata) return // ✅ Evita recursão

        val artworkTarget = withContext(Dispatchers.Main) { getCurrentArtworkTarget() } ?: return
        val artworkUpdate = buildCurrentArtworkUpdate(artworkTarget) ?: return
        val artworkKey = "${artworkUpdate.mediaUri}:${artworkUpdate.artworkData.size}:${artworkUpdate.artworkData.contentHashCode()}"

        if (lastAppliedArtworkKey == artworkKey) {
            return
        }

        withContext(Dispatchers.Main) {
            player?.let { currentPlayer ->
                val currentIndex = currentPlayer.currentMediaItemIndex
                val currentItem = currentPlayer.currentMediaItem ?: return@let
                val currentUri = currentItem.localConfiguration?.uri?.toString() ?: return@let

                if (currentUri != artworkUpdate.mediaUri) {
                    return@let
                }

                isUpdatingMetadata = true

                try {
                    val newMetadata = MediaMetadata.Builder()
                        .setTitle(artworkUpdate.title)
                        .setDisplayTitle(artworkUpdate.title)
                        .setArtist("MistVD")
                        .setArtworkData(artworkUpdate.artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()

                    val newMediaItem = currentItem.buildUpon()
                        .setMediaMetadata(newMetadata)
                        .build()

                    currentPlayer.replaceMediaItem(currentIndex, newMediaItem)
                    lastAppliedArtworkKey = artworkKey
                    Log.d("MediaPlaybackService", "Notificação atualizada com thumbnail")
                } finally {
                    isUpdatingMetadata = false
                }
            }
        }
    }

    private fun getCurrentArtworkTarget(): CurrentArtworkTarget? {
        val currentItem = player?.currentMediaItem ?: return null
        val uri = currentItem.localConfiguration?.uri?.toString() ?: return null
        val isLocked = uri.startsWith("locked://")
        val videoPath = if (isLocked) uri.removePrefix("locked://") else uri.removePrefix("file://")
        val file = File(videoPath)
        val title = if (isLocked) {
            LockedPlaybackSession.getOriginalName(file.name)?.substringBeforeLast(".") ?: file.nameWithoutExtension
        } else {
            file.nameWithoutExtension
        }

        return CurrentArtworkTarget(
            mediaUri = uri,
            videoPath = videoPath,
            title = title,
            isLocked = isLocked
        )
    }

    private suspend fun buildCurrentArtworkUpdate(target: CurrentArtworkTarget): ArtworkUpdate? = withContext(Dispatchers.IO) {
        val thumbnail = loadThumbnailWithContext(target.videoPath) ?: return@withContext null

        ArtworkUpdate(
            mediaUri = target.mediaUri,
            title = target.title,
            artworkData = bitmapToByteArray(thumbnail)
        )
    }

    private fun updateWindow(window: List<String>, currentIndexInWindow: Int) {
        updatePlaylist(window, currentIndexInWindow)
    }

    private fun seekToPlaylistIndex(playlistIndex: Int, autoPlay: Boolean) {
        player?.let { currentPlayer ->
            if (playlistIndex !in 0 until currentPlayer.mediaItemCount) {
                Log.w("MediaPlaybackService", "seekToPlaylistIndex ignorado: índice inválido $playlistIndex/${currentPlayer.mediaItemCount}")
                return
            }

            if (currentPlayer.currentMediaItemIndex != playlistIndex) {
                clearSavedProgressForCurrentItem(currentPlayer)
            }

            pendingSeekIndex = playlistIndex
            if (autoPlay) {
                currentPlayer.playWhenReady = true
            }
            processPendingSeekIfNeeded(currentPlayer)
        }
    }

    private fun processPendingSeekIfNeeded(currentPlayer: ExoPlayer) {
        val targetIndex = pendingSeekIndex ?: return

        if (activeSeekIndex != null) {
            return
        }

        if (targetIndex !in 0 until currentPlayer.mediaItemCount) {
            pendingSeekIndex = null
            Log.w("MediaPlaybackService", "processPendingSeekIfNeeded ignorado: índice inválido $targetIndex/${currentPlayer.mediaItemCount}")
            return
        }

        if (currentPlayer.currentMediaItemIndex == targetIndex) {
            pendingSeekIndex = null
            PlaylistManager.confirmCurrentIndex(targetIndex)
            scheduleCurrentPlaybackProcessing()
            return
        }

        pendingSeekIndex = null
        activeSeekIndex = targetIndex
        currentPlaybackProcessingJob?.cancel()
        currentPlayer.seekToDefaultPosition(targetIndex)
    }

    private fun handleEndOfPlaylistIfNeeded(currentPlayer: ExoPlayer) {
        if (currentPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
            return
        }

        // 随机模式永远有下一条（见 PlaylistManager.next 的随机分支），不允许在这里把播放停住。
        // 这里只是兜底 —— 调用点 onPlaybackStateChanged 已经先分流给抽签了。
        if (PlaylistManager.isShuffleEnabled) {
            return
        }

        val currentIndex = currentPlayer.currentMediaItemIndex
        val isLastItem = currentIndex >= currentPlayer.mediaItemCount - 1

        if (!isLastItem || PlaylistManager.hasNext()) {
            return
        }

        pendingSeekIndex = null
        activeSeekIndex = null
        cancelSleepTimer()
        currentPlayer.playWhenReady = false
        currentPlayer.pause()
        PlaylistManager.confirmCurrentIndex(currentIndex)
    }

    private fun removePlaylistItem(removeIndex: Int, nextIndex: Int) {
        player?.let { currentPlayer ->
            if (removeIndex !in 0 until currentPlayer.mediaItemCount) {
                Log.w("MediaPlaybackService", "removePlaylistItem ignorado: índice inválido $removeIndex/${currentPlayer.mediaItemCount}")
                return
            }

            currentPlayer.removeMediaItem(removeIndex)

            if (currentPlayer.mediaItemCount == 0) {
                pendingSeekIndex = null
                activeSeekIndex = null
                persistContinueWatchingState()
                ContinueWatchingStore.setPlaybackActive(false)
                stopSelf()
                return
            }

            val targetIndex = nextIndex.coerceIn(0, currentPlayer.mediaItemCount - 1)
            PlaylistManager.syncLoadedWindow(targetIndex)
        }
    }

    private fun refreshPlayerWithCurrentState() {
        val currentPlayer = player ?: return
        val currentSession = mediaSession ?: return

        TaskLogger.w(
            TaskLogger.Channel.PLAYER, "player.rebuild",
            "trigger=refreshPlayerWithCurrentState index=${currentPlayer.currentMediaItemIndex} " +
                "posMs=${currentPlayer.currentPosition} count=${currentPlayer.mediaItemCount}"
        )

        isUpdatingMetadata = true // ✅ Evita processamento de onMediaItemTransition
        pendingSeekIndex = null
        activeSeekIndex = null

        val currentPosition = currentPlayer.currentPosition
        val currentMediaIndex = currentPlayer.currentMediaItemIndex
        val currentRepeatMode = currentPlayer.repeatMode
        val currentPlaybackSpeed = currentPlayer.playbackParameters.speed
        val shouldPlayWhenReady = currentPlayer.playWhenReady
        val currentPlaylist = (0 until currentPlayer.mediaItemCount).map { index ->
            currentPlayer.getMediaItemAt(index).localConfiguration?.uri.toString()
        }

        currentPlayer.release()

        player = createConfiguredPlayer().apply {
            repeatMode = currentRepeatMode
            // shuffleModeEnabled 刻意**不**恢复：随机只留 PlaylistManager 一套引擎，
            // ExoPlayer 自带的 shuffle 会洗出第二套互不相干的顺序（见 PlaylistManager 的 KDoc）。
            // ExoPlayer.Builder 默认即 false，所以这里不需要赋值 —— 加注释是为了防止
            // 以后有人"顺手"把它补回来。
        }

        currentSession.player = player!!

        TaskLogger.d(
            TaskLogger.Channel.PLAYER, "session.playerSwapped",
            "owner=refreshPlayerWithCurrentState"
        )

        player?.run {
            if (currentPlaylist.isNotEmpty()) {
                setMediaItems(
                    currentPlaylist.map { createMediaItemWithMetadata(it!!) },
                    currentMediaIndex,
                    currentPosition
                )
                setPlaybackSpeed(currentPlaybackSpeed)
                prepare()
                playWhenReady = shouldPlayWhenReady
            }
        }

        isUpdatingMetadata = false

        updateNotificationIntent()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistContinueWatchingState()
        ContinueWatchingStore.setPlaybackActive(false)
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.isPlaying) {
            // App removido dos recentes com vídeo pausado → limpa playlist e para o serviço
            PlaylistManager.clear()
            currentPlayer?.run {
                pause()
                clearMediaItems()
                trackedMediaItemUri = null
                stop()
            }
            stopSelf()
            return
        }
        // Se estiver tocando, o serviço continua em background
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistContinueWatchingState()
        ContinueWatchingStore.setPlaybackActive(false)
        cancelProgressPersistence()
        cancelSleepTimer()
        pendingSeekIndex = null
        activeSeekIndex = null
        // Serviço destruído com vídeo pausado (ex: notificação arrastada enquanto pausado)
        if (player?.isPlaying == false) {
            PlaylistManager.clear()
        }
        trackedMediaItemUri = null
        currentPlaybackProcessingJob?.cancel()
        preloadScope.cancel() // Cancela thumbnails em progresso
        abandonAudioFocus()
        mediaSession?.run {
            player?.release()
            release()
        }
        player = null
        mediaSession = null
        super.onDestroy()
    }

    private fun resumeLocalPlayback() {
        player?.let {
            if (!it.isPlaying) {
                it.play()
            }
        }
    }

    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                setOnAudioFocusChangeListener(audioFocusChangeListener)
                setAcceptsDelayedFocusGain(true)
            }.build()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            hasAudioFocus = false
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                player?.let {
                    it.volume = 1.0f
                    if (!it.isPlaying) {
                        it.play()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.volume = 0.2f
            }
        }
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return stream.toByteArray()
    }

    private fun scheduleProgressPersistence() {
        if (progressPersistenceJob?.isActive == true) return

        progressPersistenceJob = preloadScope.launch {
            while (isActive) {
                delay(PROGRESS_PERSIST_INTERVAL_MS)
                val shouldContinue = withContext(Dispatchers.Main) {
                    val currentPlayer = player
                    if (currentPlayer == null || !currentPlayer.isPlaying) {
                        false
                    } else {
                        persistContinueWatchingState()
                        true
                    }
                }
                if (!shouldContinue) break
            }
        }
    }

    private fun cancelProgressPersistence() {
        progressPersistenceJob?.cancel()
        progressPersistenceJob = null
    }

    private fun persistContinueWatchingState() {
        val currentPlayer = player ?: return
        val currentItem = currentPlayer.currentMediaItem ?: return
        val currentUri = currentItem.localConfiguration?.uri?.toString() ?: return
        val currentPosition = currentPlayer.currentPosition
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: 0L
        val title = currentItem.mediaMetadata.title?.toString()
            ?: File(
                if (currentUri.startsWith("locked://")) currentUri.removePrefix("locked://")
                else currentUri.removePrefix("file://")
            ).nameWithoutExtension
        val audioTrack = extractSelectedTrackPreference(currentPlayer.currentTracks, C.TRACK_TYPE_AUDIO)
        val subtitleTrack = extractSelectedTrackPreference(currentPlayer.currentTracks, C.TRACK_TYPE_TEXT)
        val externalSubtitleConfiguration = currentItem.localConfiguration
            ?.subtitleConfigurations
            ?.firstOrNull()
        val subtitlesDisabled = currentPlayer.trackSelectionParameters
            .disabledTrackTypes
            .contains(C.TRACK_TYPE_TEXT)

        ContinueWatchingStore.save(
            context = this,
            videoPath = currentUri,
            title = title,
            positionMs = currentPosition,
            durationMs = duration,
            audioTrack = audioTrack,
            subtitleTrack = subtitleTrack,
            externalSubtitleUri = externalSubtitleConfiguration?.uri?.toString(),
            externalSubtitleName = externalSubtitleConfiguration?.label,
            subtitlesDisabled = subtitlesDisabled
        )

        VideoProgressStore.save(
            context = this,
            videoPath = currentUri,
            title = title,
            positionMs = currentPosition,
            durationMs = duration
        )
    }

    private fun clearSavedProgressForCurrentItem(currentPlayer: ExoPlayer) {
        val currentUri = currentPlayer.currentMediaItem
            ?.localConfiguration
            ?.uri
            ?.toString()
            ?: return

        clearSavedProgressForUri(currentUri)
    }

    private fun clearSavedProgressForUri(uri: String) {
        val normalizedPath = normalizeUriPath(uri)
        if (normalizedPath.isBlank()) return

        VideoProgressStore.clear(this, normalizedPath)

        val continueWatchingEntry = ContinueWatchingStore.get(this)
        if (continueWatchingEntry?.videoPath == normalizedPath) {
            ContinueWatchingStore.clear(this)
        }
    }

    private fun buildPendingContinueWatchingRestore(
        playlist: List<String>,
        initialIndex: Int,
        initialPositionMs: Long
    ): ContinueWatchingEntry? {
        if (initialPositionMs <= 0L) return null

        val targetPath = playlist.getOrNull(initialIndex)?.let(::normalizeUriPath) ?: return null
        val entry = ContinueWatchingStore.get(this) ?: return null

        return entry.takeIf { it.videoPath == targetPath }
    }

    private fun applyPendingContinueWatchingRestore(tracks: Tracks) {
        val restore = pendingContinueWatchingRestore ?: return
        val currentPath = player?.currentMediaItem
            ?.localConfiguration
            ?.uri
            ?.toString()
            ?.let(::normalizeUriPath)
            ?: return

        if (currentPath != restore.videoPath) return

        val currentPlayer = player ?: return
        var parameters = currentPlayer.trackSelectionParameters
            .buildUpon()

        if (restore.subtitlesDisabled) {
            parameters = parameters
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else if (restore.subtitleTrack != null) {
            findMatchingTrack(tracks, C.TRACK_TYPE_TEXT, restore.subtitleTrack)?.let { (group, trackIndex) ->
                parameters = parameters
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
        }

        if (restore.audioTrack != null) {
            findMatchingTrack(tracks, C.TRACK_TYPE_AUDIO, restore.audioTrack)?.let { (group, trackIndex) ->
                parameters = parameters
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            }
        }

        currentPlayer.trackSelectionParameters = parameters.build()
        pendingContinueWatchingRestore = null
    }

    private fun findMatchingTrack(
        tracks: Tracks,
        trackType: Int,
        preferredTrack: ContinueWatchingTrackPreference
    ): Pair<Tracks.Group, Int>? {
        tracks.groups.forEach { group ->
            if (group.type != trackType) return@forEach

            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                if (preferredTrack.matches(format)) {
                    return group to trackIndex
                }
            }
        }

        return null
    }

    private fun extractSelectedTrackPreference(
        tracks: Tracks,
        trackType: Int
    ): ContinueWatchingTrackPreference? {
        tracks.groups.forEach { group ->
            if (group.type != trackType) return@forEach

            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) {
                    return group.getTrackFormat(trackIndex).toContinueWatchingTrackPreference()
                }
            }
        }

        return null
    }

    private fun ContinueWatchingTrackPreference.matches(format: Format): Boolean {
        val normalizedLabel = label?.trim()?.lowercase()
        val normalizedLanguage = language?.trim()?.lowercase()
        val normalizedMimeType = mimeType?.trim()?.lowercase()
        val formatLabel = format.label?.trim()?.lowercase()
        val formatLanguage = format.language?.trim()?.lowercase()
        val formatMimeType = format.sampleMimeType?.trim()?.lowercase()
        val flagsMatch = selectionFlags == null || selectionFlags == format.selectionFlags
        val rolesMatch = roleFlags == null || roleFlags == format.roleFlags

        val baseMatch = when {
            normalizedLabel != null && normalizedLanguage != null -> {
                formatLabel == normalizedLabel && formatLanguage == normalizedLanguage
            }
            normalizedLanguage != null && normalizedMimeType != null -> {
                formatLanguage == normalizedLanguage && formatMimeType == normalizedMimeType
            }
            normalizedLanguage != null -> formatLanguage == normalizedLanguage
            normalizedLabel != null -> formatLabel == normalizedLabel
            normalizedMimeType != null -> formatMimeType == normalizedMimeType
            else -> false
        }

        return baseMatch && flagsMatch && rolesMatch
    }

    private fun Format.toContinueWatchingTrackPreference(): ContinueWatchingTrackPreference {
        return ContinueWatchingTrackPreference(
            label = label?.takeIf { it.isNotBlank() },
            language = language?.takeIf { it.isNotBlank() },
            mimeType = sampleMimeType?.takeIf { it.isNotBlank() },
            selectionFlags = selectionFlags,
            roleFlags = roleFlags
        )
    }

    private fun normalizeUriPath(uri: String): String {
        return when {
            uri.startsWith("locked://") -> uri.removePrefix("locked://")
            uri.startsWith("file://") -> uri.removePrefix("file://")
            else -> uri
        }
    }

}
