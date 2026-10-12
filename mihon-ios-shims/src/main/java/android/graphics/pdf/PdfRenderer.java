package android.graphics.pdf;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;

import org.skepsun.kototoro.ios.shims.IosNative;

import java.io.IOException;

/** PDF page rasterization through CoreGraphics, with Android's descriptor/page ownership rules. */
public final class PdfRenderer implements AutoCloseable {
    private final ParcelFileDescriptor descriptor;
    private long document;
    private final int pages;
    private Page open;

    public PdfRenderer(ParcelFileDescriptor descriptor) throws IOException {
        document = IosNative.openPdf(descriptor.kototoroReadBytes());
        if (document == 0) throw new IOException("Invalid or encrypted PDF");
        this.descriptor = descriptor;
        pages = IosNative.pdfPageInfo(document, -1)[0];
    }

    private void check() {
        if (document == 0) throw new IllegalStateException("PDF is closed");
    }

    public synchronized int getPageCount() {
        check();
        return pages;
    }

    public boolean shouldScaleForPrinting() {
        check();
        return true;
    }

    public synchronized Page openPage(int index) {
        check();
        if (open != null) throw new IllegalStateException("Close the current page first");
        if (index < 0 || index >= pages) throw new IllegalArgumentException("Invalid PDF page");
        int[] size = IosNative.pdfPageInfo(document, index);
        return open = new Page(index, size[0], size[1]);
    }

    @Override
    public synchronized void close() {
        check();
        if (open != null) throw new IllegalStateException("Close the current page first");
        IosNative.closePdf(document);
        document = 0;
        try {
            descriptor.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public final class Page implements java.io.Closeable {
        public static final int RENDER_MODE_FOR_DISPLAY = 1, RENDER_MODE_FOR_PRINT = 2;
        private final int index, width, height;
        private boolean closed;

        private Page(int index, int width, int height) {
            this.index = index;
            this.width = width;
            this.height = height;
        }

        private void checkPage() {
            check();
            if (closed) throw new IllegalStateException("PDF page is closed");
        }

        public int getIndex() {
            checkPage();
            return index;
        }

        public int getWidth() {
            checkPage();
            return width;
        }

        public int getHeight() {
            checkPage();
            return height;
        }

        public void render(Bitmap bitmap, Rect clip, Matrix transform, int mode) {
            synchronized (PdfRenderer.this) {
                checkPage();
                if (!bitmap.isMutable() || bitmap.getConfig() != Bitmap.Config.ARGB_8888)
                    throw new IllegalArgumentException("PDF requires a mutable ARGB bitmap");
                if (mode != RENDER_MODE_FOR_DISPLAY && mode != RENDER_MODE_FOR_PRINT)
                    throw new IllegalArgumentException("Invalid render mode");
                if (transform != null || clip != null)
                    throw new UnsupportedOperationException(
                            "PDF transforms and partial rendering are not available");
                IosNative.renderPdf(
                        document,
                        index,
                        bitmap.kototoroPixels(),
                        bitmap.getWidth(),
                        bitmap.getHeight());
            }
        }

        @Override
        public void close() {
            synchronized (PdfRenderer.this) {
                checkPage();
                closed = true;
                open = null;
            }
        }
    }
}
