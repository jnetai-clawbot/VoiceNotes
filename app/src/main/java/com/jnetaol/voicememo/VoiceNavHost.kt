package com.jnetaol.voicememo

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jnetaol.voicememo.ui.screens.VoiceViewModel
import com.jnetaol.voicememo.ui.screens.detail.DetailScreen
import com.jnetaol.voicememo.ui.screens.home.HomeScreen
import com.jnetaol.voicememo.ui.screens.settings.SettingsScreen

@Composable
fun VoiceNavHost() {
    val navController = rememberNavController()
    val viewModel: VoiceViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

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
}
