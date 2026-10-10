package org.skepsun.kototoro.dex

import java.nio.file.Path

/** Local conversion entry point shared by platform development scripts. No Android package installation. */
fun main(args: Array<String>) {
    require(args.size == 2 && args.all(String::isNotBlank)) { "Usage: <extension.apk> <output.jar>" }
    val converted = ApkExtensionConverter.convert(Path.of(args[0]), Path.of(args[1]))
    check(converted.report.brokenMethods.isEmpty()) {
        "Conversion contains broken methods: ${converted.report.brokenMethods.joinToString()}"
    }
    println(
        "Converted ${converted.manifest.packageName} (${converted.manifest.versionCode}); " +
            "${converted.report.classes} classes",
    )
}
