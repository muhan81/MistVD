package com.nkls.nekovideo.components.player.beauty

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * 把"磨皮 + 锐化"这组参数包装成一个 Media3 [GlEffect]。
 *
 * 为什么只包这两项：7 个可调项里美白/红润/亮度/对比度/饱和度都有 Media3 内置实现
 * （`RgbAdjustment` / `Brightness` / `Contrast` / `HslAdjustment`），零自研；
 * 只有磨皮和锐化需要自写着色器 —— 而这两项在 GPUPixel 里本来就做在**同一个** fragment shader 内，
 * 所以只需要一个 [GlEffect]，不需要拆成两个。
 *
 * [isNoOp] 是关键的双保险：强度全为 0 时返回 true，Media3 会**直接把整个效果跳过**，
 * 既不建管线也不跑 GPU。调用侧另外还会在构建效果列表时过滤掉未启用的项。
 */
@UnstableApi
class BeautyGlEffect(
    private val smooth: Float,
    private val sharpen: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        BeautyShaderProgram(useHdr = useHdr, smooth = smooth, sharpen = sharpen)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        smooth <= 0f && sharpen <= 0f
}
