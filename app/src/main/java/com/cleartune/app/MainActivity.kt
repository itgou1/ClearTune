package com.cleartune.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.cleartune.app.auth.AuthUiState
import com.cleartune.app.auth.AuthViewModel
import com.cleartune.app.library.MusicViewModel
import com.cleartune.app.player.PlayerViewModel
import com.cleartune.app.download.DownloadViewModel
import com.cleartune.app.settings.SettingsViewModel
import com.cleartune.core.designsystem.ClearTuneTheme
import com.cleartune.core.model.ServerProfile
import com.cleartune.core.datastore.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val authViewModel: AuthViewModel by viewModels()
    private val musicViewModel: MusicViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels()
    private val downloadViewModel: DownloadViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            ClearTuneTheme(darkTheme = darkTheme) {
                val state by authViewModel.state.collectAsStateWithLifecycle()
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val current = state) {
                        AuthUiState.Restoring -> RestoringScreen()
                        is AuthUiState.Login -> LoginScreen(
                            state = current,
                            onConnect = authViewModel::connect,
                        )
                        is AuthUiState.Connected -> ConnectedScreen(
                            profile = current.profile,
                            restoredOffline = current.restoredOffline,
                            musicViewModel = musicViewModel,
                            playerViewModel = playerViewModel,
                            downloadViewModel = downloadViewModel,
                            settingsViewModel = settingsViewModel,
                            onLogout = authViewModel::logout,
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        playerViewModel.persistNow()
        super.onStop()
    }
}

@Composable
private fun RestoringScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.loading_session))
    }
}

@Composable
private fun LoginScreen(
    state: AuthUiState.Login,
    onConnect: (String, String, String, Boolean) -> Unit,
) {
    var address by rememberSaveable(state.address) { mutableStateOf(state.address) }
    var username by rememberSaveable(state.username) { mutableStateOf(state.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var allowHttp by rememberSaveable(state.allowHttp) { mutableStateOf(state.allowHttp) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showHttpWarning by remember { mutableStateOf(false) }
    var showDevelopmentOptions by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 1.dp,
            ) {
                ClearTuneAppMark(Modifier.padding(12.dp).size(72.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.connect_server),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.connect_server_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(28.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_address)) },
                    placeholder = { Text(stringResource(R.string.server_address_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    enabled = !state.isConnecting,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    enabled = !state.isConnecting,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                        }
                    },
                    singleLine = true,
                    enabled = !state.isConnecting,
                )
                state.errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onConnect(address, username, password, allowHttp) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isConnecting,
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.testing_connection))
                    } else {
                        Text(stringResource(R.string.test_connection))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDevelopmentOptions = !showDevelopmentOptions }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.development_options), modifier = Modifier.weight(1f))
                    Icon(
                        if (showDevelopmentOptions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(showDevelopmentOptions) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.allow_http),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = allowHttp,
                            onCheckedChange = { enabled ->
                                if (enabled) showHttpWarning = true else allowHttp = false
                            },
                            enabled = !state.isConnecting,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.login_privacy_note),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showHttpWarning) {
        AlertDialog(
            onDismissRequest = { showHttpWarning = false },
            title = { Text(stringResource(R.string.http_warning_title)) },
            text = { Text(stringResource(R.string.http_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        allowHttp = true
                        showHttpWarning = false
                    },
                ) {
                    Text(stringResource(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHttpWarning = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectedScreen(
    profile: ServerProfile,
    restoredOffline: Boolean,
    musicViewModel: MusicViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    settingsViewModel: SettingsViewModel,
    onLogout: () -> Unit,
) {
    NotificationPermissionEffect()
    ClearTuneApp(
        profile,
        restoredOffline,
        musicViewModel,
        playerViewModel,
        downloadViewModel,
        settingsViewModel,
        onLogout,
    )
}

@Composable
private fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
