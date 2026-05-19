package com.jnetaol.voicememo.ui.screens.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.ui.components.*
import com.jnetaol.voicememo.ui.screens.VoiceViewModel
import com.jnetaol.voicememo.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VoiceViewModel,
    onNavigateToRecording: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val recordings by viewModel.recordings.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingTime by viewModel.recordingTime.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(VMBackground)) {
        Row(Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("VoiceMemo", color = VMTextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("Offline Voice Notes", color = VMTextMuted, fontSize = 13.sp) }
            IconButton(onNavigateToSettings) { Icon(Icons.Default.Settings, null, tint = VMTextSecondary) }
        }

        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it; viewModel.searchRecordings(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Search recordings...", color = VMTextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = VMTextMuted) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton({ searchQuery = ""; viewModel.searchRecordings("") }) { Icon(Icons.Default.Clear, null, tint = VMTextMuted) } },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = VMTextWhite, unfocusedTextColor = VMTextWhite, focusedBorderColor = VMPrimary.copy(alpha = 0.5f), unfocusedBorderColor = VMSurfaceVariant, cursorColor = VMPrimary),
            shape = RoundedCornerShape(12.dp), singleLine = true)

        // Recording status bar
        AnimatedVisibility(visible = isRecording) {
            Surface(Modifier.fillMaxWidth(), color = VMError.copy(alpha = 0.15f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VMRecordingWaveAnimation(isActive = true)
                        Spacer(Modifier.width(12.dp))
                        Column { Text("Recording...", color = VMError, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(formatTime(recordingTime), color = VMTextSecondary, fontSize = 13.sp) }
                    }
                    Row {
                        IconButton({ viewModel.pauseRecording() }) { Icon(Icons.Default.Pause, "Pause", tint = VMNeonOrange, modifier = Modifier.size(28.dp)) }
                        IconButton({
                            viewModel.stopRecording()
                            viewModel.showToast("Recording saved")
                        }) { Icon(Icons.Default.Stop, "Stop", tint = VMError, modifier = Modifier.size(28.dp)) }
                    }
                }
            }
        }

        if (recordings.isEmpty()) {
            VMEmptyState(Icons.Default.Mic, "No Recordings", "Tap the mic to start recording")
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recordings, key = { it.id }) { recording -> RecordingCard(recording, onClick = { onNavigateToRecording(recording.id) }, onDelete = { viewModel.deleteRecording(recording) }, onToggleFavorite = { viewModel.toggleFavorite(recording.id, !recording.isFavorite) }) }
            }
        }

        if (!isRecording) {
            Surface(Modifier.fillMaxWidth(), color = VMSurface, tonalElevation = 8.dp) {
                Box(Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(72.dp).shadow(20.dp, CircleShape, ambientColor = VMPrimary.copy(alpha = 0.6f), spotColor = VMPrimary.copy(alpha = 0.6f)).clip(CircleShape).background(Brush.radialGradient(listOf(VMPrimaryVariant, VMPrimary, VMPrimary))), contentAlignment = Alignment.Center) {
                        IconButton({ viewModel.startRecording() }, Modifier.size(56.dp)) { Icon(Icons.Default.Mic, "Record", tint = VMTextWhite, modifier = Modifier.size(32.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingCard(recording: Recording, onClick: () -> Unit, onDelete: () -> Unit, onToggleFavorite: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = VMCard), border = androidx.compose.foundation.BorderStroke(1.dp, VMSurfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            VMStatusBadge(recording.title.take(2).uppercase(), color = VMNeonCyan, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(recording.title, color = VMTextWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatTime(recording.durationMs), color = VMTextMuted, fontSize = 13.sp)
                if (recording.transcription.isNotBlank()) Text(recording.transcription.take(60) + "...", color = VMTextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onToggleFavorite, Modifier.size(36.dp)) { Icon(if (recording.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (recording.isFavorite) VMError else VMTextMuted, modifier = Modifier.size(18.dp)) }
            IconButton(onDelete, Modifier.size(36.dp)) { Icon(Icons.Default.Delete, null, tint = VMTextMuted, modifier = Modifier.size(18.dp)) }
        }
    }
}

fun formatTime(ms: Long): String {
    val secs = ms / 1000; val mins = secs / 60; val remaining = secs % 60
    return "%02d:%02d".format(mins, remaining)
}
