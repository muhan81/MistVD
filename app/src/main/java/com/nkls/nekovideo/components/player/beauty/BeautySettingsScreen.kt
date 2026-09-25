package com.nkls.nekovideo.components.player.beauty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.BeautyPreset
import com.nkls.nekovideo.components.helpers.BeautyPresetStore
import com.nkls.nekovideo.components.helpers.BeautySettingsStore

/**
 * 设置里的「美颜」页（第 6 轮）。
 *
 * 两件事：
 * 1. **全局参数** —— 总开关 + 7 个滑块，所有视频默认跟随；
 * 2. **方案管理** —— 把当前参数命名保存、套用、重命名、删除。
 *
 * 刻意不复用 `SettingsScreen.kt` 里的私有渲染组件（那些是为 Int 滑块写的，
 * 且可见性不一定 public），这里自己用 Card + [BeautySlider] 组，换来的是零跨文件耦合。
 *
 * 本页只负责"全局"，单视频参数在播放器面板里用「仅此视频」开关处理 —— 两者互不干扰。
 */
@Composable
fun BeautySettingsScreen(videoPath: String? = null) {
    val context = LocalContext.current
    // 第 7 轮：同一个页面承担「全局」与「单个视频」两件事。
    // perVideoPath 非空 = 从九宫格对某个视频点「美颜」进来的，参数读写全走单视频键。
    val perVideoPath = videoPath?.takeIf { it.isNotBlank() }

    var enabled by remember { mutableStateOf(BeautySettingsStore.isEnabled(context)) }
    var global by remember(perVideoPath) {
        mutableStateOf(
            if (perVideoPath != null) {
                // 没单独设过就先拿全局值当起点，用户一调就固化成"这个视频专属"
                BeautySettingsStore.getForVideo(context, perVideoPath)
                    ?: BeautySettingsStore.getGlobal(context)
            } else {
                BeautySettingsStore.getGlobal(context)
            }
        )
    }
    var hasPerVideo by remember(perVideoPath) {
        mutableStateOf(perVideoPath != null && BeautySettingsStore.hasForVideo(context, perVideoPath))
    }
    var presets by remember { mutableStateOf(BeautyPresetStore.getAll(context)) }

    // 命名对话框：新建与重命名共用一个（用 editingId 区分）。
    var showNameDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }

    var presetToDelete by remember { mutableStateOf<BeautyPreset?>(null) }

    fun refreshPresets() {
        presets = BeautyPresetStore.getAll(context)
    }

    /**
     * 写参数：单视频页写单视频键，全局页写全局键。
     *
     * ⚠️ 按业主裁决（第 7 轮 §2.6），这里**不会**顺手打开总开关 —— 关着就只是存下来，
     * 等用户自己打开总开关时即刻生效。
     */
    fun commitParams(params: BeautyParams) {
        global = params
        if (perVideoPath != null) {
            hasPerVideo = true
            BeautySettingsStore.setForVideo(context, perVideoPath, params)
        } else {
            BeautySettingsStore.setGlobal(context, params)
        }
    }

    /** 单视频页专用：放弃该视频的单独设置，改回跟随全局。 */
    fun clearToGlobal() {
        val path = perVideoPath ?: return
        BeautySettingsStore.clearForVideo(context, path)
        hasPerVideo = false
        global = BeautySettingsStore.getGlobal(context)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        @Suppress("UnusedBoxWithConstraintsScope")
        val isCompact = this.maxWidth > 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isCompact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 12.dp)
        ) {
            // 单视频身份卡：说清楚这一页改的是哪个视频，并给出"改回跟随全局"的出口
            if (perVideoPath != null) {
                item {
                    BeautyCard {
                        Text(
                            text = stringResource(R.string.beauty_for_this_video),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = java.io.File(perVideoPath).name,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (hasPerVideo) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { clearToGlobal() }) {
                                    Text(text = stringResource(R.string.beauty_clear_this_video))
                                }
                            }
                        } else {
                            // 还没单独设过 → 这一页的起点就是全局值；写清楚，免得用户以为在改全局
                            Text(
                                text = stringResource(R.string.beauty_follow_global),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            // 总开关关着但已经调过参数 → 提示"存下来了，开开关才生效"（第 7 轮 §2.6）
            if (!enabled && !global.isDefault) {
                item {
                    BeautyCard {
                        Text(
                            text = stringResource(R.string.beauty_master_off_hint),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            item {
                BeautyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.beauty_enabled),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.beauty_enabled_desc),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                BeautySettingsStore.setEnabled(context, it)
                            }
                        )
                    }
                }
            }

            item {
                BeautyCard {
                    Text(
                        text = stringResource(R.string.beauty_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    BeautySlider(
                        label = stringResource(R.string.beauty_smooth),
                        value = global.smooth,
                        range = BeautyParams.UNIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(smooth = it) },
                        onCommit = { commitParams(global) }
                    )
                    BeautySlider(
                        label = stringResource(R.string.beauty_whiten),
                        value = global.whiten,
                        range = BeautyParams.UNIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(whiten = it) },
                        onCommit = { commitParams(global) }
                    )
                    BeautySlider(
                        label = stringResource(R.string.beauty_rosy),
                        value = global.rosy,
                        range = BeautyParams.UNIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(rosy = it) },
                        onCommit = { commitParams(global) }
                    )
                    BeautySlider(
                        label = stringResource(R.string.beauty_sharpen),
                        value = global.sharpen,
                        range = BeautyParams.UNIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(sharpen = it) },
                        onCommit = { commitParams(global) }
                    )
                    BeautySlider(
                        label = stringResource(R.string.beauty_brightness),
                        value = global.brightness,
                        range = BeautyParams.BIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(brightness = it) },
                        onCommit = { commitParams(global) }
                    )
                    BeautySlider(
                        label = stringResource(R.string.beauty_contrast),
                        value = global.contrast,
                        range = BeautyParams.BIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(contrast = it) },
                        onCommit = { commitParams(global) }
                    )
                    BeautySlider(
                        label = stringResource(R.string.beauty_saturation),
                        value = global.saturation,
                        range = BeautyParams.BIPOLAR_RANGE,
                        enabled = true,
                        onValueChange = { global = global.copy(saturation = it) },
                        onCommit = { commitParams(global) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { commitParams(BeautyParams.DEFAULT) },
                            enabled = enabled
                        ) {
                            Text(text = stringResource(R.string.beauty_reset))
                        }
                    }
                }
            }

            item {
                BeautyCard {
                    Text(
                        text = stringResource(R.string.beauty_presets),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    if (presets.isEmpty()) {
                        Text(
                            text = stringResource(R.string.beauty_preset_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
            }

            items(presets, key = { it.id }) { preset ->
                BeautyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = preset.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { commitParams(preset.params) }) {
                            Text(text = stringResource(R.string.beauty_preset_apply))
                        }
                        IconButton(
                            onClick = {
                                editingId = preset.id
                                nameInput = preset.name
                                nameError = false
                                showNameDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.rename)
                            )
                        }
                        IconButton(onClick = { presetToDelete = preset }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        editingId = null
                        nameInput = ""
                        nameError = false
                        showNameDialog = true
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.beauty_save_preset))
                }
            }
        }
    }

    if (showNameDialog) {
        val isRename = editingId != null
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = {
                Text(
                    text = stringResource(
                        if (isRename) R.string.beauty_preset_rename_title else R.string.beauty_save_preset
                    )
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it
                            nameError = false
                        },
                        label = { Text(stringResource(R.string.beauty_preset_name)) },
                        singleLine = true,
                        isError = nameError
                    )
                    if (nameError) {
                        Text(
                            text = stringResource(R.string.beauty_preset_duplicate),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val result = if (isRename) {
                            BeautyPresetStore.rename(context, editingId!!, nameInput)
                        } else {
                            BeautyPresetStore.create(context, nameInput, global).map { Unit }
                        }
                        if (result.isSuccess) {
                            showNameDialog = false
                            refreshPresets()
                        } else {
                            nameError = true
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.beauty_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text(text = stringResource(R.string.beauty_preset_delete_title)) },
            text = { Text(text = preset.name) },
            confirmButton = {
                TextButton(
                    onClick = {
                        BeautyPresetStore.delete(context, preset.id)
                        presetToDelete = null
                        refreshPresets()
                    }
                ) {
                    Text(text = stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun BeautyCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}
