package com.offlinevoice.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinevoice.app.pipeline.PipelineState
import com.offlinevoice.app.pipeline.VoicePipelineManager

class MainActivity : ComponentActivity() {

    private lateinit var pipelineManager: VoicePipelineManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipelineManager = VoicePipelineManager(applicationContext)

        setContent {
            OfflineVoiceAppTheme {
                VoiceAppScreen(pipelineManager = pipelineManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pipelineManager.release()
    }
}

@Composable
fun OfflineVoiceAppTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF00E5FF),
        onPrimary = Color(0xFF00363D),
        primaryContainer = Color(0xFF004F58),
        onPrimaryContainer = Color(0xFF97F0FF),
        secondary = Color(0xFF00E676),
        onSecondary = Color(0xFF003919),
        background = Color(0xFF111418),
        surface = Color(0xFF191C20),
        surfaceVariant = Color(0xFF23272E),
        onSurface = Color(0xFFE2E2E6),
        onSurfaceVariant = Color(0xFFC3C7CF)
    )

    MaterialTheme(
        colorScheme = darkColors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAppScreen(pipelineManager: VoicePipelineManager) {
    val context = LocalContext.current
    val uiState by pipelineManager.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            pipelineManager.startListening()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice app", Toast.LENGTH_LONG).show()
        }
    }

    var editorKeywords by remember(uiState.keywordsText) {
        mutableStateOf(uiState.keywordsText)
    }
    var showEditor by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Offline Voice",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Header Badge
            StatusBadgeCard(state = uiState.state, rms = uiState.currentRms)

            // Audio Level RMS Meter
            AudioLevelMeter(
                rms = uiState.currentRms,
                isActive = uiState.state != PipelineState.IDLE
            )

            // Main Controls
            ActionButtonsRow(
                state = uiState.state,
                hasPermission = hasMicPermission,
                onStart = {
                    if (hasMicPermission) {
                        pipelineManager.startListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = {
                    pipelineManager.stopListening()
                },
                onManualTrigger = {
                    if (hasMicPermission) {
                        pipelineManager.triggerManualTranscription()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )

            // Stage 1: Wakeword Result Card
            WakewordResultCard(
                wakewordLabel = uiState.lastWakewordLabel,
                rawKeyword = uiState.lastWakeword,
                stage1LatencyMs = uiState.stage1LatencyMs,
                isManual = uiState.isManualTrigger
            )

            // Stage 2: Offline Transcription Card
            TranscriptionResultCard(
                transcription = uiState.lastTranscription,
                stage2LatencyMs = uiState.stage2LatencyMs,
                stage1LatencyMs = uiState.stage1LatencyMs,
                utteranceDuration = uiState.utteranceDurationSeconds,
                isTranscribing = uiState.state == PipelineState.TRANSCRIBING
            )

            // Stage 1 Keywords Editor Card
            KeywordsEditorCard(
                isExpanded = showEditor,
                onToggleExpand = { showEditor = !showEditor },
                keywordsText = editorKeywords,
                onTextChange = { editorKeywords = it },
                onSave = {
                    val ok = pipelineManager.reloadKeywords(editorKeywords)
                    if (ok) {
                        Toast.makeText(context, "Keywords reloaded successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to reload keywords", Toast.LENGTH_SHORT).show()
                    }
                },
                onReset = {
                    val defaultText = pipelineManager.resetKeywords()
                    editorKeywords = defaultText
                    Toast.makeText(context, "Reset to default keywords", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatusBadgeCard(state: PipelineState, rms: Float) {
    val (statusText, badgeColor, icon) = when (state) {
        PipelineState.IDLE -> Triple("Pipeline Idle (Stopped)", Color(0xFF757575), Icons.Default.PauseCircle)
        PipelineState.LISTENING_FOR_WAKEWORD -> Triple("Stage 1: Always-On Wakeword Listening", Color(0xFF00E5FF), Icons.Default.Hearing)
        PipelineState.RECORDING_UTTERANCE -> Triple("Stage 2: Capturing Utterance (RMS Endpointing)", Color(0xFFFF9100), Icons.Default.GraphicEq)
        PipelineState.TRANSCRIBING -> Triple("Stage 2: Offline Whisper Transcription", Color(0xFF00E676), Icons.Default.Psychology)
    }

    val animatedColor by animateColorAsState(targetValue = badgeColor, label = "badgeColor")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(animatedColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AudioLevelMeter(rms: Float, isActive: Boolean) {
    val normalizedRms = if (isActive) (rms * 15f).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Microphone Level (RMS)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format("%.4f", rms),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { normalizedRms },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (rms > 0.015f) Color(0xFF00E676) else Color(0xFF00E5FF),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
fun ActionButtonsRow(
    state: PipelineState,
    hasPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onManualTrigger: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state == PipelineState.IDLE) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start Pipeline", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop Pipeline", fontWeight = FontWeight.Bold)
            }
        }

        OutlinedButton(
            onClick = onManualTrigger,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Test Trigger", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun WakewordResultCard(
    wakewordLabel: String?,
    rawKeyword: String?,
    stage1LatencyMs: Long?,
    isManual: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFFFD600),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Stage 1: Wakeword (Zipformer KWS)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                if (stage1LatencyMs != null) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                if (isManual) "Manual" else "${stage1LatencyMs} ms",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF2C2F36),
                            labelColor = Color(0xFFFFD600)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (wakewordLabel != null) {
                Text(
                    text = wakewordLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!rawKeyword.isNullOrBlank() && rawKeyword != wakewordLabel) {
                    Text(
                        text = "Tokens: $rawKeyword",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Text(
                    text = "Awaiting wakeword trigger (say \"Hey Siri\", \"Hello World\", \"Alexa\", or tap Test Trigger)...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TranscriptionResultCard(
    transcription: String,
    stage2LatencyMs: Long?,
    stage1LatencyMs: Long?,
    utteranceDuration: Float?,
    isTranscribing: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Stage 2: Transcription (Whisper.cpp)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                if (stage2LatencyMs != null) {
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                "${stage2LatencyMs} ms",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF1B3826),
                            labelColor = Color(0xFF00E676)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isTranscribing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF00E676)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Transcribing audio offline with ggml-tiny.en-q5_1...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF00E676)
                    )
                }
            } else if (transcription.isNotBlank()) {
                Text(
                    text = "\"$transcription\"",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (utteranceDuration != null || stage2LatencyMs != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (utteranceDuration != null) {
                            Text(
                                text = "Audio: ${String.format("%.1f", utteranceDuration)}s (incl. 1s pre-roll)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (stage1LatencyMs != null && stage2LatencyMs != null) {
                            Text(
                                text = "Total Latency: ${stage1LatencyMs + stage2LatencyMs} ms",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Offline transcribed speech will appear here after wakeword detection or test trigger.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun KeywordsEditorCard(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    keywordsText: String,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Keywords Configuration (Stage 1)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand"
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Editable keywords for Sherpa-ONNX Zipformer KWS. Note: @Label suffixes must not contain spaces (use underscores like @Hello_World, @Hey_Siri, @Alexa).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = keywordsText,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 220.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onSave,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save & Reload")
                        }

                        OutlinedButton(
                            onClick = onReset,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Default")
                        }
                    }
                }
            }
        }
    }
}
