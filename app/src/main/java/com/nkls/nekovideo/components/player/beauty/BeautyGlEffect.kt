package com.nkls.nekovideo.components.player.beauty

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.nkls.nekovideo.components.helpers.logging.TaskLogger

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
 *
 * 第 8 轮补充：本类两个方法都接了诊断日志 —— 排查"开启美颜后无法播放"时，
 * 日志里有没有 `toGlShaderProgram` 决定了"特效到底有没有被 Media3 接手"。
 */
@UnstableApi
class BeautyGlEffect(
    private val smooth: Float,
    private val sharpen: Float
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        // ★ 第 8 轮：这一行出现 = Media3 真的把我们的着色器建起来了
        //   （即"特效已生效"，可以排除 H1"美颜压根没启用"）。
        TaskLogger.i(
            TaskLogger.Channel.GL, "toGlShaderProgram",
            "useHdr=$useHdr smooth=$smooth sharpen=$sharpen"
        )
        return BeautyShaderProgram(useHdr = useHdr, smooth = smooth, sharpen = sharpen)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean {
        val noOp = smooth <= 0f && sharpen <= 0f
        if (noOp) {
            // "第二道保险被触发"的证据：即使这项被加进链里，Media3 也会直接跳过它。
            TaskLogger.d(TaskLogger.Channel.GL, "isNoOp", "skipped=true")
        }
        return noOp
    }
}
