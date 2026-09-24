package com.nkls.nekovideo.components.helpers

import java.util.Locale

/**
 * 全库**唯一**的文件大小格式化实现。
 *
 * 历史上存在两份彼此不一致的私有实现（`ThumbnailManager.getFileSize()` 输出 `1.5MB`，
 * `StorageLocationScreen.formatBytes()` 输出 `1.5 MB`），第 4 轮统一到这里：
 * `B / KB / MB` 取整，`GB / TB` 保留一位小数，**单位前带一个空格**。
 *
 * 调用方若需要「0 显示为占位符」的旧行为，自行判断后决定是否调用本函数
 * （例如 `StorageLocationScreen` 对未挂载的卷显示 `—`）。
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.size - 1) {
        value /= 1024.0
        index++
    }
    val pattern = if (index >= 3) "%.1f %s" else "%.0f %s"
    return String.format(Locale.US, pattern, value, units[index])
}
