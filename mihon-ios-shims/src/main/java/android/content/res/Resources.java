package android.content.res;

import android.util.AndroidRuntimeException;
import android.util.DisplayMetrics;

/**
 * The system resources an extension can reach without an APK of its own: display metrics and the
 * configuration. Lookups by resource id fail with NotFoundException, as there are no compiled
 * resources on iOS.
 */
public class Resources {
    public static final int ID_NULL = 0;
    private static final Resources SYSTEM = new Resources(null, new DisplayMetrics(), null);

    private final AssetManager assets;
    private final DisplayMetrics metrics;
    private final Configuration configuration;

    public Resources(AssetManager assets, DisplayMetrics metrics, Configuration configuration) {
        this.assets = assets;
        this.metrics = metrics == null ? new DisplayMetrics() : metrics;
        this.configuration =
                configuration == null ? new Configuration() : new Configuration(configuration);
    }

    public static Resources getSystem() {
        return SYSTEM;
    }

    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public final AssetManager getAssets() {
        return assets;
    }

    public void updateConfiguration(Configuration configuration, DisplayMetrics metrics) {
        if (metrics != null) this.metrics.setTo(metrics);
        if (configuration != null) this.configuration.setTo(configuration);
    }

    public int getIdentifier(String name, String type, String packageName) {
        return ID_NULL;
    }

    public String getString(int id) throws NotFoundException {
        throw new NotFoundException("String resource ID #0x" + Integer.toHexString(id));
    }

    public String getString(int id, Object... formatArguments) throws NotFoundException {
        return String.format(getString(id), formatArguments);
    }

    public CharSequence getText(int id) throws NotFoundException {
        return getString(id);
    }

    public CharSequence getText(int id, CharSequence fallback) {
        return fallback;
    }

    public float getDimension(int id) throws NotFoundException {
        throw new NotFoundException("Dimension resource ID #0x" + Integer.toHexString(id));
    }

    public int getDimensionPixelSize(int id) throws NotFoundException {
        return Math.round(getDimension(id));
    }

    public int getDimensionPixelOffset(int id) throws NotFoundException {
        return (int) getDimension(id);
    }

    public int getColor(int id) throws NotFoundException {
        throw new NotFoundException("Color resource ID #0x" + Integer.toHexString(id));
    }

    public boolean getBoolean(int id) throws NotFoundException {
        throw new NotFoundException("Boolean resource ID #0x" + Integer.toHexString(id));
    }

    public int getInteger(int id) throws NotFoundException {
        throw new NotFoundException("Integer resource ID #0x" + Integer.toHexString(id));
    }

    public String[] getStringArray(int id) throws NotFoundException {
        throw new NotFoundException("Array resource ID #0x" + Integer.toHexString(id));
    }

    public static class NotFoundException extends AndroidRuntimeException {
        public NotFoundException() {
            super();
        }

        public NotFoundException(String name) {
            super(name);
        }

        public NotFoundException(String name, Exception cause) {
            super(name, cause);
        }
    }
}
