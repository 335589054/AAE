# Arcaea 安装包修改器

修改 Arcaea 安装包（APK）内的歌曲 / 谱面 / 曲包资源，重新打包并签名，得到一个可直接安装的 APK。
同一套核心逻辑提供了两个平台的实现：

| 目录 | 平台 | 技术栈 | 特点 |
| --- | --- | --- | --- |
| [`windows/`](windows/) | Windows 桌面 | Electron + React + TypeScript | 需要 `java` / `apksigner`；支持 adb 无线调试一键安装到手机、清除游戏缓存引导 |
| [`android/`](android/) | Android 手机 | Kotlin + Jetpack Compose | **不需要电脑**：应用内完成打包与签名（内置 apksig），导入导出走系统文件选择器 |

两者产出的 APK 完全等价（同样的重打包规则、同样的包名改写逻辑）。

```
Arcaea Apk Manager/
├─ windows/           Windows 桌面版（含 Sample 参考资源与核心自检）
│  ├─ electron/       主进程：zip / axml / project / signer / adb
│  ├─ shared/         与渲染进程共用的类型
│  ├─ src/            React 界面
│  ├─ scripts/        selftest.ts（端到端自检）、diff-apk-resources.mjs（资源对账）
│  └─ Sample/         参考 APK 解包内容（**未随仓库提供**，见下方说明）
├─ android/           Android 版（Kotlin/Compose，详见 android/README.md）
│  └─ app/src/main/java/dev/local/arcaea/apkmanager/
│     ├─ core/        纯 JVM 核心：Zip / Axml / Json / Model / Project / Signer / SelfTest
│     ├─ data/        SAF 导入导出、应用内签名
│     └─ ui/          Compose 界面
└─ AGENTS.md
```

> `windows/Sample/` 是解包出来的 Arcaea 安装包内容（700MB+，含游戏版权资源），**不随仓库提供**。
> 需要跑 Windows 版自检时，请把你**合法拥有**的安装包解包后放到 `windows/Sample/` 即可。
> 两端各自带有端到端自检（打包 → 签名 → 校验 → 重新解析产物），Windows 版也支持把
> `Sample/` 换成你自己的 APK 解包内容。同理，`node_modules/` 与构建产物也未提交。

## 快速开始

### Android（推荐，无需电脑）

```powershell
cd android
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\157ge0dm0jrajsz3pjvqs98mf\gradle-8.7\bin\gradle.bat" :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`（release 为 `.../release/app-release.apk`）。
手机上打开 → 「导入 APK」选择安装包 → 改歌曲/曲包 → 「导出」选保存位置即可。

### Windows

```powershell
cd windows
npm install
npm start          # 需要 java + Android SDK build-tools 里的 apksigner
npm run selftest   # 端到端自检
```

## 必须遵守的两条数据规则

这两条都是实测踩坑后确认的，改错会直接导致**游戏打开歌单就闪退**（原生 `std::exception` → SIGABRT，
logcat 里只有 `libcocos2dcpp.so` 偏移，非常难定位）：

1. **普通曲包不能为空**：只有带 `"is_extend_pack": true` 的扩展包可以没有歌曲。
   若某首歌是它所在普通曲包的最后一首，删掉它之后曲包就空了 → 必须同时删除该曲包 / 或给它添加歌曲 /
   或给它加上 `is_extend_pack`。
2. **改包名要同步改写全机唯一的标识符**：`<permission android:name>` 与 `<provider android:authorities>`
   里以旧包名为前缀的值必须一起改，否则与原版共存时会报
   `INSTALL_FAILED_DUPLICATE_PERMISSION` / `INSTALL_FAILED_CONFLICTING_PROVIDER`。

两端的工具都已经把这两条做成了自动检查 / 默认行为，详细说明见
[windows/README.md](windows/README.md) 的 4.4.1 与 6.2 节、[android/README.md](android/README.md) 第五节。

## 合规提示

仅用于对你**合法拥有**的安装包做本地修改与学习研究。请勿用于传播他人版权资源，并自行承担相应后果。
