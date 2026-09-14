# Arcaea 安装包修改器 · Android 版

在手机上直接修改 Arcaea 安装包（APK）内的歌曲 / 谱面 / 曲包资源，重新打包并**在应用内完成签名**，最后导出为一个可直接安装的 APK。

与 Windows 版的区别：**不需要电脑、不需要 adb、不需要 apksigner / zipalign**，导入导出全部通过系统的文件选择器（SAF）完成。

---

## 一、功能

| 功能 | 说明 |
| --- | --- |
| 导入 APK | 通过系统文件选择器选择 `.apk`，复制到应用工作目录后解析（不申请任何存储权限） |
| 歌曲管理 | 列表（缩略图 + 难度徽章）、搜索、编辑全部字段、编辑 5 个难度、上移 / 下移 / 删除、新增 |
| 新增歌曲导入资源包 | 新增时可选择一个 zip：自动按规则解压到 `assets/songs/<id>/`，并用包内 `songlist` / `slst` 片段填充曲名、定数等字段（id 与曲包以表单填写为准） |
| 修改歌曲 id | 支持；`songlist` 的 id 与整个资源目录会一起搬到新目录名下（逐文件经临时目录搬运，不吃内存） |
| 曲包管理 | 编辑 `packlist` 全部字段、新增、删除（歌曲可一并删除或迁移到其它曲包） |
| 资源管理 | 查看歌曲目录下文件、导入（谱面 / 音频 / 封面）、导入资源 zip（自动剥离 `assets/songs/<id>/` 前缀）、导出、删除 |
| 资源检查 | 导出前自动检查：缺谱面 / 缺封面 / 缺背景 / 缺横幅 / **空曲包** 等问题与提示 |
| 重新打包 | 未改动的条目**直通拷贝压缩数据**，不重新压缩，1GB 级 APK 也能快速完成 |
| 应用内签名 | 使用官方 `apksig`（与 `apksigner` 同一套实现），v1 + v2 + v3 全开 |
| **固定签名密钥** | 导出**始终使用同一把内置密钥**，因此同一台设备上的新包可以直接覆盖安装更新（指纹见第七节） |
| 改包名 | 直接改写二进制 `AndroidManifest.xml` 的字符串池，可选同时改写自定义权限名 / Provider 授权名 |
| 自检 | 调试构建启动时自动跑一次端到端自检（打包 → 签名 → 校验 → 重新解析），结果输出到 logcat（tag `AAM-SELFTEST`） |
| **清除缓存** | 导出完成后弹出独立提示框，可选择删除「导入的源 APK / 缓存的导出 APK / 项目缓存数据」；「工程」页也有缓存占用明细与清理入口（详见第六节） |
| **包名预设** | 可把常用的新包名保存为预设（持久化保存，最多 20 个）在导出时一键填入；并自动记住上次用过的包名用于预填 |

## 二、构建

工程使用 Gradle 8.7 + AGP 8.5.2 + Kotlin 2.0.21 + Compose，`compileSdk 35 / minSdk 26`。

用 Android Studio 直接打开 `android/` 即可。命令行构建：

```powershell
cd android
# 方式一：用本地缓存中的 Gradle 8.7
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\157ge0dm0jrajsz3pjvqs98mf\gradle-8.7\bin\gradle.bat" :app:assembleDebug

# 方式二：若本机 Gradle 已能联网（能下载发行版），先补一个 wrapper 再用 ./gradlew
gradle wrapper --gradle-version 8.7 --distribution-type bin
.\gradlew.bat :app:assembleDebug
```

> 本机环境注意：Java 侧存在 TLS 证书链校验问题，Gradle **无法下载发行版**，因此本仓库没有附带 wrapper；
> Maven 依赖（如 junit）可以正常下载，若确实无法联网可加 `--offline` 使用本地缓存。

**产物路径**

| 构建类型 | 路径 |
| --- | --- |
| Debug | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release | `android/app/build/outputs/apk/release/app-release.apk` |

Release 默认复用 debug 签名配置（见 `app/build.gradle.kts`），方便直接安装测试；正式分发请换成自己的签名配置。

## 三、测试

```powershell
cd android
# 核心链路自检（桌面 JVM，与手机内跑同一套代码）
& "…\gradle-8.7\bin\gradle.bat" :app:testDebugUnitTest --tests '*SelfTestTest*'

# 清单解析 / 包名改写
& "…\gradle-8.7\bin\gradle.bat" :app:testDebugUnitTest --tests '*AxmlTest*'

# JSON 保序与无损往返
& "…\gradle-8.7\bin\gradle.bat" :app:testDebugUnitTest --tests '*JsonTest*'

# 资源压缩包条目名规范化与元数据片段解析
& "…\gradle-8.7\bin\gradle.bat" :app:testDebugUnitTest --tests '*ResourceZipTest*'
```

`SelfTest` 覆盖：打开工程 → 新增歌曲/曲包 → **资源 zip 导入（含元数据填充）** → **改歌曲 id（目录整体搬迁）** →
空曲包规则 → 改包名（含标识符改写）→ 重新打包 → 应用内签名 → `ApkVerifier` 校验 → 重新解析产物 →
未改动资源逐字节一致校验。自检用的是一份 **7KB 的合成 APK**
（`app/src/main/assets/selftest.apk`，含真实的二进制 AndroidManifest.xml）。

在手机上验证（会额外验证 apksig 在 Android **运行时**的可用性）：

```powershell
adb -s <serial> install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n dev.local.arcaea.apkmanager/.MainActivity
adb -s <serial> logcat -d -s AAM-SELFTEST
```

> ✅ 已在真机验证通过：安装 debug 包并启动后，自检输出 `AAM-SELFTEST: PASS (46/46)`，
> 说明应用内的 **v1 + v2 + v3 签名 + `ApkVerifier` 校验**在 Android 上工作正常。

## 四、代码结构

```
android/app/src/main/java/dev/local/arcaea/apkmanager/
├─ core/                    纯 JVM 逻辑（可在桌面单元测试，不依赖 Android API）
│  ├─ Zip.kt                ZIP 读写：直通拷贝、4 字节对齐、lib/*.so 页对齐
│  ├─ Axml.kt               二进制 AndroidManifest.xml 解析与包名/标识符改写
│  ├─ Json.kt               保序、数字无损的 JSON 解析与序列化
│  ├─ Model.kt              Song / Pack（直接在 Json 上读写，保留未知字段与顺序）
│  ├─ Project.kt            工程模型：增删改、资源暂存、资源检查、重新打包
│  ├─ Signer.kt             apksig 封装（v1+v2+v3）
│  └─ SelfTest.kt           端到端自检
├─ data/                    Android 层
│  ├─ AppRepository.kt      SAF 导入/导出、工作目录、应用内签名落地
│  └─ ApkViewModel.kt       界面唯一状态源（串行化写操作）
└─ ui/                      Compose 界面（工程 / 歌曲 / 曲包 / 检查 / 导出）
```

设计上刻意把 `core/` 与 Android API 隔离，因此核心逻辑能在桌面 JVM 上被完整测试。

## 五、重要规则：**普通曲包不能为空**

这是实测踩坑确认的硬性规则，删歌前务必注意：

| 曲包类型 | 能否为空 |
| --- | --- |
| `"is_extend_pack": true` | ✅ 可以（原始 mod 里 `lowest`/`slow`/`slow2`/`extra` 都是空的） |
| 普通曲包（无该字段） | ❌ **为空时游戏打开歌单会直接闪退**（原生 `std::exception` → SIGABRT） |

典型触发：某首歌是它所在普通曲包的**唯一一首**，删掉它之后曲包就空了。
应用里的处理：删除歌曲时若会删空某个普通曲包会给出红色警告；「曲包」页对空的普通曲包显示
`空曲包 · 会闪退`；「检查」页会把这种情况列为**问题**并阻止（需勾选确认）导出。

修法三选一：给该曲包添加歌曲 / 删除该曲包 / 给它加上 `is_extend_pack`。

## 六、缓存与清理

应用处理过程中需要的大文件会放在**专属工作目录**里
（`Android/data/dev.local.arcaea.apkmanager/files/work/`），共三类，都可以安全删除
（不会影响你已经导出到其它位置的 APK）：

| 类别 | 文件 | 说明 |
| --- | --- | --- |
| 导入的源 APK | `input.apk` | 你导入的安装包副本，通常最大（数百 MB）。删除后**当前会话仍可继续修改与导出**（读取走的是已打开的文件句柄），但关闭工程或重启应用后需要重新导入 |
| 缓存的导出 APK | `last-export.apk` | 上次导出时保留的产物副本，方便稍后再次另存；导出时是**重命名**而非复制，不额外占用空间 |
| 项目缓存数据 | `import/`、`selftest/` | 导入资源、改 id 时产生的临时文件，以及自检文件 |

两个清理入口：

1. **导出完成后**会自动弹出一个独立的提示框，显示三类缓存的占用大小，由你勾选要删除哪些
   （默认勾选「缓存的导出 APK」与「项目缓存数据」，源 APK 默认保留）；
2. 「工程」页底部的 **「缓存与清理」** 卡片，随时可查看占用明细并清理。

安全约束：**存在未导出的改动时不会清理「项目缓存数据」**——那些改动依赖导入 / 改 id 时产生的临时文件，
删掉会让它们失效，因此对话框里该项会被禁用并提示先导出。

## 七、签名密钥（固定，便于覆盖安装更新）

导出修改后的 Arcaea 包时，**始终使用应用内置的同一把密钥**，因此同一台设备上后续导出的新包
可以直接覆盖安装更新，不必先卸载。导出对话框里会显示当前密钥指纹，方便核对。

| 密钥别名 | 用途 | 文件位置 | SHA-256 指纹 |
| --- | --- | --- | --- |
| `arcaeamod` | 给**导出的 Arcaea 包**签名（内置，导出时自动使用） | `app/src/main/assets/auto-sign.p12` | `3F:EE:75:B8:EF:7A:ED:47:8B:AD:52:1E:E7:78:46:15:FF:E1:D4:54:81:00:8F:2A:4E:84:02:AD:F9:B1:7A:31` |
| `arcaeaapkmanager` | 给**本工具自身**的 release 包签名（随仓库提供，保证任何机器上构建的包签名一致，可互相覆盖安装） | `keystore/release.p12` | `07:41:6A:F3:CE:01:72:10:6E:ED:DB:95:E1:7A:11:2D:FD:F2:BE:FD:49:44:EA:74:65:C5:E0:24:89:10:E1:B8` |

两个密钥库的密码都是 `arcaeamod`（工具用途，非生产密钥）。
**请勿删除或重新生成这两个文件**，否则已安装的包将无法覆盖更新。

## 八、已知限制

1. 改包名的**标识符改写**只覆盖自定义权限名与 Provider 授权名，不动组件类名（与 Windows 版一致）。
2. 导出需要约 **2 倍 APK 大小**的临时空间（未签名包 + 签名包），工作目录在
   `Android/data/dev.local.arcaea.apkmanager/files/work/`，导出后会自动清理。
3. 不支持在界面里导入**自定义**签名密钥；如需换成自己的密钥，替换
   `app/src/main/assets/auto-sign.p12` 后重新构建即可（Android 原生不支持 JKS，需用 PKCS#12）。
4. 不做「清除游戏缓存」引导（Android 版不涉及 adb）。
5. 改 id 时会逐文件搬运整首歌的资源，单首歌资源极大（>100MB）时耗时较久、需要额外临时空间。

## 九、合规提示

仅用于对你**合法拥有**的安装包做本地修改与学习研究；请勿用于传播他人版权资源，并自行承担相应后果。
