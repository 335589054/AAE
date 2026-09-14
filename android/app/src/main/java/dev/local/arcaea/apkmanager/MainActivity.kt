package dev.local.arcaea.apkmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.local.arcaea.apkmanager.data.ApkViewModel
import dev.local.arcaea.apkmanager.ui.AppBackground
import dev.local.arcaea.apkmanager.ui.AppScreen
import dev.local.arcaea.apkmanager.ui.AppTheme

class MainActivity : ComponentActivity() {

    /** 界面唯一数据源（AndroidViewModel，retain 到 Activity 生命周期） */
    private val vm: ApkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(color = AppBackground, modifier = Modifier.fillMaxSize()) {
                    AppScreen(vm)
                }
            }
        }
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            // 调试构建下自动跑一次端到端自检（打包 → 应用内签名 → 校验），结果同时输出到
            // logcat（tag: AAM-SELFTEST），方便用 adb 直接验证设备上的签名能力。
            vm.runSelfTest()
        }
    }
}
