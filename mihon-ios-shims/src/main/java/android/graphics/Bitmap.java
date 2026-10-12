package android.graphics;

import android.util.DisplayMetrics;

import org.skepsun.kototoro.ios.shims.IosNative;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * ARGB pixels live in Java; ImageIO is used only at the encoded image boundary. No AWT is loaded.
 */
public final class Bitmap {
    public enum Config {
        ALPHA_8,
        RGB_565,
        ARGB_4444,
        ARGB_8888,
        RGBA_F16,
        HARDWARE
    }

    public enum CompressFormat {
        JPEG,
        PNG,
        WEBP,
        WEBP_LOSSY,
        WEBP_LOSSLESS
    }

    public static final int DENSITY_NONE = 0;
    private final int width, height;
    private final Config config;
    private final boolean mutable;
    private int[] pixels;
    private int density = DisplayMetrics.DENSITY_DEFAULT;
    private boolean alpha = true;

    private Bitmap(int width, int height, Config config, boolean mutable) {
        if (width <= 0 || height <= 0 || (long) width * height > 64 * 1024 * 1024)
            throw new IllegalArgumentException(
                    "Invalid or excessive bitmap dimensions: " + width + "x" + height);
        if (config == null || config == Config.HARDWARE || config == Config.RGBA_F16)
            throw new IllegalArgumentException("Unsupported bitmap configuration: " + config);
        this.width = width;
        this.height = height;
        this.config = config;
        this.mutable = mutable;
        pixels = new int[width * height];
        alpha = config != Config.RGB_565;
        if (!alpha) Arrays.fill(pixels, 0xff000000);
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height, config, true);
    }

    public static Bitmap createBitmap(
            DisplayMetrics metrics, int width, int height, Config config) {
        Bitmap result = createBitmap(width, height, config);
        if (metrics != null) result.setDensity(metrics.densityDpi);
        return result;
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    public static Bitmap createBitmap(
            int[] colors, int offset, int stride, int width, int height, Config config) {
        Bitmap result = new Bitmap(width, height, config, false);
        result.transfer(colors, offset, stride, 0, 0, width, height, true);
        return result;
    }

    public static Bitmap createBitmap(Bitmap source) {
        return source.copy(source.config, false);
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        source.region(x, y, width, height);
        Bitmap result =
                createBitmap(
                        source.pixels,
                        y * source.width + x,
                        source.width,
                        width,
                        height,
                        source.config);
        result.density = source.density;
        result.alpha = source.alpha;
        return result;
    }

    public static Bitmap createScaledBitmap(Bitmap source, int width, int height, boolean filter) {
        Bitmap result = createBitmap(width, height, source.config);
        new Canvas(result).drawBitmap(source, null, new Rect(0, 0, width, height), null);
        result.density = source.density;
        return result;
    }

    public Bitmap copy(Config config, boolean mutable) {
        check();
        Bitmap result = new Bitmap(width, height, config, mutable);
        for (int i = 0; i < pixels.length; i++) result.pixels[i] = result.color(pixels[i]);
        result.density = density;
        return result;
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        check();
        if (quality < 0 || quality > 100)
            throw new IllegalArgumentException("Quality must be 0..100");
        if (format == null || stream == null) throw new NullPointerException();
        byte[] data = IosNative.encodeImage(pixels, width, height, format.name(), quality);
        if (data == null) return false;
        try {
            stream.write(data);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    public int getWidth() {
        check();
        return width;
    }

    public int getHeight() {
        check();
        return height;
    }

    public Config getConfig() {
        return config;
    }

    public int getDensity() {
        return density;
    }

    public void setDensity(int density) {
        this.density = density;
    }

    public int getRowBytes() {
        return width * 4;
    }

    public int getByteCount() {
        return width * height * 4;
    }

    public int getAllocationByteCount() {
        return getByteCount();
    }

    public boolean isMutable() {
        return mutable && !isRecycled();
    }

    public boolean isRecycled() {
        return pixels == null;
    }

    public boolean hasAlpha() {
        return alpha;
    }

    public void setHasAlpha(boolean alpha) {
        this.alpha = alpha && config != Config.RGB_565;
    }

    public boolean isPremultiplied() {
        return false;
    }

    public int getPixel(int x, int y) {
        region(x, y, 1, 1);
        return pixels[y * width + x];
    }

    public void setPixel(int x, int y, int value) {
        writable();
        region(x, y, 1, 1);
        pixels[y * width + x] = color(value);
    }

    public void getPixels(int[] values, int offset, int stride, int x, int y, int w, int h) {
        transfer(values, offset, stride, x, y, w, h, false);
    }

    public void setPixels(int[] values, int offset, int stride, int x, int y, int w, int h) {
        writable();
        transfer(values, offset, stride, x, y, w, h, true);
    }

    public void eraseColor(int color) {
        writable();
        Arrays.fill(pixels, color(color));
    }

    public void recycle() {
        pixels = null;
    }

    public boolean sameAs(Bitmap other) {
        check();
        return other != null
                && width == other.width
                && height == other.height
                && Arrays.equals(pixels, other.pixels);
    }

    public void copyPixelsToBuffer(Buffer buffer) {
        check();
        if (buffer instanceof IntBuffer) ((IntBuffer) buffer).put(pixels);
        else if (buffer instanceof ByteBuffer)
            for (int pixel : pixels) ((ByteBuffer) buffer).putInt(pixel);
        else throw new IllegalArgumentException("Expected IntBuffer or ByteBuffer");
    }

    public void copyPixelsFromBuffer(Buffer buffer) {
        writable();
        if (buffer instanceof IntBuffer) ((IntBuffer) buffer).get(pixels);
        else if (buffer instanceof ByteBuffer)
            for (int i = 0; i < pixels.length; i++) pixels[i] = ((ByteBuffer) buffer).getInt();
        else throw new IllegalArgumentException("Expected IntBuffer or ByteBuffer");
    }

    /** Internal JNI boundary, deliberately independent of Android's private native bitmap ABI. */
    public int[] kototoroPixels() {
        check();
        return pixels;
    }

    private void check() {
        if (pixels == null) throw new IllegalStateException("Bitmap is recycled");
    }

    private void writable() {
        check();
        if (!mutable) throw new IllegalStateException("Bitmap is immutable");
    }

    private void region(int x, int y, int w, int h) {
        check();
        if (x < 0 || y < 0 || w < 0 || h < 0 || (long) x + w > width || (long) y + h > height)
            throw new IllegalArgumentException("Region is outside bitmap");
    }

    private int color(int value) {
        if (config == Config.RGB_565) {
            int r = (value >>> 19) & 31, g = (value >>> 10) & 63, b = (value >>> 3) & 31;
            return 0xff000000
                    | ((r << 3) | (r >>> 2)) << 16
                    | ((g << 2) | (g >>> 4)) << 8
                    | (b << 3)
                    | (b >>> 2);
        }
        if (config == Config.ALPHA_8) return value & 0xff000000;
        return value;
    }

    private void transfer(
            int[] values, int offset, int stride, int x, int y, int w, int h, boolean intoBitmap) {
        region(x, y, w, h);
        if (w == 0 || h == 0) return;
        long last = offset + (long) (h - 1) * stride;
        if (Math.abs((long) stride) < w
                || offset < 0
                || last < 0
                || (long) offset + w > values.length
                || last + w > values.length)
            throw new ArrayIndexOutOfBoundsException("Pixel array/stride is too small");
        for (int row = 0; row < h; row++) {
            if (intoBitmap)
                for (int col = 0; col < w; col++)
                    pixels[(y + row) * width + x + col] =
                            color(values[offset + row * stride + col]);
            else System.arraycopy(pixels, (y + row) * width + x, values, offset + row * stride, w);
        }
    }
}
