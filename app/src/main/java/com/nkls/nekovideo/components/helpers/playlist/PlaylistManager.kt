package com.nkls.nekovideo.components.helpers

import android.util.Log

/**
 * 播放列表管理器。
 *
 * 这里同时存在**两套引擎**，由 [isShuffleEnabled] 决定走哪套：
 *
 * - **顺序引擎**（`isShuffleEnabled == false`）—— 与原实现完全一致：游标沿列表线性前进 / 后退，
 *   到两端返回 [NavigationResult.EndOfPlaylist] / [StartOfPlaylist]。
 * - **随机引擎**（`isShuffleEnabled == true`）—— 「**洗牌袋 + 历史栈**」：
 *   - **洗牌袋**：每抽一条就从袋里拿走，**同一轮内绝不重复**；袋空了才重新装牌，
 *     并且自动避开"刚播完的那条"，不会出现"上一条刚播完立刻又播它"。
 *   - **历史栈**：记录实际播放顺序。「上一个」= 沿历史回退，回到你**真正看过**的那条；
 *     **回退之后再抽签时，原来那条"未来"路径会被整个丢弃、重新抽** —— 所以回退后再前进
 *     每一次都是新随机的。
 *   - **永不结束**：[next] 在随机模式下**绝不**返回 [NavigationResult.EndOfPlaylist]；
 *     [previous] 退到历史最开头也会回绕（业主定的：两个方向都循环）。
 *
 * ⚠️ 随机模式下 [fullPlaylist] **故意不打乱**。理由三条：
 * ① 打乱就变成"洗牌袋 + 列表本身"两套随机打架（这正是第 5 轮缺陷的第二层根因）；
 * ② 索引 ↔ 路径的映射必须稳定 —— 服务端是用索引 `seekToPlaylistIndex()` 定位的；
 * ③ 历史栈、投屏起播索引、迷你播放器的 "x / total" 计数全都依赖这份稳定映射。
 *
 * ⚠️ 随机模式还必须**关掉 ExoPlayer 自带的 `shuffleModeEnabled`**，全局只留这一套随机源；
 * 否则播放器会再洗一遍牌，两套顺序互不相干。见 `VideoPlayerOverlay.applyRepeatMode()`。
 */
object PlaylistManager {
    private const val TAG = "PlaylistManager"

    private var fullPlaylist: MutableList<String> = mutableListOf()
    private var currentIndex = 0
    private var requestedIndex = 0

    // Estado
    var isShuffleEnabled = false
        private set

    private var originalPlaylist: List<String> = emptyList()

    // —————————————————————— 随机引擎专用状态 ——————————————————————

    /** 待抽索引池（不放回）。 */
    private val shuffleBag = ArrayDeque<Int>()

    /** 实际播放顺序（按抽签发生的先后）。 */
    private val history = mutableListOf<Int>()

    /** 当前处在 [history] 的哪个位置。 */
    private var historyCursor = -1

    // —————————————————————————————— 列表装载 ——————————————————————————————

    /**
     * 装载播放列表。
     *
     * @param shuffle 为 true 走随机模式：**不打乱列表**，而是立即抽一条作为起播条目。
     *   调用方必须用 [getCurrentIndex] 作为 [com.nkls.nekovideo.MediaPlaybackService.startWithPlaylist]
     *   的起播索引 —— **不能硬编码 0**，否则服务端的 `syncLoadedWindow(0)` 会把刚抽好的签覆盖掉。
     */
    fun setPlaylist(playlist: List<String>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (playlist.isEmpty()) {
            clear()
            return
        }

        originalPlaylist = playlist
        fullPlaylist = playlist.toMutableList()

        if (shuffle) {
            isShuffleEnabled = true
            clearShuffleState()

            // 起播的第一条也要是随机的 —— 这里就是"引擎启动时的那一次抽签"。
            refillBag(avoid = NO_AVOID)
            val first = drawFromBag()
            history.add(first)
            historyCursor = 0
            currentIndex = first
            requestedIndex = first
            Log.d(TAG, "Shuffle playlist: start at $first/${fullPlaylist.size} (bag=${shuffleBag.size})")
        } else {
            isShuffleEnabled = false
            clearShuffleState()
            currentIndex = startIndex.coerceIn(0, fullPlaylist.size - 1)
            requestedIndex = currentIndex
        }
    }

    fun clear() {
        fullPlaylist.clear()
        originalPlaylist = emptyList()
        currentIndex = 0
        requestedIndex = 0
        isShuffleEnabled = false
        clearShuffleState()
    }

    // ————————————————————————————— 随机引擎 ——————————————————————————————

    /**
     * 开 / 关随机引擎。由 UI 在切换播放模式时调用（见 `VideoPlayerOverlay.applyRepeatMode`）。
     *
     * 打开时会以**当前正在播的那条**为起点重新开局；关闭时清空随机状态，
     * 并把线性游标停在当前条目上，保证切回顺序模式后行为立刻正常。
     */
    fun setShuffleEnabled(enabled: Boolean) {
        if (isShuffleEnabled == enabled) return

        isShuffleEnabled = enabled
        clearShuffleState()

        if (enabled) {
            history.add(currentIndex)
            historyCursor = 0
            refillBag(avoid = currentIndex)
            requestedIndex = currentIndex
        } else {
            requestedIndex = currentIndex
        }
        Log.d(TAG, "Shuffle mode ${if (enabled) "ON" else "OFF"} (index=$currentIndex, size=${fullPlaylist.size})")
    }

    private fun clearShuffleState() {
        shuffleBag.clear()
        history.clear()
        historyCursor = -1
    }

    /**
     * 重新装牌。
     *
     * @param avoid 本轮不想抽到的索引（通常是"刚播完的那条"）；[NO_AVOID] 表示不排除任何条目。
     */
    private fun refillBag(avoid: Int) {
        shuffleBag.clear()
        if (fullPlaylist.isEmpty()) return

        if (fullPlaylist.size == 1) {
            // 只有一条视频：候选集为空，只能放回它自己（已知行为，非新缺陷）。
            shuffleBag.add(0)
            return
        }

        shuffleBag.addAll(fullPlaylist.indices.filter { it != avoid }.shuffled())
    }

    private fun drawFromBag(): Int {
        if (shuffleBag.isEmpty()) {
            // 一轮抽完 → 重装，并避开当前这条，避免"刚播完立刻又播它"。
            refillBag(avoid = currentIndex)
        }
        return shuffleBag.removeFirst()
    }

    // —————————————————————————————— 导航 ——————————————————————————————

    fun next(): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty

        if (isShuffleEnabled) {
            // 用户曾沿历史回退过 → 原来那条"未来"路径作废，整个丢掉重新抽。
            if (historyCursor < history.lastIndex) {
                history.subList(historyCursor + 1, history.size).clear()
            }

            val picked = drawFromBag()
            history.add(picked)
            historyCursor = history.lastIndex
            currentIndex = picked
            requestedIndex = picked

            Log.d(
                TAG,
                "Shuffle next: picked $picked/${fullPlaylist.size} " +
                    "(bag=${shuffleBag.size}, history=${history.size}, cursor=$historyCursor)"
            )
            return NavigationResult.Success(fullPlaylist[picked], needsWindowUpdate())
        }

        val targetIndex = requestedIndex + 1

        if (targetIndex >= fullPlaylist.size) {
            requestedIndex = fullPlaylist.size - 1
            return NavigationResult.EndOfPlaylist
        }

        requestedIndex = targetIndex
        Log.d(TAG, "Next requested: index $requestedIndex/${fullPlaylist.size} (confirmed=$currentIndex)")
        return NavigationResult.Success(fullPlaylist[requestedIndex], needsWindowUpdate())
    }

    fun previous(): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty

        if (isShuffleEnabled) {
            if (history.isEmpty()) {
                // 兜底：没有历史就没有"上一条"可回，原地返回。
                return NavigationResult.Success(fullPlaylist[currentIndex], needsWindowUpdate())
            }

            historyCursor = if (historyCursor > 0) {
                historyCursor - 1
            } else {
                // 业主定的：退到最开头也循环 —— 回绕到最近播过的那条（不是当前这条）。
                history.lastIndex
            }

            val target = history[historyCursor]
            currentIndex = target
            requestedIndex = target
            Log.d(TAG, "Shuffle previous: $target (cursor=$historyCursor/${history.lastIndex}, history=${history.size})")
            return NavigationResult.Success(fullPlaylist[target], needsWindowUpdate())
        }

        val targetIndex = requestedIndex - 1

        if (targetIndex < 0) {
            requestedIndex = 0
            return NavigationResult.StartOfPlaylist
        }

        requestedIndex = targetIndex
        Log.d(TAG, "Previous requested: index $requestedIndex/${fullPlaylist.size} (confirmed=$currentIndex)")
        return NavigationResult.Success(fullPlaylist[requestedIndex], needsWindowUpdate())
    }

    fun jumpTo(index: Int): NavigationResult {
        if (fullPlaylist.isEmpty()) return NavigationResult.Empty
        if (index !in fullPlaylist.indices) return NavigationResult.InvalidIndex

        requestedIndex = index

        if (isShuffleEnabled) {
            // 显式跳转也记进历史，并丢弃原来的"未来"。
            shuffleBag.remove(index)
            if (historyCursor < history.lastIndex) {
                history.subList(historyCursor + 1, history.size).clear()
            }
            history.add(index)
            historyCursor = history.lastIndex
        }

        Log.d(TAG, "Jump requested: index $requestedIndex/${fullPlaylist.size} (confirmed=$currentIndex)")
        return NavigationResult.Success(fullPlaylist[requestedIndex], true)
    }

    private fun needsWindowUpdate(): Boolean {
        return false
    }

    // ————————————————————————— 与播放器状态对齐 —————————————————————————

    fun syncLoadedWindow(currentIndexInWindow: Int) {
        alignToIndex(currentIndexInWindow)
    }

    /**
     * 把内部状态对齐到"播放器实际已装载 / 已确认的索引"。
     *
     * 服务端在换列表、删除条目之后调用。随机模式下这会**重新开局**（以该索引为起点）——
     * 因为列表长度变了，袋与历史里存的旧索引已经失效。删除视频是低频操作，
     * 重置比"逐个修正索引"可靠得多（见计划 §3.7）。
     */
    fun alignToIndex(index: Int) {
        val safe = index.coerceIn(0, (fullPlaylist.size - 1).coerceAtLeast(0))
        currentIndex = safe
        requestedIndex = safe

        if (isShuffleEnabled) {
            clearShuffleState()
            history.add(safe)
            historyCursor = 0
            // 该条已经在播，从袋里拿掉，别让它在本轮再被抽中。
            shuffleBag.remove(safe)
        }
    }

    /**
     * 播放器确认了当前索引（seek 落地 / 条目切换完成）。
     * 随机模式下顺带把历史栈的栈顶校准到该索引，避免出现"历史与播放器不一致"。
     */
    fun confirmCurrentIndex(index: Int) {
        val safe = index.coerceIn(0, (fullPlaylist.size - 1).coerceAtLeast(0))
        currentIndex = safe
        requestedIndex = safe

        if (!isShuffleEnabled) return

        when {
            history.isEmpty() -> {
                history.add(safe)
                historyCursor = 0
            }
            history[historyCursor] != safe -> {
                shuffleBag.remove(safe)
                if (historyCursor < history.lastIndex) {
                    history.subList(historyCursor + 1, history.size).clear()
                }
                history.add(safe)
                historyCursor = history.lastIndex
            }
        }
    }

    // —————————————————————————————— 删除 ——————————————————————————————

    fun removeCurrent(): RemovalResult {
        if (fullPlaylist.isEmpty()) return RemovalResult.Empty

        val removedPath = fullPlaylist[currentIndex]
        fullPlaylist.removeAt(currentIndex)

        originalPlaylist = originalPlaylist.filter { it != removedPath }

        if (fullPlaylist.isEmpty()) {
            currentIndex = 0
            requestedIndex = 0
            clearShuffleState()
            return RemovalResult.PlaylistEmpty
        }

        if (currentIndex >= fullPlaylist.size) {
            currentIndex = fullPlaylist.size - 1
        }

        requestedIndex = currentIndex

        if (isShuffleEnabled) {
            // 列表长度变了 → 袋与历史里的旧索引失效，以当前条为起点重新开局。
            clearShuffleState()
            history.add(currentIndex)
            historyCursor = 0
            refillBag(avoid = currentIndex)
        }

        val nextVideo = fullPlaylist.getOrNull(currentIndex)

        return if (nextVideo != null) {
            RemovalResult.Success(nextVideo, true)
        } else {
            RemovalResult.PlaylistEmpty
        }
    }

    // —————————————————————————————— 查询 ——————————————————————————————

    fun getCurrentWindow(): List<String> {
        return fullPlaylist.toList()
    }

    fun getWindowStartIndex(): Int {
        return 0
    }

    fun getCurrentIndexInWindow(): Int {
        return currentIndex
    }

    fun getTotalSize(): Int = fullPlaylist.size

    fun getCurrentIndex(): Int = currentIndex

    fun getRequestedIndex(): Int = requestedIndex

    /**
     * 随机模式下**永远**为 true（列表非空时）—— 无限循环是设计目标，不是"还有没有下一条"。
     */
    fun hasNext(): Boolean =
        if (isShuffleEnabled) fullPlaylist.isNotEmpty() else requestedIndex < fullPlaylist.size - 1

    /**
     * 随机模式下同样恒为 true（列表非空时）：退到最开头会回绕，两个方向都不卡。
     */
    fun hasPrevious(): Boolean =
        if (isShuffleEnabled) fullPlaylist.isNotEmpty() else requestedIndex > 0

    fun getFullPlaylist(): List<String> = fullPlaylist.toList()

    private const val NO_AVOID = -1

    sealed class NavigationResult {
        data class Success(val videoPath: String, val needsWindowUpdate: Boolean) : NavigationResult()
        object EndOfPlaylist : NavigationResult()
        object StartOfPlaylist : NavigationResult()
        object Empty : NavigationResult()
        object InvalidIndex : NavigationResult()
    }

    sealed class RemovalResult {
        data class Success(val nextVideoPath: String, val needsWindowUpdate: Boolean) : RemovalResult()
        object PlaylistEmpty : RemovalResult()
        object Empty : RemovalResult()
    }
}
