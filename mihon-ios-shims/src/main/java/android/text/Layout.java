package android.text;

import android.graphics.Canvas;
import android.graphics.Rect;

/**
 * Text layout state used by extension-generated images, independent of AndroidCompat's AWT text
 * renderer.
 */
public abstract class Layout {
    public enum Alignment {
        ALIGN_NORMAL,
        ALIGN_OPPOSITE,
        ALIGN_CENTER
    }

    public static final int DIR_LEFT_TO_RIGHT = 1, DIR_RIGHT_TO_LEFT = -1;

    public static final class Directions {
        private Directions() {}
    }

    public static final Directions DIRS_ALL_LEFT_TO_RIGHT = new Directions(),
            DIRS_ALL_RIGHT_TO_LEFT = new Directions();
    private final CharSequence text;
    private final TextPaint paint;
    private int width;
    private final Alignment alignment;
    private final float multiplier, addition;

    protected Layout(
            CharSequence text,
            TextPaint paint,
            int width,
            Alignment alignment,
            float multiplier,
            float addition) {
        if (width < 0) throw new IllegalArgumentException("Negative layout width");
        this.text = text;
        this.paint = paint;
        this.width = width;
        this.alignment = alignment;
        this.multiplier = multiplier;
        this.addition = addition;
    }

    public static float getDesiredWidth(CharSequence text, TextPaint paint) {
        return getDesiredWidth(text, 0, text.length(), paint);
    }

    public static float getDesiredWidth(CharSequence text, int start, int end, TextPaint paint) {
        float width = 0;
        for (String paragraph : text.subSequence(start, end).toString().split("\n", -1))
            width = Math.max(width, paint.measureText(paragraph));
        return width;
    }

    public CharSequence getText() {
        return text;
    }

    public TextPaint getPaint() {
        return paint;
    }

    public int getWidth() {
        return width;
    }

    public Alignment getAlignment() {
        return alignment;
    }

    public float getSpacingMultiplier() {
        return multiplier;
    }

    public float getSpacingAdd() {
        return addition;
    }

    public void increaseWidthTo(int width) {
        if (width < this.width) throw new IllegalArgumentException();
        this.width = width;
    }

    public abstract int getLineCount();

    public abstract int getLineTop(int line);

    public abstract int getLineDescent(int line);

    public abstract int getLineStart(int line);

    public abstract int getParagraphDirection(int line);

    public abstract boolean getLineContainsTab(int line);

    public abstract Directions getLineDirections(int line);

    public abstract int getTopPadding();

    public abstract int getBottomPadding();

    public abstract int getEllipsisStart(int line);

    public abstract int getEllipsisCount(int line);

    public int getHeight() {
        return getLineTop(getLineCount());
    }

    public int getHeight(boolean cap) {
        return getHeight();
    }

    public int getLineEnd(int line) {
        return getLineStart(line + 1);
    }

    public int getLineBottom(int line) {
        return getLineTop(line + 1);
    }

    public int getLineBaseline(int line) {
        return getLineBottom(line) - getLineDescent(line);
    }

    public int getLineAscent(int line) {
        return getLineTop(line) - getLineBaseline(line);
    }

    public float getLineWidth(int line) {
        return paint.measureText(text, getLineStart(line), visibleEnd(line));
    }

    private int visibleEnd(int line) {
        int end = getLineEnd(line);
        return end > getLineStart(line) && text.charAt(end - 1) == '\n' ? end - 1 : end;
    }

    public int getLineForVertical(int y) {
        int line = 0;
        while (line + 1 < getLineCount() && getLineTop(line + 1) <= y) line++;
        return line;
    }

    public int getLineForOffset(int offset) {
        int line = 0;
        while (line + 1 < getLineCount() && getLineStart(line + 1) <= offset) line++;
        return line;
    }

    public int getLineBounds(int line, Rect bounds) {
        bounds.set(0, getLineTop(line), width, getLineBottom(line));
        return getLineBaseline(line);
    }

    public void draw(Canvas canvas) {
        for (int line = 0; line < getLineCount(); line++) {
            float measured = getLineWidth(line);
            float x =
                    alignment == Alignment.ALIGN_CENTER
                            ? (width - measured) / 2
                            : alignment == Alignment.ALIGN_OPPOSITE ? width - measured : 0;
            canvas.drawText(
                    text, getLineStart(line), visibleEnd(line), x, getLineBaseline(line), paint);
        }
    }

    public void drawText(Canvas canvas) {
        draw(canvas);
    }
}
