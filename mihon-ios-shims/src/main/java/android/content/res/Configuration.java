package android.content.res;

import java.util.Locale;

/** Basic locale and screen configuration for the headless extension host. */
public class Configuration implements Comparable<Configuration> {
    public static final int ORIENTATION_UNDEFINED = 0,
            ORIENTATION_PORTRAIT = 1,
            ORIENTATION_LANDSCAPE = 2;
    public float fontScale = 1;
    public Locale locale = Locale.getDefault();
    public int densityDpi = 480,
            screenWidthDp = 360,
            screenHeightDp = 780,
            smallestScreenWidthDp = 360;
    public int orientation = ORIENTATION_PORTRAIT, uiMode = 0x11;

    public Configuration() {}

    public Configuration(Configuration other) {
        setTo(other);
    }

    public void setTo(Configuration other) {
        fontScale = other.fontScale;
        locale = other.locale;
        densityDpi = other.densityDpi;
        screenWidthDp = other.screenWidthDp;
        screenHeightDp = other.screenHeightDp;
        smallestScreenWidthDp = other.smallestScreenWidthDp;
        orientation = other.orientation;
        uiMode = other.uiMode;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    @Override
    public int compareTo(Configuration other) {
        return locale.toLanguageTag().compareTo(other.locale.toLanguageTag());
    }
}
