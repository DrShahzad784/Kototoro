package android.os;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Monotonic VM clock. AndroidCompat's management/AWT-dependent clock is never loaded. */
public final class SystemClock {
    private SystemClock() {}

    public static long uptimeMillis() {
        return System.nanoTime() / 1_000_000;
    }

    public static long elapsedRealtime() {
        return uptimeMillis();
    }

    public static long elapsedRealtimeNanos() {
        return System.nanoTime();
    }

    public static void sleep(long millis) {
        if (millis <= 0) return;
        long start = elapsedRealtime();
        boolean interrupted = false;
        do {
            try {
                Thread.sleep(Math.max(0, millis - (elapsedRealtime() - start)));
            } catch (InterruptedException error) {
                interrupted = true;
            }
        } while (elapsedRealtime() - start < millis);
        if (interrupted) Thread.currentThread().interrupt();
    }

    public static boolean setCurrentTimeMillis(long millis) {
        return false;
    }

    public static Clock uptimeMillisClock() {
        return uptimeClock();
    }

    public static Clock uptimeClock() {
        return monotonic(ZoneOffset.UTC);
    }

    public static Clock elapsedRealtimeClock() {
        return uptimeClock();
    }

    private static Clock monotonic(ZoneId zone) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId newZone) {
                return monotonic(newZone);
            }

            @Override
            public long millis() {
                return uptimeMillis();
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis());
            }
        };
    }
}
