package android.graphics;

import org.skepsun.kototoro.ios.shims.IosNative;

import java.io.*;

public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds, inMutable, inDither, inPurgeable, inInputShareable;
        public boolean inScaled = true, inPremultiplied = true;
        public int inSampleSize = 1, inDensity, inTargetDensity, inScreenDensity;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public Bitmap inBitmap;
        public byte[] inTempStorage;
        public int outWidth = -1, outHeight = -1;
        public String outMimeType;

        public void requestCancelDecode() {}
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        if (offset < 0 || length < 0 || (long) offset + length > data.length)
            throw new ArrayIndexOutOfBoundsException();
        if (options != null) {
            options.outWidth = -1;
            options.outHeight = -1;
            options.outMimeType = null;
        }
        int[] decoded =
                IosNative.decodeImage(
                        java.util.Arrays.copyOfRange(data, offset, offset + length),
                        options != null && options.inJustDecodeBounds,
                        options == null ? 1 : Math.max(1, options.inSampleSize));
        if (decoded == null) return null;
        int width = decoded[0], height = decoded[1];
        if (options != null) {
            options.outWidth = width;
            options.outHeight = height;
        }
        if (options != null && options.inJustDecodeBounds) return null;
        Bitmap bitmap =
                Bitmap.createBitmap(
                        decoded,
                        2,
                        width,
                        width,
                        height,
                        options == null ? Bitmap.Config.ARGB_8888 : options.inPreferredConfig);
        if (options != null && options.inMutable)
            bitmap = bitmap.copy(options.inPreferredConfig, true);
        if (options != null && options.inDensity > 0) bitmap.setDensity(options.inDensity);
        return bitmap;
    }

    public static Bitmap decodeStream(InputStream stream) {
        return decodeStream(stream, null, null);
    }

    public static Bitmap decodeStream(InputStream stream, Rect padding, Options options) {
        if (stream == null) return null;
        try {
            byte[] bytes = stream.readAllBytes();
            if (padding != null) padding.set(-1, -1, -1, -1);
            return decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IOException error) {
            return null;
        }
    }

    public static Bitmap decodeFile(String file) {
        return decodeFile(file, null);
    }

    public static Bitmap decodeFile(String file, Options options) {
        try (InputStream stream = new FileInputStream(file)) {
            return decodeStream(stream, null, options);
        } catch (IOException error) {
            return null;
        }
    }
}
