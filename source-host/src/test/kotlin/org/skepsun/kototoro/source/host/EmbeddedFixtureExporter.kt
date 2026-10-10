package org.skepsun.kototoro.source.host

import org.skepsun.kototoro.core.source.SourceProtocolJson
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider

/** Exports only self-authored test ABI; never included in the production source-host distribution. */
fun main(args: Array<String>) {
    require(args.size == 1)
    val root = Path.of(args[0]).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val fixture = RuntimeJarFixture(root)
    val jar = fixture.sourceJar()
    val source = root.resolve("EmbeddedFixturePlatform.java")
    Files.writeString(source, """
        package fixture;
        public final class EmbeddedFixturePlatform implements org.skepsun.kototoro.source.host.SourceHostPlatform {
            public ClassLoader initialize() { return getClass().getClassLoader(); }
            public void close() {}
        }
    """.trimIndent())
    val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
    check(compiler.run(null, null, null, "--release", "11", "-classpath", System.getProperty("java.class.path"),
        "-d", fixture.jars.apiClasses.toString(), source.toString()) == 0)
    fixture.jars.jar(root.resolve("fixture-api.jar"), null, fixture.jars.apiClasses)
    val config = SourceHostConfig("fixture.EmbeddedFixturePlatform",
        listOf(SourceHostJar(jar.fileName.toString(), fixture.jars.identity(jar))), "images")
    Files.writeString(root.resolve("config.json"), SourceProtocolJson.encodeToString(config))
}
