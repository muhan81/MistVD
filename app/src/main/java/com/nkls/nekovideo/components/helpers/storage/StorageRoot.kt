package com.nkls.nekovideo.components.helpers.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Fonte ÚNICA de verdade para as raízes de armazenamento do app.
 *
 * Motivo de existir (bug real corrigido aqui): a raiz de LISTAGEM era dinâmica
 * (`Environment.getExternalStorageDirectory()`) enquanto a raiz do COFRE era
 * literal (`"/storage/emulated/0"`). Quando o sistema muda a
 * "localização de armazenamento padrão" para o cartão SD, as duas raízes
 * divergem — e todo conteúdo movido para o cofre desaparece da interface,
 * embora os arquivos continuem no disco.
 *
 * Identificação de volume: a escolha do usuário é salva por UUID do volume
 * (`StorageVolume.getUuid()`) + último caminho conhecido. UUID primeiro: se a
 * montagem mudar de caminho (formatar, trocar slot, fabricante remontando),
 * o volume certo é reencontrado pelo UUID. Sem UUID disponível (alguns
 * aparelhos retornam null para o volume primário) cai para o caminho salvo.
 *
 * Regra de ouro: **nenhum outro arquivo do app deve montar caminho de raiz
 * por conta própria.** Todos devem passar por aqui.
 */
object StorageRoot {

    private const val PREFS_NAME = "nekovideo_settings"
    private const val PREF_BROWSE_ROOT = "storage_browse_root"
    private const val PREF_VAULT_ROOT = "storage_vault_root"
    private const val PREF_BROWSE_UUID = "storage_browse_uuid"
    private const val PREF_VAULT_UUID = "storage_vault_uuid"

    /** Nome da pasta do cofre dentro do volume escolhido. */
    const val VAULT_FOLDER_NAME = "NekoVideo"

    /**
     * Volume escolhido: uuid (quando o sistema fornece) + último caminho visto.
     * `null` = seguir o sistema.
     */
    private data class Selection(val uuid: String?, val path: String)

    private val browseSel = mutableStateOf<Selection?>(null)
    private val vaultSel = mutableStateOf<Selection?>(null)

    /** Raiz que o sistema considera como armazenamento externo primário. */
    fun systemRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    /** Raiz usada para listar e navegar pastas. */
    val browseRoot: String get() = browseSel.value?.path ?: systemRoot()

    /** Volume onde o cofre é gravado. */
    val vaultRoot: String get() = vaultSel.value?.path ?: systemRoot()

    /** Caminho completo do cofre dentro do volume escolhido. */
    val vaultPath: String get() = File(vaultRoot, VAULT_FOLDER_NAME).absolutePath

    val isBrowseRootCustom: Boolean get() = browseSel.value != null
    val isVaultRootCustom: Boolean get() = vaultSel.value != null

    /**
     * Deve ser chamado uma vez no início da Activity, ANTES de qualquer leitura
     * de caminho e antes de montar a interface.
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        browseSel.value = resolveSelection(prefs, PREF_BROWSE_UUID, PREF_BROWSE_ROOT, context)
        vaultSel.value = resolveSelection(prefs, PREF_VAULT_UUID, PREF_VAULT_ROOT, context)
    }

    /**
     * Reconstrói a seleção salva. Com UUID salvo, prefere o caminho ATUAL do
     * volume correspondente (a montagem pode mudar de caminho); sem UUID ou sem
     * correspondência viva, mantém o último caminho conhecido — se ele não
     * existir mais, a tela mostra "indisponível" em vez de cair no volume errado.
     */
    private fun resolveSelection(
        prefs: SharedPreferences,
        uuidKey: String,
        pathKey: String,
        context: Context
    ): Selection? {
        val savedPath = prefs.getString(pathKey, null)?.takeIf { it.isNotBlank() } ?: return null
        val uuid = prefs.getString(uuidKey, null)?.takeIf { it.isNotBlank() }
        if (uuid != null) {
            val live = availableVolumes(context).firstOrNull { it.uuid == uuid }
            if (live != null) return Selection(uuid, live.path)
        }
        return Selection(uuid, savedPath)
    }

    /** `volume = null` significa "voltar a seguir o sistema". */
    fun setBrowseRoot(context: Context, volume: VolumeInfo?) {
        browseSel.value = persistSelection(context, PREF_BROWSE_UUID, PREF_BROWSE_ROOT, volume)
    }

    /** `volume = null` significa "voltar a seguir o sistema". */
    fun setVaultRoot(context: Context, volume: VolumeInfo?) {
        vaultSel.value = persistSelection(context, PREF_VAULT_UUID, PREF_VAULT_ROOT, volume)
    }

    private fun persistSelection(
        context: Context,
        uuidKey: String,
        pathKey: String,
        volume: VolumeInfo?
    ): Selection? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (volume == null) {
            prefs.edit().remove(uuidKey).remove(pathKey).apply()
            null
        } else {
            // putString(key, null) remove a chave — uuid ausente é aceitável.
            prefs.edit()
                .putString(uuidKey, volume.uuid)
                .putString(pathKey, volume.path)
                .apply()
            Selection(volume.uuid, volume.path)
        }
    }

    /** O volume listado agora é o volume escolhido para LEITURA? */
    fun isBrowseVolumeSelected(volume: VolumeInfo): Boolean =
        matchesSelection(browseSel.value, volume)

    /** O volume listado agora é o volume escolhido para o COFRE? */
    fun isVaultVolumeSelected(volume: VolumeInfo): Boolean =
        matchesSelection(vaultSel.value, volume)

    private fun matchesSelection(sel: Selection?, volume: VolumeInfo): Boolean {
        sel ?: return false
        if (sel.uuid != null && volume.uuid != null) return sel.uuid == volume.uuid
        return sel.path == volume.path
    }

    /** Um caminho de volume está utilizável agora? (falso = cartão removido etc.) */
    fun isPathAvailable(path: String): Boolean {
        return try {
            val file = File(path)
            file.exists() && file.isDirectory && file.canRead()
        } catch (e: Exception) {
            false
        }
    }

    val isVaultRootAvailable: Boolean get() = isPathAvailable(vaultRoot)

    data class VolumeInfo(
        val uuid: String?,
        val path: String,
        val label: String,
        val isRemovable: Boolean,
        val isPrimary: Boolean,
        val isMounted: Boolean,
        val freeBytes: Long,
        val totalBytes: Long
    )

    /**
     * Lista os volumes de armazenamento realmente disponíveis no aparelho.
     * Volumes sem diretório (ex.: cartão removido) não entram na lista.
     */
    fun availableVolumes(context: Context): List<VolumeInfo> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()

        val volumes = mutableListOf<VolumeInfo>()
        for (volume in manager.storageVolumes) {
            val dir = volume.directory ?: continue
            val path = dir.absolutePath
            if (path.isBlank()) continue

            val stat = runCatching { StatFs(path) }.getOrNull()
            val label = runCatching { volume.getDescription(context) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: dir.name
            val uuid = runCatching { volume.uuid }.getOrNull()?.takeIf { it.isNotBlank() }

            volumes += VolumeInfo(
                uuid = uuid,
                path = path,
                label = label,
                isRemovable = volume.isRemovable,
                isPrimary = volume.isPrimary,
                isMounted = volume.state == Environment.MEDIA_MOUNTED,
                freeBytes = stat?.availableBytes ?: 0L,
                totalBytes = stat?.totalBytes ?: 0L
            )
        }

        return volumes
            .distinctBy { it.path }
            .sortedWith(compareByDescending<VolumeInfo> { it.isPrimary }.thenBy { it.label })
    }
}
