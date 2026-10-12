package org.skepsun.kototoro.ios.shims

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** Runs the actual Objective-C++/JNI bridge on macOS; no mock native implementation. */
class NativeShimsTest {
    @BeforeEach fun nativeLibrary() { assumeTrue(System.getProperty("kototoro.native.library") != null) }

    @Test fun `ImageIO PNG preserves row orientation channels and transparency`() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0x80808080.toInt())
        val source = Bitmap.createBitmap(pixels, 2, 2, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, stream))
        val bytes = stream.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        assertEquals(2, options.outWidth)
        assertEquals(2, options.outHeight)
        val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val actual = IntArray(4)
        image.getPixels(actual, 0, 2, 0, 0, 2, 2)
        assertArrayEquals(pixels, actual)
        assertNull(BitmapFactory.decodeByteArray(byteArrayOf(1, 2, 3), 0, 3))
    }

    @Test fun `JavaScriptCore contexts isolate globals bind Java and JS interfaces and propagate errors`() {
        QuickJs.create().use { js ->
            assertEquals("中文😀", js.evaluate("'中文😀'"))
            assertEquals(7.0, js.evaluate("const factor=7;factor"))
            js.set("math", MathApi::class.java, object : MathApi { override fun add(a: Int, b: Int) = a + b })
            assertEquals(5.0, js.evaluate("math.add(2,3)"))
            js.evaluate("globalThis.math2={add:function(a,b){return a+b+factor}}")
            assertEquals(12, js.get("math2", MathApi::class.java).add(2, 3))
            assertThrows(QuickJsException::class.java) { js.evaluate("throw new Error('错误😀')", "source.js") }
            QuickJs.create().use { other -> assertEquals("undefined", other.evaluate("typeof factor")) }
        }
        val closed = QuickJs.create()
        closed.close(); closed.close()
        assertThrows(IllegalStateException::class.java) { closed.evaluate("1") }
    }

    @Test fun `CoreText measures glyphs renders ink and wraps static layout`() {
        val paint = TextPaint().apply { textSize = 24f; color = 0xff000000.toInt() }
        assertTrue(paint.measureText("Hello世界") > 20)
        assertTrue(paint.ascent() < 0)
        assertTrue(paint.descent() > 0)
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xffffffff.toInt())
        val layout = StaticLayout("Hello\n世界", paint, 200, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
        assertEquals(2, layout.lineCount)
        layout.draw(Canvas(bitmap))
        assertTrue(bitmap.kototoroPixels().any { it != 0xffffffff.toInt() })
    }

    @Test fun `CoreGraphics PDF owns descriptor enforces page lifecycle and renders the page`() {
        val file = Files.createTempFile("kototoro-shims", ".pdf")
        // A minimal PDF fixture with one 20x10 point page and a red filled rectangle.
        val body = "0 0 1 rg 0 0 20 10 re f\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 20 10] /Contents 4 0 R >>", "<< /Length ${body.length} >>\nstream\n${body}endstream")
        val text = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, obj -> text.length.also { text.append("${index + 1} 0 obj\n$obj\nendobj\n") } }
        val xref = text.length
        text.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { text.append("%010d 00000 n \n".format(it)) }
        text.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        Files.writeString(file, text.toString())
        val descriptor = ParcelFileDescriptor.open(file.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { pdf ->
            assertEquals(1, pdf.pageCount)
            pdf.openPage(0).use { page ->
                assertEquals(20, page.width)
                assertEquals(10, page.height)
                assertThrows(IllegalStateException::class.java) { pdf.close() }
                val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                assertEquals(0xff0000ff.toInt(), bitmap.getPixel(20, 10))
            }
        }
        assertThrows(IllegalStateException::class.java) { descriptor.statSize }
    }

    interface MathApi { fun add(a: Int, b: Int): Int }
}
