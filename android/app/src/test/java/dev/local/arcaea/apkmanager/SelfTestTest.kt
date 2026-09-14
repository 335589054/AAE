package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.SelfTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 在桌面 JVM 上跑完整的核心链路自检（与手机内跑的是同一套代码）。
 * 覆盖：打开工程 → 增删改 → 改包名 → 重新打包 → 应用内签名 → 校验签名 → 重新解析产物。
 */
class SelfTestTest {

    @Test
    fun fullChainWorks() {
        val apk = File("src/main/assets/selftest.apk")
        val keystore = File("src/main/assets/auto-sign.p12")
        assertTrue("缺少自检 APK：${apk.absolutePath}", apk.exists())
        assertTrue("缺少内置签名密钥：${keystore.absolutePath}", keystore.exists())

        val work = File("build/selftest-work").apply { mkdirs() }
        val result = SelfTest.run(work, apk.readBytes(), keystore.readBytes()) { println(it) }
        assertTrue(result.summary, result.passed)
    }
}
