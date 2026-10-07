package com.nkls.nekovideo.components.player.beauty

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R

/**
 * 播放器内的美颜面板（第 6 轮）。仿倍速面板的做法，由一个 ModalBottomSheet 承载。
 *
 * **关键设计：拖动过程中不提交，松手才提交一次。**
 * Media3 收到新的特效设置会**重建整条处理管线**（社区实测生效延迟可达数百毫秒~数秒，
 * 画面会卡一下），而本播放器的横滑快进 / 双击快进非常频繁，已知"特效 + 频繁 seek"
 * 会偶发卡顿。所以这里维护一个本地草稿 [draft]，`onValueChange` 只改草稿，
 * 只有 `onValueChangeFinished`（松手）才通过 [onParamsCommit] 上报给宿主。
 */
@Composable
fun BeautyPanel(
    params: BeautyParams,
    onlyThisVideo: Boolean,
    hdrBlocked: Boolean,
    masterEnabled: Boolean = true,
    onParamsCommit: (BeautyParams) -> Unit,
    onOnlyThisVideoChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 外部参数变化时（切视频、套用方案）重新建立草稿，保证界面与实际生效值一致。
    var draft by remember(params) { mutableStateOf(params) }
    // 第 13 轮（S3）：API < 31 没有 `RenderEffect` / `View.setRenderEffect`，颜色类滤镜根本没有落点
    // ⇒ 整个面板灰显并说明原因（目标机是 Android 12；minSdk 30 只是兜底分支）。
    val apiOk = BeautyColorFilter.apiOk
    val enabled = !hdrBlocked && apiOk

    Column(modifier = modifier.fillMaxWidth()) {
        if (hdrBlocked) {
            Text(
                text = stringResource(R.string.beauty_hdr_unsupported),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (!apiOk) {
            Text(
                text = stringResource(R.string.beauty_api31_required),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 第 7 轮（按业主裁决）：总开关关着也允许调整并保存，打开总开关后生效 ——
        // 但必须说清楚"现在还不生效"，否则用户会以为调坏了。
        if (!masterEnabled && !hdrBlocked && !draft.isDefault) {
            Text(
                text = stringResource(R.string.beauty_master_off_hint),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 第 13 轮（S3，按业主裁决）：磨皮 / 锐化是空间卷积，View 层的颜色矩阵做不了，
        // 本版本先禁用。灰显之外必须说清楚原因，否则用户只会以为调坏了（同 HDR 那条的做法）。
        Text(
            text = stringResource(R.string.beauty_skin_unsupported),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        BeautySlider(
            label = stringResource(R.string.beauty_smooth),
            value = draft.smooth,
            range = BeautyParams.UNIPOLAR_RANGE,
            enabled = false,
            onValueChange = { draft = draft.copy(smooth = it) },
            onCommit = { onParamsCommit(draft) }
        )
        BeautySlider(
            label = stringResource(R.string.beauty_whiten),
            value = draft.whiten,
            range = BeautyParams.UNIPOLAR_RANGE,
            enabled = enabled,
            onValueChange = { draft = draft.copy(whiten = it) },
            onCommit = { onParamsCommit(draft) }
        )
        BeautySlider(
            label = stringResource(R.string.beauty_rosy),
            value = draft.rosy,
            range = BeautyParams.UNIPOLAR_RANGE,
            enabled = enabled,
            onValueChange = { draft = draft.copy(rosy = it) },
            onCommit = { onParamsCommit(draft) }
        )
        BeautySlider(
            label = stringResource(R.string.beauty_sharpen),
            value = draft.sharpen,
            range = BeautyParams.UNIPOLAR_RANGE,
            enabled = false,
            onValueChange = { draft = draft.copy(sharpen = it) },
            onCommit = { onParamsCommit(draft) }
        )
        BeautySlider(
            label = stringResource(R.string.beauty_brightness),
            value = draft.brightness,
            range = BeautyParams.BIPOLAR_RANGE,
            enabled = enabled,
            onValueChange = { draft = draft.copy(brightness = it) },
            onCommit = { onParamsCommit(draft) }
        )
        BeautySlider(
            label = stringResource(R.string.beauty_contrast),
            value = draft.contrast,
            range = BeautyParams.BIPOLAR_RANGE,
            enabled = enabled,
            onValueChange = { draft = draft.copy(contrast = it) },
            onCommit = { onParamsCommit(draft) }
        )
        BeautySlider(
            label = stringResource(R.string.beauty_saturation),
            value = draft.saturation,
            range = BeautyParams.BIPOLAR_RANGE,
            enabled = enabled,
            onValueChange = { draft = draft.copy(saturation = it) },
            onCommit = { onParamsCommit(draft) }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.beauty_only_this_video),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.beauty_only_this_video_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
            Switch(
                checked = onlyThisVideo,
                onCheckedChange = onOnlyThisVideoChange,
                enabled = enabled
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
        ) {
            TextButton(onClick = onReset, enabled = enabled) {
                Text(text = stringResource(R.string.beauty_reset))
            }
        }
    }
}

/**
 * 一个美颜滑块：左侧名字、右侧百分比、下方滑条。
 * 播放器面板与设置页共用，保证两边手感一致。
 *
 * [onValueChange] 在拖动中持续回调（只应更新本地状态），
 * [onCommit] 只在松手时回调一次（真正下发到播放器）。
 */
@Composable
fun BeautySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatBeautyValue(value, range),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
            enabled = enabled
        )
    }
}

/** 单向项显示 `0%..100%`，双向项显示带符号的 `-100%..+100%`。 */
private fun formatBeautyValue(value: Float, range: ClosedFloatingPointRange<Float>): String {
    val percent = (value * 100f).toInt()
    return if (range.start < 0f && percent > 0) "+$percent%" else "$percent%"
}
