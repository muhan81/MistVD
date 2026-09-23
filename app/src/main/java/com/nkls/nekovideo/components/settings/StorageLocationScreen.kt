package com.nkls.nekovideo.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nkls.nekovideo.R
import com.nkls.nekovideo.components.helpers.SortRowMessageCenter
import com.nkls.nekovideo.components.helpers.storage.StorageRoot
import com.nkls.nekovideo.components.helpers.storage.VaultMigrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Configurações → Armazenamento → Local de armazenamento.
 *
 * Permite escolher em qual volume o app LÊ os vídeos e em qual volume ele GRAVA o cofre.
 * Existe porque, em aparelhos onde o sistema usa o cartão de memória como armazenamento
 * padrão, a raiz da lista e o caminho do cofre divergiam — e o conteúdo do cofre
 * desaparecia da interface.
 *
 * Os dois seletores são independentes: dá para ler do cartão e guardar o cofre no
 * armazenamento interno, ou qualquer outra combinação.
 *
 * A escolha é salva por UUID do volume + último caminho (StorageRoot cuida disso);
 * a verificação de "qual linha está marcada" também vai pelo UUID.
 *
 * Migração (2ª fase): ao trocar o volume do COFRE com conteúdo no cofre antigo,
 * o app pergunta o que fazer — mover tudo (VaultMigrator, com verificação), deixar
 * os dados onde estão, ou cancelar. Trocar o volume de LEITURA nunca pergunta.
 */
@Composable
fun StorageLocationScreen() {
    val context = LocalContext.current

    // Lista de volumes: recalculada a cada entrada na tela.
    val volumes = remember { StorageRoot.availableVolumes(context) }

    val browseIsCustom = StorageRoot.isBrowseRootCustom
    val vaultIsCustom = StorageRoot.isVaultRootCustom
    val systemRoot = StorageRoot.systemRoot()

    // Caminhos atuais (para o aviso de indisponibilidade).
    val currentBrowseRoot = StorageRoot.browseRoot
    val currentVaultRoot = StorageRoot.vaultRoot

    // ---------- Migração do cofre ----------
    var pendingVaultTarget by remember { mutableStateOf<StorageRoot.VolumeInfo?>(null) }
    var migrating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun saveVaultSelection(target: StorageRoot.VolumeInfo?) {
        StorageRoot.setVaultRoot(context, target)
        SortRowMessageCenter.showSuccess(
            context.getString(R.string.storage_location_saved)
        )
    }

    fun requestVaultChange(target: StorageRoot.VolumeInfo?) {
        val newRoot = target?.path ?: StorageRoot.systemRoot()
        // Mesmo volume de agora: nada a migrar, só salvar.
        if (File(newRoot).absolutePath == File(StorageRoot.vaultRoot).absolutePath) {
            saveVaultSelection(target)
            return
        }
        // Cofre antigo tem conteúdo? Perguntar. Senão, trocar direto.
        if (VaultMigrator.oldVaultHasContent()) {
            pendingVaultTarget = target
        } else {
            saveVaultSelection(target)
        }
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
            // ---------- Onde ler os vídeos ----------
            item {
                SettingsSectionHeader(
                    stringResource(R.string.storage_location_browse_section),
                    isCompact
                )
            }

            item {
                LocationRow(
                    title = stringResource(R.string.storage_location_follow_system),
                    subtitle = systemRoot,
                    icon = Icons.Default.Folder,
                    selected = !browseIsCustom,
                    isCompact = isCompact,
                    onClick = {
                        StorageRoot.setBrowseRoot(context, null)
                        SortRowMessageCenter.showSuccess(
                            context.getString(R.string.storage_location_saved)
                        )
                    }
                )
            }

            items(volumes.size) { index ->
                val volume = volumes[index]
                LocationRow(
                    title = volumeTitle(volume),
                    subtitle = volumeSubtitle(volume),
                    icon = Icons.Default.Storage,
                    selected = StorageRoot.isBrowseVolumeSelected(volume),
                    isCompact = isCompact,
                    onClick = {
                        StorageRoot.setBrowseRoot(context, volume)
                        SortRowMessageCenter.showSuccess(
                            context.getString(R.string.storage_location_saved)
                        )
                    }
                )
            }

            if (browseIsCustom && volumes.none { it.path == currentBrowseRoot }) {
                item { UnavailableNotice(currentBrowseRoot) }
            }

            item { Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp)) }

            // ---------- Onde guardar o cofre ----------
            item {
                SettingsSectionHeader(
                    stringResource(R.string.storage_location_vault_section),
                    isCompact
                )
            }

            item {
                LocationRow(
                    title = stringResource(R.string.storage_location_follow_system),
                    subtitle = systemRoot,
                    icon = Icons.Default.Folder,
                    selected = !vaultIsCustom,
                    isCompact = isCompact,
                    onClick = { requestVaultChange(null) }
                )
            }

            items(volumes.size) { index ->
                val volume = volumes[index]
                LocationRow(
                    title = volumeTitle(volume),
                    subtitle = volumeSubtitle(volume),
                    icon = Icons.Default.Storage,
                    selected = StorageRoot.isVaultVolumeSelected(volume),
                    isCompact = isCompact,
                    onClick = { requestVaultChange(volume) }
                )
            }

            if (vaultIsCustom && volumes.none { it.path == currentVaultRoot }) {
                item { UnavailableNotice(currentVaultRoot) }
            }

            item {
                Text(
                    text = stringResource(R.string.storage_location_fat32_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (isCompact) 6.dp else 12.dp)
                )
            }
        }

        // ---------- Diálogo: o que fazer com o cofre antigo ----------
        if (!migrating) {
            pendingVaultTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingVaultTarget = null },
                    title = { Text(stringResource(R.string.storage_migration_title)) },
                    text = { Text(stringResource(R.string.storage_migration_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            migrating = true
                            scope.launch(Dispatchers.IO) {
                                val status = VaultMigrator.migrate(context, target)
                                withContext(Dispatchers.Main) {
                                    migrating = false
                                    pendingVaultTarget = null
                                    when (status) {
                                        VaultMigrator.Status.SUCCESS ->
                                            SortRowMessageCenter.showSuccess(
                                                context.getString(R.string.storage_migration_success)
                                            )
                                        VaultMigrator.Status.TARGET_EXISTS ->
                                            SortRowMessageCenter.showError(
                                                context.getString(R.string.storage_migration_target_exists)
                                            )
                                        else ->
                                            SortRowMessageCenter.showError(
                                                context.getString(R.string.storage_migration_failed)
                                            )
                                    }
                                }
                            }
                        }) {
                            Text(stringResource(R.string.storage_migration_migrate))
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                pendingVaultTarget = null
                                saveVaultSelection(target)
                            }) {
                                Text(stringResource(R.string.storage_migration_keep))
                            }
                            TextButton(onClick = { pendingVaultTarget = null }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                )
            }
        }

        // ---------- Diálogo: migração em andamento ----------
        if (migrating) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.storage_migration_running)) },
                text = {
                    Column {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.storage_migration_running_hint))
                    }
                },
                confirmButton = {},
                dismissButton = {}
            )
        }
    }
}

@Composable
private fun LocationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    isCompact: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(if (isCompact) 10.dp else 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.storage_location_current),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun UnavailableNotice(path: String) {
    Text(
        text = stringResource(R.string.storage_location_unavailable) + "\n" + path,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun volumeTitle(volume: StorageRoot.VolumeInfo): String {
    return if (volume.isRemovable) {
        stringResource(R.string.storage_location_removable)
    } else {
        stringResource(R.string.storage_location_internal)
    }
}

@Composable
private fun volumeSubtitle(volume: StorageRoot.VolumeInfo): String {
    val space = if (volume.totalBytes > 0) {
        stringResource(
            R.string.storage_location_free_space,
            formatBytes(volume.freeBytes),
            formatBytes(volume.totalBytes)
        )
    } else {
        ""
    }
    return if (space.isBlank()) volume.path else "${volume.label}\n${volume.path}\n$space"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
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

@Composable
private fun SettingsSectionHeader(title: String, isCompact: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = 4.dp,
            top = if (isCompact) 8.dp else 16.dp,
            bottom = 2.dp
        )
    )
}
