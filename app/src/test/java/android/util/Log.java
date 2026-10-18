package android.util;

/** Minimal unit-test replacement for the Android log API used by the production Logger. */
public final class Log {
    private Log() {
    }

    public static int println(int priority, String tag, String message) {
        return 0;
    }
}
