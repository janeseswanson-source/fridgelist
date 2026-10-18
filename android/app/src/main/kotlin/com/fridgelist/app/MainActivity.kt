package com.fridgelist.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.fridgelist.app.navigation.FridgeListNavHost
import com.fridgelist.app.ui.theme.FridgeListTheme
import dagger.hilt.android.AndroidEntryPoint

/** AppCompatActivity so per-app language changes recreate the screen on every API level. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FridgeListTheme {
                FridgeListNavHost()
            }
        }
    }
}
