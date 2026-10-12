package android.app;

/** Memory figures for the embedded JVM, not the application's entire native heap. */
public class ActivityManager {
    public static class MemoryInfo {
        public long availMem, totalMem, threshold;
        public boolean lowMemory;
    }

    public int getMemoryClass() {
        return (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    public int getLargeMemoryClass() {
        return getMemoryClass();
    }

    public boolean isLowRamDevice() {
        return Runtime.getRuntime().maxMemory() < 256L * 1024 * 1024;
    }

    public void getMemoryInfo(MemoryInfo info) {
        Runtime vm = Runtime.getRuntime();
        info.totalMem = vm.maxMemory();
        info.availMem = vm.maxMemory() - vm.totalMemory() + vm.freeMemory();
        info.threshold = info.totalMem / 10;
        info.lowMemory = info.availMem < info.threshold;
    }
}
