package uvd;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** DEV PROFILER (2026-09-25): per-phase wall-clock accumulators for the
 *  edit pipeline, ON only with `-Dfvd.timing=1` (every call site is a
 *  static-final-guarded no-op otherwise — zero cost in normal runs).
 *  Pure Java, no JavaFX. The `-Dfvd.bridgeprobe=<file>` hook resets and
 *  reports it per simulated spinner step; any future hook can do the
 *  same around a gesture. */
public final class Prof {
    public static final boolean ON = System.getProperty("fvd.timing") != null;
    private static final Map<String, long[]> ACC = new LinkedHashMap<>();

    private Prof() {}

    /** A start stamp (0 when off — add() ignores it). */
    public static long t() {
        return ON ? System.nanoTime() : 0L;
    }

    /** Accumulate the time since `t0` under `key` (+1 call). */
    public static void add(String key, long t0) {
        if (!ON) return;
        long[] a = ACC.computeIfAbsent(key, k -> new long[2]);
        a[0] += System.nanoTime() - t0;
        a[1]++;
    }

    /** Count an event under `key` without a duration. */
    public static void count(String key) {
        if (!ON) return;
        ACC.computeIfAbsent(key, k -> new long[2])[1]++;
    }

    public static void reset() {
        if (ON) ACC.clear();
    }

    /** "key 12× 34.5 ms | ..." in insertion order. */
    public static String report() {
        if (!ON) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, long[]> e : ACC.entrySet()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(String.format(Locale.US, "%s %d× %.1f ms",
                e.getKey(), e.getValue()[1], e.getValue()[0] / 1e6));
        }
        return sb.toString();
    }
}
