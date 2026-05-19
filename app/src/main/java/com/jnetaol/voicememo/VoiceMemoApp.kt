package com.jnetaol.voicememo

import android.app.Application
import com.jnetaol.voicememo.logger.VoiceLogger

class VoiceMemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VoiceLogger.init(this)
        VoiceLogger.i("VoiceMemoApp", "App started", "VM-APP-001", mapOf("version" to "1.0.0"))
    }
    override fun onTerminate() { VoiceLogger.i("VoiceMemoApp", "Terminating", "VM-APP-002"); VoiceLogger.shutdown(); super.onTerminate() }
}
