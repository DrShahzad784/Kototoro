package android.util;

/**
 * Display metrics of the phone-sized page the extension host pretends to be (xxhdpi, 1080 x 2340).
 */
public class DisplayMetrics {
    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_140 = 140;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_180 = 180;
    public static final int DENSITY_200 = 200;
    public static final int DENSITY_TV = 213;
    public static final int DENSITY_220 = 220;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_260 = 260;
    public static final int DENSITY_280 = 280;
    public static final int DENSITY_300 = 300;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_340 = 340;
    public static final int DENSITY_360 = 360;
    public static final int DENSITY_400 = 400;
    public static final int DENSITY_420 = 420;
    public static final int DENSITY_440 = 440;
    public static final int DENSITY_450 = 450;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_560 = 560;
    public static final int DENSITY_600 = 600;
    public static final int DENSITY_XXXHIGH = 640;
    public static final int DENSITY_DEFAULT = DENSITY_MEDIUM;
    public static final int DENSITY_DEVICE_STABLE = DENSITY_XXHIGH;

    public float density;
    public int densityDpi;
    public int heightPixels;
    public float scaledDensity;
    public int widthPixels;
    public float xdpi;
    public float ydpi;

    public DisplayMetrics() {
        setToDefaults();
    }

    public void setTo(DisplayMetrics other) {
        density = other.density;
        densityDpi = other.densityDpi;
        heightPixels = other.heightPixels;
        scaledDensity = other.scaledDensity;
        widthPixels = other.widthPixels;
        xdpi = other.xdpi;
        ydpi = other.ydpi;
    }

    public void setToDefaults() {
        densityDpi = DENSITY_XXHIGH;
        density = densityDpi / (float) DENSITY_DEFAULT;
        scaledDensity = density;
        widthPixels = 1080;
        heightPixels = 2340;
        xdpi = densityDpi;
        ydpi = densityDpi;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DisplayMetrics && equals((DisplayMetrics) other);
    }

    public boolean equals(DisplayMetrics other) {
        return other != null
                && density == other.density
                && densityDpi == other.densityDpi
                && heightPixels == other.heightPixels
                && scaledDensity == other.scaledDensity
                && widthPixels == other.widthPixels
                && xdpi == other.xdpi
                && ydpi == other.ydpi;
    }

    @Override
    public int hashCode() {
        return widthPixels * heightPixels * densityDpi;
    }

    @Override
    public String toString() {
        return "DisplayMetrics{density="
                + density
                + ", width="
                + widthPixels
                + ", height="
                + heightPixels
                + ", scaledDensity="
                + scaledDensity
                + ", xdpi="
                + xdpi
                + ", ydpi="
                + ydpi
                + "}";
    }
}
