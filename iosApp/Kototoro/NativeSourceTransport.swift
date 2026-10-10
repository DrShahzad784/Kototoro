import Foundation
import KototoroShared

/// Owns the JNI session. The JVM and its static libraries live until process exit.
final class NativeSourceTransport: IosSourceTransport {
    private let lock = NSLock()
    private var session: OpaquePointer?
    private var closed = false

    static var isAvailable: Bool { ktr_vm_available() != 0 }

    static var dataDirectory: URL {
        get throws {
            try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                        appropriateFor: nil, create: true)
                .appendingPathComponent("Kototoro/sources", isDirectory: true)
        }
    }

    func exchange(requestJson: String) throws -> String {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { throw failure("源会话已关闭") }
        if session == nil { try open() }
        let input = Array(requestJson.utf16)
        var output: UnsafeMutablePointer<UInt16>?
        var length = 0
        var error: UnsafeMutablePointer<CChar>?
        let status = input.withUnsafeBufferPointer {
            ktr_session_exchange(session, $0.baseAddress, $0.count, &output, &length, &error)
        }
        defer { ktr_free(error); ktr_free(output) }
        guard status == 0, let output else { throw nativeFailure(error) }
        return String(decoding: UnsafeBufferPointer(start: output, count: length), as: UTF16.self)
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        guard !closed else { return }
        closed = true
        if let session {
            var error: UnsafeMutablePointer<CChar>?
            _ = ktr_session_close(session, &error)
            ktr_free(error)
            self.session = nil
        }
    }

    deinit { close() }

    private func open() throws {
        guard Self.isAvailable, let resources = Bundle.main.resourceURL else {
            throw failure("此构建未包含扩展运行时")
        }
        let runtime = resources.appendingPathComponent("jvm", isDirectory: true)
        let home = runtime.appendingPathComponent("java_bundle", isDirectory: true)
        let lib = runtime.appendingPathComponent("lib", isDirectory: true)
        let jars = try FileManager.default.contentsOfDirectory(at: lib, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "jar" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        let data = try Self.dataDirectory
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        var options = ["-Djava.io.tmpdir=\(FileManager.default.temporaryDirectory.path)",
                       "-Duser.home=\(data.path)"]
        let bootShims = runtime.appendingPathComponent("boot-shims.jar")
        if FileManager.default.fileExists(atPath: bootShims.path) {
            options.append("-Xbootclasspath/a:\(bootShims.path)")
        }
        let optionStrings = options.map { strdup($0) }
        defer { optionStrings.forEach { free($0) } }
        let pointers = optionStrings.map { $0.map { UnsafePointer<CChar>($0) } }
        var error: UnsafeMutablePointer<CChar>?
        let status = home.path.withCString { homePath in
            jars.map(\.path).joined(separator: ":").withCString { classpath in
                pointers.withUnsafeBufferPointer {
                    ktr_vm_start(homePath, classpath, $0.baseAddress, $0.count, &error)
                }
            }
        }
        defer { ktr_free(error) }
        guard status == 0 else { throw nativeFailure(error) }

        let configDirectory = runtime.appendingPathComponent("sources", isDirectory: true)
        let configURL = configDirectory.appendingPathComponent("config.json")
        guard var config = try JSONSerialization.jsonObject(with: Data(contentsOf: configURL)) as? [String: Any],
              var extensions = config["jars"] as? [[String: Any]] else {
            throw failure("扩展运行时配置无效")
        }
        for index in extensions.indices {
            guard let path = extensions[index]["path"] as? String else { throw failure("扩展路径缺失") }
            extensions[index]["path"] = configDirectory.appendingPathComponent(path).path
        }
        config["jars"] = extensions
        // Swift and the host share one image directory, irrespective of a preview's relative config path.
        config["imageDirectory"] = "images"
        let json = String(decoding: try JSONSerialization.data(withJSONObject: config), as: UTF8.self)
        let configuration = Array(json.utf16)
        let directory = Array(data.path.utf16)
        var openError: UnsafeMutablePointer<CChar>?
        session = configuration.withUnsafeBufferPointer { configText in
            directory.withUnsafeBufferPointer {
                ktr_session_open(configText.baseAddress, configText.count, $0.baseAddress, $0.count, &openError)
            }
        }
        defer { ktr_free(openError) }
        guard session != nil else { throw nativeFailure(openError) }
    }

    private func nativeFailure(_ error: UnsafeMutablePointer<CChar>?) -> NSError {
        failure(error.map { String(cString: $0) } ?? "扩展运行时调用失败")
    }

    private func failure(_ message: String) -> NSError {
        NSError(domain: "org.skepsun.kototoro.source", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
