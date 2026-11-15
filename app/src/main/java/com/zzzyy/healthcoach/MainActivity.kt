package com.zzzyy.healthcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.zzzyy.healthcoach.ui.HealthCoachApp
import com.zzzyy.healthcoach.ui.HealthViewModel
import com.zzzyy.healthcoach.ui.theme.HealthTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HealthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HealthTheme {
                HealthCoachApp(viewModel)
            }
        }
    }
}
