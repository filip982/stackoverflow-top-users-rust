package dev.filip.stackoverflowusers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.filip.stackoverflowusers.ui.AppNavHost
import dev.filip.stackoverflowusers.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val gateway = (application as StackOverflowUsersApp).container.gateway
        setContent {
            AppTheme { AppNavHost(gateway) }
        }
    }
}
