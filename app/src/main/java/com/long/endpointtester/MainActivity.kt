package com.longdev.endpointtester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.longdev.endpointtester.ui.EndpointTesterScreen
import com.longdev.endpointtester.ui.theme.EndpointTesterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EndpointTesterTheme {
                EndpointTesterScreen()
            }
        }
    }
}
