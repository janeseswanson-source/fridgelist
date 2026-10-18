package com.fridgelist.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fridgelist.app.navigation.FridgeListNavHost
import com.fridgelist.app.ui.theme.FridgeListTheme
import dagger.hilt.android.AndroidEntryPoint

/** AppCompatActivity so per-app language changes recreate the screen on every API level. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) app.onLink(intent?.dataString)
        setContent {
            FridgeListTheme {
                val start by app.startStep.collectAsStateWithLifecycle()
                // The session is read from disk in a few ms; the window background shows meanwhile.
                start?.let { FridgeListNavHost(start = it, app = app) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.onLink(intent.dataString)
    }
}
