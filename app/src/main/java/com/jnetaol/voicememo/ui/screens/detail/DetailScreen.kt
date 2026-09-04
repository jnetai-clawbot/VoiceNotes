package com.jnetaol.voicememo.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.jnetaol.voicememo.data.model.Recording
import com.jnetaol.voicememo.ui.components.*
import com.jnetaol.voicememo.ui.screens.VoiceViewModel
import com.jnetaol.voicememo.ui.screens.home.formatTime
import com.jnetaol.voicememo.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(recordingId: Long, viewModel: VoiceViewModel, onNavigateBack: () -> Unit) {
    val recordings by viewModel.recordings.collectAsState()
    val recording = recordings.find { it.id == recordingId }
    val processingIds by viewModel.processingIds.collectAsState()
    val transcriptionStatus by viewModel.transcriptionStatus.collectAsState()
    val installSuggestion by viewModel.installSuggestion.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    if (recording == null) { Box(Modifier.fillMaxSize().background(VMBackground), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VMPrimary) }; return }

    val isProcessing = recording.id in processingIds

    var isEditingTags by remember { mutableStateOf(false) }
    var tagText by remember { mutableStateOf(recording.tags) }
    var isEditingTitle by remember { mutableStateOf(false) }
    var titleText by remember { mutableStateOf(recording.title) }

    Column(Modifier.fillMaxSize().background(VMBackground)) {
        Row(Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconButton(onNavigateBack) { Icon(Icons.Default.ArrowBack, null, tint = VMTextWhite) }
            Text("Recording", color = VMTextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton({ viewModel.toggleFavorite(recording.id, !recording.isFavorite) }) { Icon(if (recording.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (recording.isFavorite) VMError else VMTextSecondary) }
                IconButton({ viewModel.deleteRecording(recording); onNavigateBack() }) { Icon(Icons.Default.Delete, null, tint = VMTextMuted) }
            }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Title
            if (isEditingTitle) {
                OutlinedTextField(value = titleText, onValueChange = { titleText = it }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = VMTextWhite, unfocusedTextColor = VMTextWhite, focusedBorderColor = VMPrimary.copy(alpha = 0.5f), unfocusedBorderColor = VMSurfaceVariant, cursorColor = VMPrimary),
                    shape = RoundedCornerShape(12.dp))
                Row { TextButton({ isEditingTitle = false }) { Text("Cancel", color = VMTextMuted) }; TextButton({ viewModel.updateTags(recording.id, titleText); isEditingTitle = false }) { Text("Save", color = VMPrimary) } }
            } else {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(recording.title, color = VMTextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold); TextButton({ isEditingTitle = true }) { Text("Edit", color = VMTextMuted) }
                }
            }

            // Info cards
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                VMNeonCard(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("Duration", color = VMTextMuted, fontSize = 11.sp); Text(formatTime(recording.durationMs), color = VMSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
                VMNeonCard(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("Size", color = VMTextMuted, fontSize = 11.sp); Text("${recording.fileSizeBytes / 1024} KB", color = VMNeonGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold) } }
                VMNeonCard(Modifier.weight(1f)) { Column(Modifier.padding(12.dp)) { Text("Date", color = VMTextMuted, fontSize = 11.sp); Text(formatDate(recording.createdAt), color = VMTextSecondary, fontSize = 12.sp) } }
            }

            // Tags
            Spacer(Modifier.height(16.dp)); VMSectionHeader("Tags", if (isEditingTags) "Save" else "Edit") { if (isEditingTags) { viewModel.updateTags(recording.id, tagText) }; isEditingTags = !isEditingTags }
            if (isEditingTags) OutlinedTextField(value = tagText, onValueChange = { tagText = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("comma separated tags...", color = VMTextMuted) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = VMTextWhite, unfocusedTextColor = VMTextWhite, focusedBorderColor = VMPrimary.copy(alpha = 0.5f), unfocusedBorderColor = VMSurfaceVariant, cursorColor = VMPrimary), shape = RoundedCornerShape(12.dp))
            else if (recording.tags.isNotBlank()) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { recording.tags.split(",").filter { it.isNotBlank() }.forEach { VMStatusBadge(it.trim(), VMNeonPurple) } }

            // Transcription
            Spacer(Modifier.height(16.dp)); VMSectionHeader("Transcription")
            VMGlowButton(
                text = if (isProcessing) "Transcribing..." else "Transcribe",
                icon = if (isProcessing) Icons.Default.AccessTime else Icons.Default.GraphicEq,
                onClick = { viewModel.transcribeRecording(recording.id, recording.filePath, recording.language) },
                enabled = !isProcessing,
                glowColor = VMSecondary,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = VMCard)) {
                Text(recording.transcription.ifBlank { "No transcription yet. Tap Transcribe to convert this recording to text (tries Google directly, then offline, then speaker playback)." }, color = if (recording.transcription.isBlank()) VMTextMuted else VMTextPrimary, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (transcriptionStatus != null) {
                Text(transcriptionStatus.orEmpty(), color = if (isProcessing) VMPrimary else VMError, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }

            // Export
            Spacer(Modifier.height(16.dp)); VMSectionHeader("Export Options")
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                VMGlowButton("Copy Text", Icons.Default.ContentCopy, onClick = { clipboard.setText(AnnotatedString(recording.transcription.ifBlank { "No transcription" })); viewModel.showToast("Copied") }, glowColor = VMSecondary, modifier = Modifier.weight(1f))
                VMGlowButton("Share", Icons.Default.Share, onClick = {
                    val text = recording.transcription.ifBlank { "VoiceMemo: ${recording.title}" }
                    val intent = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, text); type = "text/plain" }
                    context.startActivity(Intent.createChooser(intent, null))
                }, glowColor = VMNeonPurple, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            VMGlowButton("Share Audio File", Icons.Default.Audiotrack, onClick = {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(recording.filePath))
                    val intent = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uri); type = "audio/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    context.startActivity(Intent.createChooser(intent, "Share Audio"))
                } catch (e: Exception) { viewModel.showToast("File not available") }
            }, glowColor = VMPrimary, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(32.dp))
        }

        // Bottom bar
        Surface(Modifier.fillMaxWidth(), color = VMSurface, tonalElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), Arrangement.SpaceEvenly) {
                VMGlowButton("Play Audio", Icons.Default.PlayArrow, onClick = {
                    try { val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(recording.filePath)), "audio/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(intent) }
                    catch (e: Exception) { viewModel.showToast("Cannot play file") }
                }, glowColor = VMNeonGreen)
                VMGlowButton("Delete", Icons.Default.Delete, onClick = { viewModel.deleteRecording(recording); onNavigateBack() }, glowColor = VMError)
            }
        }
    }

    installSuggestion?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissInstallSuggestion() },
            title = { Text("Transcription needs setup", color = VMTextWhite) },
            text = { Text(message, color = VMTextSecondary) },
            confirmButton = {
                TextButton({
                    viewModel.dismissInstallSuggestion()
                    val speechApp = context.packageManager.getLaunchIntentForPackage("com.google.android.tts")
                    if (speechApp != null) {
                        try { context.startActivity(speechApp) } catch (_: Exception) { openSpeechSettings(context) }
                    } else {
                        openSpeechSettings(context)
                    }
                }) { Text("Fix", color = VMSecondary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton({ viewModel.dismissInstallSuggestion() }) { Text("Later", color = VMTextSecondary) } },
            containerColor = VMSurface
        )
    }
}

private fun openSpeechSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
            } catch (_: Exception) {}
        }
    }
}

fun formatDate(timestamp: Long): String = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US).format(Date(timestamp))
