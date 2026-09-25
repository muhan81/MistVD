package com.nkls.nekovideo.components.player.beauty

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * 把 [BeautyParams] 翻译成 Media3 的效果链（供给 `ExoPlayer.setVideoEffects`）。
 *
 * 7 个可调项里 **5 项零自研**（都是 Media3 内置实现），只有磨皮+锐化走自研着色器。
 * 链路顺序（见《计划-第6轮》§2.3）：磨皮+锐化 → 美白+红润 → 亮度+对比度 → 饱和度。
 *
 * **值为 0 的项一律不进链** —— 这样"优雅退出"不靠把强度设成 0 硬跑 GPU，
 * 而是真的把该效果摘掉。自研那个 [BeautyGlEffect.isNoOp] 是第二道保险。
 *
 * 这里是**全项目唯一**做"归一化值 → 各 API 原始单位"换算的地方，
 * 单位换算不要散到 UI 或存储层去。
 */
@UnstableApi
object BeautyEffects {

    /**
     * 美白：三通道等比上抬（蓝通道抬得少一点，避免泛蓝/发紫）。
     * 归一化 1.0（拉满）时亮度约 +20%。
     */
    private const val WHITEN_RED_GAIN = 0.20f
    private const val WHITEN_GREEN_GAIN = 0.20f
    private const val WHITEN_BLUE_GAIN = 0.12f

    /**
     * 红润：提红、略压绿、压蓝，让肤色偏暖。
     * 与美白**合并进同一个 `RgbAdjustment`**，省一趟 GPU。
     */
    private const val ROSY_RED_GAIN = 0.18f
    private const val ROSY_GREEN_CUT = 0.03f
    private const val ROSY_BLUE_CUT = 0.08f

    /** `HslAdjustment.adjustSaturation` 的单位是 [-100, 100]，不是 [-1, 1]。 */
    private const val HSL_SATURATION_SCALE = 100f

    /**
     * 构建效果列表。传全 0 参数会得到**空列表**，调用方据此把 effect 整体清掉。
     */
    fun build(params: BeautyParams): List<Effect> {
        val p = BeautyParams.sanitize(params)
        if (p.isDefault) {
            return emptyList()
        }

        val effects = mutableListOf<Effect>()

        // ① 磨皮 + 锐化（自研，3 趟；两者做在同一个着色器里）
        if (p.needsSkinShader) {
            effects += BeautyGlEffect(smooth = p.smooth, sharpen = p.sharpen)
        }

        // ② 美白 + 红润（合并）
        if (p.whiten > 0f || p.rosy > 0f) {
            effects += RgbAdjustment.Builder()
                .setRedScale(
                    1f + p.whiten * WHITEN_RED_GAIN + p.rosy * ROSY_RED_GAIN
                )
                .setGreenScale(
                    1f + p.whiten * WHITEN_GREEN_GAIN - p.rosy * ROSY_GREEN_CUT
                )
                .setBlueScale(
                    1f + p.whiten * WHITEN_BLUE_GAIN - p.rosy * ROSY_BLUE_CUT
                )
                .build()
        }

        // ③ 亮度
        if (p.brightness != 0f) {
            effects += Brightness(p.brightness)
        }

        // ④ 对比度
        if (p.contrast != 0f) {
            effects += Contrast(p.contrast)
        }

        // ⑤ 饱和度
        if (p.saturation != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation(p.saturation * HSL_SATURATION_SCALE)
                .build()
        }

        return effects
    }
}
