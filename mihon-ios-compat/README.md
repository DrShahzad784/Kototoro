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

WKWebView、系统服务、Android 资源、包管理、图形与 JavaScript 仍未实现，相关调用明确 unsupported。
来自上游 Mobile shim 的 native 方法也尚未注册，不宣称所有这类调用都可用。
本模块已验证再漫画 1.6.0 的转换/枚举/公开列表、详情/封面/章节及首章图片阅读；
默认预览和真实 APK 预览均非正式发行，S3 与真机门槛待完成。
