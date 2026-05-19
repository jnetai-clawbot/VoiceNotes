package com.jnetaol.voicememo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jnetaol.voicememo.ui.components.VMGlowButton
import com.jnetaol.voicememo.ui.screens.VoiceViewModel
import com.jnetaol.voicememo.ui.screens.detail.DetailScreen
import com.jnetaol.voicememo.ui.screens.home.HomeScreen
import com.jnetaol.voicememo.ui.screens.settings.SettingsScreen
import com.jnetaol.voicememo.ui.theme.*
import kotlinx.coroutines.flow.collectLatest

@Composable
fun VoiceNavHost() {
    val navController = rememberNavController()
    val viewModel: VoiceViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Box {
        NavHost(navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToRecording = { id -> navController.navigate("detail/$id") },
                    onNavigateToSettings = { navController.navigate("settings") }
                )
            }
            composable("detail/{recordingId}", arguments = listOf(navArgument("recordingId") { type = NavType.LongType })) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("recordingId") ?: return@composable
                DetailScreen(recordingId = id, viewModel = viewModel, onNavigateBack = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel, onNavigateBack = { navController.popBackStack() })
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
