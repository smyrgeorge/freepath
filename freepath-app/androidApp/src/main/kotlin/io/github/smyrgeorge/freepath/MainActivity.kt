package io.github.smyrgeorge.freepath

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.smyrgeorge.freepath.util.AndroidContextHolder

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidContextHolder.applicationContext = applicationContext
        enableEdgeToEdge()
        setContent {
            App()
        }
    }
}
