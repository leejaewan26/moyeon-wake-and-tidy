package com.moyeon.waketidy

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moyeon.waketidy.ui.WakeTidyApp
import com.moyeon.waketidy.ui.WakeTidyTheme

class MainActivity : ComponentActivity() {
    private val controller: AppController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleDemoExtra(intent)
        setContent {
            val state = controller.ui.collectAsStateWithLifecycle()
            WakeTidyTheme {
                WakeTidyApp(state.value, controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemoExtra(intent)
    }

    /** `adb shell am start -n com.moyeon.waketidy/.MainActivity --ez demo true` 로 시연 시나리오를 바로 재생. */
    private fun handleDemoExtra(intent: Intent?) {
        if (intent?.getBooleanExtra("demo", false) == true) {
            intent.removeExtra("demo")
            controller.startScenarioIfIdle()
        }
    }
}
