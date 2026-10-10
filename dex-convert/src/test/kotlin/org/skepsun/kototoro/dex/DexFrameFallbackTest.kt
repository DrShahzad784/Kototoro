package org.skepsun.kototoro.dex

import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.smali.Smali
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class DexFrameFallbackTest {
    @TempDir lateinit var root: Path

    @Test
    fun `missing converter classpath parents merge to the actual JVM Object`() {
        // Left/Right are supplied only by the runtime loader, exactly as extension API classes are.
        val smali = """
            .class public Lfixture/Merge;
            .super Ljava/lang/Object;
            .method public static choose(ZLexternal/Left;Lexternal/Right;)Ljava/lang/Object;
                .registers 5
                if-eqz p0, :right
                move-object v0, p1
                goto :merge
                :right
                move-object v0, p2
                :merge
                invoke-static {v0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                move-result v1
                return-object v0
            .end method
        """.trimIndent()
        val writer = DexFileWriter()
        Smali.smaliFile2Node("Merge.smali", smali).accept(writer)
        val dex = Files.write(root.resolve("merge.dex"), writer.toByteArray())
        val jar = root.resolve("merge.jar")
        DexJarConverter.convert(dex, jar)
        val api = object : ClassLoader(null) {
            override fun findClass(name: String): Class<*> {
                if (name !in setOf("external.Left", "external.Right")) throw ClassNotFoundException(name)
                val bytes = ClassWriter(0).apply {
                    visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null)
                    visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                        visitCode()
                        visitVarInsn(Opcodes.ALOAD, 0)
                        visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                        visitInsn(Opcodes.RETURN)
                        visitMaxs(1, 1)
                        visitEnd()
                    }
                    visitEnd()
                }.toByteArray()
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
        URLClassLoader(arrayOf(jar.toUri().toURL()), api).use { loader ->
            val leftType = api.loadClass("external.Left")
            val rightType = api.loadClass("external.Right")
            val left = leftType.getConstructor().newInstance()
            val right = rightType.getConstructor().newInstance()
            val choose = loader.loadClass("fixture.Merge").getMethod("choose", Boolean::class.javaPrimitiveType,
                leftType, rightType)
            assertSame(left, choose.invoke(null, true, left, right))
            assertSame(right, choose.invoke(null, false, left, right))
        }
    }
}
