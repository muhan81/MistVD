package com.nkls.nekovideo.components.player.beauty

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import com.nkls.nekovideo.components.helpers.logging.TaskLogger

/**
 * 美颜「颜色类」5 参数（美白 / 红润 / 亮度 / 对比度 / 饱和度）的 **View 层滤镜**（第 13 轮 S1）。
 *
 * 为什么不再走播放器特效管线：Media3 的 `setVideoEffects` 管线在业主设备上已证实不可用
 * （官方 issue #2505，不修）。本类把颜色类参数合成一个 [ColorMatrix]，
 * 用 API 31+ 的 `RenderEffect.createColorFilterEffect` 挂在**视频输出面**上 ——
 * 播放器从此完全不知道"美颜"的存在，调色即时生效（不再重建播放器）。
 *
 * 颜色数学（《计划-第13轮》§1.2；链序与旧的 `BeautyEffects.kt` 完全一致：
 * 美白+红润 → 亮度 → 对比度 → 饱和度）：
 * - **美白 + 红润**：三通道对角增益，系数照搬 `BeautyEffects.kt`，精确等价；
 * - **亮度 / 对比度**：运行时直接取 Media3 官方 `RgbMatrix.getMatrix()`（`Brightness` / `Contrast`），
 *   再把列主序 4×4 摊成 Android 行主序 4×5，精确等价；
 * - **饱和度**：`ColorMatrix.setSaturation(1 + sat)` 近似 —— 旧实现是 `HslAdjustment` 的 HSL 空间
 *   非线性调整，4×5 矩阵表达不了；这是全项目唯一不精确的一项，验收以肉眼为准。
 *
 * 磨皮 / 锐化不在这里：空间卷积用颜色矩阵做不了（v1.21.3 起面板灰显）。
 *
 * [apiOk] 为 false（API 30）时一个字都不写：[RenderEffect] 与 `View.setRenderEffect`
 * 都是 API 31 才有的 API，minSdk 30 只是兜底分支（目标机是 Android 12）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object BeautyColorFilter {

    // ── 美白 / 红润的增益（★ 与 BeautyEffects.kt 里的常数逐字一致；那边删掉后这里就是唯一来源）

    /** 美白：三通道等比上抬（蓝通道抬得少一点，避免泛蓝/发紫）。 */
    private const val WHITEN_RED_GAIN = 0.20f
    private const val WHITEN_GREEN_GAIN = 0.20f
    private const val WHITEN_BLUE_GAIN = 0.12f

    /** 红润：提红、略压绿、压蓝，让肤色偏暖。 */
    private const val ROSY_RED_GAIN = 0.18f
    private const val ROSY_GREEN_CUT = 0.03f
    private const val ROSY_BLUE_CUT = 0.08f

    /**
     * 本机是否支持 View 层滤镜（= [RenderEffect] 与 `View.setRenderEffect` 是否存在）。
     *
     * 美颜面板据此整体灰显：API 30 上 5 个滑块调了也不会有画面变化，不如明说。
     */
    val apiOk: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 5 项**颜色**参数里是否至少有一项非 0（磨皮 / 锐化不算 —— 滤镜天然不读这两个字段）。
     *
     * 输入会先过 [BeautyParams.sanitize] 再判断，与旧 `BeautyEffects.build` 的口径一致
     * （越界值被夹取后仍为 0 的，视为"没开"）。
     */
    fun hasColorWork(p: BeautyParams): Boolean {
        val s = BeautyParams.sanitize(p)
        return s.whiten != 0f || s.rosy != 0f || s.brightness != 0f ||
            s.contrast != 0f || s.saturation != 0f
    }

    /**
     * 参数 → Android 4×5 [ColorMatrix]。
     *
     * 参数全默认（没有颜色类工作时）返回的是**单位矩阵** —— 调用方（[applyTo]）据此判断要不要挂。
     * 输入会先过 [BeautyParams.sanitize]：[Brightness] / [Contrast] 的构造函数有
     * `checkArgument` 区间校验（`[-1, 1]`），越界值会在那里直接抛异常。
     *
     * 串联用 [ColorMatrix.postConcat]：`postConcat(x)` = 先应用本矩阵再应用 x，
     * 所以按 ②③④ 的顺序 postConcat 就得到上面写的链序。
     */
    fun toColorMatrix(p: BeautyParams): ColorMatrix {
        val s = BeautyParams.sanitize(p)

        // ① 美白 + 红润：对角增益（合并进同一个矩阵；对角阵的行主序/列主序写法相同）
        val out = ColorMatrix(
            floatArrayOf(
                redScale(s), 0f, 0f, 0f, 0f,
                0f, greenScale(s), 0f, 0f, 0f,
                0f, 0f, blueScale(s), 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )

        // ② 亮度：取 Media3 官方矩阵，零公式推导风险
        if (s.brightness != 0f) {
            out.postConcat(toColorMatrix(Brightness(s.brightness).getMatrix(0L, false)))
        }

        // ③ 对比度：同上（Contrast 的官方矩阵自带"以 0.5 为支点"的偏移）
        if (s.contrast != 0f) {
            out.postConcat(toColorMatrix(Contrast(s.contrast).getMatrix(0L, false)))
        }

        // ④ 饱和度：近似等价（见类注释）
        if (s.saturation != 0f) {
            out.postConcat(ColorMatrix().apply { setSaturation(1f + s.saturation) })
        }

        return out
    }

    /**
     * 把滤镜挂到 [target] 上（通常是 `playerView.videoSurfaceView`，即内部的 TextureView
     * ⇒ 只滤视频、不滤字幕；拿不到时才回落整只 PlayerView）。
     *
     * - [apiOk] 为 false 或参数全默认 ⇒ `setRenderEffect(null)`（把旧滤镜摘掉，回到原画）；
     * - 否则挂 `RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(矩阵))`。
     *
     * 参数或目标变化时**直接再调一次**即可，幂等：本方法每次都重算并覆盖。
     * 必须主线程调用（View 只允许主线程改）。
     *
     * 日志走 `beautyFilter.apply`，业主验收口径就是搜这行看 `matrix=on`（见《计划-第13轮》§五）。
     */
    fun applyTo(target: View, p: BeautyParams) {
        val s = BeautyParams.sanitize(p)
        val matrix = if (apiOk && hasColorWork(s)) toColorMatrix(s) else null

        // 这里写内联版本判断（而不是复用 apiOk）：lint 的 NewApi 检查认得出这一种写法，
        // 而在 API 30 上整块跳过正是我们要的兜底。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = if (matrix != null) {
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            } else {
                null
            }
            target.setRenderEffect(effect)
        }

        TaskLogger.i(
            TaskLogger.Channel.BEAUTY, "beautyFilter.apply",
            "target=${target.javaClass.simpleName} apiLevel=${Build.VERSION.SDK_INT} " +
                "supported=$apiOk matrix=${if (matrix != null) "on" else "off"} " +
                "whiten=${s.whiten} rosy=${s.rosy} bright=${s.brightness} " +
                "contrast=${s.contrast} sat=${s.saturation}"
        )
    }

    private fun redScale(p: BeautyParams): Float =
        1f + p.whiten * WHITEN_RED_GAIN + p.rosy * ROSY_RED_GAIN

    private fun greenScale(p: BeautyParams): Float =
        1f + p.whiten * WHITEN_GREEN_GAIN - p.rosy * ROSY_GREEN_CUT

    private fun blueScale(p: BeautyParams): Float =
        1f + p.whiten * WHITEN_BLUE_GAIN - p.rosy * ROSY_BLUE_CUT

    /**
     * Media3 `RgbMatrix.getMatrix()` 的 4×4 **列主序**矩阵 → Android 的 4×5 行主序矩阵。
     *
     * Media3 的着色器按 `M × vec4(r, g, b, 1.0)` 用它，所以：
     * 第 c 列（c=0..2）是第 c 个颜色通道的系数、第 4 列（下标 12..14）是"加到输出上的常数"。
     * Android 则按 `r' = a·r + b·g + c·b + d·a + e` 解释（行主序，末位是偏移）。
     * 因此映射为：本行系数 = 该列系数，末位 = 第 4 列那三个常数；第 4 位（alpha 系数）恒 0
     * —— 视频帧是不透明的（`a ≡ 1`），所以 alpha 系数与常数在数学上等价，取常数才不丢偏移。
     *
     * 已验证（`javap -c` 打在 media3-effect-1.7.1 上）：
     * `Brightness(b)` = 单位阵经 `android.opengl.Matrix.translateM(…, b, b, b)`（偏移在下标 12..14）；
     * `Contrast(c)` 的偏移恰为 `0.5·(1-s)`（`s = (1+c)/(1.0001-c)`），即以 0.5 为支点的对比度。
     * 两者都只吃 `useHdr=false`（`Brightness` 对 `useHdr=true` 会直接 `checkArgument` 失败）。
     */
    private fun toColorMatrix(m: FloatArray): ColorMatrix {
        val out = FloatArray(20)
        for (r in 0..3) {
            out[r * 5 + 0] = m[r]
            out[r * 5 + 1] = m[4 + r]
            out[r * 5 + 2] = m[8 + r]
            out[r * 5 + 3] = 0f
            out[r * 5 + 4] = m[12 + r]
        }
        return ColorMatrix(out)
    }
}
