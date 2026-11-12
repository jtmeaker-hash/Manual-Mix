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
import app.setflow.domain.LocalTrackRecord
import app.setflow.domain.RelinkCandidateEvaluation
import app.setflow.domain.TrackAvailabilityState
import java.util.Locale

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
fun SetFlowAppShell(
    viewModel: SetFlowShellViewModel,
    onLaunchSafMultiPicker: () -> Unit = {},
    onLaunchSafRelinkPicker: (String) -> Unit = {}
) {
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
                                else -> "SetFlow • Sets & Local Audio"
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
                    SetsAndLocalTracksScreen(
                        state = state,
                        onLaunchSafMultiPicker = onLaunchSafMultiPicker,
                        onLaunchSafRelinkPicker = onLaunchSafRelinkPicker,
                        onCancelImport = { viewModel.controller.cancelActiveImport() },
                        onVerifyAllTracks = { viewModel.controller.verifyAllTracks() },
                        onConfirmRelink = { viewModel.controller.confirmPendingRelink(userConfirmedUncertainMatch = true) },
                        onDismissRelink = { viewModel.controller.dismissPendingRelink() },
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
private fun SetsAndLocalTracksScreen(
    state: SetFlowShellUiState,
    onLaunchSafMultiPicker: () -> Unit,
    onLaunchSafRelinkPicker: (String) -> Unit,
    onCancelImport: () -> Unit,
    onVerifyAllTracks: () -> Unit,
    onConfirmRelink: () -> Unit,
    onDismissRelink: () -> Unit,
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
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Stage 03 — Local Audio Import, Identity & Relink",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Offline-first • Zero network permissions • Non-destructive SAF read access.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLaunchSafMultiPicker) {
                        Text("Import Audio Files (SAF)")
                    }
                    OutlinedButton(onClick = onVerifyAllTracks) {
                        Text("Verify Grants")
                    }
                }
                Text(
                    text = "Note: Two-deck audio playback, waveform preview, and Room set persistence are NOT IMPLEMENTED in Stage 03 (Stages 04–10). Audio is inactive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Import progress & recent batch outcomes
        val progress = state.importProgress
        if (progress.isImporting || progress.outcomes.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (progress.isImporting) {
                            "Importing (${progress.completedCount}/${progress.totalCount}): ${progress.currentDisplayName}"
                        } else if (progress.cancelled) {
                            "Last Import Batch Cancelled (${progress.completedCount}/${progress.totalCount})"
                        } else {
                            "Last Import Batch Complete (${progress.completedCount}/${progress.totalCount})"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (progress.isImporting) {
                        OutlinedButton(onClick = onCancelImport) {
                            Text("Cancel Import")
                        }
                    }
                    progress.outcomes.takeLast(5).forEach { outcome ->
                        Text(
                            text = "• ${outcome.displayName}: ${outcome.outcomeType} — ${outcome.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (outcome.errorCode != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Pending Relink Confirmation Banner
        val relinkEval = state.pendingRelinkEvaluation
        if (relinkEval != null) {
            RelinkEvaluationCard(
                evaluation = relinkEval,
                onConfirmRelink = onConfirmRelink,
                onDismissRelink = onDismissRelink
            )
        }

        // Imported Local Tracks Section
        Text(
            text = "Local Track Library (${state.tracks.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (state.tracks.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "No local tracks imported yet. Tap 'Import Audio Files (SAF)' to select DRM-free WAV, FLAC, MP3, M4A, or OGG files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            state.tracks.forEach { track ->
                LocalTrackRowCard(
                    track = track,
                    onRelinkClick = { onLaunchSafRelinkPicker(track.trackId) }
                )
            }
        }

        // Sets list placeholder section
        Text(
            text = "DJ Sets (Stage 04 Room Persistence Placeholder)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
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
private fun RelinkEvaluationCard(
    evaluation: RelinkCandidateEvaluation,
    onConfirmRelink: () -> Unit,
    onDismissRelink: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (evaluation) {
                is RelinkCandidateEvaluation.UncertainMatchRequiresConfirmation -> {
                    Text(
                        text = "Relink Confirmation Required",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = evaluation.reason,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirmRelink) {
                            Text("Confirm Relink")
                        }
                        OutlinedButton(onClick = onDismissRelink) {
                            Text("Cancel")
                        }
                    }
                }
                is RelinkCandidateEvaluation.RejectedCandidate -> {
                    Text(
                        text = "Relink Rejected (${evaluation.errorCode.code})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = evaluation.reason,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = onDismissRelink) {
                        Text("Dismiss")
                    }
                }
                is RelinkCandidateEvaluation.ExactMatch -> {
                    Text(
                        text = "Exact Fingerprint Match: ${evaluation.candidateDisplayName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Button(onClick = onConfirmRelink) {
                        Text("Apply Relink")
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalTrackRowCard(
    track: LocalTrackRecord,
    onRelinkClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${track.title} — ${track.artist}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${track.displayName} • ${formatDurationUs(track.durationUs)} • ${track.codecDescription} • ${track.sampleRateHz} Hz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "State: ${track.availabilityState} • Grant: ${track.identity.grantStatus} • FP: ${track.identity.contentFingerprint.take(28)}…",
                style = MaterialTheme.typography.bodySmall,
                color = if (track.availabilityState == TrackAvailabilityState.AVAILABLE) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            if (track.statusDetail.isNotEmpty()) {
                Text(
                    text = track.statusDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (track.availabilityState != TrackAvailabilityState.AVAILABLE) {
                OutlinedButton(onClick = onRelinkClick) {
                    Text("Track missing/changed — Choose Replacement to Relink")
                }
            }
        }
    }
}

private fun formatDurationUs(durationUs: Long): String {
    if (durationUs <= 0L) return "0:00.0"
    val totalTenths = durationUs / 100_000L
    val minutes = totalTenths / 600L
    val seconds = (totalTenths % 600L) / 10L
    val tenths = totalTenths % 10L
    return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
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
                    text = "NOT IMPLEMENTED IN STAGE 03",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = detail?.notImplementedBanner
                        ?: "Set Detail, Transition Editor, and Playback are not implemented yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Playback state: ${state.playbackState.phase} • Audio playing: ${state.playbackState.isAudioPlaying}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "No fake play/waveform/transition controls are shown until their audio and persistence gates pass in Stages 04–10.",
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
                    text = "Audio output sample-rate policy, transition defaults, and user-initiated diagnostic file export are not implemented in Stage 03.",
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
