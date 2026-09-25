package com.nkls.nekovideo.components.helpers

import android.content.Context
import com.nkls.nekovideo.components.player.beauty.BeautyParams
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 美颜参数的读写（第 6 轮）。
 *
 * 存 **SharedPreferences 而不是数据库** —— 刻意选择：不引入任何数据迁移风险，
 * 万一要退回本轮，删掉这个 prefs 文件即可，不留残渣（《计划-第6轮》§6）。
 *
 * 两条层次：
 * - **全局参数**：设置里配，所有视频生效；
 * - **单视频参数**：按视频路径存，只对那一个视频生效，优先于全局。
 *
 * ⚠️ 已知限制（与"继续观看进度""音轨/字幕记忆"行为一致，不是新毛病）：
 * 单视频参数按**文件路径**做键，视频**改名或搬家后参数会丢失**（回落用全局）。
 *
 * 包名注意：本文件位于 `helpers/video/` 目录下，但 package 是 `components.helpers`
 * （目录名不等于包名，是本项目的既有约定，见 `VideoProgressStore`）。
 */
object BeautySettingsStore {

    private const val PREFS_NAME = "nekovideo_beauty"

    private const val KEY_ENABLED = "enabled"

    private const val GLOBAL_PREFIX = "g_"
    private const val VIDEO_PREFIX = "v_"

    private const val SUFFIX_SMOOTH = "smooth"
    private const val SUFFIX_WHITEN = "whiten"
    private const val SUFFIX_ROSY = "rosy"
    private const val SUFFIX_SHARPEN = "sharpen"
    private const val SUFFIX_BRIGHTNESS = "brightness"
    private const val SUFFIX_CONTRAST = "contrast"
    private const val SUFFIX_SATURATION = "saturation"

    private val _changeVersion = MutableStateFlow(0)
    val changeVersion: StateFlow<Int> = _changeVersion.asStateFlow()

    // ---------------------------------------------------------------- 总开关

    /**
     * 总开关，**默认关闭** —— 保证升级后不打开就完全看不到任何画面变化。
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        bump()
    }

    // ---------------------------------------------------------------- 全局参数

    fun getGlobal(context: Context): BeautyParams {
        val p = prefs(context)
        return BeautyParams(
            smooth = p.getFloat(GLOBAL_PREFIX + SUFFIX_SMOOTH, 0f),
            whiten = p.getFloat(GLOBAL_PREFIX + SUFFIX_WHITEN, 0f),
            rosy = p.getFloat(GLOBAL_PREFIX + SUFFIX_ROSY, 0f),
            sharpen = p.getFloat(GLOBAL_PREFIX + SUFFIX_SHARPEN, 0f),
            brightness = p.getFloat(GLOBAL_PREFIX + SUFFIX_BRIGHTNESS, 0f),
            contrast = p.getFloat(GLOBAL_PREFIX + SUFFIX_CONTRAST, 0f),
            saturation = p.getFloat(GLOBAL_PREFIX + SUFFIX_SATURATION, 0f)
        )
    }

    fun setGlobal(context: Context, params: BeautyParams) {
        val p = BeautyParams.sanitize(params)
        prefs(context).edit()
            .putFloat(GLOBAL_PREFIX + SUFFIX_SMOOTH, p.smooth)
            .putFloat(GLOBAL_PREFIX + SUFFIX_WHITEN, p.whiten)
            .putFloat(GLOBAL_PREFIX + SUFFIX_ROSY, p.rosy)
            .putFloat(GLOBAL_PREFIX + SUFFIX_SHARPEN, p.sharpen)
            .putFloat(GLOBAL_PREFIX + SUFFIX_BRIGHTNESS, p.brightness)
            .putFloat(GLOBAL_PREFIX + SUFFIX_CONTRAST, p.contrast)
            .putFloat(GLOBAL_PREFIX + SUFFIX_SATURATION, p.saturation)
            .apply()
        bump()
    }

    // ---------------------------------------------------------------- 单视频参数

    /** 该视频是否单独调过参数。 */
    fun hasForVideo(context: Context, videoPath: String): Boolean {
        val key = videoKey(videoPath) ?: return false
        return prefs(context).contains("$VIDEO_PREFIX$key$SUFFIX_SMOOTH")
    }

    /** 读该视频的参数；没单独设过返回 null（调用方据此回落到全局）。 */
    fun getForVideo(context: Context, videoPath: String): BeautyParams? {
        val key = videoKey(videoPath) ?: return null
        val p = prefs(context)
        val prefix = "$VIDEO_PREFIX$key"
        if (!p.contains(prefix + SUFFIX_SMOOTH)) return null

        return BeautyParams(
            smooth = p.getFloat(prefix + SUFFIX_SMOOTH, 0f),
            whiten = p.getFloat(prefix + SUFFIX_WHITEN, 0f),
            rosy = p.getFloat(prefix + SUFFIX_ROSY, 0f),
            sharpen = p.getFloat(prefix + SUFFIX_SHARPEN, 0f),
            brightness = p.getFloat(prefix + SUFFIX_BRIGHTNESS, 0f),
            contrast = p.getFloat(prefix + SUFFIX_CONTRAST, 0f),
            saturation = p.getFloat(prefix + SUFFIX_SATURATION, 0f)
        )
    }

    fun setForVideo(context: Context, videoPath: String, params: BeautyParams) {
        val key = videoKey(videoPath) ?: return
        val p = BeautyParams.sanitize(params)
        val prefix = "$VIDEO_PREFIX$key"
        prefs(context).edit()
            .putFloat(prefix + SUFFIX_SMOOTH, p.smooth)
            .putFloat(prefix + SUFFIX_WHITEN, p.whiten)
            .putFloat(prefix + SUFFIX_ROSY, p.rosy)
            .putFloat(prefix + SUFFIX_SHARPEN, p.sharpen)
            .putFloat(prefix + SUFFIX_BRIGHTNESS, p.brightness)
            .putFloat(prefix + SUFFIX_CONTRAST, p.contrast)
            .putFloat(prefix + SUFFIX_SATURATION, p.saturation)
            .apply()
        bump()
    }

    /** 取消该视频的单独设置（改回跟随全局）。 */
    fun clearForVideo(context: Context, videoPath: String) {
        val key = videoKey(videoPath) ?: return
        val prefix = "$VIDEO_PREFIX$key"
        prefs(context).edit()
            .remove(prefix + SUFFIX_SMOOTH)
            .remove(prefix + SUFFIX_WHITEN)
            .remove(prefix + SUFFIX_ROSY)
            .remove(prefix + SUFFIX_SHARPEN)
            .remove(prefix + SUFFIX_BRIGHTNESS)
            .remove(prefix + SUFFIX_CONTRAST)
            .remove(prefix + SUFFIX_SATURATION)
            .apply()
        bump()
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 播放时实际生效的参数：**单视频 > 全局 > 全关**。
     *
     * 总开关关闭时一律返回全 0（连单视频参数也不生效）—— 语义清晰、可预期：
     * "关掉美颜"就是真的关掉，不会因为某个视频单独调过而漏出来。
     */
    fun resolve(context: Context, videoPath: String?): BeautyParams {
        if (!isEnabled(context)) return BeautyParams.DEFAULT

        if (videoPath != null) {
            getForVideo(context, videoPath)?.let { return it }
        }
        return getGlobal(context)
    }

    // ---------------------------------------------------------------- 内部

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 把视频路径规整成可做 prefs 键的形式。
     * 与 [VideoProgressStore] 一致：剥掉 `locked://` / `file://` 前缀。
     * 另外把 `/` 换成 `|`，避免某些实现里路径分隔符带来的歧义。
     */
    private fun videoKey(videoPath: String): String? {
        val normalized = when {
            videoPath.startsWith("locked://") -> videoPath.removePrefix("locked://")
            videoPath.startsWith("file://") -> videoPath.removePrefix("file://")
            else -> videoPath
        }.takeIf { it.isNotBlank() } ?: return null

        return normalized.replace('/', '|')
    }

    /** 供"设置里显示占用空间"之类的地方用；不常用，失败返回 0。 */
    fun storageBytes(context: Context): Long =
        File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml")
            .takeIf { it.exists() }
            ?.length()
            ?: 0L

    private fun bump() {
        _changeVersion.value = _changeVersion.value + 1
    }
}
