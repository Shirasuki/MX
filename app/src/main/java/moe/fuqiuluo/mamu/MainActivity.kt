package moe.fuqiuluo.mamu

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.config.autoStartFloatingWindow
import moe.fuqiuluo.mamu.floating.service.FloatingWindowService
import moe.fuqiuluo.mamu.ui.screen.MainScreen
import moe.fuqiuluo.mamu.ui.theme.MXTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MXTheme {
                MainScreen()
            }
        }

        // 检查是否需要自动启动悬浮窗
        checkAutoStartFloatingWindow()
    }

    private fun checkAutoStartFloatingWindow() {
        val mmkv = MMKV.defaultMMKV()
        if (mmkv.autoStartFloatingWindow) {
            val intent = Intent(this, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}