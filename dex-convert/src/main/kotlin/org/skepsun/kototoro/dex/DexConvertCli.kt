package org.skepsun.kototoro.dex

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Local conversion entry point shared by platform development scripts. No Android package installation.
 * Arguments are `<input> <output.jar>` pairs: an extension APK (has AndroidManifest.xml) keeps its manifest as text,
 * a DEX-only parser plugin jar is converted as is. One `CONVERTED {json}` line per pair reports the identity a host
 * configuration needs.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty() && args.size % 2 == 0 && args.all(String::isNotBlank)) {
        "Usage: <extension.apk|plugin.jar> <output.jar> [<input> <output.jar> ...]"
    }
    for ((input, output) in args.toList().chunked(2).map { Path.of(it[0]) to Path.of(it[1]) }) {
        val isApk = ZipFile(input.toFile()).use { it.getEntry("AndroidManifest.xml") != null }
        val (report, identity) = if (isApk) {
            val converted = ApkExtensionConverter.convert(input, output)
            converted.report to """"kind":"apk","packageName":"${converted.manifest.packageName}",""" +
                """"versionCode":${converted.manifest.versionCode}"""
        } else {
            DexJarConverter.convert(input, output) to """"kind":"plugin""""
        }
        check(report.brokenMethods.isEmpty()) {
            "Conversion of $input contains broken methods: ${report.brokenMethods.joinToString()}"
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output))
            .joinToString("") { "%02x".format(it) }
        println("""CONVERTED {$identity,"output":"${output.toAbsolutePath()}","sha256":"$sha256","classes":${report.classes}}""")
    }
}
