package uvd;

import java.util.Arrays;

/** THE RIDE CLOCK's pulse smoother (batch 36). Pure Java, no JavaFX —
 *  feeds an AnimationTimer's per-pulse advance (the POV ride and the
 *  fly camera).
 *
 *  WHY: the wall delta between PULSE STARTS is not the interval between
 *  DISPLAYED frames. On Windows the glass pulse timer runs at an INTEGER
 *  16 ms (QuantumToolkit.PULSE_INTERVAL = 1000 / 60 — 62.5 Hz; WinTimer
 *  has no vsync timer) while vsync'd frames present at the 16.67 ms
 *  vblank, and every pulse's scene sync blocks in "Waiting for previous
 *  rendering" (the PaintCollector latch behind the render thread's
 *  Present) for the accumulated 0.67 ms/tick drift — until the wait
 *  swallows a whole timer tick, every ~25 pulses (0.4 s): the pulse-
 *  start deltas read 16, 16, …, 32 while the frames still display ONE
 *  PER VBLANK. Advancing the ride by the raw delta therefore ran it
 *  ~4 % slow for 24 frames and then jumped two frames at once — the
 *  user's periodic POV skip, reproduced on screen by a per-pulse
 *  barcode read off the display (27 consecutive-frame 32 ms ride jumps
 *  per 6 s at 0.4 s spacing). A second pulse source, the vsync HINT
 *  posted after each present, splits ticks into 3-8 ms + 24-29 ms
 *  pairs; a busy app (mesh work after entering POV, heavy redraws)
 *  produces runs of 32 ms pulses below any hitch threshold.
 *
 *  FIX: the advance is the MEDIAN of the last WINDOW raw deltas PLUS a
 *  bounded DRIFT term. The median is immune alike to the swallowed
 *  32 ms tick, the 3-8 ms hint pulses and a busy run shorter than half
 *  the window — a plain windowed MEAN (the first draft) let every one of
 *  those inflate or deflate the period (busy-startup 32s ran the ride
 *  20-45 % fast for a second; hint pulses sank it to 13.5 ms). The
 *  window is EVEN and the median is the mean of the two middle order
 *  statistics: an exact 24/8 hint alternation reads 16 ms instead of
 *  flipping between 8 and 24 as an odd median does, and 32 slots keep a
 *  12-pulse busy run a minority. The DRIFT accumulator carries what
 *  wall time still owes against the median rate (Σraw − Σadvance, the
 *  16 → 16.67 ms gap the median cannot see) and bleeds it into the
 *  advance at deficit/WINDOW per pulse, capped at BLEED × the median in
 *  either direction, the accumulator itself bounded at ±DEFICIT_CAP —
 *  so the long-term rate equals wall time (the sawtooth's skipped tick
 *  spreads to ±1.6 % over ~a window) without the plain mean's tail lag,
 *  and a sub-hitch busy stretch can never bank more than six frames
 *  of catch-up. A HITCH — a raw delta over HITCH × max(median, NOMINAL)
 *  (a modal dialog, a GC pause, the first pulse after the app was
 *  busy; the NOMINAL floor keeps a 32 ms swallowed tick from ever
 *  counting as one when hint pulses dip the median) or the historic
 *  CAP — passes through WHOLE and is NOT entered into the window; the
 *  drift it found stays owed (the hitch paid its own time). The window starts FULL of the toolkit's nominal 1/60 s,
 *  so the first pulses run at the nominal rate; the drift term stays
 *  off while seeds remain (the seed phase is a guess, the ride owes
 *  nothing for it — a busy startup runs nominal and then settles on the
 *  measured cadence bitwise). Speed factors (the true/design ratio, the
 *  Shift/Ctrl multipliers) are applied per pulse OUTSIDE the clock, so
 *  the ride's dynamics keep their instant response — only the wall
 *  PERIOD estimate is smoothed. PulseClockGate pins the arithmetic.
 *
 *  RESIDUAL PLATFORM LIMIT, deliberately NOT addressed here (the
 *  reviewer's finding): JavaFX frame production is not paced to the
 *  display — 62.5 timer pulses/s plus hint pulses into a 60 Hz desktop,
 *  no present callback — so now and then a rendered frame is SUPERSEDED
 *  before composition and the screen steps two pulses' worth of ride at
 *  once (index+2 steps, ~1-3 per second, one-vblank dwell). Any clock
 *  that tracks wall time shows them; the raw clock had them too. They
 *  are inherent to the unpaced pulse (-Djavafx.animation.fullspeed makes
 *  them worse, -Djavafx.animation.pulse=58 flips them into held frames)
 *  and remain. */
final class PulseClock {
    /** Pulses in the median window — EVEN (the mid-average median; see
     *  the header), ~0.5 s at 60 Hz: longer than the 0.4 s skipped-tick
     *  beat, short enough to forget a cadence change quickly. */
    static final int WINDOW = 32;
    /** A raw delta over this × the median is a hitch. */
    static final double HITCH = 2.5;
    /** The historic per-pulse cap (s). */
    static final double CAP = 0.1;
    /** The seed period (s): the 60 Hz basis of the toolkit's own pulse
     *  timer. */
    static final double NOMINAL = 1.0 / 60;
    /** The drift bleed's cap per pulse, × the median (0.12 since the
     *  D254 closing round — see DEFICIT_CAP). */
    static final double BLEED = 0.12;
    /** The drift accumulator's bound (s) — six nominal frames. 0.05 /
     *  BLEED 0.10 / zeroing on every hitch (the first cut) CLIPPED on
     *  a loaded machine: 80 double-length pulses in 694 need 1.46 ms
     *  of bleed per pulse, the accumulator sat at its bound, two
     *  hitches threw 47 ms away each, and the ride ran 2.3 % SLOW over
     *  12 s (measured, -Dfvd.povprobe=12). The wider bound carries
     *  that load with room; what it cannot carry is a machine dropping
     *  more than ~1 frame in 8 — there the ride runs slow rather than
     *  jumping, the deliberate choice. */
    static final double DEFICIT_CAP = 0.10;
    /** DEV: `-Dfvd.povprobe.raw=1` restores the historic raw advance
     *  (the capped pulse-start delta) so the POV probe can measure the
     *  old behavior from the same build. Inert otherwise. */
    static final boolean RAW = System.getProperty("fvd.povprobe.raw") != null;

    private final double[] hist = new double[WINDOW];
    private final double[] sorted = new double[WINDOW];
    private int idx;
    private int seeded;      // seed slots not yet replaced by a real pulse
    private double deficit;  // Σraw − Σadvance, bounded (s)
    private long last;

    PulseClock() { reset(); }

    /** Forget the measured cadence (the window refills with NOMINAL, the
     *  drift zeroes); the next pulse only seeds the stamp (advance −1). */
    void reset() {
        Arrays.fill(hist, NOMINAL);
        idx = 0;
        seeded = WINDOW;
        deficit = 0;
        last = 0;
    }

    /** The advance (s) for the pulse stamped `now` (ns, the
     *  AnimationTimer's argument): −1 on the seeding pulse (the
     *  timers' historic first-pulse return — nothing moves). */
    double advance(long now) {
        if (last == 0) { last = now; return -1; }
        double raw = Math.min((now - last) / 1e9, CAP);
        last = now;
        return smooth(raw);
    }

    /** The smoothed advance for one raw pulse delta (s) — the pure
     *  half, gate-driven. */
    double smooth(double raw) {
        if (RAW) return raw;                              // dev: historic
        double med = median();
        // the hitch threshold is FLOORED at the nominal period (D254
        // closing round, measured on a hint-heavy machine: 65 of 430
        // pulses under 8 ms): there the median dips toward 12 ms, and
        // HITCH × 12.7 = 31.8 ms let the very 32 ms swallowed tick this
        // clock exists to smooth pass through WHOLE — the periodic skip
        // back, once per dip. A swallowed tick is never a hitch; a
        // dialog, a GC pause or a busy app (> 41.7 ms) still is.
        if (raw > HITCH * Math.max(med, NOMINAL)) {       // hitch: whole,
            return raw;                                   // out of the window;
        }                                                 // the owed drift STAYS
        hist[idx] = raw;
        idx = (idx + 1) % WINDOW;
        if (seeded > 0) {
            seeded--;
            return median();                              // seed phase: no drift
        }
        med = median();
        deficit = clamp(deficit + raw - med, -DEFICIT_CAP, DEFICIT_CAP);
        double lim = BLEED * med;
        double bleed = clamp(deficit / WINDOW, -lim, lim);
        deficit -= bleed;
        return med + bleed;
    }

    /** The current median period (s) — the probe's readout. */
    double period() { return median(); }

    /** The window's median: the mean of the two middle order statistics
     *  (WINDOW is even). */
    private double median() {
        System.arraycopy(hist, 0, sorted, 0, WINDOW);
        Arrays.sort(sorted);
        return (sorted[WINDOW / 2 - 1] + sorted[WINDOW / 2]) / 2;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
