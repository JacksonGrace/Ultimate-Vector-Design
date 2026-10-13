package uvd;

import java.util.Locale;

/** PulseClock gate (batch 36) — the POV/fly ride clock's pulse
 *  smoother: an EVEN-window median (the mean of the two middle order
 *  statistics) plus a bounded drift accumulator, off during the seed
 *  phase, hitches passed through whole.
 *  Pins: (a) the measured Windows sawtooth (24 × 16 ms then one 32 ms
 *  swallowed tick) advances within 3 % of its mean every pulse once
 *  warm — the raw delta jumped 2× — and the advances sum to the wall
 *  time (ratio within 0.3 %); (b) the alternating 24/8 ms hint pairs
 *  likewise (an ODD median would flip 8/24), and a 12 ms delta is a
 *  frame of its own; (c) a realistic mixed stream (12 % hint pulses,
 *  skipped ticks, a 12 ms) sums to wall time, plus the heavy 30 %-hint
 *  stress stream; (d) a hitch (90 ms > 2.5× the median) passes through
 *  whole, stays OUT of the window and the next pulse is unaffected; (e)
 *  the 0.1 s cap; (f) the seeding pulse returns −1, a busy 32 ms first
 *  pulse advances the nominal 1/60 s (seed phase, no drift), a 47 ms
 *  first pulse is a hitch; (g) a steady 16.2 ms cadence is reproduced
 *  EXACTLY once the window is full; (h) reset() refills the seed; (i)
 *  12 busy 32 ms pulses at startup, then steady 16 ms — no advance over
 *  1.15× the steady cadence at any pulse, and the cadence returns
 *  exactly; (ii) 3-8 ms hint pulses interleaved — never below 0.85× nor
 *  above 1.15× of 16 ms once warm; (j) a sub-hitch busy stretch AFTER
 *  warm-up: the drift is bounded, the catch-up never exceeds BLEED, the
 *  cadence recovers; (k) the hitch threshold's NOMINAL floor — with
 *  the median dipped by hint pulses a 32 ms swallowed tick is still
 *  smoothed, never passed whole — and the drift SURVIVES a hitch.
 *  Run: java -cp "target/classes;gates/out" uvd.PulseClockGate */
public class PulseClockGate {
    static int pass, fail;

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + name
            + (detail == null ? "" : "  " + detail));
    }

    static String f(double v) { return String.format(Locale.US, "%.6f", v); }

    static PulseClock warm(double period) {
        PulseClock c = new PulseClock();
        for (int i = 0; i < 2 * PulseClock.WINDOW; i++) c.smooth(period);
        return c;
    }

    /** Feed a repeated pattern; returns {worstRelDev vs `ref` after
     *  warm, ratio Σadv/Σraw after warm, min adv after warm, max adv
     *  after warm, pulses}. */
    static double[] run(PulseClock c, double[] pat, int reps, double ref) {
        int warmN = 2 * PulseClock.WINDOW, n = 0;
        double sumRaw = 0, sumAdv = 0, worst = 0;
        double lo = Double.MAX_VALUE, hi = 0;
        for (int rep = 0; rep < reps; rep++) {
            for (double raw : pat) {
                double adv = c.smooth(raw);
                n++;
                if (n <= warmN) continue;
                sumRaw += raw; sumAdv += adv;
                worst = Math.max(worst, Math.abs(adv - ref) / ref);
                lo = Math.min(lo, adv); hi = Math.max(hi, adv);
            }
        }
        return new double[]{ worst, sumAdv / sumRaw, lo, hi, n };
    }

    public static void main(String[] args) {
        check("0 the window is even (the mid-average median)",
            PulseClock.WINDOW % 2 == 0 && PulseClock.WINDOW >= 26,
            "WINDOW " + PulseClock.WINDOW);

        // (a) the measured Windows sawtooth
        double[] saw = new double[25];
        java.util.Arrays.fill(saw, 0.016);
        saw[24] = 0.032;
        double sawMean = (24 * 0.016 + 0.032) / 25;
        double[] ra = run(new PulseClock(), saw, 40, sawMean);
        check("a sawtooth advance within 3% of its mean at every warm pulse (was 2x on the skipped tick)",
            ra[0] < 0.03, String.format(Locale.US, "worst %.4f (min %s max %s)", ra[0], f(ra[2]), f(ra[3])));
        check("a advances sum to wall time (rate exact to the bounded drift)",
            Math.abs(ra[1] - 1) < 0.003,
            String.format(Locale.US, "ratio %.5f over %.0f pulses", ra[1], ra[4]));

        // (b) alternating 24/8 hint pairs
        double[] rb = run(new PulseClock(), new double[]{ 0.024, 0.008 }, 200, 0.016);
        check("b 24/8 pairs advance within 3% of 16 ms (an odd median would flip 8/24)",
            rb[0] < 0.03, String.format(Locale.US, "worst %.4f", rb[0]));
        check("b 24/8 pairs sum to wall time", Math.abs(rb[1] - 1) < 0.003,
            String.format(Locale.US, "ratio %.5f", rb[1]));
        PulseClock c = warm(0.0165);
        double f12 = c.smooth(0.012);
        check("b a 12 ms delta is a frame (advances ~the cadence, not 12 ms)",
            f12 > 0.016 && f12 < 0.017, f(f12));

        // (c) a realistic mixed stream: 12% hint pulses (3-8 ms with
        // their 24-29 ms partners), one skipped tick, a 12 ms
        double[] mixed = new double[50];
        java.util.Arrays.fill(mixed, 0.016);
        mixed[7] = 0.032;
        mixed[12] = 0.0035; mixed[13] = 0.0285;
        mixed[20] = 0.008;  mixed[21] = 0.024;
        mixed[31] = 0.012;
        mixed[38] = 0.0052; mixed[39] = 0.0268;
        mixed[45] = 0.032;
        double[] rc = run(new PulseClock(), mixed, 30, 0.016);
        check("c mixed stream (12% hints + skipped ticks): advances sum to wall time",
            Math.abs(rc[1] - 1) < 0.003,
            String.format(Locale.US, "ratio %.5f over %.0f pulses", rc[1], rc[4]));
        check("c mixed stream: every warm advance within 15% of 16 ms",
            rc[2] > 0.85 * 0.016 && rc[3] < 1.15 * 0.016,
            "min " + f(rc[2]) + " max " + f(rc[3]));
        // the heavy STRESS stream (30% hint pulses, mean 14.5 ms under a
        // 16 ms median): the drift bleed carries it (needed 1.5 ms/pulse
        // < the 10% cap) — reported, bound loose by design
        double[] stress = { 0.016, 0.016, 0.016, 0.032, 0.013, 0.0035, 0.016,
                            0.016, 0.0086, 0.0086, 0.016, 0.016, 0.016, 0.016,
                            0.030, 0.002, 0.016, 0.016, 0.016, 0.0165 };
        double[] rs = run(new PulseClock(), stress, 60, 0.016);
        check("c2 30%-hint stress stream: rate within 1% of wall, every advance >= 0.85x 16 ms",
            Math.abs(rs[1] - 1) < 0.01 && rs[2] >= 0.85 * 0.016,
            String.format(Locale.US, "ratio %.5f min %s max %s", rs[1], f(rs[2]), f(rs[3])));

        // (d) a hitch passes through whole, stays out of the window, and
        // is forgotten
        c = warm(0.016);
        double hitch = c.smooth(0.09);
        double per = c.period();
        double after = c.smooth(0.016);
        check("d hitch (90 ms > 2.5x median) returned whole", hitch == 0.09, f(hitch));
        check("d the hitch never entered the window (period still 16 ms)",
            Math.abs(per - 0.016) < 1e-15, f(per));
        check("d the pulse after a hitch is unaffected (16 ms exactly)",
            Math.abs(after - 0.016) < 1e-12, f(after));
        double tick = c.smooth(0.032);
        check("d a 32 ms skipped tick is smoothed (16.5 ms: median + drift), not passed through",
            tick > 0.016 && tick < 0.0166, f(tick));

        // (e) the cap via advance(now)
        c = new PulseClock();
        check("f seeding pulse returns -1", c.advance(1_000_000_000L) == -1, null);
        double capped = c.advance(6_000_000_000L);   // 5 s later
        check("e a 5 s gap is capped at 0.1 s (a hitch)", capped == 0.1, f(capped));

        // (f) the nominal seed
        c = new PulseClock();
        c.advance(1L);
        double first = c.advance(1 + 32_000_000L);
        check("f 32 ms first pulse advances the nominal 1/60 s (seed phase, no drift)",
            Math.abs(first - PulseClock.NOMINAL) < 1e-15, f(first));
        c = new PulseClock();
        c.advance(1L);
        double h47 = c.advance(1 + 47_000_000L);
        double next = c.advance(1 + 63_000_000L);
        check("f 47 ms first pulse = hitch: whole, then the nominal (no inflation)",
            h47 == 0.047 && Math.abs(next - PulseClock.NOMINAL) < 1e-15,
            f(h47) + " then " + f(next));

        // (g) a steady cadence is reproduced EXACTLY once the window is full
        c = new PulseClock();
        double last = 0;
        for (int i = 0; i < PulseClock.WINDOW + 5; i++) last = c.smooth(0.0162);
        check("g steady 16.2 ms cadence reproduced exactly after one window",
            last == 0.0162, String.format(Locale.US, "%.9f", last));

        // (h) reset refills the nominal seed
        c.reset();
        check("h reset() refills the nominal period",
            Math.abs(c.period() - PulseClock.NOMINAL) < 1e-15, f(c.period()));

        // (i) a busy startup: 12 pulses of 32 ms, then steady 16 ms
        c = new PulseClock();
        double maxAdv = 0, adv40 = 0;
        for (int i = 0; i < 12 + 60; i++) {
            double adv = c.smooth(i < 12 ? 0.032 : 0.016);
            maxAdv = Math.max(maxAdv, adv);
            if (i == 40) adv40 = adv;
        }
        check("i busy startup (12 x 32 ms): no advance over 1.15x the steady 16 ms cadence",
            maxAdv <= 1.15 * 0.016, "max " + f(maxAdv));
        check("i ...and the cadence returns exactly (pulse 40 = 16 ms)",
            adv40 == 0.016, f(adv40));

        // (ii) hint pulses interleaved
        double[] hints = { 0.016, 0.016, 0.008, 0.024, 0.016, 0.016, 0.003, 0.029, 0.016 };
        double[] rh = run(new PulseClock(), hints, 50, 0.016);
        check("ii 3-8 ms hint pulses interleaved: every warm advance within 0.85x..1.15x of 16 ms",
            rh[2] > 0.85 * 0.016 && rh[3] < 1.15 * 0.016,
            "min " + f(rh[2]) + " max " + f(rh[3]));
        check("ii hint stream sums to wall time", Math.abs(rh[1] - 1) < 0.003,
            String.format(Locale.US, "ratio %.5f", rh[1]));

        // (j) a sub-hitch busy stretch AFTER warm-up: 12 x 32 ms — the
        // drift is bounded and bled at <= BLEED x the median; a long
        // 30 ms stretch (40 pulses) becomes the cadence, and the return
        // to 16 ms never drops the advance below 0.85x nor keeps a tail
        c = warm(0.016);
        maxAdv = 0;
        for (int i = 0; i < 12 + 60; i++) {
            double adv = c.smooth(i < 12 ? 0.032 : 0.016);
            maxAdv = Math.max(maxAdv, adv);
        }
        check("j 12 busy 32 ms pulses after warm-up: catch-up capped at 1 + BLEED (1.12x)",
            maxAdv <= (1 + PulseClock.BLEED) * 0.016 + 1e-12 && maxAdv > 0.0165,
            "max " + f(maxAdv));
        c = warm(0.016);
        double minAdv = Double.MAX_VALUE, at300 = 0;
        for (int i = 0; i < 40; i++) c.smooth(0.030);
        double busyPer = c.period();
        for (int i = 0; i < 300; i++) {
            double adv = c.smooth(0.016);
            minAdv = Math.min(minAdv, adv);
            if (i == 299) at300 = adv;
        }
        check("j a 40-pulse 30 ms stretch becomes the cadence (median 30 ms)",
            Math.abs(busyPer - 0.030) < 1e-15, f(busyPer));
        check("j the return to 16 ms never advances below 0.85x (bounded drift) and recovers",
            minAdv >= 0.85 * 0.016 && Math.abs(at300 - 0.016) < 1e-5,
            "min " + f(minAdv) + " at+300 " + f(at300));

        // (k) the hitch threshold's NOMINAL floor (D254 closing round): on
        // a hint-heavy machine the median dips (here: a sustained 8 ms
        // cadence), and HITCH × the dipped median would call the 32 ms
        // swallowed tick a hitch and pass it WHOLE — the periodic skip
        // back. Floored at the nominal period the tick is smoothed; a
        // real hitch (> 2.5 × 1/60 s = 41.7 ms) still passes whole.
        c = warm(0.008);
        double dipTick = c.smooth(0.032);
        check("k a 32 ms tick over a dipped 8 ms median is SMOOTHED (not passed whole)",
            dipTick < 0.010 && dipTick > 0.008, f(dipTick));
        c = warm(0.008);
        double dipHitch = c.smooth(0.045);
        check("k a 45 ms pulse (> 2.5 x nominal) over the same median still passes whole",
            dipHitch == 0.045, f(dipHitch));
        c = warm(0.0127);
        double tick127 = c.smooth(0.032);
        check("k the measured case: median 12.7 ms, 32 ms tick -> smoothed (was whole: 2.5 x 12.7 = 31.75)",
            tick127 < 0.0145 && tick127 > 0.0127, f(tick127));
        // the owed drift SURVIVES a hitch (the closing round's second
        // correction — zeroing it per hitch threw ~47 ms away each
        // time and the loaded ride ran 2.3 % slow): build a positive
        // deficit with double-length pulses, pass a hitch, and the
        // very next steady pulse must still bleed
        c = warm(0.016);
        for (int i = 0; i < 40; i++) c.smooth(i % 5 == 4 ? 0.032 : 0.016);
        double kHitch = c.smooth(0.090);
        double afterHitch = c.smooth(0.016);
        check("k the owed drift survives a hitch (the next pulse still bleeds it)",
            kHitch == 0.090 && afterHitch > 0.016 + 1e-6
                && afterHitch <= 0.016 * (1 + PulseClock.BLEED) + 1e-12,
            "hitch " + f(kHitch) + " next " + f(afterHitch));

        System.out.println("PulseClockGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
