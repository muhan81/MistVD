package com.nkls.nekovideo.components.helpers.storage

import android.content.Context
import android.util.Log
import com.nkls.nekovideo.components.helpers.FolderLockManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Migra o cofre (`<volume>/NekoVideo`) de um volume para outro, INTEIRO.
 *
 * Por que a pasta inteira e não arquivo por arquivo: o `.neko_manifest.enc`
 * (mapa de nomes originais) e o `.neko_locked` (salt) ficam DENTRO de cada pasta
 * bloqueada. Mover só os vídeos sem eles = nomes originais perdidos para sempre.
 *
 * Ordem de segurança (a fonte antiga é autoridade até o fim):
 *  1. copia antigo → novo (o antigo NUNCA é tocado durante a cópia);
 *  2. confere árvore inteira (contagem de arquivos, pastas e bytes) — se não bater,
 *     apaga a cópia e aborta com os dados antigos intactos;
 *  3. reescreve registro de pastas bloqueadas / sessões de reprodução / caminho
 *     customizado (FolderLockManager.onLockedFolderMoved) apontando para o novo local;
 *  4. troca a seleção de volume (StorageRoot);
 *  5. só então apaga o diretório antigo (falha aqui deixa lixo no volume antigo,
 *     nunca perda de dado — a cópia nova já foi verificada).
 *
 * Rodar em Dispatchers.IO — vídeos grandes levam minutos.
 */
object VaultMigrator {

    private const val TAG = "VaultMigrator"

    /** Marcadores que não contam como "conteúdo" do cofre. */
    private const val VAULT_MARKER = ".nekovideo"
    private const val NOMEDIA_MARKER = ".nomedia"

    enum class Status {
        /** Copiado, verificado, registro reescrito, antigo removido. */
        SUCCESS,

        /** Já existe uma pasta de cofre com conteúdo no volume de destino. */
        TARGET_EXISTS,

        /** Falha na cópia (sem espaço, volume sumido, I/O). Antigo intacto. */
        COPY_FAILED,

        /** Cópia concluiu mas a conferência não bateu; cópia descartada. Antigo intacto. */
        VERIFY_FAILED
    }

    private data class TreeStats(val fileCount: Long, val dirCount: Long, val totalBytes: Long)

    /**
     * Há algo a migrar? Marcadores (.nekovideo / .nomedia) sozinhos não contam.
     * Volume desplugado = pasta inexistente = falso (nada a fazer, só trocar).
     */
    fun oldVaultHasContent(): Boolean {
        val old = File(StorageRoot.vaultPath)
        if (!old.isDirectory) return false
        val entries = old.listFiles() ?: return false
        return entries.any { it.name != VAULT_MARKER && it.name != NOMEDIA_MARKER }
    }

    /**
     * Executa a migração para o volume escolhido. Bloqueante — chamar fora da UI thread.
     */
    fun migrate(context: Context, targetVolume: StorageRoot.VolumeInfo): Status {
        val oldVault = File(StorageRoot.vaultPath) // ler ANTES de trocar a seleção
        val newVault = File(targetVolume.path, StorageRoot.VAULT_FOLDER_NAME)

        if (!oldVault.isDirectory) {
            // Nada no volume antigo (já migrado, apagado ou volume ausente):
            // apenas assume o novo volume.
            StorageRoot.setVaultRoot(context, targetVolume)
            return Status.SUCCESS
        }

        val leftovers = newVault.listFiles()
        if (newVault.exists() && leftovers?.isNotEmpty() == true) {
            Log.e(TAG, "Target already has content: $newVault")
            return Status.TARGET_EXISTS
        }

        // 1) copiar (antigo intocado durante toda a cópia)
        try {
            if (newVault.exists()) newVault.deleteRecursively() // sobra vazia de tentativa anterior
            copyTree(oldVault, newVault)
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: $oldVault -> $newVault", e)
            runCatching { newVault.deleteRecursively() }
            return Status.COPY_FAILED
        }

        // 2) conferir árvore inteira antes de qualquer troca de estado
        val src = stats(oldVault)
        val dst = stats(newVault)
        if (src != dst) {
            Log.e(TAG, "Verify failed: src=$src dst=$dst")
            runCatching { newVault.deleteRecursively() }
            return Status.VERIFY_FAILED
        }

        // 3) registro de pastas bloqueadas / sessões / caminho custom → novo local
        FolderLockManager.onLockedFolderMoved(context, oldVault.absolutePath, newVault.absolutePath)

        // 4) trocar a seleção (a partir daqui StorageRoot.vaultPath aponta para o novo volume)
        StorageRoot.setVaultRoot(context, targetVolume)

        // 5) remover o antigo (falha = lixo no volume antigo, sem perda de dado)
        if (!oldVault.deleteRecursively()) {
            Log.w(TAG, "Old vault was not fully removed: ${oldVault.absolutePath}")
        }

        Log.i(TAG, "Vault migrated: $oldVault -> $newVault ($src)")
        return Status.SUCCESS
    }

    private fun copyTree(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) throw IOException("mkdirs failed: $dst")
            val children = src.listFiles() ?: throw IOException("listFiles failed: $src")
            for (child in children) {
                copyTree(child, File(dst, child.name))
            }
        } else {
            src.inputStream().use { input ->
                FileOutputStream(dst).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun stats(root: File): TreeStats {
        var files = 0L
        var dirs = 0L
        var bytes = 0L
        root.walkTopDown().forEach { f ->
            if (f.isDirectory) dirs++ else {
                files++
                bytes += f.length()
            }
        }
        return TreeStats(files, dirs, bytes)
    }
}
