package com.ideliver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.ideliver.ui.SettingsRoute

/**
 * Sole entry point for now. Hosts the settings screen, which is the only UI in
 * Phase 1: turn on notification access and export what the harness has captured.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsRoute()
            }
        }
    }
}
