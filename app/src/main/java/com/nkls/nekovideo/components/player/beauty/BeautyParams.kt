package com.nkls.nekovideo.components.player.beauty

/**
 * 美颜参数（第 6 轮"纯滤镜档"）。
 *
 * 7 个可调项，全部**默认 0 = 该项关闭**，这也让"全部拉到 0 时画面与关闭美颜完全一致"这条
 * 验收项天然成立（见《计划-第6轮》§5.2 第 7 项）。
 *
 * 取值区间刻意分成两类：
 * - **单向项**（[SMOOTH] / [WHITEN] / [ROSY] / [SHARPEN]）取 `0f..1f`，只能"加强"不能"反向"；
 * - **双向项**（[BRIGHTNESS] / [CONTRAST] / [SATURATION]）取 `-1f..1f`，`0f` 是原点，
 *   负值代表往反方向调（变暗 / 降对比 / 降饱和）。
 *
 * 注意：这里存的是**归一化值**，不是各 API 的原始单位。映射到 Media3 与自研着色器时
 * 各自换算，换算集中在 [BeautyEffects] 一处，避免单位泄漏到 UI 与存储里。
 * 特别是 [SATURATION]：Media3 的 `HslAdjustment.adjustSaturation` 用的是 `[-100, 100]`
 * 且带 `checkArgument` 校验，直接传归一化值会抛异常。
 */
data class BeautyParams(
    /** 磨皮强度，0f..1f。映射为 GPUPixel 的 blurAlpha。 */
    val smooth: Float = 0f,
    /** 美白强度，0f..1f。映射为 RgbAdjustment 的三通道上抬。 */
    val whiten: Float = 0f,
    /** 红润强度，0f..1f。映射为 RgbAdjustment 的提红压蓝。 */
    val rosy: Float = 0f,
    /** 锐化强度，0f..1f。映射为 GPUPixel 的高频回加系数。 */
    val sharpen: Float = 0f,
    /** 亮度，-1f..1f。直接对应 Media3 `Brightness`。 */
    val brightness: Float = 0f,
    /** 对比度，-1f..1f。直接对应 Media3 `Contrast`。 */
    val contrast: Float = 0f,
    /** 饱和度，-1f..1f。映射为 `HslAdjustment.adjustSaturation(x * 100f)`。 */
    val saturation: Float = 0f
) {
    /** 是否全部为 0（= 等价于关闭美颜）。 */
    val isDefault: Boolean
        get() = smooth == 0f && whiten == 0f && rosy == 0f && sharpen == 0f &&
                brightness == 0f && contrast == 0f && saturation == 0f

    /** 是否需要挂自研的磨皮/锐化着色器。为 false 时不把该效果加进链，省一趟 GPU。 */
    val needsSkinShader: Boolean
        get() = smooth > 0f || sharpen > 0f

    companion object {
        /** 单向项的区间。 */
        val UNIPOLAR_RANGE = 0f..1f

        /** 双向项的区间。 */
        val BIPOLAR_RANGE = -1f..1f

        /** 默认值：全部关闭。 */
        val DEFAULT = BeautyParams()

        /** 把任意来源的值夹到合法区间，避免外部传入越界值把着色器/API 打崩。 */
        fun sanitize(params: BeautyParams): BeautyParams = BeautyParams(
            smooth = params.smooth.coerceIn(UNIPOLAR_RANGE),
            whiten = params.whiten.coerceIn(UNIPOLAR_RANGE),
            rosy = params.rosy.coerceIn(UNIPOLAR_RANGE),
            sharpen = params.sharpen.coerceIn(UNIPOLAR_RANGE),
            brightness = params.brightness.coerceIn(BIPOLAR_RANGE),
            contrast = params.contrast.coerceIn(BIPOLAR_RANGE),
            saturation = params.saturation.coerceIn(BIPOLAR_RANGE)
        )
    }
}
