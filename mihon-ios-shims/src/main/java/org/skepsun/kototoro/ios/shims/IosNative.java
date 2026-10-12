package org.skepsun.kototoro.ios.shims;

/**
 * Apple framework operations, registered by KototoroNative.mm before any extension is constructed.
 */
public final class IosNative {
    static {
        String library = System.getProperty("kototoro.native.library");
        if (library != null) System.load(library);
    }

    private IosNative() {}

    public static native int[] decodeImage(byte[] data, boolean boundsOnly, int sampleSize);

    public static native byte[] encodeImage(
            int[] pixels, int width, int height, String format, int quality);

    public static native float[] measureText(String text, String family, int style, float size);

    public static native void drawText(
            int[] pixels,
            int width,
            int height,
            String text,
            float x,
            float y,
            String family,
            int style,
            float size,
            int color,
            float[] transform);

    public static native long openPdf(byte[] data);

    public static native int[] pdfPageInfo(long document, int index);

    public static native void renderPdf(
            long document, int index, int[] pixels, int width, int height);

    public static native void closePdf(long document);

    public static native long createJavaScript(Object bridge);

    public static native String evaluateJavaScript(long context, String script, String filename);

    public static native void closeJavaScript(long context);
}
