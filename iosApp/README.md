# Kototoro iOS 开发入口

接手开发先读 [iOS 阶段交接记录](../docs/architecture/ios-development-handoff-2026-10.md)，
其中列出调用链、生命周期约束、验证证据与后续工作入口。

本模块已打通 SwiftUI 漫画库分类 → Kotlin/Native → 共享 Room v84 数据库，以及可选的
SwiftUI → 共享源协议 → C/JNI → OpenJDK Mobile Zero → JVM source-host 源调用链。
可选预览已支持列表 → 详情/封面 → 章节 → 图片阅读，以及前后翻页和同分支换章。
`ios-shared` 是 iOS 平台装配层，复用 `core-db`、`core-domain`、`core-source`、`core-backup`、`core-net`
和 `reader-core`。Swift 只消费应用接口，不直接操作 Room 实体或 JVM parser ABI。

## 本机运行

需要 Apple Silicon Mac、Xcode、iOS 模拟器运行时、JDK 17+，以及本项目 Android SDK 配置。
首次 Gradle 构建会下载 Kotlin/Native 和 Apple 目标依赖。Xcode 构建阶段使用
[Kotlin 官方 direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
编译 `KototoroShared` 静态 framework；无需 CocoaPods 或 XcodeGen。

```bash
open "iosApp/Kototoro.xcodeproj"
```

选择 `Kototoro` scheme 和 iPhone/iPad 模拟器，然后运行。JDK 由构建脚本通过 `java_home -v '17+'` 定位。
真机运行时，在 Xcode Signing & Capabilities 中选择自己的开发团队。

命令行构建（仓库根目录）：

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme "Kototoro" \
    -configuration Debug -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO build
```

默认应用支持添加、读取分类；分类写入同一数据库并在重启后恢复。默认构建不包含第三方 JVM/兼容 JAR，
浏览页显示运行时未包含的提示。下面的开发预览可枚举源、显示第一页列表并进入详情与章节阅读。
数据库位于应用沙盒 `Library/Application Support/Kototoro/library.db`，不在缓存或临时目录。
不使用破坏性 schema 回退；导入旧版本 SQLite 文件的迁移不属于当前切片。

## 验证

同时打包真机 Arm64 和模拟器 Arm64 的 Debug XCFramework：

```bash
./gradlew :ios-shared:assembleKototoroSharedDebugXCFramework
```

输出为 `ios-shared/build/XCFrameworks/debug/KototoroShared.xcframework`。真机切片构建通过不等于真机运行验收。

先用 `xcrun simctl list devices available` 获取设备 UUID，再执行：

```bash
./gradlew :ios-shared:iosSimulatorArm64Test -PiosSimulatorDevice=<UUID>

xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme "Kototoro" \
    -configuration Debug -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO \
    -parallel-testing-enabled NO test
```

Native 测试覆盖数据库重开、空标题拒绝和排序；XCTest 覆盖 SwiftUI 创建分类及应用重启后的读取。
UI 测试每次创建唯一分类，测试数据留在测试设备沙盒中。

如依赖下载失败，可给 Gradle 添加仓库约定的代理参数：
`-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`。
Xcode 构建阶段也会调用 Gradle，可用命令作用域内的 `JAVA_TOOL_OPTIONS` 传入这些参数，或在首次构建前完成下载。

## 嵌入式 JVM 模拟器预览

额外需要 JDK 24。所有下载/源码/构建工具/原生库/兼容 JAR 均在 ignored `iosApp/build` 中；
脚本不创建 Git 分支、不提交、不执行全局安装或清理用户数据。GNU m4/autoconf 安装到本地 build/tools。
固定版本和 SHA-256 见 `Scripts/jvm-preview-pins.json`，上游补丁和原始许可证保存在下载的源码树中。

仓库根目录执行：

```bash
python3 "iosApp/Scripts/build-jvm-preview.py"
python3 "iosApp/Scripts/test-jvm-bridge.py"
```

第一条从固定 OpenJDK 源码构建 **arm64 模拟器 Zero**，应用 iOS 路径/静态符号补丁及模拟器 MAP_JIT / W^X
补丁。Java 26 模块数据和 legal 目录取自已校验的 TachiyomiAzIOS v8.9.1，避免与移动 VM 混用新版 snapshot。
默认预览只放入自有测试 ABI/扩展；不使用第三方兼容 JAR 或上游 ExtensionHost。
第二条验证同一个 C/JNI 桥的 Unicode、64 位 ID、多线程交换、Java 异常恢复和关闭。

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/JVMPreview" CODE_SIGNING_ALLOWED=NO KOTOTORO_JVM=1 \
    "KOTOTORO_JVM_LDFLAGS=$(pwd)/iosApp/build/jvm/libkototoro-jvm.a -lz -liconv -framework Security -framework SystemConfiguration" \
    -only-testing:KototoroUITests/SourceRuntimeTests/testIndependentSourceRunsThroughEmbeddedJVM test
```

测试覆盖源枚举、真实 Java fixture 调用、应用终止/重启、详情/章节、图片解码、前后翻页、换章与返回导航。
仍不是生态兼容性验收。
不同预览使用各自的 DerivedData 目录。普通构建若复用含 JVM 产物的目录会明确拒绝，避免残留的第三方产物进入默认包。

## 真实 Mihon APK 预览

```bash
python3 "iosApp/Scripts/stage-mihon-preview.py"
```

该脚本从同一已校验 IPA 提取 Mobile API/boot shims，启用独立 `:mihon-ios-compat`，
下载 SHA-256 固定的再漫画 1.6.0 APK，并调用共享 `:dex-convert:convertApk` 生成 JAR。
没有安装 APK、启动 Suwayomi Server 或调用上游 ExtensionHost。运行时 JAR 以明确的编号维持 classpath 顺序，
保留独立的 serialization-json-okio 与 coroutines-jdk8 适配包；共享基础 Kotlin/JSON/coroutines 使用本项目版本。
生成目录为 `iosApp/build/jvm-real-api`，`inventory.json` 记录每个本地产物的摘要。

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/RealApiPreview" CODE_SIGNING_ALLOWED=NO KOTOTORO_JVM=1 \
    "KOTOTORO_JVM_DIRECTORY=$(pwd)/iosApp/build/jvm-real-api" \
    "KOTOTORO_JVM_LDFLAGS=$(pwd)/iosApp/build/jvm-real-api/libkototoro-jvm.a -lz -liconv -framework Security -framework SystemConfiguration" \
    -only-testing:KototoroUITests/SourceRuntimeTests/testConvertedRealMihonApkRunsOnSimulator test
```

此测试访问真实源的公开在线列表，依赖站点与网络可用性。2026-10-10 已验证再漫画
`MIHON_524579092615598717` 的 APK 转换、模拟器内枚举及第一页列表，返回 10 项。
继续验证《大理寺日志》详情与封面、392 个章节，以及首章两张真实图片的 SwiftUI 阅读/翻页。
JNI 复用常驻 JVM/Java 会话；Java 的临时目录与数据目录位于应用沙盒，偏好文件由既有 store 持久化。
`IosSources` 复用 `core-source/SourceProtocolClient`，Swift 不重复实现版本/请求 ID/错误/64 位 ID 协议。

APK 转换期间修复了 dex2jar 2.4.38 的 `java/util/Object` 公共父类型回退错误，仅纠正栈帧，
不改写指令/字段/字符串；转换缓存版本由 3 升为 4，已有 Windows 消费者同步获益。
最小 DEX/JVM 回归覆盖外部类型不在转换 classpath、实际运行时两条分支均可验证并执行。

详情、章节与页对象在 Kotlin 内保留完整 `SourceContent` / `SourceChapter` / `SourcePage`，包括 opaque
字段和原生页面索引；Swift 不重建请求模型。翻章复用 `SourceChapterNavigation` 的来源/分支规则。
封面与正文图片均由 JVM 源客户端下载并物化，保持扩展自己的 headers/interceptors/cookies，
Swift 读取同一沙盒图片目录中的文件，校验路径、长度、SHA-256 后使用 ImageIO 在后台解码。
ImageIO 长边上限为 4096 像素，阅读器只持有当前页的解码图片；超长图片的精细分块仍待接入。

新增 Native 回归验证完整隐藏字段及图片身份校验；`SourceImageFileTests` 验证实际 PNG 解码、篡改、
长度错误、路径越界、符号链接、超限和无法解码的数据。可单独执行：

```bash
xcodebuild -project "iosApp/Kototoro.xcodeproj" -scheme Kototoro \
    -configuration Debug -destination 'platform=iOS Simulator,id=<UUID>' \
    -derivedDataPath "iosApp/build/DerivedData" CODE_SIGNING_ALLOWED=NO \
    -only-testing:KototoroUITests/SourceImageFileTests test
```

## 接续范围

1. 接入用户选择 APK/JAR 的导入与安装记录，继续复用共享转换器/校验器。
2. 补充搜索、分页、收藏/历史与阅读进度持久化，接入完整阅读场景与缩放/分块。
3. 接入 WKWebView、WebKit Cookie、JavaScript 与图形 native 桥，扩大代表源兼容验证。
4. 补充真机 Zero 构建/启动、内存和代表源性能数据；模拟器测试不替代主方案 G0/G2。
5. 完成运行时/兼容层的分发与许可证 S3 门槛。

[上游 IOS_PORT.md](https://github.com/az4521/TachiyomiAZ/blob/master/IOS_PORT.md) 记录了 OpenJDK 模拟器
切片对 `MAP_JIT`、W^X 与 `pthread_jit_write_protect_np` 的专用补丁；接入 JVM 时需要逐项核对。

第三方源码/运行时仅用于本地可选开发预览，没有加入仓库或默认发行包。WKWebView/完整 Cookie 同步、
JS/图形、用户扩展安装界面、备份恢复、完整漫画库和完整场景阅读器仍待实现；
当前基本图片阅读不能代表全量源或真机兼容性。Swift Task 取消会丢弃过期结果，
尚不能中断已进入同步 JNI 的网络调用；调用仍受 JVM 源客户端的超时约束。
