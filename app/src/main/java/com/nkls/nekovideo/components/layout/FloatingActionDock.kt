package com.nkls.nekovideo.components.layout

import android.content.Context
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.R
import kotlin.math.roundToInt

/**
 * 悬浮按钮坞：把「私密保险库锁」与「设置」两个 FAB 竖排成一列，**整列一起拖动**。
 *
 * 位置以**归一化比例**（0f..1f）持久化到 `nekovideo_settings`：
 * `x = 0` 最左 / `x = 1` 最右；`y = 0` 最上 / `y = 1` 最下。默认 `(1, 1)` = 右下角。
 * 存比例而非绝对像素，才能适配横竖屏切换与不同分辨率。
 *
 * ## 为什么这个坞必须挂在 `Scaffold` 之外的整屏 `Box` 里（见 `AGENTS.md` §六.12）
 *
 * `Modifier.offset` 只移动自身及其后代，**父容器的放置矩形不动**；而 Compose 的命中测试
 * 逐层下降时，每层都要求指针落在该层**已放置的矩形**内。挂在 `Scaffold.floatingActionButton`
 * 槽位里的悬浮窗一旦被拖出槽位边界，指针就落在槽位矩形之外 → **命中测试在容器那层断掉**：
 * 当次手势内还能拖，松手后在新位置既点不动、也拖不起来（第 4 轮修复的既有 bug）。
 *
 * 把坞放进覆盖整屏的 `Box` 后，容器矩形就是整屏，只要坞还在屏内就一定能点到。
 *
 * @param isVaultUnlocked 保险库当前是否已解锁 —— 决定锁图标是「合着」还是「打开」
 * @param onVaultClick    锁按钮点击回调。**完整语义由调用方提供**（与左上角连点 3 次一致）：
 *                        未解锁 → 弹密码框；已解锁 → 直接隐藏、不弹密码 + toast 提示
 * @param settingsFab     设置按钮的内容。由调用方传入 `ActionFAB`，其内部的
 *                        `ModalBottomSheet` 菜单逻辑保持原样不动
 */
@Composable
fun FloatingActionDock(
    isVaultUnlocked: Boolean,
    onVaultClick: () -> Unit,
    modifier: Modifier = Modifier,
    settingsFab: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 归一化位置。默认右下角 (1, 1)
    var xRatio by remember { mutableFloatStateOf(prefs.getFloat(KEY_RATIO_X, 1f).coerceIn(0f, 1f)) }
    var yRatio by remember { mutableFloatStateOf(prefs.getFloat(KEY_RATIO_Y, 1f).coerceIn(0f, 1f)) }
    var dockSize by remember { mutableStateOf(IntSize.Zero) }

    val density = LocalDensity.current
    val edgeMarginPx = remember(density) { with(density) { EDGE_MARGIN.toPx() } }

    BoxWithConstraints(modifier = modifier) {
        val areaWidthPx = constraints.maxWidth.toFloat()
        val areaHeightPx = constraints.maxHeight.toFloat()

        // 坞左上角可以落在 [margin, margin + maxOffset] 区间内。
        // 首帧 dockSize 还是零，maxOffset 会偏大一点点，同帧重组后即修正。
        val maxOffsetX = (areaWidthPx - dockSize.width - edgeMarginPx * 2f).coerceAtLeast(0f)
        val maxOffsetY = (areaHeightPx - dockSize.height - edgeMarginPx * 2f).coerceAtLeast(0f)

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .onSizeChanged { dockSize = it }
                .offset {
                    // 以「右下角」为原点向左上偏移，因此这里恒为负值或零
                    IntOffset(
                        x = ((xRatio - 1f) * maxOffsetX).roundToInt(),
                        y = ((yRatio - 1f) * maxOffsetY).roundToInt()
                    )
                }
                .pointerInput(maxOffsetX, maxOffsetY) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (maxOffsetX > 0f) {
                                xRatio = (xRatio + dragAmount.x / maxOffsetX).coerceIn(0f, 1f)
                            }
                            if (maxOffsetY > 0f) {
                                yRatio = (yRatio + dragAmount.y / maxOffsetY).coerceIn(0f, 1f)
                            }
                        },
                        // 只在手势结束时落盘，避免拖动过程中高频 I/O
                        onDragEnd = {
                            prefs.edit()
                                .putFloat(KEY_RATIO_X, xRatio)
                                .putFloat(KEY_RATIO_Y, yRatio)
                                .apply()
                        },
                        onDragCancel = {
                            prefs.edit()
                                .putFloat(KEY_RATIO_X, xRatio)
                                .putFloat(KEY_RATIO_Y, yRatio)
                                .apply()
                        }
                    )
                },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 保险库锁：未解锁 = 合锁，已解锁 = 开锁
            FloatingActionButton(
                onClick = onVaultClick,
                modifier = Modifier.size(48.dp),
                containerColor = if (isVaultUnlocked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isVaultUnlocked) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ) {
                Icon(
                    imageVector = if (isVaultUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = stringResource(
                        if (isVaultUnlocked) R.string.vault_fab_unlocked else R.string.vault_fab_locked
                    ),
                    modifier = Modifier.size(22.dp)
                )
            }

            // 设置按钮：由调用方传入，内部菜单逻辑不变
            settingsFab()
        }
    }
}

private const val PREFS_NAME = "nekovideo_settings"
private const val KEY_RATIO_X = "fab_dock_x_ratio"
private const val KEY_RATIO_Y = "fab_dock_y_ratio"

/** 坞与可拖区域边缘的最小间距，防止贴死屏幕边或落到手势条上 */
private val EDGE_MARGIN = 12.dp
