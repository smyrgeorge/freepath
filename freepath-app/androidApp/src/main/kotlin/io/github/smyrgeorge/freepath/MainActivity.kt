package io.github.smyrgeorge.freepath

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.smyrgeorge.freepath.util.AndroidContextHolder

class MainActivity : ComponentActivity() {

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results ignored — app degrades gracefully if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppHooks.onCreate()
        AndroidContextHolder.applicationContext = applicationContext
        requestBlePermissionsIfNeeded()
        enableEdgeToEdge()
        intent.data?.toString()?.let { AppViewState.handleDeepLink(it) }
        setContent { App() }
    }

    private fun requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val needed = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) blePermissionLauncher.launch(needed.toTypedArray())
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
