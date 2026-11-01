package com.streamline.play

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.streamline.play.streamLineContainer
import com.streamline.play.ui.AppViewModel
import com.streamline.play.ui.StreamLineShell
import com.streamline.play.ui.theme.StreamLineTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            StreamLineTheme {
                StreamLineShell(vm = viewModel)
            }
        }
    }

    override fun onDestroy() {
        val container = application.streamLineContainer
        if (container.settingsRepository.current.value.clearCacheOnExit) {
            val cacheManager = container.cacheManager
            Thread({ cacheManager.clearCache() }, "cache-clear-exit").apply {
                isDaemon = true
                start()
            }
        }
        super.onDestroy()
    }
}
