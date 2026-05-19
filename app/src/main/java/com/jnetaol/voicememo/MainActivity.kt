package com.jnetaol.voicememo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jnetaol.voicememo.ui.theme.VMBackground
import com.jnetaol.voicememo.ui.theme.VoiceMemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoiceMemoTheme {
                Surface(Modifier.fillMaxSize(), color = VMBackground) {
                    VoiceNavHost()
                }
            }
        }
    }
}
