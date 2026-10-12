package android.graphics;

import org.skepsun.kototoro.ios.shims.IosNative;

/** Paint state and CoreText measurement used by extension image composition. */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1,
            FILTER_BITMAP_FLAG = 2,
            DITHER_FLAG = 4,
            UNDERLINE_TEXT_FLAG = 8,
            STRIKE_THRU_TEXT_FLAG = 16,
            FAKE_BOLD_TEXT_FLAG = 32,
            LINEAR_TEXT_FLAG = 64,
            SUBPIXEL_TEXT_FLAG = 128;
    public static final int START_HYPHEN_EDIT_NO_EDIT = 0, END_HYPHEN_EDIT_NO_EDIT = 0;

    public enum Style {
        FILL,
        STROKE,
        FILL_AND_STROKE
    }

    public enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    public enum Cap {
        BUTT,
        ROUND,
        SQUARE
    }

    public enum Join {
        MITER,
        ROUND,
        BEVEL
    }

    public static class FontMetrics {
        public float top, ascent, descent, bottom, leading;
    }

    public static class FontMetricsInt {
        public int top, ascent, descent, bottom, leading;
    }

    private int flags, color = 0xff000000;
    private float size = 16, scaleX = 1, skewX, strokeWidth, strokeMiter = 4;
    private Style style = Style.FILL;
    private Align align = Align.LEFT;
    private Cap cap = Cap.BUTT;
    private Join join = Join.MITER;
    private Typeface typeface = Typeface.DEFAULT;

    public Paint() {}

    public Paint(int flags) {
        this.flags = flags;
    }

    public Paint(Paint other) {
        set(other);
    }

    public void reset() {
        set(new Paint());
    }

    public void set(Paint p) {
        flags = p.flags;
        color = p.color;
        size = p.size;
        scaleX = p.scaleX;
        skewX = p.skewX;
        strokeWidth = p.strokeWidth;
        strokeMiter = p.strokeMiter;
        style = p.style;
        align = p.align;
        cap = p.cap;
        join = p.join;
        typeface = p.typeface;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    private void flag(int bit, boolean value) {
        flags = value ? flags | bit : flags & ~bit;
    }

    public boolean isAntiAlias() {
        return (flags & ANTI_ALIAS_FLAG) != 0;
    }

    public void setAntiAlias(boolean v) {
        flag(ANTI_ALIAS_FLAG, v);
    }

    public boolean isFilterBitmap() {
        return (flags & FILTER_BITMAP_FLAG) != 0;
    }

    public void setFilterBitmap(boolean v) {
        flag(FILTER_BITMAP_FLAG, v);
    }

    public boolean isDither() {
        return (flags & DITHER_FLAG) != 0;
    }

    public void setDither(boolean v) {
        flag(DITHER_FLAG, v);
    }

    public boolean isFakeBoldText() {
        return (flags & FAKE_BOLD_TEXT_FLAG) != 0;
    }

    public void setFakeBoldText(boolean v) {
        flag(FAKE_BOLD_TEXT_FLAG, v);
    }

    public boolean isUnderlineText() {
        return (flags & UNDERLINE_TEXT_FLAG) != 0;
    }

    public void setUnderlineText(boolean v) {
        flag(UNDERLINE_TEXT_FLAG, v);
    }

    public boolean isStrikeThruText() {
        return (flags & STRIKE_THRU_TEXT_FLAG) != 0;
    }

    public void setStrikeThruText(boolean v) {
        flag(STRIKE_THRU_TEXT_FLAG, v);
    }

    public int getColor() {
        return color;
    }

    public long getColorLong() {
        return (color & 0xffffffffL) << 32;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public void setColor(long color) {
        if ((color & 63) != 0)
            throw new UnsupportedOperationException("Wide-gamut colors are not available");
        setColor((int) (color >>> 32));
    }

    public int getAlpha() {
        return color >>> 24;
    }

    public void setAlpha(int alpha) {
        color = (color & 0xffffff) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    public void setARGB(int a, int r, int g, int b) {
        color = a << 24 | r << 16 | g << 8 | b;
    }

    public float getTextSize() {
        return size;
    }

    public void setTextSize(float size) {
        if (size < 0) throw new IllegalArgumentException();
        this.size = size;
    }

    public float getTextScaleX() {
        return scaleX;
    }

    public void setTextScaleX(float value) {
        scaleX = value;
    }

    public float getTextSkewX() {
        return skewX;
    }

    public void setTextSkewX(float value) {
        skewX = value;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public Typeface setTypeface(Typeface font) {
        Typeface old = typeface;
        typeface = font == null ? Typeface.DEFAULT : font;
        return old;
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style value) {
        style = value;
    }

    public Align getTextAlign() {
        return align;
    }

    public void setTextAlign(Align value) {
        align = value;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float value) {
        strokeWidth = value;
    }

    public float getStrokeMiter() {
        return strokeMiter;
    }

    public void setStrokeMiter(float value) {
        strokeMiter = value;
    }

    public Cap getStrokeCap() {
        return cap;
    }

    public void setStrokeCap(Cap value) {
        cap = value;
    }

    public Join getStrokeJoin() {
        return join;
    }

    public void setStrokeJoin(Join value) {
        join = value;
    }

    private float[] metrics(String text) {
        return IosNative.measureText(
                text,
                typeface.getSystemFontFamilyName(),
                typeface.getStyle() | (isFakeBoldText() ? 1 : 0),
                size);
    }

    public float measureText(String text) {
        return metrics(text)[0] * scaleX;
    }

    public float measureText(String text, int start, int end) {
        return measureText(text.substring(start, end));
    }

    public float measureText(CharSequence text, int start, int end) {
        return measureText(text.subSequence(start, end).toString());
    }

    public float measureText(char[] text, int index, int count) {
        return measureText(new String(text, index, count));
    }

    public float getFontMetrics(FontMetrics out) {
        float[] m = metrics("");
        if (out != null) {
            out.ascent = -m[1];
            out.top = -m[1];
            out.descent = m[2];
            out.bottom = m[2];
            out.leading = m[3];
        }
        return m[1] + m[2] + m[3];
    }

    public FontMetrics getFontMetrics() {
        FontMetrics out = new FontMetrics();
        getFontMetrics(out);
        return out;
    }

    public int getFontMetricsInt(FontMetricsInt out) {
        FontMetrics m = getFontMetrics();
        if (out != null) {
            out.top = (int) Math.floor(m.top);
            out.ascent = Math.round(m.ascent);
            out.descent = Math.round(m.descent);
            out.bottom = (int) Math.ceil(m.bottom);
            out.leading = Math.round(m.leading);
        }
        return Math.round(m.descent - m.ascent + m.leading);
    }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt out = new FontMetricsInt();
        getFontMetricsInt(out);
        return out;
    }

    public float ascent() {
        return getFontMetrics().ascent;
    }

    public float descent() {
        return getFontMetrics().descent;
    }

    public float getFontSpacing() {
        return getFontMetrics(null);
    }

    public float getUnderlineThickness() {
        return Math.max(1, size / 16);
    }

    public void getTextBounds(String text, int start, int end, Rect bounds) {
        bounds.set(
                0,
                (int) Math.floor(ascent()),
                (int) Math.ceil(measureText(text, start, end)),
                (int) Math.ceil(descent()));
    }

    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        getTextBounds(new String(text, index, count), 0, count, bounds);
    }

    public int breakText(String text, boolean forward, float maxWidth, float[] measured) {
        int count = 0;
        float width = 0;
        while (count < text.length()) {
            int next =
                    count
                            + Character.charCount(
                                    forward
                                            ? text.codePointAt(count)
                                            : text.codePointBefore(text.length() - count));
            float candidate =
                    forward
                            ? measureText(text, 0, next)
                            : measureText(text, text.length() - next, text.length());
            if (candidate > maxWidth) break;
            count = next;
            width = candidate;
        }
        if (measured != null && measured.length > 0) measured[0] = width;
        return count;
    }

    public int breakText(
            CharSequence text,
            int start,
            int end,
            boolean forward,
            float maxWidth,
            float[] measured) {
        return breakText(text.subSequence(start, end).toString(), forward, maxWidth, measured);
    }

    public int breakText(char[] text, int index, int count, float maxWidth, float[] measured) {
        return breakText(new String(text, index, Math.abs(count)), count >= 0, maxWidth, measured);
    }
}
