/* SPDX-License-Identifier: GPL-3.0-only */
package app.setflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.setflow.BuildConfig

private val SetFlowDarkColors = darkColorScheme(
    primary = Color(0xFFFFA726),
    onPrimary = Color(0xFF0B0E14),
    secondary = Color(0xFF26C6DA),
    onSecondary = Color(0xFF0B0E14),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFF3F5F8),
    surface = Color(0xFF151A24),
    onSurface = Color(0xFFF3F5F8),
    surfaceVariant = Color(0xFF1F2735),
    onSurfaceVariant = Color(0xFFA5B0C2)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetFlowAppShell(viewModel: SetFlowShellViewModel) {
    val state by viewModel.uiState.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination?.route ?: SetFlowRoute.Sets.routePattern

    LaunchedEffect(state.currentRoute) {
        val target = state.currentRoute.routePattern
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                launchSingleTop = true
            }
        }
    }

    MaterialTheme(colorScheme = SetFlowDarkColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = when {
                                currentDestination.startsWith("set_detail/") -> "SetFlow • Set Detail (Placeholder)"
                                currentDestination == SetFlowRoute.Settings.routePattern -> "SetFlow • Settings (Placeholder)"
                                else -> "SetFlow • Sets (Placeholder)"
                            }
                        )
                    },
                    navigationIcon = {
                        if (currentDestination != SetFlowRoute.Sets.routePattern) {
                            IconButton(
                                onClick = {
                                    if (!viewModel.controller.navigateBack()) {
                                        viewModel.controller.openSets()
                                    }
                                    navController.popBackStack()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Navigate back"
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentDestination == SetFlowRoute.Sets.routePattern || currentDestination.startsWith("set_detail/"),
                        onClick = {
                            viewModel.controller.openSets()
                            navController.navigate(SetFlowRoute.Sets.routePattern) {
                                popUpTo(SetFlowRoute.Sets.routePattern) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Sets") },
                        label = { Text("Sets") }
                    )
                    NavigationBarItem(
                        selected = currentDestination == SetFlowRoute.Settings.routePattern,
                        onClick = {
                            viewModel.controller.openSettings()
                            navController.navigate(SetFlowRoute.Settings.routePattern) {
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = SetFlowRoute.Sets.routePattern,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                composable(SetFlowRoute.Sets.routePattern) {
                    SetsPlaceholderScreen(
                        state = state,
                        onOpenSetDetail = { setId ->
                            viewModel.controller.openSetDetail(setId)
                            navController.navigate(SetFlowRoute.SetDetail(setId).routePattern)
                        },
                        onOpenSettings = {
                            viewModel.controller.openSettings()
                            navController.navigate(SetFlowRoute.Settings.routePattern)
                        }
                    )
                }
                composable(
                    route = SetFlowRoute.SetDetail.PATTERN,
                    arguments = listOf(navArgument(SetFlowRoute.SetDetail.ARG_SET_ID) { type = NavType.StringType })
                ) { entry ->
                    val setId = entry.arguments?.getString(SetFlowRoute.SetDetail.ARG_SET_ID) ?: "sample-shell-set-01"
                    SetDetailPlaceholderScreen(
                        setId = setId,
                        state = state,
                        onBackToSets = {
                            viewModel.controller.openSets()
                            navController.popBackStack(SetFlowRoute.Sets.routePattern, inclusive = false)
                        }
                    )
                }
                composable(SetFlowRoute.Settings.routePattern) {
                    SettingsPlaceholderScreen(state = state)
                }
            }
        }
    }
}

@Composable
private fun SetsPlaceholderScreen(
    state: SetFlowShellUiState,
    onOpenSetDetail: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Stage 02 — Standalone Local-Only Shell",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Offline-first • Zero network permissions • No Spotify login, WebView, or extensions.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Status: Set creation, SAF local audio import, and Room persistence are NOT IMPLEMENTED yet (Stages 03–04). Audio playback is inactive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.sets.forEach { setItem ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Placeholder set ${setItem.title}" }
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = setItem.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Tracks: ${setItem.trackCount} • Revision: ${setItem.revision}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = setItem.statusNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { onOpenSetDetail(setItem.setId) }) {
                        Text("Inspect Set Detail Placeholder")
                    }
                }
            }
        }

        OutlinedButton(onClick = onOpenSettings) {
            Text("Open Settings & License Notices")
        }
    }
}

@Composable
private fun SetDetailPlaceholderScreen(
    setId: String,
    state: SetFlowShellUiState,
    onBackToSets: () -> Unit
) {
    val detail = state.activeSetDetail
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = detail?.title ?: "Set Detail ($setId)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "NOT IMPLEMENTED IN STAGE 02",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = detail?.notImplementedBanner
                        ?: "Set Detail, Track Import, Transition Editor, and Playback are not implemented yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Playback state: ${state.playbackState.phase} • Audio playing: ${state.playbackState.isAudioPlaying}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "No fake play/waveform/transition controls are shown until their audio and persistence gates pass in Stages 03–10.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(onClick = onBackToSets) {
            Text("Back to Sets")
        }
    }
}

@Composable
private fun SettingsPlaceholderScreen(state: SetFlowShellUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Application & Service Boundary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Application ID", style = MaterialTheme.typography.bodySmall)
                    Text(BuildConfig.APPLICATION_ID, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Version", style = MaterialTheme.typography.bodySmall)
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Offline-Only Mode", style = MaterialTheme.typography.bodySmall)
                    Text("${state.offlineOnlyEnforced} (No INTERNET perm)", style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Account Required", style = MaterialTheme.typography.bodySmall)
                    Text("${state.accountRequired}", style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Beat This! ONNX Asset", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (state.analysisStatus.modelAssetPresent) "Present (${state.analysisStatus.modelAssetBytes / 1024} KB)" else "Missing",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Settings Placeholder (Not Implemented Yet)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Audio output sample-rate policy, transition defaults, and user-initiated diagnostic file export are not implemented in Stage 02.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "GPL-3.0 & Open-Source Attribution",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• Local analysis & Segue DSP engine (:automix, SeguePlan, SegueValidator, SegueDeck, SegueProcessor, TrackDecoder) derived from Myspoti v2.4.0 (https://github.com/kota-zo/myspoti) under GNU GPL v3.0.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• EqualizerProcessor adapted from BitChord (https://github.com/kushagrasinghx/BitChord) under GNU GPL v3.0-or-later.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "• Beat This! small0 beat/downbeat model (CPJKU, MIT License) on ONNX Runtime (MIT License).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
