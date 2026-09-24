package com.nkls.nekovideo.components.helpers

import android.util.LruCache
import java.io.File

/**
 * 递归统计文件夹内**全部内容**的总字节数（第 4 轮「显示文件夹大小」功能）。
 *
 * ## 为什么要递归
 * `MediaItem.sizeInBytes` 早已存在，但它只统计该文件夹里**直放**的视频
 * （非递归），而且只用于排序、从不显示。本对象提供真正的递归求和。
 *
 * ## 性能（本功能最大的风险点）
 * 真正的风险不是「引入文件系统访问」（既有代码本来就同步访问），而是**叠加了无上限的
 * 递归深度**。因此这里有三道闸：
 * 1. [MAX_DEPTH] —— 挡住 `Android/data`、`Android/obb` 这类极深目录；
 * 2. [MAX_ENTRIES] —— 单次统计的条目上限，超出即返回已累计的部分；
 * 3. [TTL_MS] 短期缓存 —— 同一文件夹在时间窗内重复请求直接命中（配合 LruCache 限容）。
 * 调用方还应在 `Dispatchers.IO` 上执行，避免占用主线程。
 *
 * ## 保险库内的文件
 * 私密保险库里的视频只对**前 8KB 做原地 XOR**（`RandomAccessFile(file,"rw")` 写回**等长**头部），
 * 因此 `File.length()` 拿到的是**正确字节数**，无需读 manifest 的 `originalSize`。
 *
 * ## 排除项
 * 逐层套用（不只是顶层）：缩略图缓存目录、标记文件、清单文件等都不计入用户可见的"文件夹大小"。
 */
object FolderSizeCalculator {

    /** 递归深度上限（根的第 0 层开始计数） */
    private const val MAX_DEPTH = 8

    /** 单次统计遍历的条目上限 */
    private const val MAX_ENTRIES = 20_000

    /** 结果缓存有效期：60 秒 */
    private const val TTL_MS = 60_000L

    /** 结果缓存条目上限 */
    private const val CACHE_SIZE = 200

    /** 这些名字**逐层**排除：应用自身的元数据 / 缓存，不是用户内容 */
    private val EXCLUDED_NAMES = setOf(
        ".nomedia",
        ".neko_locked",
        ".neko_manifest.enc",
        ".neko_lock_in_progress",
        ".nekovideo",
        ".neko_thumbs",
        "recovery_hint.txt"
    )

    private class Entry(val bytes: Long, val at: Long)

    private val cache = LruCache<String, Entry>(CACHE_SIZE)

    /**
     * 返回 [folderPath] 下全部内容的字节总和（带 TTL 缓存）。
     * 目录不存在或读取失败返回 0。
     *
     * 必须在后台线程调用（内部是阻塞的 IO）。
     */
    fun getFolderSize(folderPath: String): Long {
        val now = System.currentTimeMillis()
        cache.get(folderPath)?.let { entry ->
            if (now - entry.at < TTL_MS) return entry.bytes
        }
        val total = compute(File(folderPath))
        cache.put(folderPath, Entry(total, now))
        return total
    }

    /** 内容变动后主动失效（例如删除 / 移动 / 迁移之后） */
    fun invalidate(folderPath: String) {
        cache.remove(folderPath)
    }

    /** 整批失效（例如换卷 / 整库迁移之后） */
    fun invalidateAll() {
        cache.evictAll()
    }

    private fun compute(root: File): Long {
        if (!root.isDirectory) return 0L

        var total = 0L
        var visited = 0

        // 显式栈的深度优先遍历，避免递归调用栈过深
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth >= MAX_DEPTH) continue

            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (visited >= MAX_ENTRIES) return total
                visited++
                if (child.name in EXCLUDED_NAMES) continue

                if (child.isDirectory) {
                    stack.addLast(child to depth + 1)
                } else {
                    total += child.length()
                }
            }
        }
        return total
    }
}
