package com.nkls.nekovideo.components.helpers

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nkls.nekovideo.components.player.beauty.BeautyParams
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一套命名保存的美颜参数（用户口中的"方案"）。
 */
data class BeautyPreset(
    val id: String,
    val name: String,
    val params: BeautyParams,
    val createdAt: Long
)

/**
 * 美颜方案（预设）的增删改查（第 6 轮）。
 *
 * 存储选择：**SharedPreferences 里放一段 JSON**。
 * 方案数据量极小（每条 7 个 float + 名字），用不着数据库，更用不着迁移 ——
 * 与《计划-第6轮》§6"本轮不碰数据、退回可删干净"的约束一致。
 *
 * ⚠️ 与计划书的一处**有意的偏离**（已在交付汇总中报备）：
 * 计划书 §4.1 写的是"仿 `VideoTagStore` 做 JSON 文件 + MediaStore 自动备份"，
 * 实际实现**不做自动备份**，理由：
 * ① 方案是用户手动创建的少量数据，丢了重建成本极低；
 * ② 自动备份要走 MediaStore 的 `Documents/` 写入与权限流程，而本项目在鸿蒙上的
 *    存储权限模型已经踩过坑（`isExternalStorageManager()` 报 true ≠ 根目录真的可写），
 *    为一份预设引入这条链路的风险收益比不划算。
 * 因此改为提供**显式导出/导入 JSON 文本**，需要备份时手动走一次。
 */
object BeautyPresetStore {

    private const val PREFS_NAME = "nekovideo_beauty_presets"
    private const val KEY_PRESETS = "presets"
    private const val KEY_LIBRARY_NAME = "library_name"

    /** 导出文件里的版本号，便于以后改格式时做兼容。 */
    private const val BACKUP_VERSION = 1

    private const val MAX_NAME_LENGTH = 40

    /**
     * 序列化用的扁平 DTO。
     *
     * 刻意**不直接序列化** [BeautyPreset] / [BeautyParams]：那两个类的字段名会被 R8 混淆
     * （项目里已有 `PinnedFolderEntry` 因此丢数据的前车之鉴），而 Gson 靠字段名匹配 JSON。
     * 这里用固定的 `@SerializedName` 字面量，字段名与 JSON 键彻底解耦 —— 同时在
     * `proguard-rules.pro` 里对本类加了 keep，双保险。
     */
    private data class PresetDto(
        @SerializedName("id") val id: String,
        @SerializedName("name") val name: String,
        @SerializedName("createdAt") val createdAt: Long,
        @SerializedName("smooth") val smooth: Float,
        @SerializedName("whiten") val whiten: Float,
        @SerializedName("rosy") val rosy: Float,
        @SerializedName("sharpen") val sharpen: Float,
        @SerializedName("brightness") val brightness: Float,
        @SerializedName("contrast") val contrast: Float,
        @SerializedName("saturation") val saturation: Float
    )

    private data class BackupPayload(
        @SerializedName("version") val version: Int,
        @SerializedName("exportedAt") val exportedAt: Long,
        @SerializedName("presets") val presets: List<PresetDto>
    )

    private val gson = Gson()

    private val _changeVersion = MutableStateFlow(0)
    val changeVersion: StateFlow<Int> = _changeVersion.asStateFlow()

    // ---------------------------------------------------------------- 查询

    /** 全部方案，按创建时间升序（先建的在前，与列表展示顺序一致）。 */
    fun getAll(context: Context): List<BeautyPreset> =
        readDtos(context).sortedBy { it.createdAt }.map { it.toModel() }

    fun count(context: Context): Int = readDtos(context).size

    fun getById(context: Context, id: String): BeautyPreset? =
        readDtos(context).firstOrNull { it.id == id }?.toModel()

    fun hasName(context: Context, rawName: String, exceptId: String? = null): Boolean {
        val name = rawName.trim().lowercase()
        if (name.isEmpty()) return false
        return readDtos(context).any {
            it.id != exceptId && it.name.trim().lowercase() == name
        }
    }

    // ---------------------------------------------------------------- 写入

    /** 新建一条方案。名字为空或重名会失败。 */
    fun create(context: Context, rawName: String, params: BeautyParams): Result<BeautyPreset> {
        val name = rawName.trim().take(MAX_NAME_LENGTH)
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("empty_name"))
        if (hasName(context, name)) return Result.failure(IllegalStateException("duplicate_name"))

        val preset = BeautyPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            params = BeautyParams.sanitize(params),
            createdAt = System.currentTimeMillis()
        )

        writeDtos(context, readDtos(context) + preset.toDto())
        return Result.success(preset)
    }

    /**
     * 把某条方案更新为当前参数（"保存"按钮：用现在调的参数覆盖这条方案）。
     */
    fun overwrite(context: Context, id: String, params: BeautyParams): Result<Unit> {
        val dtos = readDtos(context)
        if (dtos.none { it.id == id }) return Result.failure(NoSuchElementException("not_found"))

        writeDtos(
            context,
            dtos.map { if (it.id == id) it.copy(fromParams = BeautyParams.sanitize(params)) else it }
        )
        return Result.success(Unit)
    }

    fun rename(context: Context, id: String, rawName: String): Result<Unit> {
        val name = rawName.trim().take(MAX_NAME_LENGTH)
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("empty_name"))
        if (hasName(context, name, exceptId = id)) {
            return Result.failure(IllegalStateException("duplicate_name"))
        }

        val dtos = readDtos(context)
        if (dtos.none { it.id == id }) return Result.failure(NoSuchElementException("not_found"))

        writeDtos(context, dtos.map { if (it.id == id) it.copy(name = name) else it })
        return Result.success(Unit)
    }

    fun delete(context: Context, id: String) {
        writeDtos(context, readDtos(context).filterNot { it.id == id })
    }

    // ---------------------------------------------------------------- 导出 / 导入

    /** 导出成 JSON 文本，供用户自行保存。 */
    fun exportJson(context: Context): String =
        gson.toJson(BackupPayload(BACKUP_VERSION, System.currentTimeMillis(), readDtos(context)))

    /**
     * 从 JSON 文本导入。同名方案会**跳过**（不覆盖用户已调的），返回新增条数。
     */
    fun importJson(context: Context, json: String): Result<Int> = runCatching {
        val payload = gson.fromJson(json.trim(), BackupPayload::class.java)
            ?: error("invalid_payload")

        val existing = readDtos(context)
        val existingNames = existing.map { it.name.trim().lowercase() }.toMutableSet()

        val incoming = payload.presets.mapNotNull { dto ->
            val name = dto.name.trim().take(MAX_NAME_LENGTH)
            if (name.isEmpty() || !existingNames.add(name.lowercase())) {
                null
            } else {
                dto.copy(id = UUID.randomUUID().toString(), name = name)
            }
        }

        if (incoming.isNotEmpty()) {
            writeDtos(context, existing + incoming)
        }
        incoming.size
    }

    // ---------------------------------------------------------------- 内部

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readDtos(context: Context): List<PresetDto> {
        val raw = prefs(context).getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson(raw, Array<PresetDto>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun writeDtos(context: Context, dtos: List<PresetDto>) {
        prefs(context).edit().putString(KEY_PRESETS, gson.toJson(dtos)).apply()
        _changeVersion.value = _changeVersion.value + 1
    }

    private fun PresetDto.toModel() = BeautyPreset(
        id = id,
        name = name,
        createdAt = createdAt,
        params = BeautyParams(
            smooth = smooth,
            whiten = whiten,
            rosy = rosy,
            sharpen = sharpen,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        )
    )

    private fun BeautyPreset.toDto() = PresetDto(
        id = id,
        name = name,
        createdAt = createdAt,
        smooth = params.smooth,
        whiten = params.whiten,
        rosy = params.rosy,
        sharpen = params.sharpen,
        brightness = params.brightness,
        contrast = params.contrast,
        saturation = params.saturation
    )

    /**
     * 把一组参数灌回 DTO（保留 id / name / createdAt）。
     * 写成命名参数形式便于对照 [toDto] 的字段顺序。
     */
    private fun PresetDto.copy(fromParams: BeautyParams) = copy(
        smooth = fromParams.smooth,
        whiten = fromParams.whiten,
        rosy = fromParams.rosy,
        sharpen = fromParams.sharpen,
        brightness = fromParams.brightness,
        contrast = fromParams.contrast,
        saturation = fromParams.saturation
    )
}
