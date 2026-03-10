package io.github.smyrgeorge.freepath

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.smyrgeorge.freepath.util.AndroidContextHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHooks.onCreate()
        AndroidContextHolder.applicationContext = applicationContext
        enableEdgeToEdge()
        intent.data?.toString()?.let { AppViewState.handleDeepLink(it) }
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.let { AppViewState.handleDeepLink(it) }
    }

    override fun onStart() {
        AppHooks.onStart()
        super.onStart()
    }

    override fun onStop() {
        AppHooks.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) AppHooks.onDestroy()
        super.onDestroy()
    }
}
