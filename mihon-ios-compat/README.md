# iOS Mihon JVM 平台适配器

可选模块；普通 Android/source-host/iOS 构建不包含本模块及第三方 API。
编译需 JDK 24、本地 Mobile AndroidCompat/Suwayomi JAR，以及 `compatibility-pins.properties` 中三个固定 API 摘要。
与 Windows 的 API pin 分离，自己的字节码仍为 Java 11。完整本地预览命令见 `../iosApp/README.md`。

```bash
./gradlew :mihon-ios-compat:stagePreview -PwithMihonIosCompat \
    -PmihonIosCompatibilityDirectory="/absolute/path/to/mobile-api"
```

平台使用应用沙盒中的独立 Application、持久化偏好桥、可停止的兼容 Looper、Koin/Injekt 与源 HTTP 客户端。
复用 `source-host` 的偏好和执行实现；没有启动服务端或使用上游 ExtensionHost。
每个 JVM 只允许一个兼容平台生命周期；关闭后重开须重启进程，扩展的常驻会话由 source-host 持有。
Close 尝试回收 HTTP 客户端、CookieHandler、容器和线程，最后释放偏好桥。

WKWebView 与 CookieManager 由本模块的 `IosWebKit`、`IosWebViewProvider` 和 `IosCookieManager` 接到 Swift。
图形、JavaScript、PDF、常用 ICU、系统资源和压缩由项目自写的 [`mihon-ios-shims`](../mihon-ios-shims/README.md)
提供，JNI 在 VM 启动时注册，不加载 TachiyomiAzIOS 的 shim JAR 或 boot shim。
包管理、未列出的系统服务以及复杂 Android framework API 仍明确 unsupported；支持边界见该模块 README。
本模块已验证再漫画 1.6.0 的转换/枚举/公开列表、详情/封面/章节及首章图片阅读；
默认预览和真实 APK 预览均非正式发行，S3 与真机门槛待完成。
