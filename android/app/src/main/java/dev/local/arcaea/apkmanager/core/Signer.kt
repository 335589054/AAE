package dev.local.arcaea.apkmanager.core

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** 一把可用于签名的密钥 */
data class SigningKey(
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
)

/**
 * APK 签名。
 *
 * 与 Windows 版不同：Windows 版调用 build-tools 里的 `apksigner` 命令行，
 * Android 版直接使用官方 `apksig` 库（与 apksigner 是同一套实现），
 * 因此不需要任何外部程序，签名完全在应用内完成（v1 + v2 + v3）。
 */
object Signer {

    const val DEFAULT_ALIAS = "arcaeamod"
    const val DEFAULT_STORE_PASSWORD = "arcaeamod"

    /** 读取 PKCS#12 密钥库（Android 原生支持 PKCS12；JKS 需 BouncyCastle，故不提供） */
    fun loadKey(
        keystoreBytes: ByteArray,
        storePassword: String,
        alias: String = DEFAULT_ALIAS,
        keyPassword: String = storePassword,
    ): SigningKey {
        val store = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(keystoreBytes).use { store.load(it, storePassword.toCharArray()) }
        val privateKey = store.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
            ?: throw IOException("密钥库中找不到别名「$alias」的私钥（或密码不正确）")
        val chain = store.getCertificateChain(alias)
            ?: throw IOException("密钥库中找不到别名「$alias」的证书链")
        return SigningKey(alias, privateKey, chain.map { it as X509Certificate })
    }

    /** 签名：默认同时启用 v1/v2/v3，兼容所有 Android 版本 */
    fun sign(
        inputApk: File,
        outputApk: File,
        key: SigningKey,
        minSdkVersion: Int = 26,
        onProgress: (String) -> Unit = {},
    ) {
        onProgress("正在签名（v1 + v2 + v3）…")
        // 注意：不要给 SignerConfig 设置 minSdkVersion —— 那会启用「按 SDK 定向签名」，
        // apksig 要求该模式必须配合 v3 且目标平台 >= Android P(28)，minSdk 较低时会直接抛异常。
        val config = ApkSigner.SignerConfig.Builder(key.alias, key.privateKey, key.certificates).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(minSdkVersion.coerceAtLeast(1))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    /** 证书指纹（SHA-256，形如 `AA:BB:…`）。用于让用户确认签名密钥始终是同一把。 */
    fun fingerprint(certificate: X509Certificate): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** 校验签名；返回问题列表（空列表表示验证通过） */
    fun verify(apk: File): List<String> {
        val result = ApkVerifier.Builder(apk).build().verify()
        val problems = mutableListOf<String>()
        for (error in result.errors) problems.add(error.toString())
        for (warning in result.warnings) problems.add("警告：$warning")
        return problems
    }
}
