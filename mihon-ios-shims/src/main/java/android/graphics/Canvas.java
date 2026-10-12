package android.graphics;

import org.skepsun.kototoro.ios.shims.IosNative;

import java.util.ArrayDeque;

/** Extension image composition in a Java ARGB buffer, with CoreText for glyph rendering. */
public final class Canvas {
    private Bitmap bitmap;
    private float tx, ty, a = 1, b, c, d = 1;
    private final ArrayDeque<float[]> saves = new ArrayDeque<>();

    public Canvas() {}

    public Canvas(Bitmap bitmap) {
        setBitmap(bitmap);
    }

    public void setBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isMutable())
            throw new IllegalStateException("Canvas needs a mutable bitmap");
        this.bitmap = bitmap;
    }

    public int getWidth() {
        return target().getWidth();
    }

    public int getHeight() {
        return target().getHeight();
    }

    private Bitmap target() {
        if (bitmap == null) throw new IllegalStateException("Canvas has no bitmap");
        return bitmap;
    }

    public void drawBitmap(Bitmap source, float x, float y, Paint paint) {
        drawBitmap(
                source,
                null,
                new Rect(
                        Math.round(x),
                        Math.round(y),
                        Math.round(x) + source.getWidth(),
                        Math.round(y) + source.getHeight()),
                paint);
    }

    public void drawBitmap(Bitmap source, Rect src, Rect dst, Paint paint) {
        if (src == null) src = new Rect(0, 0, source.getWidth(), source.getHeight());
        if (src.isEmpty() || dst.isEmpty()) return;
        if (src.left < 0
                || src.top < 0
                || src.right > source.getWidth()
                || src.bottom > source.getHeight())
            throw new IllegalArgumentException("Source rect outside bitmap");
        float determinant = a * d - b * c;
        if (Math.abs(determinant) < 1e-8f) return;
        float[] xs = {dst.left, dst.right, dst.right, dst.left},
                ys = {dst.top, dst.top, dst.bottom, dst.bottom};
        float minX = Float.POSITIVE_INFINITY,
                minY = minX,
                maxX = Float.NEGATIVE_INFINITY,
                maxY = maxX;
        for (int i = 0; i < 4; i++) {
            float x = tx + a * xs[i] + c * ys[i], y = ty + b * xs[i] + d * ys[i];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int[] pixels = source.kototoroPixels();
        if (source == bitmap) pixels = pixels.clone();
        for (int y = Math.max(0, (int) Math.floor(minY));
                y < Math.min(getHeight(), (int) Math.ceil(maxY));
                y++) {
            for (int x = Math.max(0, (int) Math.floor(minX));
                    x < Math.min(getWidth(), (int) Math.ceil(maxX));
                    x++) {
                float originalX = (d * (x + .5f - tx) - c * (y + .5f - ty)) / determinant;
                float originalY = (-b * (x + .5f - tx) + a * (y + .5f - ty)) / determinant;
                if (originalX < dst.left
                        || originalX >= dst.right
                        || originalY < dst.top
                        || originalY >= dst.bottom) continue;
                int ix =
                        src.left
                                + Math.min(
                                        src.width() - 1,
                                        (int) ((originalX - dst.left) * src.width() / dst.width()));
                int iy =
                        src.top
                                + Math.min(
                                        src.height() - 1,
                                        (int)
                                                ((originalY - dst.top)
                                                        * src.height()
                                                        / dst.height()));
                blend(
                        x,
                        y,
                        pixels[iy * source.getWidth() + ix],
                        paint == null ? 255 : paint.getAlpha());
            }
        }
    }

    private void blend(int x, int y, int color, int opacity) {
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return;
        int a = (color >>> 24) * opacity / 255;
        if (a == 0) return;
        int old = target().getPixel(x, y), oa = old >>> 24;
        int outA = a + oa * (255 - a) / 255;
        int result = outA << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            int c =
                    (((color >>> shift) & 255) * a + ((old >>> shift) & 255) * oa * (255 - a) / 255)
                            / outA;
            result |= c << shift;
        }
        target().setPixel(x, y, result);
    }

    public void drawColor(int color) {
        for (int y = 0; y < getHeight(); y++)
            for (int x = 0; x < getWidth(); x++) blend(x, y, color, 255);
    }

    public void drawColor(long color) {
        if ((color & 63) != 0)
            throw new UnsupportedOperationException("Wide-gamut colors are not available");
        drawColor((int) (color >>> 32));
    }

    public void drawARGB(int a, int r, int g, int b) {
        drawColor(a << 24 | r << 16 | g << 8 | b);
    }

    public void drawRGB(int r, int g, int b) {
        drawARGB(255, r, g, b);
    }

    public void drawPoint(float x, float y, Paint paint) {
        blend(
                Math.round(tx + a * x + c * y),
                Math.round(ty + b * x + d * y),
                paint.getColor(),
                255);
    }

    public void drawRect(float l, float t, float r, float b, Paint paint) {
        if (this.b != 0 || c != 0)
            throw new UnsupportedOperationException("Rotated rectangle paths are not available");
        int left = Math.round(tx + l * a),
                top = Math.round(ty + t * d),
                right = Math.round(tx + r * a),
                bottom = Math.round(ty + b * d);
        for (int y = Math.max(0, top); y < Math.min(getHeight(), bottom); y++)
            for (int x = Math.max(0, left); x < Math.min(getWidth(), right); x++) {
                if (paint.getStyle() != Paint.Style.STROKE
                        || x < left + paint.getStrokeWidth()
                        || x >= right - paint.getStrokeWidth()
                        || y < top + paint.getStrokeWidth()
                        || y >= bottom - paint.getStrokeWidth()) blend(x, y, paint.getColor(), 255);
            }
    }

    public void drawRect(Rect r, Paint p) {
        drawRect(r.left, r.top, r.right, r.bottom, p);
    }

    public void drawText(String text, float x, float y, Paint paint) {
        if (paint.getTextScaleX() != 1 || paint.getTextSkewX() != 0)
            throw new UnsupportedOperationException("Transformed text rendering is not available");
        float width = paint.measureText(text);
        if (paint.getTextAlign() == Paint.Align.CENTER) x -= width / 2;
        else if (paint.getTextAlign() == Paint.Align.RIGHT) x -= width;
        Typeface font = paint.getTypeface();
        IosNative.drawText(
                target().kototoroPixels(),
                getWidth(),
                getHeight(),
                text,
                x,
                y,
                font.getSystemFontFamilyName(),
                font.getStyle() | (paint.isFakeBoldText() ? 1 : 0),
                paint.getTextSize(),
                paint.getColor(),
                new float[] {a, this.b, c, d, tx, ty});
    }

    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        drawText(text.substring(start, end), x, y, paint);
    }

    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        drawText(text.subSequence(start, end).toString(), x, y, paint);
    }

    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        drawText(new String(text, index, count), x, y, paint);
    }

    public void translate(float x, float y) {
        tx += a * x + c * y;
        ty += b * x + d * y;
    }

    public void scale(float x, float y) {
        a *= x;
        b *= x;
        c *= y;
        d *= y;
    }

    public void scale(float x, float y, float px, float py) {
        translate(px, py);
        scale(x, y);
        translate(-px, -py);
    }

    public void rotate(float degrees) {
        double radians = Math.toRadians(degrees);
        float cos = (float) Math.cos(radians), sin = (float) Math.sin(radians), oldA = a, oldB = b;
        a = oldA * cos + c * sin;
        b = oldB * cos + d * sin;
        c = c * cos - oldA * sin;
        d = d * cos - oldB * sin;
    }

    public void rotate(float degrees, float px, float py) {
        translate(px, py);
        rotate(degrees);
        translate(-px, -py);
    }

    public void skew(float x, float y) {
        float oldA = a, oldB = b;
        a += c * y;
        b += d * y;
        c += oldA * x;
        d += oldB * x;
    }

    public int save() {
        int count = getSaveCount();
        saves.push(new float[] {tx, ty, a, b, c, d});
        return count;
    }

    public int getSaveCount() {
        return saves.size() + 1;
    }

    public void restore() {
        if (saves.isEmpty()) throw new IllegalStateException("Unbalanced restore");
        float[] state = saves.pop();
        tx = state[0];
        ty = state[1];
        a = state[2];
        b = state[3];
        c = state[4];
        d = state[5];
    }

    public void restoreToCount(int count) {
        if (count < 1 || count > getSaveCount()) throw new IllegalArgumentException();
        while (getSaveCount() > count) restore();
    }

    public boolean getClipBounds(Rect bounds) {
        bounds.set(0, 0, getWidth(), getHeight());
        return !bounds.isEmpty();
    }
}
