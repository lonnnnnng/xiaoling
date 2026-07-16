package com.longdev.xiaoling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.longdev.xiaoling.ui.XiaoLingApp
import com.longdev.xiaoling.ui.XiaoLingLaunch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XiaoLingLaunch {
                XiaoLingApp()
            }
        }
    }
}
