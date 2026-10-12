package org.skepsun.kototoro.dex

import com.googlecode.d2j.dex.ClassVisitorFactory
import com.googlecode.d2j.dex.ExDex2Asm
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.node.DexFileNode
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.smali.Smali
import com.googlecode.dex2jar.ir.IrMethod
import com.googlecode.dex2jar.ir.stmt.LabelStmt
import com.googlecode.dex2jar.ir.stmt.Stmt
import com.googlecode.dex2jar.ir.ts.RemoveLocalFromSSA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** The linear pass must leave every method exactly as dex2jar's own `RemoveLocalFromSSA` would. */
class LinearRemoveLocalFromSSATest {

    @Test
    fun `loop phis fed by one value or by several match the stock pass`() {
        // `v0` only circulates through loop phis from one initial value (removed); `v1` merges two initial values
        // (kept); the nested loop gives phis whose ancestry passes through another loop's phi.
        val smali = """
            .class public Lfixture/Loops;
            .super Ljava/lang/Object;
            .method public static run(ZLjava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;
                .registers 8
                move-object v0, p1
                if-eqz p0, :other
                move-object v1, p1
                goto :outer
                :other
                move-object v1, p2
                :outer
                if-lez p3, :done
                move v2, p3
                :inner
                if-lez v2, :next
                move-object v3, v0
                move-object v0, v3
                invoke-static {v1}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                add-int/lit8 v2, v2, -1
                goto :inner
                :next
                add-int/lit8 p3, p3, -1
                goto :outer
                :done
                invoke-static {v0}, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                return-object v1
            .end method
        """.trimIndent()
        val writer = DexFileWriter()
        Smali.smaliFile2Node("Loops.smali", smali).accept(writer)
        val compared = compareAllMethods(writer.toByteArray())
        assertTrue(compared.phiMethods > 0, "fixture must reach the pass with phis")
        assertEquals(emptyList<String>(), compared.mismatches)
    }

    @Test
    fun `every method of a real plugin matches the stock pass`() {
        val plugin = System.getProperty("kototoro.dex.real.plugin")
        assumeTrue(plugin != null && Files.isRegularFile(Path.of(plugin)), "No real plugin configured")
        val dex = ZipFile(plugin).use { zip -> zip.getInputStream(zip.getEntry("classes.dex")).readBytes() }
        val compared = compareAllMethods(dex)
        println("compared ${compared.methods} methods, ${compared.phiMethods} with phis")
        assertEquals(emptyList<String>(), compared.mismatches.take(20))
    }

    private class Comparison(val methods: Int, val phiMethods: Int, val mismatches: List<String>)

    /** Runs dex2jar's optimisation up to the last local pass, checking both transformers at each call site. */
    private fun compareAllMethods(dex: ByteArray): Comparison {
        val stock = RemoveLocalFromSSA()
        val linear = LinearRemoveLocalFromSSA()
        val mismatches = ArrayList<String>()
        var methods = 0
        var phiMethods = 0
        fun check(method: IrMethod) {
            if (method.phiLabels?.isNotEmpty() == true) phiMethods++
            val expected = method.clone()
            val actual = method.clone()
            val expectedChanged = stock.transformReportChanged(expected)
            val actualChanged = linear.transformReportChanged(actual)
            if (expectedChanged != actualChanged || canonical(expected) != canonical(actual)) {
                mismatches += "${method.owner}.${method.name}"
            }
            stock.transform(method)
        }
        val node = DexFileNode()
        DexFileReader(dex).accept(node, DexFileReader.SKIP_DEBUG)
        object : ExDex2Asm(null) {
            override fun optimize(irMethod: IrMethod) {
                methods++
                T_CLEAN_LABEL.transform(irMethod)
                T_DEAD_CODE.transform(irMethod)
                check(irMethod)
                T_REMOVE_CONST.transform(irMethod)
                T_ZERO.transform(irMethod)
                if (T_NPE.transformReportChanged(irMethod)) {
                    T_DEAD_CODE.transform(irMethod)
                    check(irMethod)
                    T_REMOVE_CONST.transform(irMethod)
                }
                T_NEW.transform(irMethod)
                T_FILL_ARRAY.transform(irMethod)
                T_AGG.transform(irMethod)
                T_MULTI_ARRAY.transform(irMethod)
                T_VOID_INVOKE.transform(irMethod)
                T_DEAD_CODE.transform(irMethod)
                check(irMethod)
            }

            override fun ir2j(irMethod: IrMethod, mv: MethodVisitor, clzCtx: ClzCtx) = Unit
        }.convertDex(node, ClassVisitorFactory { ClassWriter(0) })
        return Comparison(methods, phiMethods, mismatches)
    }

    /**
     * Labels and locals print identity hashes, and phi operands come from hash sets, so both are renamed by
     * position and operands compared as sets.
     */
    private fun canonical(method: IrMethod): String {
        var label = 0
        method.locals.forEachIndexed { index, local -> local.debugName = "v$index" }
        for (stmt in method.stmts) if (stmt is LabelStmt) stmt.displayName = "L${label++}"
        return buildString {
            append(method.locals.joinToString()).append('\n')
            for (stmt in method.stmts) {
                if (stmt.st == Stmt.ST.LABEL) {
                    val labelStmt = stmt as LabelStmt
                    append(labelStmt.displayName).append(':')
                    labelStmt.phis.orEmpty()
                        .map { phi -> "${phi.op1} = phi(${phi.op2.ops.map(Any::toString).sorted()})" }
                        .sorted()
                        .forEach { append(' ').append(it) }
                } else {
                    append(stmt)
                }
                append('\n')
            }
            append(method.phiLabels.orEmpty().map { it.displayName })
        }
    }
}
