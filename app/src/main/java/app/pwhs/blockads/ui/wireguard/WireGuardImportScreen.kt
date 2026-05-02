package app.pwhs.blockads.ui.wireguard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.WireGuardProfile
import app.pwhs.blockads.ui.wireguard.component.EmptyState
import app.pwhs.blockads.ui.wireguard.component.ProfileRow
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WireGuardImportScreen(
    onNavigateBack: () -> Unit,
    onEditProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WireGuardImportViewModel = koinViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isWgActive by viewModel.isWgActive.collectAsStateWithLifecycle()
    val splitDnsZones by viewModel.splitDnsZones.collectAsStateWithLifecycle()
    val excludeLan by viewModel.excludeLan.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<WireGuardProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<WireGuardProfile?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importFromUri(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is WireGuardUiEvent.ProfileImported ->
                    snackbarHostState.showSnackbar("Imported '${event.name}'")
                is WireGuardUiEvent.ProfileDeleted ->
                    snackbarHostState.showSnackbar("Deleted '${event.name}'")
                is WireGuardUiEvent.ProfileActivated ->
                    snackbarHostState.showSnackbar("Active: ${event.name}")
                is WireGuardUiEvent.ProfileRenamed ->
                    snackbarHostState.showSnackbar("Renamed")
                is WireGuardUiEvent.WireGuardToggled -> {
                    val msg = if (event.enabled) {
                        "WireGuard enabled. Restarting VPN…"
                    } else {
                        "WireGuard disabled. Restarting VPN…"
                    }
                    snackbarHostState.showSnackbar(msg)
                }
                is WireGuardUiEvent.HttpsFilteringDisabledForWg ->
                    snackbarHostState.showSnackbar("HTTPS filtering disabled (incompatible with WireGuard)")
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wireguard_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                text = { Text(stringResource(R.string.wireguard_import_button)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator()
            }

            if (profiles.isEmpty() && !isLoading) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else if (profiles.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        WireGuardToggleCard(
                            isWgActive = isWgActive,
                            onToggle = { viewModel.toggleWireGuard() },
                        )
                    }

                    items(profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == activeId,
                            onClick = { viewModel.setActiveProfile(profile.id) },
                            onEdit = { onEditProfile(profile.id) },
                            onRename = { renameTarget = profile },
                            onDelete = { deleteTarget = profile },
                        )
                    }

                    item {
                        SplitDnsCard(
                            value = splitDnsZones,
                            onValueChange = { viewModel.setSplitDnsZones(it) },
                        )
                    }

                    item {
                        ExcludeLanCard(
                            checked = excludeLan,
                            onCheckedChange = { viewModel.setExcludeLan(it) },
                        )
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameProfile(target.id, newName)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete '${target.name}'?") },
            text = { Text("This profile will be removed. The action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WireGuardToggleCard(
    isWgActive: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isWgActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.VpnLock,
                    contentDescription = null,
                    tint = if (isWgActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.wireguard_import_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (isWgActive) {
                            stringResource(R.string.wireguard_connect)
                        } else {
                            stringResource(R.string.wireguard_disconnect)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isWgActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Switch(
                checked = isWgActive,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SplitDnsCard(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.split_dns_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.split_dns_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("internal,local,lan,corp") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun ExcludeLanCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Lan,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.exclude_lan_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.exclude_lan_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun RenameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text != initialName,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
