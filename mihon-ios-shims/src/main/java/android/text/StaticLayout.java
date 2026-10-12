package android.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, newline-aware wrapping using CoreText measurement. Advanced Android span/bidi layout
 * is unsupported.
 */
public class StaticLayout extends Layout {
    private final List<Integer> starts = new ArrayList<>();
    private final int lineHeight, descent;

    public StaticLayout(
            CharSequence text,
            TextPaint paint,
            int width,
            Alignment align,
            float multiplier,
            float addition,
            boolean includePadding) {
        super(text, paint, width, align, multiplier, addition);
        lineHeight = Math.max(1, Math.round(paint.getFontSpacing() * multiplier + addition));
        descent = (int) Math.ceil(paint.descent());
        String value = text.toString();
        starts.add(0);
        int offset = 0;
        while (offset < value.length()) {
            int newline = value.indexOf('\n', offset);
            int end = newline < 0 ? value.length() : newline;
            int count = paint.breakText(value.substring(offset, end), true, width, null);
            if (count == 0 && offset < end) count = Character.charCount(value.codePointAt(offset));
            int next = offset + count;
            if (next == end && newline >= 0) next++;
            else if (next < end) {
                int word = value.lastIndexOf(' ', next - 1);
                if (word > offset) next = word + 1;
            }
            if (next <= offset) next = offset + 1;
            offset = next;
            starts.add(offset);
        }
        if (value.isEmpty() || value.endsWith("\n")) starts.add(value.length());
    }

    public StaticLayout(
            CharSequence text,
            int start,
            int end,
            TextPaint paint,
            int width,
            Alignment align,
            float multiplier,
            float addition,
            boolean padding) {
        this(text.subSequence(start, end), paint, width, align, multiplier, addition, padding);
    }

    public static class Builder {
        private final CharSequence text;
        private final TextPaint paint;
        private final int width;
        private Alignment align = Alignment.ALIGN_NORMAL;
        private float multiplier = 1, addition;
        private boolean padding = true;

        private Builder(CharSequence text, TextPaint paint, int width) {
            this.text = text;
            this.paint = paint;
            this.width = width;
        }

        public static Builder obtain(
                CharSequence text, int start, int end, TextPaint paint, int width) {
            return new Builder(text.subSequence(start, end), paint, width);
        }

        public Builder setAlignment(Alignment align) {
            this.align = align;
            return this;
        }

        public Builder setIncludePad(boolean padding) {
            this.padding = padding;
            return this;
        }

        public Builder setLineSpacing(float addition, float multiplier) {
            this.addition = addition;
            this.multiplier = multiplier;
            return this;
        }

        public StaticLayout build() {
            return new StaticLayout(text, paint, width, align, multiplier, addition, padding);
        }
    }

    @Override
    public int getLineCount() {
        return starts.size() - 1;
    }

    @Override
    public int getLineStart(int line) {
        return starts.get(line);
    }

    @Override
    public int getLineTop(int line) {
        if (line < 0 || line > getLineCount()) throw new IndexOutOfBoundsException();
        return line * lineHeight;
    }

    @Override
    public int getLineDescent(int line) {
        return descent;
    }

    @Override
    public int getParagraphDirection(int line) {
        return DIR_LEFT_TO_RIGHT;
    }

    @Override
    public boolean getLineContainsTab(int line) {
        return getText().subSequence(getLineStart(line), getLineEnd(line)).toString().indexOf('\t')
                >= 0;
    }

    @Override
    public Directions getLineDirections(int line) {
        return DIRS_ALL_LEFT_TO_RIGHT;
    }

    @Override
    public int getTopPadding() {
        return 0;
    }

    @Override
    public int getBottomPadding() {
        return 0;
    }

    @Override
    public int getEllipsisStart(int line) {
        return 0;
    }

    @Override
    public int getEllipsisCount(int line) {
        return 0;
    }
}
