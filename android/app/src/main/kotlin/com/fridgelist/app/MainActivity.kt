package com.fridgelist.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fridgelist.app.widget.WidgetSnapshotWriter
import com.fridgelist.app.navigation.FridgeListNavHost
import com.fridgelist.app.ui.theme.FridgeListTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** AppCompatActivity so per-app language changes recreate the screen on every API level. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val app: AppViewModel by viewModels()

    @Inject lateinit var widgets: WidgetSnapshotWriter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) app.onIntent(intent)
        // CONTRACT §7: the snapshot follows the lists while the app is on screen (debounced 1 s).
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { widgets.observe() } }
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
        app.onIntent(intent)
    }
}
