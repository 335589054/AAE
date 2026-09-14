# Arcaea APK Manager（Windows）

一个面向 Windows 的 Arcaea 安装包修改器。核心能力：

1. **导入你自己的 APK**（工具不内置任何 Arcaea 游戏资源），读取其中的歌曲、谱面、曲包与相关信息；
2. 可视化 **增 / 删 / 改 / 查** 歌曲、难度与曲包，并替换封面、音频、谱面等资源；
3. **重新打包 + 自动签名**，包名可由用户指定（直接补丁二进制 AndroidManifest，无需 apktool）；
4. 通过 **adb 无线调试** 安装到手机；
5. 修改完成后 **清除工具缓存**，并引导用户清理手机端缓存。

> 本项目对 `Sample/` 目录中的真实 Arcaea 解包资源做了完整实测（27 首歌曲 / 7 个曲包 / 2530 个文件条目 / 约 665 MB），
> 所有流程均在 `npm run selftest` 中做过端到端验证。

---

## 1. 快速开始

### 环境要求

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Node.js | 18+（开发时使用 26.x） | 构建与运行 |
| JDK | 17+（需含 `keytool`） | apksigner 签名、生成签名密钥 |
| adb | 任意较新版本 | 无线安装 |

### 命令

```bash
npm install          # 安装依赖（会下载 Electron 运行时）
npm run dev          # 开发模式：Vite HMR + Electron
npm run build        # 构建主进程与渲染进程
npm start            # 构建后直接以生产模式启动
npm run typecheck    # TypeScript 类型检查
npm run selftest     # 用 Sample/ 做端到端自检（ZIP / AXML / 打包 / 签名）
npm run dist         # 用 electron-builder 打包成 Windows 安装包（输出到 release/）
```

> 若 `npm install` 后 Electron 未能下载二进制（部分 npm 版本默认拦截安装脚本），执行
> `node node_modules/electron/install.js` 手动补下载，或 `npm approve-scripts electron`。

### 打包发行时的资源约束

`package.json` 的 `build.files` 只包含 `dist/`、`dist-electron/` 与 `package.json`，
并显式排除 `Sample/` 与 `tools/`，因此发行包中**不会包含任何 Arcaea 资源**。用户必须自己导入 APK。

---

## 2. 前置资源

工具在启动后会自动检测下列资源，缺失时给出提示并可一键下载到
`%APPDATA%/arcaea-apk-manager/tools/`（不改动系统 PATH）：

| 资源 | 必需 | 检测顺序 | 一键下载来源 |
| --- | --- | --- | --- |
| Java | 是 | `JAVA_HOME` → PATH | 不自动下载，引导到 [Adoptium](https://adoptium.net/temurin/releases/) |
| adb | 是 | SDK（`ANDROID_HOME` / `%LOCALAPPDATA%\Android\Sdk`）→ PATH → `tools/platform-tools` | `platform-tools-latest-windows.zip` |
| apksigner | 是 | `tools/apksigner/apksigner.jar` → SDK `build-tools/*/lib/apksigner.jar`（取最高版本） | `build-tools_r34-windows.zip`（只提取其中的 `apksigner.jar`） |
| apktool | 否 | `tools/apktool/apktool.jar` | GitHub 最新 release 的 jar |

**优先复用系统中已有的 adb**：不同版本的 adb 会在 5037 端口上互相顶掉对方的 adb server，
如果你先在命令行/其它工具里 `adb connect` 连好了手机，再让本工具使用另一个版本 adb，
那个连接就会“消失”。因此本工具把系统 adb 排在自带下载版之前；确实看不到设备时，
可以在「无线安装」页点「重启 adb 服务」重新扫描。

**不需要 zipalign**：工具在写 zip 时自行完成对齐（见 3.3）。apktool 仅作为可选备用工具，
因为包名修改已由内置的 AXML 补丁实现。

---

## 3. 工具是怎么工作的

### 3.1 导入：只读中央目录，不全量解包

`ZipReader` 直接解析 APK 的中央目录与本地文件头，因此拿到的是一个「文件索引 + 偏移」。
导入时只解压 `assets/songs/songlist`、`packlist`、`unlocks` 与 `AndroidManifest.xml`，
并按 `assets/songs/<id>/<file>` 建立目录索引。665 MB 的样本 APK 可以秒级打开。

### 3.2 编辑：在内存中维护工程状态

主进程持有一个 `ApkProject`，包含：

- 解析后的 `songlist.songs[]`、`packlist.packs[]` 与 `unlocks` 原文；
- 待写入资源表（`路径 → 内存 Buffer` 或 `路径 → 本地文件`）与待删除路径集合；
- 包名覆盖值；
- `dirtySinceExport` 标记（用于「清除缓存」的前置校验）。

渲染进程只是视图：所有修改都作为 IPC 命令发到主进程，主进程返回新的快照（`ProjectSnapshot`），
界面据此重绘。

### 3.3 导出：直通重打包（不重新压缩整个 APK）

导出时用 `ZipWriter` 逐条目输出：

- **未改动条目**：从源 APK 的压缩数据位置原样拷贝字节，不重新压缩；
- **改动/新增条目**：重新 deflate（已是压缩格式的 `png/jpg/ogg/so` 直接存储）；
- **被删除条目**：跳过；
- **`META-INF` 下的旧签名**（`MANIFEST.MF` / `*.SF` / `*.RSA` / `*.DSA` / `*.EC`）：剔除；
- **对齐**：所有条目的数据起始位置按 4 字节对齐；未被压缩的 `lib/**.so` 按 4096 字节页对齐
  （等价 `zipalign -p 4`）。

因此导出一个 637 MB 的 APK 只需要数秒，且不会因为重新压缩而破坏原生库的 mmap 装载。

### 3.4 改包名：直接重写二进制 AndroidManifest 的字符串池

APK 内的 `AndroidManifest.xml` 是二进制 AXML。`electron/core/axml.ts` 实现了最小可用的 AXML 解析：

1. 解析 `RES_STRING_POOL`（UTF-8 / UTF-16 两种编码都支持）与元素/属性；
2. 定位 `<manifest>` 上 `package` 属性所引用的字符串下标；
3. 重建字符串池（新包名可长可短），重算偏移与 chunk 尺寸，其余字节原样保留；
4. 可选地**一并改写全机唯一的标识符**：清单里以原包名为前缀的
   自定义权限名（`<permission android:name>` / `<uses-permission android:name>`）与
   Provider 授权名（`<provider android:authorities>`，支持分号分隔的多个值），
   以及任何 `android:permission` / `readPermission` / `writePermission` 上的同前缀值。

因为字符串是**按索引引用**的，重建池不会影响其他引用，也不需要 apktool/aapt2。
样本清单中 105 个组件类名全部不是以包名为前缀的（主 Activity 是 `low.moe.AppActivity`），
且工具**只改写权限名 / 授权名、不改 `android:name` 上的组件类名**，因此改包名不会导致类解析失败。

### 3.5 签名

1. 自动模式会在 `%APPDATA%/arcaea-apk-manager/keystore/auto.keystore` 生成并复用一把固定调试密钥
   （PKCS12，口令 `android`），保证多次导出可以覆盖安装；也可使用自定义 keystore 或一键新建。
2. 调用 `java -jar apksigner.jar sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true`。
3. 再用 `apksigner verify --verbose --print-certs` 校验，未通过则视为导出失败并报错。

### 3.6 缓存与清理

- 工具目录：`%APPDATA%/arcaea-apk-manager/`
  - `tools/`：下载的前置资源（清除缓存不会删除）
  - `keystore/`：签名密钥
  - `cache/tmp/`：未签名中间产物等临时文件
- 导出成功后会自动清理本次的中间产物；「缓存与清理」页提供手动兜底清理。
- **清理前置条件**：只要存在「尚未导出的改动」（`dirtySinceExport`），清除缓存会被拦截并提示先导出，
  避免误删未保存的成果。
- 清理后界面会引导用户清理**手机端** Arcaea 的缓存/数据（否则可能仍加载旧资源）。

---

### 3.7 安装：真实上传进度与随时中止

`adb install` 在输出被重定向时不会给出任何进度（它内部同样是先推文件再调用包管理器），
因此安装被拆成三步，以便拿到真实进度：

1. `adb push <apk> /data/local/tmp/<唯一名>` —— adb push **默认就输出**
   `[ 42%] /data/local/tmp/xxx.apk` 形式的进度（`-q` 才是关闭进度），
   据此得到真实百分比、已传输字节数与实时带宽（4 秒滑动窗口计算速率）。
   若某个 adb 版本确实没有输出进度，则退化为每 1.5 秒轮询设备端文件大小来估算，
   界面会标注「速率来自设备端估算」；
2. `adb shell pm install -r -t <remote>` —— 设备端解包与校验，无法获取百分比，
   此时显示不定进度条、已用时间与阶段说明；
3. `adb shell rm -f <remote>` —— 清理设备端临时文件。

整个过程可以随时点「**停止安装**」：直接结束 adb 客户端进程即可中断上传，
并尽力清理设备端残留文件。界面实时显示：百分比、已传输 / 总大小、实时速率、本阶段已用时。
adb 的原始输出（含执行的命令与退出码）会按行实时推送到「adb 输出」看板，自动滚动到最新。

---

## 4. APK 资源模型

### 4.1 `assets/songs/` 结构

```
assets/songs/
├─ songlist                       # 歌曲总表（JSON，唯一权威）
├─ packlist                       # 曲包总表（JSON，唯一权威）
├─ unlocks                        # 特殊解锁条件（JSON，样本为空数组）
├─ pack/                          # 曲包横幅
│  ├─ select_<packId>.png
│  └─ 1080_select_<packId>.png
├─ <songId>/                      # 每首歌一个目录
│  ├─ base.jpg / base_256.jpg             # 封面 / 缩略图
│  ├─ 1080_base.jpg / 1080_base_256.jpg   # 高清封面 / 高清缩略图（可选）
│  ├─ base.ogg                            # 整曲音频（剪曲包可能没有）
│  ├─ <n>.aff                             # 谱面（n = ratingClass）
│  ├─ <n>.ogg                             # 独立音频（该难度 audioOverride 时使用）
│  └─ slst                                # 单曲元数据片段（辅助，可缺省）
└─ tutorial/                      # 教程曲，独立目录，不在 songlist 中
```

样本实测：`songlist` 有 27 首歌曲、`packlist` 有 7 个曲包、`assets/songs` 下有 28 个曲目目录
（27 首 + `tutorial`），2530 个文件条目，约 665 MB。

### 4.2 `songlist` 字段

根结构：`{ "songs": [ { … } ] }`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | **唯一标识**，必须与目录名 `assets/songs/<id>/` 一致 |
| `title_localized` | object | 多语言标题（样本仅用 `en`） |
| `artist` | string | 曲师 |
| `bpm` | string | 允许区间，如 `"195"`、`"210-222"` |
| `bpm_base` | number | 常驻 BPM |
| `set` | string | **所属曲包 id**，必须存在于 `packlist.packs[].id` |
| `purchase` | string | 购买/内购标识，通常为 `""` |
| `audioPreview` / `audioPreviewEnd` | number | 试听区间（毫秒） |
| `side` | number | 阵营/配色索引（Light 光 / Conflict 对立 / Achromic 无属性）。样本中取值 `1` 与 `4`，建议沿用同曲包其它歌曲的取值 |
| `bg` | string | 游玩/结算背景 id，文件在 `assets/img/bg/1080/<bg>.jpg` |
| `date` | number | Unix 时间戳（秒） |
| `version` | string | 版本标签，如 `"1.0"`、`"7.0"` |
| `source_localized` | object | 曲目来源（可选） |
| `difficulties` | array | 难度数组，见下 |

`difficulties[]` 元素：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `ratingClass` | number | **难度索引，同时决定文件后缀** |
| `rating` | number | 定数；`0` 通常表示「占位、不可玩」 |
| `ratingPlus` | bool | 是否显示 `+`（如 9 → 9+） |
| `chartDesigner` / `jacketDesigner` | string | 谱师 / 曲绘师 |
| `title_localized` | object | 该难度独立标题（可选） |
| `bpm` / `bpm_base` | string / number | 该难度独立 BPM（可选） |
| `audioOverride` | bool | `true` 时该难度播放 `<ratingClass>.ogg` 而不是 `base.ogg` |

### 4.3 `ratingClass` 与文件命名对照

| ratingClass | 难度 | 谱面 | 独立音频 |
| --- | --- | --- | --- |
| 0 | Past（PST） | `0.aff` | `0.ogg` |
| 1 | Present（PRS） | `1.aff` | `1.ogg` |
| 2 | Future（FTR） | `2.aff` | `2.ogg` |
| 3 | Beyond（BYD） | `3.aff` | `3.ogg` |
| 4 | Eternal（ETR） | `4.aff` | `4.ogg` |

样本中 `axdcut12` 同时存在 `2/3/4.aff + 2/3/4.ogg`，且这 3 个难度都带 `audioOverride: true`，
即「剪曲」用法：每个难度使用各自截取的音频，整曲 `base.ogg` 可以删掉（样本里附带的 `删ogg的.py` 正是这个用途）。

### 4.4 `packlist` 字段

根结构：`{ "packs": [ { … } ] }`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | string | **曲包唯一标识**，被 `song.set` 引用；横幅文件名取自它 |
| `section` | string | 分组。样本出现：`arcaea`、`mainstory`、`mainstory2`、`sidestory`、`archive` |
| `is_extend_pack` / `is_active_extend_pack` | bool | 扩展/归档包相关标记 |
| `custom_banner` / `cutout_pack_image` / `small_pack_image` | bool | 横幅显示方式 |
| `plus_character` | number | 关联角色 id，无则 `-1` |
| `name_localized` | object | 多语言名称 |
| `description_localized` | object | 多语言描述 |

样本的 `packlist` 只保留 7 个曲包（`lowest / base / slow / slow2 / cut / cut2 / extra`），
而 `pack/` 目录里残留了大量官方横幅图 —— 说明**曲包以 `packlist` 为准，多余的图片不会被显示**。

### 4.4.1 关键规则：**普通曲包不能为空**

这是本项目踩过坑之后确认的硬性规则，务必遵守：

| 曲包类型 | 能否为空 | 说明 |
| --- | --- | --- |
| `"is_extend_pack": true` | ✅ 可以 | 扩展包，未激活时本就不显示。原始 mod 里 `lowest` / `slow` / `slow2` / `extra` 都是空的，且都带这个字段 |
| 普通曲包（无 `is_extend_pack`） | ❌ **不可以** | **为空时游戏打开歌单会直接闪退**（原生 `std::exception` → SIGABRT，日志里只有 `libcocos2dcpp.so` 偏移，很难定位） |

**典型触发场景**：某首歌是它所在普通曲包的**唯一一首**，把这首歌删掉/改 `set` 之后，这个曲包就空了。
例如原始 mod 的 `base` 曲包（`section: "arcaea"`，名称 `ArcAea`）**只有 `bloomingplanet` 一首**，
删掉它就会留下一个空的普通曲包，导出后游戏在打开歌单时闪退。

工具的对应处理：

- 「资源检查」会把「非 extend 空曲包」列为**问题**（而不是提示），并在导出前阻止；
- 删除歌曲时，若这是某个普通曲包的最后一首，会弹出醒目警告，并**默认勾选「同时删除该曲包及其横幅」**；
- 「曲包」页对空的普通曲包会显示 `空曲包 · 会闪退` 标记。

三种修法任选：

1. 给该曲包添加至少一首歌曲；
2. 删除该曲包（工具会一并移除 `packlist` 条目与 `select_<id>.png` / `1080_select_<id>.png`）；
3. 给该曲包加上 `"is_extend_pack": true`（会让它以扩展包的形式呈现，注意 UI 表现可能随之变化）。

### 4.5 `slst` 不是必需文件

样本中 `bloomingplanet`（自定义曲）与 `iscut12` 目录里都**没有** `slst`，但它们在 `songlist` 中都有条目；
`bloomingplanet` 用的还是 `songlist.txt` 这个不同名字。结论：

> 游戏运行时只读取根目录的 `songlist`；`slst` 是拆分编辑用的单曲元数据片段（社区习惯），
> 内容等价于 `songlist` 里的一项。本工具以 `songlist` 为唯一权威，不依赖 `slst`。

---

## 5. 增、删、改、查流程

统一原则：**元数据改 JSON（`songlist` / `packlist`），资源改文件，两者必须始终一致。**

### 5.1 查

1. 读取 `assets/songs/songlist` → `songs[]`；
2. 读取 `assets/songs/packlist` → `packs[]`，建立 `packId → 曲包` 索引；
3. 读取 `unlocks` 原文（保持不动）；
4. 遍历 `songs[]`，枚举 `assets/songs/<id>/` 下的文件，按 `ratingClass` 归属到各难度；
5. 建立 `songId → 目录与文件`、`packId → 其下歌曲` 两张索引；
6. 界面上：左侧列表（缩略图 + 曲名 + id + 各难度是否有谱面），右侧编辑。

### 5.2 增

**新增歌曲**：在「歌曲与谱面」页点 `＋`，填写 id / 曲名 / 所属曲包。有两种资源来源：

1. **直接导入资源 zip（推荐）**：在同一个弹窗里选择 zip，工具会先解析并展示
   - 将被导入的资源列表与数量；
   - 封面 / 谱面 / 音频是否齐全；
   - 若包内含 `songlist` / `songlist.txt` / `slst` / `song.json`，会自动读取其中的曲名、曲师、
     BPM、定数与难度列表（曲名以你填写的为准，`id` 与所属曲包始终以你填写的为准）。

   zip 的三种常见结构都支持：直接是歌曲目录内容（`base.jpg`、`3.aff`…）、
   包在歌曲文件夹里（`mysong/base.jpg`…）、或带上解包目录前缀（`assets/songs/mysong/base.jpg`…）。
   `__MACOSX/`、`.DS_Store` 等系统文件会被忽略，带子目录的条目会被跳过并提示。

2. 只创建条目：之后到「资源文件」页逐个导入封面、音频与谱面。

**新增曲包**：在「曲包」页点「新建曲包」，填写 id / 名称 / 分组 → 再导入
`select_<id>.png`（建议同时导入 1080 版本），最后把歌曲的 `set` 指向它。

### 5.3 改

| 修改对象 | 位置 |
| --- | --- |
| 曲名 / 曲师 / BPM / `set` / `side` / `bg` / `version` / `date` / 试听区间 | 「基本信息」页 |
| 定数与 `+` 号 / 谱师 / 曲绘师 / 难度标题 / `audioOverride` | 「难度与定数」页 |
| 增加 / 移除难度 | 「难度与定数」页（`ratingClass` 取值 0–4） |
| 封面（4 种规格）、整曲音频、谱面 | 「资源文件」页 |
| 谱面文本（明文 `.aff`） | 「资源文件」页 → 查看 / 编辑文本 |
| 曲包名称 / 描述 / 分组 / 横幅 | 「曲包」页 |
| 曲目在 `songlist` 中的顺序 | 编辑器底部「↑ 上移 / ↓ 下移」，或在曲目列表上**点右键**选择上移 / 下移 |
| 歌曲 id 重命名 | 「基本信息」页；工具会**自动搬运整个歌曲目录**到新目录名 |
| 包名 | 「打包与签名」页 |

> **关于图片显示**：曲目缩略图与曲包横幅都会**自动选用实际存在的文件**。
> 缩略图按 `base_256.jpg → 1080_base_256.jpg → base.jpg → 1080_base.jpg` 取第一个存在的，
> 编辑器的封面预览则优先高分辨率；曲包横幅在 `select_<id>.png` 缺失时回退到 `1080_select_<id>.png`。
> 因此「只有 1080 版本封面」的曲目（样本里的剪曲包就是如此）也能正常显示预览。
>
> **曲目列表右键菜单**：在左侧曲目列表的任意一项上点右键，可直接「上移 / 下移 / 删除曲目」。

### 5.4 删

- **删歌曲**：`songlist` 移除条目，并删除 `assets/songs/<id>/` 下的全部资源；
- **删曲包**：`packlist` 移除曲包，其下歌曲可选择「迁移到其它曲包」或「连同歌曲一起删除」，
  横幅图片需手动在「资源文件」页处理（或保留，不影响使用）。

### 5.5 一致性校验

「工程」页会自动检查并分为两类：

**问题（会导致曲目异常）**

- `songlist` 有条目但没有对应目录；
- 难度启用了 `audioOverride` 但缺少 `<n>.ogg`；
- 既没有 `base.ogg` 也没有任何 `audioOverride` 难度（播放无声）；
- `set` 指向的曲包在 `packlist` 中不存在；
- 曲包既没有 `select_<id>.png` 也没有 `1080_select_<id>.png`。

**提示（通常可以忽略）**

- 目录存在但没有 `songlist` 条目（资源未被使用）；
- 缺少封面 / 缩略图（两种规格都没有）；
- 定数 > 0 的难度缺少对应 `.aff`；
- `bg` 引用的背景图缺失；
- 曲包只提供了高清横幅，或曲包下没有歌曲。

（在样本 APK 上，问题为 0 条、提示十余条 —— 说明该样本本身是自洽的。）

---

## 6. 打包、签名与安装

### 6.1 打包

「打包与签名」页 → 选择是否改包名 → 选择签名方式 → 「开始打包并签名」→ 选择输出路径。
界面会实时显示进度与 apksigner 日志，成功后可「打开所在位置」或「直接安装到手机」。
导出成功的路径会被记住，「无线安装」页会把它作为默认的安装包自动填好。

### 6.2 包名

- 保持原包名：可覆盖安装原版（签名必须一致，否则需先卸载）；
- 指定新包名：相当于全新应用，不会继承存档 / 登录态 / 已购内容，需自行权衡。

包名合法性要求：至少两段、每段以字母开头，只含字母数字下划线，例如 `com.example.arcaea`。

**为什么改包名时必须同时改写自定义权限名与 Provider 授权名**

自定义权限与 ContentProvider 授权名在**整台设备上唯一**。Arcaea 的清单里声明了：

```
<permission      android:name="moe.high.ard.permission.C2D_MESSAGE" .../>
<uses-permission android:name="moe.high.ard.permission.C2D_MESSAGE"/>
<provider        android:authorities="moe.high.ard.provider" .../>
<provider        android:authorities="moe.high.ard.firebaseinitprovider" .../>
<provider        android:authorities="moe.high.ard.androidx-startup" .../>
```

只改 `<manifest package>` 而不改它们，与原版同时安装就会依次报：

```
Failure [INSTALL_FAILED_DUPLICATE_PERMISSION: Package <新包名> attempting to
redeclare permission moe.high.ard.permission.C2D_MESSAGE already owned by moe.high.ard]
Failure [INSTALL_FAILED_CONFLICTING_PROVIDER: authority moe.high.ard.provider already used]
```

因此「打包与签名」页在指定新包名时提供 **「同时改写自定义权限名与 Provider 授权名」**（默认开启）。
安装遇到这两类错误时，工具会直接给出可执行的解决步骤：

1. 勾选该选项后重新导出并安装（可与原版共存）；
2. 或者先卸载手机上的原版 Arcaea 再安装；
3. 或者不改包名直接覆盖安装（需要与原版相同的签名，普通用户不适用）。

### 6.3 adb 无线调试（Android 11+）

1. 手机：设置 → 开发者选项 → 无线调试 → 打开；
2. 点「使用配对码配对设备」，把 **IP + 配对端口 + 6 位配对码** 填入工具 → 「配对」；
3. 用无线调试页面上的 **连接端口**（与配对端口不同）→ 「连接」；
4. 在**设备列表中点选目标设备**：只有一台可用设备时会自动选中；有多台（常见于同时开着模拟器）时
   必须手动选择，否则 adb 会以 `more than one device/emulator` 拒绝安装；
5. APK 文件已默认填好**最近一次导出的安装包**，直接点「安装到所选设备」。

设备列表进入本页后每 4 秒自动刷新一次；状态为 `offline` / `unauthorized` 的设备会一并列出但不可选。
如果设备是你在命令行或其它工具里先连好的、工具内却看不到，点「**重启 adb 服务**」重新扫描即可
（不同版本的 adb 会互相顶掉对方的 server，本工具已优先复用系统 adb 以避免该问题）。

点击「安装到所选设备」后会显示**实时进度**（百分比、已传输 / 总大小、实时速率、已用时间），
大体积 APK 上传缓慢时可以随时点「**停止安装**」中止（详见 3.7）。
页面底部的「adb 输出」看板会**实时滚动**安装过程中的 adb 原始输出（已过滤掉上传进度刷屏，
并附带每条命令与退出码）：向上滚动会暂停自动跟随并出现「跟随最新」按钮，也可以随时「清空」。

工具会把 `adb` 的输出原样展示，并对常见失败给出提示：

- `more than one device/emulator` → 请在设备列表中选择目标设备后重试；
- `device offline / not found` → 设备已断开，请重新连接并刷新；
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 签名不一致，需先卸载或用新包名导出；
- `INSTALL_FAILED_VERSION_DOWNGRADE` → 目标机版本更高，需提高 versionCode 或先卸载；
- 无设备 → 先完成配对与连接。

---

## 7. 缓存与清理

1. **前提**：必须先成功导出 APK。若存在未导出的改动，清除缓存会被拦截并提示先导出。
2. 「缓存与清理」页可查看缓存路径 / 占用 / 文件数，并一键清除。
3. 清理完成后，界面会引导用户清理手机端：

   > 设置 → 应用 → Arcaea → 存储 → **清除缓存**；
   > 若改动仍未生效（尤其是替换了封面 / 音频），再执行 **清除数据**（会删除本地存档与登录状态，请谨慎）。

4. 建议启动游戏时先断网一次，避免在线校验覆盖本地资源。

---

## 8. 代码结构

```
electron/                  主进程（Node 侧，拥有所有文件与进程能力）
├─ main.ts                 创建窗口、注册 IPC
├─ preload.ts              contextBridge 暴露 window.api（与 RendererApi 契约一致）
├─ ipc.ts                  全部 IPC handler：前置资源 / 工程 / 歌曲 / 曲包 / 资源 / 打包 / adb / 缓存
└─ core/
   ├─ paths.ts             用户数据目录、tools / keystore / cache 布局、目录大小统计
   ├─ proc.ts              无 shell 的子进程执行、带进度的文件下载
   ├─ zip.ts               ZIP 直通读写器（中央目录解析、原始字节拷贝、自实现对齐）
   ├─ axml.ts              二进制 AndroidManifest 解析与包名替换
   ├─ project.ts           ApkProject：工程状态、增删改查、快照、一致性检查、重打包
   ├─ prereq.ts            前置资源检测与一键下载
   ├─ signer.ts            keystore 生成、apksigner 签名与校验
   ├─ adb.ts               无线调试：列举 / 配对 / 连接 / 断开 / 重启服务
   ├─ install.ts           带进度与可中止的安装（push 进度解析 + 兜底估算 + pm install）
   └─ cache.ts             缓存统计与清理

shared/types.ts            主/渲染共享类型，同时充当 IPC 契约
src/                       渲染进程（React + Tailwind）
├─ App.tsx                 侧边导航 + 顶栏 + 进度条 + 消息提示
├─ state.tsx               StoreProvider：快照、忙碌状态、进度事件、Toast、统一错误处理
├─ lib.ts                  难度名称、体积格式化、图片预览缓存
├─ components/ui.tsx       Button / Card / Badge / Field / Modal / Toggle 等基础组件
└─ panels/
   ├─ PrereqPanel.tsx      前置资源检测与一键下载
   ├─ ProjectPanel.tsx     工程信息、待导出改动、资源检查
   ├─ PacksPanel.tsx       曲包增删改 + 横幅导入
   ├─ SongsPanel.tsx       歌曲列表（搜索 / 按曲包过滤）
   ├─ SongEditor.tsx       基本信息 / 难度 / 资源 / 删除 + .aff 文本编辑
   ├─ BuildPanel.tsx       包名、签名密钥、导出
   ├─ InstallPanel.tsx     无线调试与安装
   └─ CachePanel.tsx       缓存清理与手机端引导

scripts/
├─ build-main.mjs          esbuild 打包主进程与 preload
├─ selftest.ts             端到端自检脚本
└─ electron-stub.cjs       自检时替身（让 core 能在纯 Node 下运行）
```

---

## 9. 自检

```bash
npm run selftest
```

脚本会用 `Sample/` 真实数据跑完整链路，覆盖 60+ 项断言：

1. 前置资源检测；
2. AXML 解析（包名 / 版本 / uses-sdk / 组件类名）、包名替换（更长与更短的包名），以及
   **改包名时同步改写自定义权限名 / Provider 授权名**（并验证组件类名未被改动、关闭开关时保持原样）；
3. 用 Sample 组装测试 APK（含一个未压缩的 `lib/**.so`）；
4. `ApkProject` 读取与一致性检查；
5. 增删改查：改曲名、加曲包、加歌曲、导入封面与横幅、删歌曲、**重命名歌曲 id（验证目录搬运）**、改包名；
6. **资源 zip 导入**：扁平结构、`assets/songs/<id>/` 前缀结构、`songlist.txt` 元数据解析、
   `__MACOSX` 忽略、元数据写入新歌曲且不覆盖用户填写的 id / 曲包；
7. 导出后校验：包名已替换、`songlist` / `packlist` 正确、目录搬迁正确、zip 导入的资源已落盘、
   旧签名被移除、4 字节对齐、`.so` 页对齐、**本地文件头与中央目录的 CRC/大小一致**、
   全部条目可解压、未改动条目字节一致；
8. **安装进度解析**：adb push 百分比、`ls -l` 兜底大小、push 摘要速率、常见失败提示（多设备 /
   签名冲突 / 存储不足），以及 `INSTALL_FAILED_DUPLICATE_PERMISSION` /
   `INSTALL_FAILED_CONFLICTING_PROVIDER` 的专用解决引导；
9. 用导出结果重新打开工程；
10. `apksigner` 签名 + `verify`，并确认签名后的 APK 仍可解析。

---

## 10. 已知限制与注意事项

1. **改包名后数据不共享**：新包名是独立应用，原版存档、登录态、已购内容都不会迁移。
2. **只改 `assets/` 与清单包名**：不会触碰 `classes.dex`、`lib/`、`resources.arsc`，以降低风险。
3. **谱面不做语法校验**：`.aff` 文本编辑器只负责读写，正确性由使用者自行保证。
4. **`side` 等取值语义未完全确认**：样本中只出现 `1` 与 `4`，建议沿用同曲包其它歌曲的取值。
5. **`AudioOffset`/`bpm` 等由游戏自行解释**：工具不做换算。
6. **超 4 GB 的 APK / 超过 65535 个条目** 不在支持范围内（未实现 ZIP64 写入）。
7. **修改前请备份原始 APK**，并使用「资源检查」先确认没有阻断性问题再导出。
8. 该工具仅用于对**你合法拥有的安装包**做本地修改与学习研究，请自行承担相应后果。

### 10.1 排障：改包后进游戏/开始曲目就闪退

先抓日志确认崩溃类型（用设备序列号或 IP:端口，多设备时必须带 `-s`）：

```bash
adb -s <serial> logcat -c                       # 清缓冲
adb -s <serial> logcat -v time > crash.log      # 然后手动复现
# 复现后 Ctrl+C，重点看这几行：
adb -s <serial> logcat -d -v time | grep -E '\*\*\* \*\*\*|Fatal signal|Abort message|backtrace:|#[0-9]{2} pc'
```

两种典型结果：

- **`FATAL EXCEPTION`（Java 异常）** → 看 `AndroidRuntime` 的堆栈，通常能直接定位到类与行。
- **`Fatal signal 6 (SIGABRT)` + `Abort message: terminating with uncaught exception of type
  std::exception`**（本项目实测遇到的就是这种，MIUI 会记成 `NE_AppException`）→ 这是**游戏原生代码
  主动 `throw` 后被 `std::terminate` 中止**，栈回溯只有 `libcocos2dcpp.so` 的偏移，release 构建
  又没有可读日志，因此**不要只看日志**，要同时验证资源。

资源侧的自动排查（把设备上已安装 APK 的清单与歌曲数据取出来比对）：

```bash
adb -s <serial> shell 'unzip -l <已安装APK路径> "assets/songs/*" "assets/img/bg/1080/*" > /sdcard/Download/aam/listing.txt'
adb -s <serial> shell 'unzip -o -p <已安装APK路径> assets/songs/songlist  > /sdcard/Download/aam/songlist.json'
adb -s <serial> shell 'unzip -o -p <已安装APK路径> assets/songs/packlist  > /sdcard/Download/aam/packlist.json'
adb -s <serial> pull /sdcard/Download/aam/ ./pulled/
node scripts/diff-apk-resources.mjs ./pulled Sample/assets/songs/songlist
```

脚本会输出：最近被写入的条目（对应本次改动）、每首歌的封面/谱面/音频/背景是否齐全、
`songlist` 与原始文件的字段差异。经验上，原生 `std::exception` 的成因按概率排序为：

1. **存在空的普通曲包**（详见 4.4.1）——删歌/改 `set` 后最常见的坑，表现为「歌单都打不开」；
2. **某一首歌缺少它声明要加载的文件**（谱面、音频、封面或 `bg` 背景图）；
3. 上面两项都正常时，再用二分法定位：只改一处 → 导出 → 安装 → 逐一验证。

> 反例提醒：**不要**把「重打包换签名」当成首要怀疑对象。本项目实测过：
> 导出包与可正常游玩的原包之间，只有 `AndroidManifest.xml`（包名）、`songlist`/`packlist`（重排版，
> 内容逐字段一致）与签名条目不同，其余文件**逐字节一致**；`.so` 与 `dex` 里也没有硬编码包名或签名校验。
> 先把「数据自身的自洽性」检查干净，再考虑这类猜测。

**若资源全部齐全、且已安装 APK 内文件与你的源 APK 逐字节一致**，请检查网络因素：

Arcaea 客户端会与服务器同步曲目数据（游戏二进制里含 `SongHashes` / `OnlineManager` /
`DownloadFileTask` 等符号，可用 `adb shell dumpsys netstats detail` 观察该 uid 的流量）。
当改包内容与服务器下发的曲目数据不一致时，可能出现「进歌曲列表正常、一开始游玩就闪退」。
社区的通行做法是**进入游戏后断网（飞行模式）再游玩**；无线调试会随断网中断，此时请改用 USB 连接。
