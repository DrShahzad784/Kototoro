package android.graphics;

public class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
    public static final Typeface DEFAULT = new Typeface("Helvetica", NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface("Helvetica", BOLD);
    public static final Typeface SANS_SERIF = DEFAULT;
    public static final Typeface SERIF = new Typeface("Times New Roman", NORMAL);
    public static final Typeface MONOSPACE = new Typeface("Courier", NORMAL);
    private final String family;
    private final int style;

    private Typeface(String family, int style) {
        this.family = family;
        this.style = style;
    }

    public static Typeface create(String family, int style) {
        if (family == null || family.equals("sans-serif")) family = DEFAULT.family;
        else if (family.equals("serif")) family = SERIF.family;
        else if (family.equals("monospace")) family = MONOSPACE.family;
        return new Typeface(family, style & 3);
    }

    public static Typeface create(Typeface family, int style) {
        return create(family == null ? null : family.family, style);
    }

    public static Typeface defaultFromStyle(int style) {
        return create(DEFAULT, style);
    }

    public int getStyle() {
        return style;
    }

    public int getWeight() {
        return isBold() ? 700 : 400;
    }

    public boolean isBold() {
        return (style & BOLD) != 0;
    }

    public boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    public String getSystemFontFamilyName() {
        return family;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Typeface
                && style == ((Typeface) other).style
                && family.equals(((Typeface) other).family);
    }

    @Override
    public int hashCode() {
        return family.hashCode() * 31 + style;
    }
}
