package com.jnetaol.voicememo.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jnetaol.voicememo.BuildConfig
import com.jnetaol.voicememo.ui.components.*
import com.jnetaol.voicememo.ui.screens.UpdateCheckState
import com.jnetaol.voicememo.ui.screens.VoiceViewModel
import com.jnetaol.voicememo.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: VoiceViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestReleaseUrl by remember { mutableStateOf<String?>(null) }
    val updateCheck by viewModel.updateCheck.collectAsState()

    LaunchedEffect(updateCheck) {
        if (updateCheck is UpdateCheckState.UpdateAvailable) {
            latestReleaseUrl = (updateCheck as UpdateCheckState.UpdateAvailable).url
            showUpdateDialog = true
        }
    }

    Column(Modifier.fillMaxSize().background(VMBackground)) {
        Row(Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            IconButton(onNavigateBack) { Icon(Icons.Default.ArrowBack, null, tint = VMTextWhite) }
            Text("Settings", color = VMTextWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(48.dp))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            VMSectionHeader("Data")
            VMNeonCard {
                SettingsRow(Icons.Default.Delete, "Delete All Recordings", "Permanently remove all recordings") { showDeleteConfirmation = true }
            }

            Spacer(Modifier.height(16.dp))
            VMSectionHeader("Export")
            VMNeonCard {
                SettingsRow(Icons.Default.Description, "Export as Text", "Export all transcriptions to text file") {
                    val text = viewModel.exportAsText()
                    val intent = Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_TEXT, text); type = "text/plain" }
                    context.startActivity(Intent.createChooser(intent, "Export"))
                }
            }

            Spacer(Modifier.height(16.dp))
            VMSectionHeader("About")
            VMNeonCard {
                SettingsRow(Icons.Default.Info, "VoiceMemo v${BuildConfig.VERSION_NAME}", "Offline Voice Notes to Text", onClick = {})
                Divider(color = VMSurfaceVariant)
                SettingsRow(Icons.Default.Language, "Made By jnetai.com", "Visit our website") {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jnetai.com"))) } catch (_: Exception) {}
                }
                Divider(color = VMSurfaceVariant)
                SettingsRow(Icons.Default.SystemUpdateAlt, "Check For Updates", when (val s = updateCheck) {
                    is UpdateCheckState.Checking -> "Checking for updates..."
                    is UpdateCheckState.UpdateAvailable -> "Update available: v${s.latestVersion}"
                    is UpdateCheckState.UpToDate -> "You're up to date (v${s.latestVersion})"
                    is UpdateCheckState.Error -> "Check failed - tap to retry"
                    UpdateCheckState.Idle -> "Check for a newer version on GitHub"
                }, onClick = { viewModel.checkForUpdates() })
                Divider(color = VMSurfaceVariant)
                SettingsRow(Icons.Default.Share, "Share App", "Share latest release link") {
                    val intent = Intent(Intent.ACTION_SEND).apply {                         putExtra(Intent.EXTRA_TEXT, "Check out VoiceMemo: https://github.com/jnetai-clawbot/VoiceNotes/releases"); type = "text/plain" }
                    context.startActivity(Intent.createChooser(intent, "Share"))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete All?", color = VMTextWhite) },
            text = { Text("This permanently deletes all recordings and transcriptions.", color = VMTextSecondary) },
            confirmButton = { TextButton({ viewModel.deleteAllRecordings(); showDeleteConfirmation = false }) { Text("Delete All", color = VMError, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ showDeleteConfirmation = false }) { Text("Cancel", color = VMTextSecondary) } },
            containerColor = VMSurface)
    }

    if (showUpdateDialog) {
        val url = latestReleaseUrl ?: "https://github.com/jnetai-clawbot/VoiceNotes/releases/latest"
        AlertDialog(onDismissRequest = { showUpdateDialog = false },
            title = { Text("Update Available", color = VMTextWhite) },
            text = { Text("A new version of VoiceMemo is available. Update now?", color = VMTextSecondary) },
            confirmButton = { TextButton({
                showUpdateDialog = false
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }) { Text("Update", color = VMSecondary, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ showUpdateDialog = false }) { Text("Later", color = VMTextSecondary) } },
            containerColor = VMSurface)
    }
}

@Composable
fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = VMPrimary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, color = VMTextWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(subtitle, color = VMTextMuted, fontSize = 12.sp) }
        Icon(Icons.Default.ChevronRight, null, tint = VMTextMuted, modifier = Modifier.size(20.dp))
    }
}
