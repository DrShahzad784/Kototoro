# iOS 数据互通方案：本地导入、Google Drive / iCloud 同步与备份兼容（2026-10-12）

本文是调研与实施方案，结论均基于当前仓库代码：Android 实现、已有 KMP 共享模块，以及 iOS 的接入现状。

**进度（2026-10-12）**：
- §3 备份已实现：Kototoro 备份补齐 source_origins、extension_repos、tracks、track_logs；
  外部备份抽取为 `ExternalLibraryImport` 和 `VeneraBackupDecoding`，iOS 可导入 Mihon、Aniyomi 和 Venera 备份。
- §1 本地导入已实现，放在新模块 `core-local`（`LocalSourceRuntime`），支持 CBZ/ZIP、EPUB 和 TXT。
  Android 的 `LocalContentParser` 尚未改为委托该模块。
- §2 同步按要求暂缓。

详见 kmp-ios-plan §115。

## 0. 总体原则

- **格式与 Android 共通**：本地作品包、备份 ZIP、Google Drive 同步快照都沿用 Android 现有格式和版本号，
  不为 iOS 另起格式。这样跨设备导入、恢复和同步都能直接工作。
- **能下沉到 commonMain 的规则全部下沉**，平台只保留文件访问、授权、后台调度和 UI。
  Android 的现有实现改为委托共享代码，避免两份逻辑分叉。
- **建议顺序**：备份兼容补齐（§3）→ 本地导入（§1）→ 同步引擎抽取 + Google Drive（§2.1）→ iCloud（§2.2）。
  前两项没有外部前置条件；后两项分别需要 Google Cloud 项目和 Apple 开发者团队配置，见各节“前置条件”。

## 1. 本地导入作品

### Android 现状

- 入口 `local/data/importer/SingleContentImporter`：文件或目录（单作品 / 多作品模式）复制到本地存储根目录；
  `LocalImportSupport` 按扩展名分类：CBZ/ZIP 为漫画，EPUB/TXT 为小说，mp4/mkv/ts/webm/avi/m3u8 为视频。
- 解析入口 `local/data/input/LocalMangaParser.kt` 中的 `LocalContentParser`（约 1000 行）：读取目录或 ZIP 里的
  `index.json`（`ContentIndex`），若没有则按文件结构推断章节、封面；EPUB 走 `local/epub/`
  （`me.ag2s.epublib` 加 jsoup）；Kototoro 自己下载的漫画由 `LocalMangaZipOutput` 写成带 `index.json` 的 CBZ。
- 平台耦合点：`UniFile`/`DocumentFile`/`ContentResolver`、`org.json`、`java.util.zip`、epublib、jsoup。

### 可共享部分

- **ZIP 读取**：Android 已使用 okio 的 `FileSystem.openZip`。已确认 okio 3.17 的 iOS klib 包含
  `ZipFileSystem`/`openZip`，因此 CBZ/ZIP 解析可以直接放进 commonMain。
- **新建 `core-local`（KMP）模块**，放入：
  - `LocalImportSupport`（扩展名分类，原样搬迁）；
  - `ContentIndex` 改用 kotlinx.serialization（字段名不变，读写 Android 已有的 `index.json`）；
  - 目录/压缩包到章节/页面的推断规则（从 `LocalContentParser` 中拆出纯逻辑，文件访问改走 okio `FileSystem`）；
  - TXT 分章规则；EPUB 用 okio ZIP 读取 `container.xml` 和 OPF 后得到章节 HTML。共享层没有 XML 库，
    只需解析 OPF 的 manifest/spine/metadata，可用一个小型受限 XML 读取器，或引入 KMP 版 xmlutil。
    章节 HTML 交给 iOS 现有的 `NovelText` 段落化。
- Android 侧 `LocalContentParser` 改为“UniFile 物化后调用共享解析”，行为不变。

### iOS 适配

- 入口：设置/作品库中的“导入”使用 `fileImporter`（支持多选和文件夹）；Info.plist 声明
  `CFBundleDocumentTypes`（cbz/zip/epub/txt），使“文件”App 和分享面板可以“用 Kototoro 打开”。
- 存储：复制到 `Application Support/local/<hash>/`（与在线源下载区隔离），并为 Android 的本地源
  （`LOCAL`、`LocalNovelSource`）写入 manga/chapters 行，使收藏、历史、备份和同步按原有身份工作。
- 阅读：漫画走现有 `SourceReaderView`（页面来自本地文件），小说走 `NovelReaderView`；视频使用 AVPlayer，
  但 **mkv/avi 不受 AVPlayer 支持**，需提示用户或后续接入 libmpv/VLCKit。
- 额外收益：iOS 已下载章节可以导出为 Android 兼容的 CBZ（带 `index.json`），实现双向互通。

## 2. 同步

### 2.1 Google Drive

**Android 现状**

| 层 | 位置 | 平台性 |
|---|---|---|
| Drive HTTP 协议（appDataFolder 列表、下载、上传、版本、删除） | `core-net/.../GoogleDriveSyncApi`（Ktor） | **已共享** |
| 快照模型、多快照合并与压缩 | `core-domain/.../GoogleDriveSyncModels`、`GoogleDriveSyncMerger`、`SyncContentImport` | **已共享** |
| 同步流程：列文件 → 合并远端 → 构建本地快照 → 合并 → 写库 → 版本检查后上传 | `app/.../GoogleDriveSyncRepository`（约 1000 行） | Android（Room `withTransaction`、扩展管理器、APK 文件） |
| 授权 | `GoogleDriveSyncAuth`（Play Services `Identity.getAuthorizationClient`） | Android 独有 |
| 定时 | `GoogleDriveSyncWorker`（WorkManager） | Android 独有 |

协议要点：appDataFolder 中只有一个规范快照 `kototoro_sync_content_v3.json`；上传前比较文件版本，
版本变化则重新合并（最多重试 `MAX_CONFLICT_RETRIES` 次），属于乐观并发。

**抽取方案**

1. 新建 `core-sync`（KMP，或并入 `core-library`）：把 `buildLocalSnapshot`、`applyToDatabase`、
   `performSync` 的流程和 track/track log 合并迁入 commonMain，数据库操作使用 KMP Room
   （`useWriterConnection { immediateTransaction { … } }`，与 `LibraryBackup` 相同）。平台差异通过接口注入：
   - `SyncSnapshotStore`：`list() / download(id) / version(id) / upload(bytes, id?) / delete(id)`，
     Google Drive 和 iCloud 各实现一份；
   - `SyncExtensionPort`：导出/恢复扩展包与仓库（Android 为 APK；iOS 为设备端转换的 JAR 和安装记录。
     快照里的 APK base64 正好可以交给 iOS 的端侧转换安装）；
   - `SyncClock`/`deviceId`。
2. Android 的 `GoogleDriveSyncRepository` 改为委托共享引擎（保留 Hilt 装配和 WorkManager）。

**iOS 授权与调度**

- OAuth 2.0 授权码 + PKCE：`ASWebAuthenticationSession`，回调使用 iOS 客户端的反向 client ID URL scheme，
  scope 为 `drive.appdata`；access/refresh token 存 Keychain，过期后用 refresh token 静默刷新。
  也可以使用 GoogleSignIn-iOS SDK，但手写 PKCE 不引入新依赖。
- 触发：前台启动/回到前台时同步（复用 Android 的 `shouldSyncOnStart` 间隔规则），并用 `BGAppRefreshTask`
  做尽力而为的后台同步（iOS 不保证执行频率）。

**前置条件**：在 **Android 现有的同一个 Google Cloud 项目**中创建 iOS 类型 OAuth 客户端。appDataFolder
按 Cloud 项目隔离，不在同一项目就看不到 Android 写入的快照。需要项目维护者提供 iOS client ID。

### 2.2 iCloud

可选两种方式：

| 方式 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. iCloud Drive 文件快照（推荐）** | 在 ubiquity 容器中保存同一份 `kototoro_sync_content_v3.json`，实现 `SyncSnapshotStore`（`NSFileCoordinator` 读写，`NSMetadataQuery` 发现，`NSFileVersion` 作为版本号，冲突版本作为多快照参与合并） | 完全复用共享引擎和合并规则；与 Google Drive 共用测试 | 仅限 Apple 设备之间；文件级同步延迟由系统决定 |
| B. CloudKit 私有库记录 | 每条收藏/历史一条 CKRecord，增量同步 | 粒度细、冲突少、可推送 | 需要新的记录模式和一整套增量逻辑，无法复用 Android 合并规则 |

建议先做 A。同一 ubiquity 容器中的 `Documents/Backups` 还可以存放自动备份，在“文件”App 中可见；
导出备份到 iCloud Drive 用现有 `fileExporter` 即可完成。

**前置条件**：Apple 开发者团队与签名（当前预览用 `CODE_SIGNING_ALLOWED=NO`），在 App ID 上启用 iCloud
（iCloud Documents 容器 `iCloud.<bundle id>`），并加入 entitlements。模拟器测试 iCloud 需要登录 Apple ID。

## 3. 备份与恢复

### 3.1 Kototoro 格式（与 Android 互通）

iOS 已通过共享 `core-library/LibraryBackup` 导出和合并恢复同一 ZIP 格式，覆盖作品、分类、收藏、历史、来源状态、
书签和统计（含 v2 work 兼容节）。iOS 目前保留不动、在预览中列为“跳过”的节如下：

| 节 | 处理建议 |
|---|---|
| `settings` / `reader_grid` | 只映射双方都有语义的键（阅读模式、翻页动画、预读、内容空间等），其余保留原样不丢弃；导出时写入同名键 |
| `extension_repos`、`source_origins` | 已有共享数据库表，补进 `LibraryBackup` 即可，iOS 端恢复后提示可安装扩展 |
| `tracks` / `track_logs` / `scrobbling` | 更新检测数据可以直接恢复（iOS 已有更新记录）；账号令牌（`auth`）不跨平台恢复 |
| `saved_filters` | 待 iOS 支持保存筛选后再接 |

### 3.2 其他应用的备份

| 格式 | 解码 | 写入作品库 | iOS 方案 |
|---|---|---|---|
| Mihon `.tachibk`（gzip + protobuf） | `core-backup` 共享 `decodeMihonOrAniyomiBackup` | Android `ExternalBackupRepository` | 抽取写入层后可直接用 |
| Aniyomi `.tachibk` | 同上 | 同上 | 同上 |
| Usagi（JSON） | `core-backup` 模型共享 | Android | 同上 |
| Venera（ZIP 内 SQLite） | Android `ExternalBackupDecoder`（`android.database.sqlite`） | Android | 用 ios-shared 已有的 bundled SQLite 驱动重写解码，逻辑可共享 |

需要抽取的是写入层：`ExternalBackupRepository.import`（约 580 行）依赖 Room `withTransaction`、
Mihon/Aniyomi 扩展管理器（用于判断来源是否已安装）以及字符串资源。方案如下：

- 在 commonMain 建立 `ExternalLibraryImport`：分类映射、作品 ID 生成、章节 ID、历史与收藏时间戳规则
  （`ExternalBulkImportPlanner` 已基本是纯逻辑）、未安装来源登记为 `source_origins`；
  来源解析通过 `ExternalSourceResolver` 接口注入（Android 用扩展管理器，iOS 用已安装扩展与仓库目录）；
  失败原因用枚举表示，由平台本地化。
- 反向导出（Kototoro 导出为 Mihon/Aniyomi/Usagi 格式，Android 的 `*BackupExportRepository`）同样以 protobuf/JSON
  模型为基础下沉，iOS 用同一入口导出。

## 4. 风险与验证

- 共享引擎改造 Android 现有同步时，需用现有同步/备份测试先固定行为，再迁移（先绿后改）。
- 合并规则已在 commonMain，iOS 与 Android 可以共用同一组快照夹具测试，避免两端合并结果分歧。
- iCloud 与 Google Drive 的真实账号测试无法在 CI 中进行；`SyncSnapshotStore` 用内存实现做端到端测试，
  真机人工验收。
