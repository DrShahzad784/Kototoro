package android.graphics;

/** Integer, half-open pixel bounds, matching Android's public rectangle API. */
public final class Rect {
    public int left, top, right, bottom;

    public Rect() {}

    public Rect(int left, int top, int right, int bottom) {
        set(left, top, right, bottom);
    }

    public Rect(Rect other) {
        set(other);
    }

    public void set(int l, int t, int r, int b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public void set(Rect other) {
        set(other.left, other.top, other.right, other.bottom);
    }

    public void setEmpty() {
        set(0, 0, 0, 0);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int getWidth() {
        return width();
    }

    public int getHeight() {
        return height();
    }

    public int centerX() {
        return (left + right) >> 1;
    }

    public int centerY() {
        return (top + bottom) >> 1;
    }

    public float exactCenterX() {
        return (left + right) * 0.5f;
    }

    public float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    public boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public boolean contains(int x, int y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(Rect r) {
        return !isEmpty()
                && left <= r.left
                && top <= r.top
                && right >= r.right
                && bottom >= r.bottom;
    }

    public void offset(int dx, int dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    public void offsetTo(int x, int y) {
        offset(x - left, y - top);
    }

    public void inset(int dx, int dy) {
        left += dx;
        right -= dx;
        top += dy;
        bottom -= dy;
    }

    public boolean intersect(int l, int t, int r, int b) {
        if (left >= r || l >= right || top >= b || t >= bottom) return false;
        set(Math.max(left, l), Math.max(top, t), Math.min(right, r), Math.min(bottom, b));
        return true;
    }

    public boolean intersect(Rect r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    public static boolean intersects(Rect a, Rect b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    public String flattenToString() {
        return left + " " + top + " " + right + " " + bottom;
    }

    public static Rect unflattenFromString(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 4) return null;
        try {
            return new Rect(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    public String toShortString() {
        return "[" + left + "," + top + "][" + right + "," + bottom + "]";
    }

    public String toShortString(StringBuilder builder) {
        return builder.append(toShortString()).toString();
    }

    @Override
    public String toString() {
        return "Rect(" + flattenToString() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rect)) return false;
        Rect r = (Rect) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(left, top, right, bottom);
    }
}
