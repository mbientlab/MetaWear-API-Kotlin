package com.mbientlab.metawear.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mbientlab.metawear.app.ui.AppNavHost
import com.mbientlab.metawear.app.ui.theme.MetaWearTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MetaWearTheme {
                AppNavHost()
            }
        }
    }
}
