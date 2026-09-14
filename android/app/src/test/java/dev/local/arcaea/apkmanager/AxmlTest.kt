package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.Axml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 临时验证：用 windows/Sample/AndroidManifest.xml（真实二进制 AXML，原包名 moe.high.ard）
 * 校验 Axml 的解析与包名替换行为。纯 JVM 单元测试，不依赖 android.*。
 */
class AxmlTest {

    private val oldPackage = "moe.high.ard"
    private val newPackage = "com.example.arcaea.mo1.mod1"

    private fun sampleManifest(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "windows/Sample/AndroidManifest.xml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("找不到 windows/Sample/AndroidManifest.xml")
    }

    @Test
    fun readManifest_readsOriginalPackage() {
        val bytes = sampleManifest().readBytes()
        val info = Axml.readManifest(bytes)
        assertTrue("应为二进制 AXML", info.isBinary)
        assertEquals(oldPackage, info.packageName)
        println("versionName=${info.versionName} versionCode=${info.versionCode} minSdk=${info.minSdk} targetSdk=${info.targetSdk}")
        println("applicationName=${info.applicationName} mainActivity=${info.mainActivity}")
        println("compileSdk=${info.compileSdk} components=${info.componentNames.size}")
    }

    @Test
    fun setPackageName_replacesPackageAndKeepsVersion() {
        val bytes = sampleManifest().readBytes()
        val before = Axml.readManifest(bytes)

        val out = Axml.setPackageName(bytes, newPackage, true)
        val after = Axml.readManifest(out)

        assertEquals(newPackage, after.packageName)
        assertEquals(before.versionName, after.versionName)
        assertEquals(before.versionCode, after.versionCode)
        assertEquals(before.minSdk, after.minSdk)
        assertEquals(before.targetSdk, after.targetSdk)
    }

    @Test
    fun setPackageName_rewritesDerivedIdentifiers() {
        val bytes = sampleManifest().readBytes()
        val out = Axml.setPackageName(bytes, newPackage, true)
        val pool = Axml.dumpStringPool(out)

        assertFalse(
            "改写后不应再存在旧的 C2D_MESSAGE 权限名",
            pool.contains("$oldPackage.permission.C2D_MESSAGE"),
        )
        assertFalse(
            "改写后不应再存在旧的 provider 授权名",
            pool.contains("$oldPackage.provider"),
        )
        assertTrue(
            "改写后应出现新的 C2D_MESSAGE 权限名",
            pool.contains("$newPackage.permission.C2D_MESSAGE"),
        )
        assertTrue("改写后不应残留旧包名条目", pool.none { it == oldPackage })
    }

    @Test
    fun setPackageName_keepsComponentClassNames() {
        val bytes = sampleManifest().readBytes()
        val before = Axml.readManifest(bytes)
        val out = Axml.setPackageName(bytes, newPackage, true)
        val after = Axml.readManifest(out)

        // 组件类名（例如 low.moe.AppActivity）必须保持不变
        assertEquals("low.moe.AppActivity", before.mainActivity)
        assertEquals(before.mainActivity, after.mainActivity)
        assertTrue("组件类名应保持原样", after.componentNames.contains("low.moe.AppActivity"))
        assertEquals(before.applicationName, after.applicationName)

        // 权限名以外的组件类名（不含旧包名前缀的非权限标识符）应逐条保持不变
        val beforeNonDerived = before.componentNames.filterNot { it.startsWith("$oldPackage.") }
        val afterNonDerived = after.componentNames.filterNot { it.startsWith("$newPackage.") }
        assertEquals(beforeNonDerived, afterNonDerived)
    }
}
