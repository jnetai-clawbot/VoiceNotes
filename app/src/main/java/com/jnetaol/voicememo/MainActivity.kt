package com.jnetaol.voicememo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jnetaol.voicememo.logger.VoiceLogger
import com.jnetaol.voicememo.ui.theme.VMBackground
import com.jnetaol.voicememo.ui.theme.VoiceMemoTheme

class MainActivity : ComponentActivity() {

    private val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        VoiceLogger.d("MainActivity", "Permission result: $allGranted", "VM-PERM-001")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        setContent {
            VoiceMemoTheme {
                Surface(Modifier.fillMaxSize(), color = VMBackground) {
                    VoiceNavHost()
                }
            }
        }
    }

    private fun requestPermissions() {
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
