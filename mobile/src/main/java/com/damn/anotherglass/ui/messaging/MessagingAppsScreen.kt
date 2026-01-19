package com.damn.anotherglass.ui.messaging

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.damn.anotherglass.R
import com.damn.anotherglass.core.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val name: String,
    val icon: android.graphics.Bitmap?
)

class MessagingAppsViewModel(
    private val settings: Settings,
    private val packageManager: PackageManager
) : ViewModel() {

    var messagingApps by mutableStateOf<List<AppInfo>>(emptyList())
        private set

    var showAddDialog by mutableStateOf(false)

    var isLoading by mutableStateOf(false)
        private set

    init {
        loadMessagingApps()
    }

    fun loadMessagingApps() {
        viewModelScope.launch {
            isLoading = true
            messagingApps = withContext(Dispatchers.IO) {
                settings.messagingAppPackages.mapNotNull { packageName ->
                    try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        AppInfo(
                            packageName = packageName,
                            name = packageManager.getApplicationLabel(appInfo).toString(),
                            icon = packageManager.getApplicationIcon(packageName).toBitmap(48, 48)
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        // App not installed, keep in list anyway
                        AppInfo(
                            packageName = packageName,
                            name = packageName,
                            icon = null
                        )
                    }
                }.sortedBy { it.name }
            }
            isLoading = false
        }
    }

    fun removeApp(packageName: String) {
        val currentPackages = settings.messagingAppPackages.toMutableSet()
        currentPackages.remove(packageName)
        settings.messagingAppPackages = currentPackages
        loadMessagingApps()
    }

    fun showAddAppDialog() {
        showAddDialog = true
    }

    fun addApp(packageName: String) {
        val currentPackages = settings.messagingAppPackages.toMutableSet()
        currentPackages.add(packageName)
        settings.messagingAppPackages = currentPackages
        showAddDialog = false
        loadMessagingApps()
    }

    fun dismissAddDialog() {
        showAddDialog = false
    }

    companion object {
        fun Factory(settings: Settings, packageManager: PackageManager) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MessagingAppsViewModel(settings, packageManager) as T
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagingAppsScreen(
    navController: NavHostController,
    viewModel: MessagingAppsViewModel = viewModel(
        factory = MessagingAppsViewModel.Factory(
            Settings(LocalContext.current),
            LocalContext.current.packageManager
        )
    )
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messaging Apps") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.showAddAppDialog() },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, "Add app")
            }
        }
    ) { padding ->
        if (viewModel.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (viewModel.messagingApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No messaging apps configured.\nTap + to add apps.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(viewModel.messagingApps) { app ->
                    MessagingAppItem(
                        app = app,
                        onRemove = { viewModel.removeApp(app.packageName) }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (viewModel.showAddDialog) {
            AddAppDialog(
                onAppSelected = { viewModel.addApp(it) },
                onDismiss = { viewModel.dismissAddDialog() }
            )
        }
    }
}

@Composable
fun MessagingAppItem(
    app: AppInfo,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun AddAppDialog(
    onAppSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualPackageName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Messaging App") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = manualPackageName,
                    onValueChange = { manualPackageName = it },
                    label = { Text("Package Name") },
                    placeholder = { Text("com.example.app") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter the full package name of the app.\nYou can find this in Settings → Apps → App info.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { 
                    if (manualPackageName.isNotBlank()) {
                        onAppSelected(manualPackageName.trim())
                    }
                },
                enabled = manualPackageName.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
