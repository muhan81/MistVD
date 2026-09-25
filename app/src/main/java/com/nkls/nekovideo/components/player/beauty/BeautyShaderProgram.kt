/*
 * 第 6 轮美颜 —— 磨皮 + 锐化的着色器程序（本轮核心技术件）。
 *
 * 着色器内核移植自 GPUPixel (https://github.com/pixpark/gpupixel)
 * commit 5415b738，Apache License 2.0，Copyright (c) 2021 PixPark
 * 来源文件：
 *   - src/filter/beauty_face_unit_filter.cc （混合公式、保边判据、3x3 高频回加）
 *   - src/filter/box_difference_filter.cc   （边缘强度：min(((原图-均值)*7.07)^2, 1)）
 *   - src/filter/box_mono_blur_filter.cc    （5-tap 盒式模糊，radius=4 的精确等价核）
 * 修改点：
 *   ① 变量名适配自研顶点着色器（uTexSampler / vTexCoord / aPosition / aTexCoord）；
 *   ② 移除依赖 LUT 贴图的美白分支（GPUPixel 的 4 张 lookup_*.png 不在其仓库里），
 *      美白改由 Media3 内置 RgbAdjustment 承担，见 BeautyEffects.kt；
 *   ③ 合并"差分趟"：GPUPixel 的高通链内部又做了一遍模糊，但它的模糊参数
 *      （radius=4、texelSpacingMultiplier=4）与主模糊完全相同，结果逐像素一致，
 *      故直接复用均值图、在混合着色器内现算边缘强度 → 6 趟降为 3 趟（数学等价，非近似）；
 *   ④ 采样偏移原由顶点着色器预计算成 varying，这里改为在片元内按 uTexelStep 现算，
 *      因为 8 次加法在片元里的成本可忽略，换来两个片元着色器共用一个顶点着色器。
 */
package com.nkls.nekovideo.components.player.beauty

import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram

/**
 * 三趟渲染：
 * ```
 *   ① 横糊  inputTex → texA
 *   ② 竖糊  texA     → texB   （texB = 均值图 / 低频图）
 *   ③ 混合  inputTex + texB → 父类给的输出 FBO（边缘强度 + 保边 mix + 高频回加）
 * ```
 *
 * **关于输出 FBO**：`BaseGlShaderProgram.queueInputFrame` 在调用 [drawFrame] 之前已经
 * 聚焦好输出纹理的 FBO。我们中途要切两次 FBO，所以必须先把父类绑定的那个 FBO 记下来、
 * 第三趟再切回去 —— 这是本文件唯一没有现成参考、必须自己写对的地方，写错会黑屏/花屏。
 */
@UnstableApi
class BeautyShaderProgram(
    private val useHdr: Boolean,
    private val smooth: Float,
    private val sharpen: Float
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private var blurProgram: GlProgram? = null
    private var mixProgram: GlProgram? = null

    private var texA = 0
    private var fboA = 0
    private var texB = 0
    private var fboB = 0

    private var width = 0
    private var height = 0

    /** 父类在调用 drawFrame 前绑定好的输出 FBO，第三趟要切回去。 */
    private val previousFbo = IntArray(1)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // ⚠️ 第 7 轮修正：Media3 在**分辨率变化时会重复调用 configure** —— 必须先释放上一轮的
        //    GL 资源，否则每换一次分辨率就泄漏 2 个 program + 2 个纹理 + 2 个 FBO。
        releaseGlResources()

        width = inputWidth
        height = inputHeight

        try {
            blurProgram = GlProgram(VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
            mixProgram = GlProgram(VERTEX_SHADER, MIX_FRAGMENT_SHADER)

            // 两张中间纹理：texA 存横糊结果，texB 存竖糊结果（= 均值图）。
            // 精度随片源走（useHdr），避免对 HDR 无谓降精度。
            texA = GlUtil.createTexture(inputWidth, inputHeight, useHdr)
            fboA = GlUtil.createFboForTexture(texA)
            texB = GlUtil.createTexture(inputWidth, inputHeight, useHdr)
            fboB = GlUtil.createFboForTexture(texB)

            // 顶点属性是常量（整屏四边形），只需在尺寸变化时设置一次。
            // 两个 bounds 都返回 4 个 vec4（16 个 float），所以分量数 = 4、顶点数 = 4。
            blurProgram?.apply {
                setBufferAttribute(ATTRIBUTE_POSITION, GlUtil.getNormalizedCoordinateBounds(), COMPONENTS_PER_VERTEX)
                setBufferAttribute(ATTRIBUTE_TEX_COORD, GlUtil.getTextureCoordinateBounds(), COMPONENTS_PER_VERTEX)
            }
            mixProgram?.apply {
                setBufferAttribute(ATTRIBUTE_POSITION, GlUtil.getNormalizedCoordinateBounds(), COMPONENTS_PER_VERTEX)
                setBufferAttribute(ATTRIBUTE_TEX_COORD, GlUtil.getTextureCoordinateBounds(), COMPONENTS_PER_VERTEX)
            }
        } catch (e: GlUtil.GlException) {
            Log.e(
                "BeautyShaderProgram",
                "[beauty] configure failed (${inputWidth}x$inputHeight, useHdr=$useHdr)", e
            )
            throw VideoFrameProcessingException(e)
        }

        Log.i(
            "BeautyShaderProgram",
            "[beauty] configure ${inputWidth}x$inputHeight useHdr=$useHdr smooth=$smooth sharpen=$sharpen"
        )

        // 不缩放：输入多大输出多大。
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            return
        }

        try {
            // ★ 父类已绑定输出 FBO，先记下来（见类注释）。
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, previousFbo, 0)

            // ① 横向模糊：原图 → texA
            GlUtil.focusFramebufferUsingCurrentContext(fboA, w, h)
            GlUtil.clearFocusedBuffers()
            drawBlur(inputTexId, BLUR_TEXEL_SPACING / w, 0f)

            // ② 纵向模糊：texA → texB（= 均值图）
            GlUtil.focusFramebufferUsingCurrentContext(fboB, w, h)
            GlUtil.clearFocusedBuffers()
            drawBlur(texA, 0f, BLUR_TEXEL_SPACING / h)

            // ③ 混合 + 边缘强度 + 高频回加 → 父类的输出 FBO
            GlUtil.focusFramebufferUsingCurrentContext(previousFbo[0], w, h)
            drawMix(inputTexId, texB, w, h)
        } catch (e: GlUtil.GlException) {
            // ⚠️ 抛出去会让这一帧永远不输出（真机表现 = 画面卡死 / 转圈），
            //    所以必须先留日志，别让排查只能靠猜。
            Log.e("BeautyShaderProgram", "[beauty] drawFrame failed (${w}x$h)", e)
            throw VideoFrameProcessingException(e)
        }
    }

    /** 释放本轮 [configure] 建出来的 GL 资源。可重复调用（幂等）。 */
    private fun releaseGlResources() {
        runCatching {
            blurProgram?.delete()
            mixProgram?.delete()
            if (texA != 0) GlUtil.deleteTexture(texA)
            if (fboA != 0) GlUtil.deleteFbo(fboA)
            if (texB != 0) GlUtil.deleteTexture(texB)
            if (fboB != 0) GlUtil.deleteFbo(fboB)
        }
        blurProgram = null
        mixProgram = null
        texA = 0
        fboA = 0
        texB = 0
        fboB = 0
    }

    override fun release() {
        releaseGlResources()
        super.release()
    }

    /** 一趟可分离盒式模糊。[stepX] / [stepY] 决定方向（横趟只给 X，竖趟只给 Y）。 */
    private fun drawBlur(inputTexId: Int, stepX: Float, stepY: Float) {
        val program = blurProgram ?: return
        program.use()
        program.setSamplerTexIdUniform(UNIFORM_TEX_SAMPLER, inputTexId, /* texUnit= */ 0)
        program.setFloatUniform(UNIFORM_TEXEL_STEP_X, stepX)
        program.setFloatUniform(UNIFORM_TEXEL_STEP_Y, stepY)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GlUtil.checkGlError()
    }

    /** 混合趟：读取原图与均值图，算边缘强度与保边系数，再回加高频细节（= 锐化）。 */
    private fun drawMix(inputTexId: Int, meanTexId: Int, w: Int, h: Int) {
        val program = mixProgram ?: return
        program.use()
        program.setSamplerTexIdUniform(UNIFORM_TEX_SAMPLER, inputTexId, /* texUnit= */ 0)
        program.setSamplerTexIdUniform(UNIFORM_BLUR_SAMPLER, meanTexId, /* texUnit= */ 1)
        program.setFloatUniform(UNIFORM_STRENGTH, smooth)
        program.setFloatUniform(UNIFORM_SHARPEN, sharpen)
        // 高频采样用 1 个 texel 的步长（GPUPixel 的 widthOffset/heightOffset 同样是 1/宽、1/高）
        program.setFloatUniform(UNIFORM_TEXEL_STEP_X, 1f / w)
        program.setFloatUniform(UNIFORM_TEXEL_STEP_Y, 1f / h)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GlUtil.checkGlError()
    }

    private companion object {
        const val ATTRIBUTE_POSITION = "aPosition"
        const val ATTRIBUTE_TEX_COORD = "aTexCoord"
        const val UNIFORM_TEX_SAMPLER = "uTexSampler"
        const val UNIFORM_BLUR_SAMPLER = "uBlurSampler"
        const val UNIFORM_TEXEL_STEP_X = "uTexelStepX"
        const val UNIFORM_TEXEL_STEP_Y = "uTexelStepY"
        const val UNIFORM_STRENGTH = "uStrength"
        const val UNIFORM_SHARPEN = "uSharpen"

        /** 两个 bounds 方法都返回 4 个 vec4，故每顶点 4 个分量。 */
        const val COMPONENTS_PER_VERTEX = 4

        /** 4 个顶点组成一个三角带 = 一个整屏四边形。 */
        const val VERTEX_COUNT = 4

        /**
         * 模糊的采样间距，对应 GPUPixel 的 `SetTexelSpacingMultiplier(4)`。
         * 它的主模糊与高通链模糊用的是同一个值，所以这里一份就够。
         */
        const val BLUR_TEXEL_SPACING = 4f

        /**
         * 顶点着色器。attribute 名自定义（Media3 对 [BaseGlShaderProgram] 的子类没有命名约束）。
         * 取 `aTexCoord.xy` 得到 (0,0)(1,0)(0,1)(1,1) 四个纹理坐标。
         */
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        /**
         * 可分离盒式模糊（横/竖共用一个着色器）。
         * 核来自 GPUPixel `BoxMonoBlurFilter`（radius=4）的 5-tap 优化形式：
         * 中心 ×1/9，±1.5 ×2/9，±3.5 ×2/9 —— 权重和恰为 1，
         * 且借助双线性插值精确等价于 9 个 texel 的均匀盒式模糊。
         */
        const val BLUR_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform float uTexelStepX;
            uniform float uTexelStepY;
            varying vec2 vTexCoord;
            void main() {
                vec2 step = vec2(uTexelStepX, uTexelStepY);
                float w = 1.0 / 9.0;
                vec4 sum = texture2D(uTexSampler, vTexCoord) * w;
                sum += texture2D(uTexSampler, vTexCoord + step * 1.5) * (w * 2.0);
                sum += texture2D(uTexSampler, vTexCoord - step * 1.5) * (w * 2.0);
                sum += texture2D(uTexSampler, vTexCoord + step * 3.5) * (w * 2.0);
                sum += texture2D(uTexSampler, vTexCoord - step * 3.5) * (w * 2.0);
                gl_FragColor = sum;
            }
        """

        /**
         * 混合 + 保边 + 锐化。
         *
         * 保边判据 `1 - meanVar / (meanVar + theta)` 是这套磨皮的精髓：
         * - 平坦皮肤区 → 原图与均值图差异小 → meanVar 小 → 系数接近 1 → 多磨；
         * - 边缘/五官/发丝 → 差异大 → meanVar 大 → 系数接近 0 → 少磨。
         * `p` 是暗部/毛孔检测（亮部才磨），`uStrength` 就是用户拖的磨皮强度。
         */
        const val MIX_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTexSampler;
            uniform sampler2D uBlurSampler;
            uniform float uStrength;
            uniform float uSharpen;
            uniform float uTexelStepX;
            uniform float uTexelStepY;
            varying vec2 vTexCoord;

            const float HIGH_PASS_DELTA = 7.07;
            const float EDGE_THETA = 0.1;

            void main() {
                vec4 iColor = texture2D(uTexSampler, vTexCoord);
                vec4 meanColor = texture2D(uBlurSampler, vTexCoord);

                // 边缘强度：(原图 - 均值) 平方后截断（GPUPixel box_difference_filter）
                vec3 diff = (iColor.rgb - meanColor.rgb) * HIGH_PASS_DELTA;
                vec3 varColor = min(diff * diff, vec3(1.0));

                vec3 color = iColor.rgb;

                // 只磨皮不锐化 / 只锐化不磨皮 / 两者都要，三种组合都走这里。
                // （uStrength = 0 时 kMin 为 0，mix 结果就是原图，但锐化仍然生效 —— 与 GPUPixel 行为一致。）
                if (uStrength > 0.0 || uSharpen > 0.0) {
                    float p = clamp((min(iColor.r, meanColor.r - 0.1) - 0.2) * 4.0, 0.0, 1.0);
                    float meanVar = (varColor.r + varColor.g + varColor.b) / 3.0;
                    float kMin = clamp((1.0 - meanVar / (meanVar + EDGE_THETA)) * p * uStrength, 0.0, 1.0);
                    vec3 resultColor = mix(iColor.rgb, meanColor.rgb, kMin);

                    // 3x3 二项式核取样，得到高频细节后按锐化强度回加
                    vec3 sum = 0.25 * iColor.rgb;
                    sum += 0.125 * texture2D(uTexSampler, vTexCoord + vec2(uTexelStepX, 0.0)).rgb;
                    sum += 0.125 * texture2D(uTexSampler, vTexCoord + vec2(-uTexelStepX, 0.0)).rgb;
                    sum += 0.125 * texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelStepY)).rgb;
                    sum += 0.125 * texture2D(uTexSampler, vTexCoord + vec2(0.0, -uTexelStepY)).rgb;
                    sum += 0.0625 * texture2D(uTexSampler, vTexCoord + vec2(uTexelStepX, uTexelStepY)).rgb;
                    sum += 0.0625 * texture2D(uTexSampler, vTexCoord + vec2(-uTexelStepX, -uTexelStepY)).rgb;
                    sum += 0.0625 * texture2D(uTexSampler, vTexCoord + vec2(-uTexelStepX, uTexelStepY)).rgb;
                    sum += 0.0625 * texture2D(uTexSampler, vTexCoord + vec2(uTexelStepX, -uTexelStepY)).rgb;

                    vec3 hPass = iColor.rgb - sum;
                    color = resultColor + uSharpen * hPass * 2.0;
                }

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), iColor.a);
            }
        """
    }
}
