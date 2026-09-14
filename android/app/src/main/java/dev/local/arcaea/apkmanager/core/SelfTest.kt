package dev.local.arcaea.apkmanager.core

import java.io.File

/**
 * 端到端自检。
 *
 * 用内置的小型合成 APK（含**真实的二进制 AndroidManifest.xml**）跑完整链路：
 * 打开工程 → 新增歌曲/曲包 → 改包名（含标识符改写）→ 重新打包 → 应用内签名 → 校验签名 → 重新解析产物。
 *
 * 纯 JVM 实现，因此**桌面单元测试与手机内可以跑同一套逻辑**：
 * 桌面跑证明移植逻辑正确，手机内跑还能额外证明 apksig 在 Android 上可用。
 */
object SelfTest {

    data class Check(val name: String, val ok: Boolean, val detail: String = "")

    data class Result(val checks: List<Check>) {
        val passed: Boolean get() = checks.all { it.ok }
        val passedCount: Int get() = checks.count { it.ok }

        val summary: String
            get() = buildString {
                append(if (passed) "PASS" else "FAIL")
                append(" ($passedCount/${checks.size})")
                for (c in checks.filter { !it.ok }) {
                    append("\n  ✗ ").append(c.name)
                    if (c.detail.isNotEmpty()) append(" → ").append(c.detail)
                }
            }
    }

    const val TAG = "AAM-SELFTEST"

    fun run(
        workDir: File,
        testApk: ByteArray,
        keystore: ByteArray,
        log: (String) -> Unit = {},
    ): Result {
        val checks = mutableListOf<Check>()

        fun check(name: String, ok: Boolean, detail: String = "") {
            checks.add(Check(name, ok, detail))
            log(if (ok) "  [PASS] $name" else "  [FAIL] $name ${if (detail.isNotEmpty()) "→ $detail" else ""}")
        }

        fun runCatchingCheck(name: String, body: () -> Pair<Boolean, String>) {
            try {
                val (ok, detail) = body()
                check(name, ok, detail)
            } catch (err: Throwable) {
                check(name, false, "${err::class.simpleName}: ${err.message}")
            }
        }

        val newPackage = "dev.local.arcaea.selftest.mod1"
        var signedApk: File? = null

        runCatchingCheck("准备工程目录") {
            workDir.mkdirs()
            val input = File(workDir, "selftest-input.apk")
            input.writeBytes(testApk)
            (input.length() == testApk.size.toLong()) to "${input.length()} 字节"
        }

        runCatchingCheck("打开工程并读取清单") {
            val input = File(workDir, "selftest-input.apk")
            ApkProject(input).use { project ->
                val info = project.manifest
                check("清单为二进制 AXML", info.isBinary)
                check("读出原包名 moe.high.ard", info.packageName == "moe.high.ard", info.packageName)
                check("读出 versionName / versionCode", info.versionName == "4.3.7" && info.versionCode == 3020102, "${info.versionName}/${info.versionCode}")
                check("读出歌曲数 1", project.songs.size == 1, project.songs.size.toString())
                check("歌曲 id 正确", project.songIds() == listOf("selftest1"), project.songIds().toString())
                check("曲包读取正确", project.packs.size == 1 && project.packs[0].id == "base")
                check("曲包歌曲统计正确", project.songCountInPack("base") == 1)
            }
            true to ""
        }

        runCatchingCheck("资源增删改与检查规则") {
            val project = ApkProject(File(workDir, "selftest-input.apk"))
            try {
                // 新增歌曲 + 谱面
                val song = Song.template("selftest2", "base", "Self Test 2")
                song.difficultyList().isEmpty()
                val difficulty = song.ensureDifficulty(2)
                difficulty.put("rating", JsonNumber.of(9))
                project.addSong(song)
                project.stageWriteBytes("assets/songs/selftest2/2.aff", "AudioOffset:0\n-\n".toByteArray())
                project.stageWriteBytes("assets/songs/selftest2/base.ogg", ByteArray(64))
                check("新增歌曲后曲目数为 2", project.songs.size == 2, project.songs.size.toString())
                check("新增歌曲的资源目录可见", project.listSongFiles("selftest2").contains("2.aff"))

                // 缺谱面应报警
                val missing = project.snapshot().warnings.any { it.contains("selftest2") && it.contains("2.aff") }
                check("缺少谱面时资源检查报警（删掉谱面再检查）", run {
                    project.stageDelete("assets/songs/selftest2/2.aff")
                    val warned = project.snapshot().warnings.any { it.contains("selftest2") && it.contains("2.aff") }
                    project.stageWriteBytes("assets/songs/selftest2/2.aff", "AudioOffset:0\n-\n".toByteArray())
                    warned
                })

                // 普通曲包不能为空（这条规则是真实闪退的根因）
                val emptyPack = Pack.template("emptypack", "arcaea", "Empty")
                project.addPack(emptyPack)
                check(
                    "空普通曲包被列为问题",
                    project.snapshot().warnings.any { it.contains("emptypack") && it.contains("is_extend_pack") },
                )
                project.updatePack("emptypack") { it.isExtendPack = true }
                check(
                    "标记 extend 后不再报警",
                    project.snapshot().warnings.none { it.contains("emptypack") && it.contains("is_extend_pack") },
                )
                project.removePack("emptypack")
                check("删除曲包后恢复正常", project.snapshot().warnings.none { it.contains("emptypack") })

                // ===== 资源压缩包导入：新建歌曲 + 自动解压 + 用包内元数据填充 =====
                val fragment = ResourceZip.parseSongFragment(
                    (
                        """{"id":"ignored","set":"ignored","title_localized":{"en":"From Zip"},""" +
                            """"bpm":"150","difficulties":[{"ratingClass":2,"rating":7}]}"""
                        ).toByteArray(),
                )
                check("能从片段里解析出歌曲对象", fragment != null)
                val bundleDir = File(workDir, "bundle").apply { mkdirs() }
                val chartFile = File(bundleDir, "2.aff").apply { writeBytes("AudioOffset:0\n-\n".toByteArray()) }
                val audioFile = File(bundleDir, "base.ogg").apply { writeBytes(ByteArray(64)) }
                val zipSong = Song.template("selftest3", "base", "临时曲名")
                project.addSongWithResources(
                    zipSong,
                    SongResourceBundle(listOf("2.aff" to chartFile, "base.ogg" to audioFile), fragment),
                )
                val created = project.song("selftest3")
                check(
                    "zip 导入时 id 与曲包以填写值为准",
                    created != null && created.id == "selftest3" && created.set == "base",
                    "${created?.id}/${created?.set}",
                )
                check("zip 元数据填充了曲名", created?.title() == "From Zip", created?.title().orEmpty())
                check(
                    "zip 元数据填充了定数",
                    created?.difficulty(2)?.let { Song.difficultyRating(it) } == 7,
                    created?.difficulty(2)?.let { Song.difficultyRating(it).toString() }.orEmpty(),
                )
                check(
                    "zip 内文件写入到歌曲目录",
                    project.listSongFiles("selftest3").containsAll(listOf("2.aff", "base.ogg")),
                    project.listSongFiles("selftest3").toString(),
                )
                check(
                    "zip 导入后不再报缺谱面",
                    project.snapshot().warnings.none { it.contains("selftest3") },
                )

                // ===== 改 id：整个资源目录一起搬 =====
                val before = project.readEntry("assets/songs/selftest3/2.aff")
                val renameTemp = File(workDir, "import").apply { mkdirs() }
                project.renameSong("selftest3", "renamed3", renameTemp)
                check("改 id 后 songlist 使用新 id", project.song("renamed3") != null && project.song("selftest3") == null)
                check("改 id 后旧目录清空", project.listSongFiles("selftest3").isEmpty(), project.listSongFiles("selftest3").toString())
                check(
                    "改 id 后文件搬到新目录",
                    project.listSongFiles("renamed3").containsAll(listOf("2.aff", "base.ogg")),
                    project.listSongFiles("renamed3").toString(),
                )
                check(
                    "改 id 后资源内容不变",
                    project.readEntry("assets/songs/renamed3/2.aff")?.contentEquals(before) == true,
                )
                check(
                    "改 id 时拒绝已存在的 id",
                    runCatching { project.renameSong("renamed3", "selftest2", renameTemp) }.isFailure,
                )
                check(
                    "改 id 时拒绝空 id",
                    runCatching { project.renameSong("renamed3", "   ", renameTemp) }.isFailure,
                )
                check(
                    "改 id 时拒绝非法字符",
                    runCatching { project.renameSong("renamed3", "bad/id", renameTemp) }.isFailure,
                )
                project.removeSong("renamed3")
                check("清理新增的测试歌曲", project.songs.size == 2, project.songs.size.toString())

                // 改动摘要
                val pending = project.snapshot().pending
                check("改动摘要包含 songlist", pending.any { it.contains("songlist") })
                check("改动摘要包含写入的资源", pending.any { it.contains("assets/songs/selftest2/2.aff") })

                // 改包名
                project.setPackageNameOverride(newPackage, true)
                check(
                    "改动摘要包含新包名",
                    project.snapshot().pending.any { it.contains(newPackage) },
                )

                // 重新打包
                val unsigned = File(workDir, "selftest-unsigned.apk")
                unsigned.delete()
                project.exportUnsigned(unsigned) { message, _ -> log("    · $message") }
                check("产出未签名 APK", unsigned.length() > 0, "${unsigned.length()} 字节")

                // 应用内签名
                val key = Signer.loadKey(keystore, Signer.DEFAULT_STORE_PASSWORD)
                val signed = File(workDir, "selftest-signed.apk")
                signed.delete()
                Signer.sign(unsigned, signed, key, 26) { log("    · $it") }
                signedApk = signed
                check("产出签名 APK", signed.length() > 0, "${signed.length()} 字节")

                val problems = Signer.verify(signed)
                check("签名校验通过", problems.isEmpty(), problems.joinToString("; "))
            } finally {
                project.close()
            }
            true to ""
        }

        runCatchingCheck("重新解析产物") {
            val signed = signedApk ?: throw IllegalStateException("签名产物不存在")
            ApkProject(signed).use { project ->
                check("产物可被解析且为 2 首歌", project.songs.size == 2, project.songs.size.toString())
                check("产物包名已改写", project.manifest.packageName == newPackage, project.manifest.packageName)
                check("产物版本信息不变", project.manifest.versionName == "4.3.7")
                check("新增的谱面存在于产物", project.readEntry("assets/songs/selftest2/2.aff") != null)

                val pool = Axml.dumpStringPool(project.readEntry(ApkProject.MANIFEST_PATH)!!)
                check(
                    "自定义权限名随包名改写",
                    pool.contains("$newPackage.permission.C2D_MESSAGE") && !pool.contains("moe.high.ard.permission.C2D_MESSAGE"),
                )
                check(
                    "Provider 授权名随包名改写",
                    !pool.contains("moe.high.ard.provider") && pool.contains("$newPackage.provider"),
                )
                check("组件类名未被改动", pool.contains("low.moe.AppActivity"))

                // 未改动的资源必须逐字节一致（直通拷贝正确性）
                val originalBytes = File(workDir, "selftest-input.apk").let { ApkProject(it) }.use {
                    it.readEntry("assets/songs/selftest1/base.ogg")
                }
                val newBytes = project.readEntry("assets/songs/selftest1/base.ogg")
                check(
                    "未改动资源逐字节一致",
                    originalBytes != null && newBytes != null && originalBytes.contentEquals(newBytes),
                    "原始 ${originalBytes?.size} / 产物 ${newBytes?.size}",
                )

                // 所有条目都能解压（CRC 与长度自洽）
                var readable = 0
                var broken = 0
                for (name in project.reader.listAll()) {
                    val data = project.readEntry(name)
                    if (data == null) broken++ else readable++
                }
                check("产物所有条目可解压", broken == 0, "可读 $readable / 异常 $broken")
            }
            true to ""
        }

        val result = Result(checks)
        log("$TAG: ${result.summary}")
        return result
    }
}
