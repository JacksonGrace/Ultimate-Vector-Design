package uvd;

import java.io.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;

/** D259 §4.15 gate — THE KINEMATIC SOLVER (Stage 2a run 1, v20.2; uvd.ScSolve
 *  + uvd.SectionFrame). Fixtures built in code; bounds, not bitwise unless
 *  the row says so. Rows (§4.15; s waits for Stage 3a's rig — SKIPPED):
 *  a) inverse∘forward — 2000 random planar + 500 helix: params and position
 *     to 1e-12 (near-whole-lap turns |Θ − 2πk| < 0.05 rad, k ≥ 1, are
 *     excluded: the chord collapses there and the position fixes the params
 *     only to ~eps/chord — a conditioning limit, the position still
 *     round-trips; documented in docs/sc-2a-run1-notes.md);
 *  b) forward vs ScIntegrator — R20 90° at dir 0/30/45/90 from level and
 *     20°-pitched entries, a helix R20 90° β 10° → 30°: ≤ 1e-7 m / 1e-6°;
 *  c) lap sweep — the target circling the centre 3 laps in 720 steps: Θ
 *     monotone, max step < 10°, no wrap;
 *  d) pass-through-straight — the target sweeping across the forward ray:
 *     κ changes sign with |Δdir| < 1e-9, L continuous;
 *  e) projections idempotent — every PROJECTED row of §4.4 / §4.5 re-solved
 *     at its landed point returns the params BITWISE;
 *  f) the straight-node hand-off — L_k hits 0.5 first, then κ tightens, the
 *     landed position continuous across the switch;
 *  g) the yawed copy — the same world-relative drag on a pitched-entry
 *     fixture and on a copy yawed 37° + translated: params to 1e-12; equal
 *     params ⇒ the compiled shape bitwise (heights / v / felt / s / t);
 *  h) absorb — the S-bend: the absorber's exit T restored, later sample
 *     deltas constant, residualRot 0;
 *  i) absorb floor — a drag that straightens the S: L = 0.5, κ → 0
 *     continuously, no element vanishes;
 *  j) the feasibility catalogue — one fixture per §4.14 sentence, REFUSED (or
 *     the PROJECTED notice) with the exact constant;
 *  k) biarc — 200 random pose pairs, Λ ∈ {0, 5}, ℓ ∈ {0, 3}: pose met to
 *     1e-9 m / 1e-9 rad, tie-break or fallback flagged, planar pairs == an
 *     independent 2-D construction to 1e-12;
 *  l) single snap — heading / elevation on curve+straight and two-curve
 *     tails to 1e-9, refusals on a lone curve;
 *  m) bounded Newton — 100 ramped planar + 50 SINUSOIDAL-pitch helix targets
 *     within 20 % of the seed: ≤ 20 steps to 1e-9 m, 0 failures, bitwise
 *     across two runs;
 *  n) golden section — κ + heading locked, κ + Y locked, 50 targets each:
 *     ≤ 40 evaluations, within 1e-6 m of a dense-sample minimum, idempotent;
 *  o) insert against a lock — the 0/1/2-root cases, the lock holds to 1e-12;
 *  p) the helix inverse vs a 64-node quadrature incl. J0 — 1e-9 m;
 *  q) determinism — one node edit on the default fixture then rebuild: a
 *     bitwise no-op; save → load → rebuild bitwise;
 *  r) the predictor — a 2 m blend on R20 90°: the typed corrector lands
 *     < 1 mm in ≤ 3 rounds; δ = 0 ⇒ bitwise the sharp path.
 *
 *  Compile + run (from the project root):
 *    javac -encoding UTF-8 -cp target/classes -d gates/out gates/ScSolveGate.java
 *    java -cp "target/classes;gates/out" uvd.ScSolveGate   (':' on Linux)
 */
public final class ScSolveGate {

    private ScSolveGate() {}

    static final double DT = 0.01, V = 20;
    static int pass = 0, fail = 0;
    static final StraightCurveSection.Kind STRAIGHT = StraightCurveSection.Kind.STRAIGHT,
        PLANAR = StraightCurveSection.Kind.PLANAR, HELICAL = StraightCurveSection.Kind.HELICAL;
    static final StraightCurveSection.Interp LIN = StraightCurveSection.Interp.LINEAR,
        SIN = StraightCurveSection.Interp.SINUSOIDAL;
    static final int L_LEN = StraightCurveSection.Element.LOCK_LEN, L_K0 = StraightCurveSection.Element.LOCK_K0,
        L_K1 = StraightCurveSection.Element.LOCK_K1, L_TURN = StraightCurveSection.Element.LOCK_TURN,
        L_DIR = StraightCurveSection.Element.LOCK_DIR, L_HEAD = StraightCurveSection.Element.LOCK_HEAD,
        L_ELEV = StraightCurveSection.Element.LOCK_ELEV, L_X = StraightCurveSection.Element.LOCK_X,
        L_Y = StraightCurveSection.Element.LOCK_Y, L_Z = StraightCurveSection.Element.LOCK_Z,
        L_B1 = StraightCurveSection.Element.LOCK_B1;
    static final ScSolve.Chart LOCAL = ScSolve.Chart.LOCAL, GLOBAL = ScSolve.Chart.GLOBAL;

    static void check(boolean ok, String name, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name
            + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    static void skip(String name, String why) {
        System.out.println("SKIP  " + name + "  (" + why + ")");
    }

    static void info(String name, String detail) {
        System.out.println("INFO  " + name + "  (" + detail + ")");
    }

    static String f(String fmt, Object... a) { return String.format(Locale.US, fmt, a); }

    static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    static boolean bits(Vec3 a, Vec3 b) {
        return bits(a.x(), b.x()) && bits(a.y(), b.y()) && bits(a.z(), b.z());
    }

    /** Every field of two params bitwise (dir compared raw — the stored value). */
    static boolean same(ScSolve.ElemParams a, ScSolve.ElemParams b) {
        return a.kind() == b.kind() && bits(a.L(), b.L()) && bits(a.k0(), b.k0()) && bits(a.k1(), b.k1())
            && a.kLink() == b.kLink() && a.kInterp() == b.kInterp() && bits(a.dirDeg(), b.dirDeg())
            && bits(a.b1Deg(), b.b1Deg()) && a.bLink() == b.bLink() && a.bInterp() == b.bInterp()
            && a.locks() == b.locks();
    }

    static boolean same(List<ScSolve.ElemParams> a, List<ScSolve.ElemParams> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
        return true;
    }

    static double angDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.atan2(a.cross(b).length(), a.dot(b)));
    }

    // ---------------------------------------------------------------
    // fixtures — params, frames, sections
    // ---------------------------------------------------------------

    static ScSolve.ElemParams straight(double L) {
        return new ScSolve.ElemParams(STRAIGHT, L, 0, 0, true, LIN, 0, 0, false, LIN, 0, 0);
    }

    static ScSolve.ElemParams planar(double L, double k, double dir) {
        return new ScSolve.ElemParams(PLANAR, L, k, k, true, LIN, dir, 0, false, LIN, 0, 0);
    }

    /** R / turn form: k = sign(turn)/R, L = |turn|·R. */
    static ScSolve.ElemParams curve(double R, double turnDeg, double dir) {
        double k = Math.signum(turnDeg) / R;
        return planar(Math.abs(Math.toRadians(turnDeg)) * R, k, dir);
    }

    static ScSolve.ElemParams helix(double L, double ch, double b1) {
        return new ScSolve.ElemParams(HELICAL, L, ch, ch, true, LIN, 0, b1, false, LIN, 0, 0);
    }

    static List<ScSolve.ElemParams> els(ScSolve.ElemParams... ps) { return new ArrayList<>(Arrays.asList(ps)); }

    /** The level canonical entry at the origin (heading 0). */
    static ScSolve.Frame level() {
        return ScSolve.Frame.of(ElementChain.entry(Vec3.ZERO, Quat.IDENTITY));
    }

    /** A frame with elevation e (deg) and heading h (deg) at p. */
    static ScSolve.Frame entryAt(Vec3 p, double headDeg, double elevDeg) {
        return ScSolve.Frame.level(p, ScSolve.tangentOf(headDeg, elevDeg), new Vec3(0, 1, 0));
    }

    static Quat quatOf(ScSolve.Frame fr) { return Quat.fromFrame(fr.T(), fr.U()); }

    static StraightCurveSection scOf(List<ScSolve.ElemParams> ps) {
        List<StraightCurveSection.Element> l = new ArrayList<>();
        for (ScSolve.ElemParams p : ps) l.add(p.toElement());
        return new StraightCurveSection(l, V);
    }

    static ScSolve.Frame fwd(ScSolve.Frame f, ScSolve.ElemParams e) { return ScSolve.forwardEnd(f, e); }

    static Vec3 endOf(ScSolve.Frame f, List<ScSolve.ElemParams> ps, int k) {
        return ScSolve.frames(f, ps)[k + 1].p();
    }

    static ScSolve.Solution drag(List<ScSolve.ElemParams> ps, ScSolve.Frame f, int k, Vec3 tgt) {
        return ScSolve.project(ps, f, k, tgt, LOCAL);
    }

    // ---------------------------------------------------------------
    // a) inverse ∘ forward
    // ---------------------------------------------------------------

    static void rowA() {
        Random r = new Random(2026_10_21);
        double worstParam = 0, worstPos = 0, worstDir = 0;
        double fullParam = 0, fullPos = 0;
        int n = 0, skipped = 0, nearLap = 0;
        ScSolve.ElemParams base = planar(10, 0.05, 0);
        while (n < 2000) {
            double k = (r.nextDouble() * 2 - 1) * 10;
            if (n % 50 == 0) k = 0;
            double th = (r.nextDouble() * 2 - 1) * 3 * Math.PI;
            double dir = r.nextDouble() * 360;
            double L;
            if (k == 0) { th = 0; L = 1 + r.nextDouble() * 40; }
            else {
                L = Math.abs(th / k);
                if (L < 0.5) { skipped++; continue; }
            }
            boolean lapBand = false;
            if (k != 0) {
                double phi = Math.abs(th);
                double red = phi - 2 * Math.PI * Math.floor(phi / (2 * Math.PI));
                lapBand = phi >= Math.PI && (red < 0.5 || red > 2 * Math.PI - 0.5);
            }
            // entry: level / pitched / banked (the roll-free axis ignores bank) / yawed
            int ec = n % 4;
            ScSolve.Frame fe = ec == 0 ? level()
                : ec == 1 ? entryAt(new Vec3(1, 2, 3), 0, 20)
                : ec == 2 ? entryAt(new Vec3(-4, 7, 1), 125, -15)
                : entryAt(new Vec3(0, 0, 0), 230, 0);
            ScSolve.ElemParams ex = base.withK(k).withL(L).withDir(dir);
            Vec3 end = fwd(fe, ex).p();
            // the hint: the element as the user holds it before the drag,
            // perturbed κ ±2 %, L ±5 %, dir ±5°
            ScSolve.ElemParams hint = ex.withK(k * (1 + 0.02 * (r.nextDouble() * 2 - 1)))
                .withL(L * (1 + 0.05 * (r.nextDouble() * 2 - 1)))
                .withDir(dir + 5 * (r.nextDouble() * 2 - 1));
            ScSolve.Solution s = drag(els(hint), fe, 0, end);
            ScSolve.ElemParams got = s.params().get(0);
            double dk = Math.abs(got.k0() - k), dL = Math.abs(got.L() - L);
            double dd = k == 0 ? 0 : Math.abs(ScSolve.nearest360(got.dirDeg(), dir) - dir);
            double dp = fwd(fe, got).p().sub(end).length();
            fullParam = Math.max(fullParam, Math.max(dk, dL));
            fullPos = Math.max(fullPos, dp);
            if (lapBand) { nearLap++; }
            else {
                worstParam = Math.max(worstParam, Math.max(dk, dL));
                worstDir = Math.max(worstDir, dd);
                worstPos = Math.max(worstPos, dp);
            }
            if (s.status() != ScSolve.Status.EXACT && n < 5) info("a planar non-exact sample", s.status() + " " + s.why());
            n++;
        }
        check(worstParam <= 1e-12 && worstDir <= 1e-9 && worstPos <= 1e-12,
            "ScSolveGate a 2000 random planar inverse∘forward (laps within 0.5 rad of a whole turn set aside — the conditioning limit): κ, L to 1e-12, dir to 1e-9°, position to 1e-12 m",
            f("worst κ/L %.2e, dir %.2e°, pos %.2e m over %d draws (%d sub-floor draws skipped)", worstParam, worstDir, worstPos, n - nearLap, skipped));
        check(fullPos <= 1e-12 && fullParam <= 1e-7,
            "ScSolveGate a2 the near-whole-lap draws included (" + nearLap + "): position still to 1e-12 m, params to 1e-7 (the chord collapses toward 2πk — eps/chord conditioning, documented)",
            f("worst κ/L %.2e, pos %.2e m", fullParam, fullPos));
        // helix
        double wk = 0, wb = 0, wL = 0, wp = 0;
        int m = 0;
        while (m < 500) {
            double b0 = (r.nextDouble() * 2 - 1) * 85, b1 = (r.nextDouble() * 2 - 1) * 85;
            double ch = (r.nextDouble() * 2 - 1) * 10;
            if (Math.abs(ch) < 0.01) continue;
            double L = 2 + r.nextDouble() * 60;
            double fac = Math.cos(Math.toRadians((b0 + b1) / 2)) * ElementChain.sinc(Math.toRadians(b1 - b0) / 2);
            double dpsi = Math.abs(ch) * L * fac;
            if (dpsi > 3 * Math.PI) { continue; }
            double red = dpsi - 2 * Math.PI * Math.floor(dpsi / (2 * Math.PI));
            if (dpsi >= Math.PI && (red < 0.05 || red > 2 * Math.PI - 0.05)) continue;
            if (L * fac < 0.5) continue;
            ScSolve.Frame fe = entryAt(new Vec3(2, 5, -1), m % 3 == 0 ? 0 : 77, b0);
            ScSolve.ElemParams ex = helix(L, ch, b1);
            Vec3 end = fwd(fe, ex).p();
            ScSolve.ElemParams hint = ex.withK(ch * (1 + 0.02 * (r.nextDouble() * 2 - 1)))
                .withL(L * (1 + 0.05 * (r.nextDouble() * 2 - 1)))
                .withB1(Math.max(-85, Math.min(85, b1 + 3 * (r.nextDouble() * 2 - 1))));
            ScSolve.Solution s = drag(els(hint), fe, 0, end);
            ScSolve.ElemParams got = s.params().get(0);
            wk = Math.max(wk, Math.abs(got.k0() - ch));
            wb = Math.max(wb, Math.abs(got.b1Deg() - b1));
            wL = Math.max(wL, Math.abs(got.L() - L));
            wp = Math.max(wp, fwd(fe, got).p().sub(end).length());
            m++;
        }
        check(wk <= 1e-12 && wb <= 1e-9 && wL <= 1e-11 && wp <= 1e-12,
            "ScSolveGate a 500 random helix inverse∘forward (I9): c_h to 1e-12, β1 to 1e-9°, L to 1e-11, position to 1e-12 m",
            f("worst c_h %.2e, β1 %.2e°, L %.2e, pos %.2e m", wk, wb, wL, wp));
    }

    // ---------------------------------------------------------------
    // b) forward vs the integrator
    // ---------------------------------------------------------------

    static void rowB() {
        double wp = 0, wt = 0;
        for (double dir : new double[]{ 0, 30, 45, 90 }) {
            for (double elev : new double[]{ 0, 20 }) {
                ScSolve.Frame fe = entryAt(Vec3.ZERO, 0, elev);
                ScSolve.ElemParams e = curve(20, 90, dir);
                ScSolve.Frame end = fwd(fe, e);
                ScIntegrator.Result ir = ScIntegrator.integrate(scOf(els(e)), DT, V, Vec3.ZERO, quatOf(fe));
                FVDIntegrator.Sample last = ir.samples().get(ir.samples().size() - 1);
                wp = Math.max(wp, last.pos().sub(end.p()).length());
                wt = Math.max(wt, angDeg(last.T(), end.T()));
            }
        }
        {
            ScSolve.Frame fe = entryAt(Vec3.ZERO, 0, 10);
            ScSolve.ElemParams e = helix(10 * Math.PI / Math.cos(Math.toRadians(20)), 0.05, 30);
            ScSolve.Frame end = fwd(fe, e);
            ScIntegrator.Result ir = ScIntegrator.integrate(scOf(els(e)), DT, V, Vec3.ZERO, quatOf(fe));
            FVDIntegrator.Sample last = ir.samples().get(ir.samples().size() - 1);
            wp = Math.max(wp, last.pos().sub(end.p()).length());
            wt = Math.max(wt, angDeg(last.T(), end.T()));
        }
        check(wp <= 1e-7 && wt <= 1e-6,
            "ScSolveGate b forwardEnd vs ScIntegrator: R20 90° at dir 0/30/45/90 from level and 20°-pitched entries + a helix R20 90° β 10° → 30° — ≤ 1e-7 m / 1e-6°",
            f("worst pos %.2e m, T %.2e°", wp, wt));
    }

    // ---------------------------------------------------------------
    // c) the lap sweep
    // ---------------------------------------------------------------

    static void rowC() {
        ScSolve.Frame fe = level();
        double kap = 0.05;
        ScSolve.ElemParams cur = planar(Math.toRadians(10) / kap, kap, 0);
        double prevTheta = Math.toDegrees(cur.turnRad());
        double maxStep = 0, minStep = Double.MAX_VALUE;
        boolean monotone = true, wrapped = false;
        int steps = 720;
        for (int i = 1; i <= steps; i++) {
            double phi = Math.toRadians(10 + 1080.0 * i / steps);
            Vec3 tgt = new Vec3(Math.sin(phi) / kap, 0, (1 - Math.cos(phi)) / kap);
            ScSolve.Solution s = drag(els(cur), fe, 0, tgt);
            cur = s.params().get(0);
            double th = Math.toDegrees(cur.turnRad());
            double step = th - prevTheta;
            if (step <= 0) monotone = false;
            maxStep = Math.max(maxStep, step);
            minStep = Math.min(minStep, step);
            if (Math.abs(cur.dirDeg()) > 1e-9) wrapped = true;
            prevTheta = th;
        }
        check(monotone && maxStep < 10 && !wrapped && Math.abs(prevTheta - 1090) < 1e-6,
            "ScSolveGate c the target circling the centre 3 laps in 720 steps: Θ monotone 10° → 1090°, max step < 10°, dir never wraps",
            f("steps %.4f°..%.4f°, final Θ %.6f°, monotone %b, wrapped %b", minStep, maxStep, prevTheta, monotone, wrapped));
    }

    // ---------------------------------------------------------------
    // d) pass-through-straight
    // ---------------------------------------------------------------

    static void rowD() {
        ScSolve.Frame fe = level();
        ScSolve.ElemParams cur = planar(10, 0.05, 0);
        double worstDir = 0, worstLJump = 0;
        boolean signFlipped = false;
        double prevL = Double.NaN, prevK = Double.NaN;
        int n = 801;
        for (int i = 0; i < n; i++) {
            double z = 2 - 4.0 * i / (n - 1);
            Vec3 tgt = new Vec3(12, 0, z);
            ScSolve.Solution s = drag(els(cur), fe, 0, tgt);
            cur = s.params().get(0);
            worstDir = Math.max(worstDir, Math.abs(cur.dirDeg()));
            if (!Double.isNaN(prevL)) worstLJump = Math.max(worstLJump, Math.abs(cur.L() - prevL));
            if (!Double.isNaN(prevK) && Math.signum(prevK) * Math.signum(cur.k0()) < 0) signFlipped = true;
            prevL = cur.L();
            if (cur.k0() != 0) prevK = cur.k0();
        }
        check(worstDir <= 1e-9 && signFlipped && worstLJump < 1e-6 + 0.02,
            "ScSolveGate d the target sweeping across the forward ray: κ changes sign with |Δdir| < 1e-9, L continuous",
            f("|dir| worst %.2e°, sign flipped %b, max L step between 5 mm target steps %.2e m", worstDir, signFlipped, worstLJump));
    }

    // ---------------------------------------------------------------
    // e) projections idempotent
    // ---------------------------------------------------------------

    record ProjCase(String name, List<ScSolve.ElemParams> els, ScSolve.Frame f, int k, Vec3 target) {}

    static List<ProjCase> projCases() {
        List<ProjCase> cs = new ArrayList<>();
        ScSolve.Frame lv = level();
        ScSolve.ElemParams c0 = curve(20, 90, 0);   // end (20, 0, 20), T = +Z
        cs.add(new ProjCase("planar dir locked, target off-plane", els(c0.withLocks(L_DIR)), lv, 0, new Vec3(15, 4, 25)));
        cs.add(new ProjCase("planar κ locked, target off the circle", els(c0.withLocks(L_K0)), lv, 0, new Vec3(30, 0, 35)));
        cs.add(new ProjCase("planar Θ locked, target off the cone", els(c0.withLocks(L_TURN)), lv, 0, new Vec3(10, 3, 30)));
        cs.add(new ProjCase("planar L locked, the bearing surface", els(c0.withLocks(L_LEN)), lv, 0, new Vec3(25, 0, 25)));
        cs.add(new ProjCase("planar κ + Θ locked, the chord circle", els(c0.withLocks(L_K0 | L_TURN)), lv, 0, new Vec3(20, 5, 25)));
        cs.add(new ProjCase("planar κ + dir locked", els(c0.withLocks(L_K0 | L_DIR)), lv, 0, new Vec3(30, 2, 30)));
        cs.add(new ProjCase("planar Θ + dir locked, the ray", els(c0.withLocks(L_TURN | L_DIR)), lv, 0, new Vec3(25, 3, 15)));
        cs.add(new ProjCase("planar X locked, the plane then I1", els(c0.withLocks(L_X)), lv, 0, new Vec3(25, 0, 30)));
        cs.add(new ProjCase("planar X + Z locked, the line then I1", els(c0.withLocks(L_X | L_Z)), lv, 0, new Vec3(25, 4, 30)));
        cs.add(new ProjCase("planar nothing locked, the horn-torus core", els(c0), lv, 0, new Vec3(0.01, 0, 0.1)));
        cs.add(new ProjCase("planar nothing locked, the backward ray", els(planar(10, 0, 0)), lv, 0, new Vec3(-5, 0, 0)));
        cs.add(new ProjCase("planar nothing locked, the MIN_ELEM_LEN floor", els(c0), lv, 0, new Vec3(0.2, 0, 0.05)));
        cs.add(new ProjCase("planar κ + heading locked (golden)", els(c0.withLocks(L_K0 | L_HEAD)), lv, 0, new Vec3(30, 5, 30)));
        cs.add(new ProjCase("planar κ + Y locked (golden)", els(c0.withLocks(L_K0 | L_Y)), lv, 0, new Vec3(30, 5, 30)));
        cs.add(new ProjCase("planar lone heading lock (κ held)", els(c0.withLocks(L_HEAD)), lv, 0, new Vec3(28, 3, 24)));
        cs.add(new ProjCase("planar heading + elevation locked, the ray", els(c0.withLocks(L_HEAD | L_ELEV)), lv, 0, new Vec3(25, 3, 15)));
        cs.add(new ProjCase("planar dir + X locked (golden, κ from X)", els(c0.withLocks(L_DIR | L_X)), lv, 0, new Vec3(20, 4, 40)));
        List<ScSolve.ElemParams> cs2 = els(c0, straight(12));   // straight end (20, 0, 32)
        cs.add(new ProjCase("straight, L_k locked, inside the sphere", els(c0, straight(12).withLocks(L_LEN)), lv, 1, new Vec3(5, 0, 5)));
        cs.add(new ProjCase("straight, κ_j locked, target inside the circle", els(c0.withLocks(L_K0), straight(12)), lv, 1, new Vec3(10, 0, 15)));
        cs.add(new ProjCase("straight, Θ_j locked, ℓ under the floor", els(c0.withLocks(L_TURN), straight(12)), lv, 1, new Vec3(10, 0, 9)));
        cs.add(new ProjCase("straight, Θ_j + dir_j locked, the ray", els(c0.withLocks(L_TURN | L_DIR), straight(12)), lv, 1, new Vec3(25, 3, 40)));
        cs.add(new ProjCase("straight, curve fully locked, length only", els(c0.withLocks(L_K0 | L_TURN | L_DIR), straight(12)), lv, 1, new Vec3(23, 2, 50)));
        cs.add(new ProjCase("straight, dir_j locked, target off-plane", els(c0.withLocks(L_DIR), straight(12)), lv, 1, new Vec3(15, 3, 40)));
        cs.add(new ProjCase("straight with no curve before (I16)", els(straight(10)), lv, 0, new Vec3(12, 1, 2)));
        cs.add(new ProjCase("straight, L_k locked + κ_j locked, the angle only", els(c0.withLocks(L_K0), straight(12).withLocks(L_LEN)), lv, 1, new Vec3(30, 0, 40)));
        cs.add(new ProjCase("straight, L_k locked + Θ_j locked, the ray in 1/κ", els(c0.withLocks(L_TURN), straight(12).withLocks(L_LEN)), lv, 1, new Vec3(30, 2, 40)));
        ScSolve.ElemParams h0 = helix(10 * Math.PI, 0.05, 20);
        cs.add(new ProjCase("helix pitch linked, the height implied", els(h0.withBLink(true)), lv, 0, new Vec3(20, 3, 20)));
        cs.add(new ProjCase("helix B1 locked, the height implied", els(h0.withLocks(L_B1)), lv, 0, new Vec3(22, 3, 21)));
        cs.add(new ProjCase("helix c_h locked, the plan torus", els(h0.withLocks(L_K0)), lv, 0, new Vec3(30, 2, 35)));
        cs.add(new ProjCase("helix L locked, β1 from the plan length", els(h0.withLocks(L_LEN)), lv, 0, new Vec3(22, 4, 24)));
        cs.add(new ProjCase("helix X locked, the plane then I9", els(h0.withLocks(L_X)), lv, 0, new Vec3(25, 4, 24)));
        cs.add(new ProjCase("helix β1 past B1_MAX, clamped", els(h0), lv, 0, new Vec3(0.5, 40, 0.5)));
        return cs;
    }

    static void rowE() {
        int projected = 0, exact = 0, idem = 0, n = 0;
        StringBuilder bad = new StringBuilder();
        for (ProjCase pc : projCases()) {
            n++;
            ScSolve.Solution s1 = drag(pc.els, pc.f, pc.k, pc.target);
            if (s1.refused()) { bad.append(" [").append(pc.name).append(": REFUSED ").append(s1.why()).append("]"); continue; }
            if (s1.status() == ScSolve.Status.PROJECTED) projected++; else { exact++; bad.append(" [").append(pc.name).append(": landed EXACT]"); }
            ScSolve.Solution s2 = drag(s1.params(), pc.f, pc.k, s1.landedPos());
            boolean ok = !s2.refused() && same(s1.params(), s2.params()) && s2.status() == ScSolve.Status.EXACT;
            if (ok) idem++; else bad.append(" [").append(pc.name).append(": re-solve differs ").append(s2.status()).append("]");
        }
        check(idem == n && projected == n,
            "ScSolveGate e every PROJECTED row of §4.4 / §4.5 (" + n + " fixtures: the planar lock rows, the core / ray / floor, the golden rows, the straight rows incl. I16, the helix rows) re-solved at its landed point returns the params BITWISE and EXACT",
            f("projected %d / idempotent %d of %d%s", projected, idem, n, bad));
    }

    // ---------------------------------------------------------------
    // f) the straight-node hand-off
    // ---------------------------------------------------------------

    static void rowF() {
        ScSolve.Frame lv = level();
        List<ScSolve.ElemParams> base = els(curve(20, 90, 0), straight(12));
        Vec3 a = new Vec3(20, 0, 32), b = new Vec3(5, 0, 18);
        double tSwitch = Double.NaN;
        boolean kConstBefore = true, lkFloorAfter = true, kMonoAfter = true, allExact = true;
        double prevK = 0.05;
        int steps = 2000;
        List<ScSolve.ElemParams> cur = base;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Vec3 tgt = a.add(b.sub(a).scale(t));
            ScSolve.Solution s = drag(cur, lv, 1, tgt);
            if (s.refused()) { allExact = false; break; }
            ScSolve.ElemParams c = s.params().get(0), st = s.params().get(1);
            boolean floored = st.L() <= 0.5 + 1e-12;
            if (Double.isNaN(tSwitch)) {
                if (floored) tSwitch = t;
                else if (!bits(c.k0(), 0.05)) kConstBefore = false;
            } else {
                if (!floored) lkFloorAfter = false;
                if (Math.abs(c.k0()) < Math.abs(prevK) - 1e-12) kMonoAfter = false;
            }
            if (s.status() != ScSolve.Status.EXACT) allExact = false;
            prevK = c.k0();
            cur = s.params();
        }
        // the switch point: bisect it, then the landed points 1e-9 apart
        // in the parameter straddling it
        double lo = Math.max(0, tSwitch - 1.0 / steps), hi = tSwitch;
        for (int it = 0; it < 60; it++) {
            double mid = (lo + hi) / 2;
            ScSolve.Solution s = drag(base, lv, 1, a.add(b.sub(a).scale(mid)));
            if (s.params().get(1).L() <= 0.5 + 1e-12) hi = mid; else lo = mid;
        }
        ScSolve.Solution sl = drag(base, lv, 1, a.add(b.sub(a).scale(lo)));
        ScSolve.Solution sh = drag(base, lv, 1, a.add(b.sub(a).scale(hi)));
        double jump = sl.landedPos().sub(sh.landedPos()).length();
        check(!Double.isNaN(tSwitch) && kConstBefore && lkFloorAfter && kMonoAfter && allExact && jump < 1e-6,
            "ScSolveGate f the target entering the previous circle: the straight shortens to its 0.5 m floor FIRST (κ bitwise 0.05 until then), then κ tightens monotonically, every landing exact, the landed position continuous across the switch",
            f("switch at t = %.4f, κ const before %b, floor after %b, κ monotone after %b, exact %b, jump %.2e m", tSwitch, kConstBefore, lkFloorAfter, kMonoAfter, allExact, jump));
    }

    // ---------------------------------------------------------------
    // g) the yawed copy
    // ---------------------------------------------------------------

    static Coaster pitchedFixture(double x, double y, double z, double yaw) {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(x, y, z, yaw);
        double e = Math.toRadians(12);
        p.baseOrient = Quat.fromFrame(new Vec3(Math.cos(e), Math.sin(e), 0), new Vec3(-Math.sin(e), Math.cos(e), 0));
        p.sections.add(scOf(els(straight(10), curve(20, 90, 0), straight(8))));
        c.pieces.add(p);
        return c;
    }

    static Vec3 rotY(Vec3 v, double deg) {
        double rad = Math.toRadians(deg);
        double c = Math.cos(rad), s = Math.sin(rad);
        return new Vec3(c * v.x() + s * v.z(), v.y(), -s * v.x() + c * v.z());
    }

    static void rowG() {
        Coaster base = pitchedFixture(0, 21, 0, 0), copy = pitchedFixture(50, 24, -20, 37);
        TrackCompiler.Result rb = TrackCompiler.compile(base, DT), rc = TrackCompiler.compile(copy, DT);
        SectionFrame fb = SectionFrame.of(rb, base, 0, 0), fc = SectionFrame.of(rc, copy, 0, 0);
        Vec3 sharpB = fb.bounds().get(2).sharpPos(), sharpC = fc.bounds().get(2).sharpPos();
        Vec3 dc = new Vec3(1.5, 0.7, -2.0);
        Vec3 tgtC = sharpB.add(dc);
        Vec3 wB = fb.toWorld(tgtC), wC = fc.toWorld(tgtC);
        // the same world-relative drag: the copy's world drag is the base's rotated by the yaw
        Vec3 dragB = wB.sub(fb.entryW()), dragC = wC.sub(fc.entryW());
        double rot = dragC.sub(rotY(dragB, 37)).length();
        Vec3 tb = fb.toCanonical(wB), tc = fc.toCanonical(wC);
        double frameRt = Math.max(tb.sub(tgtC).length(), tc.sub(tgtC).length());
        List<ScSolve.ElemParams> pb = ScSolve.ElemParams.of((StraightCurveSection) base.pieces.get(0).sections.get(0));
        List<ScSolve.ElemParams> pcp = ScSolve.ElemParams.of((StraightCurveSection) copy.pieces.get(0).sections.get(0));
        ScSolve.Solution sb = ScSolve.solveNodeDrag(pb, fb.entryFrame(), 1, tb, LOCAL);
        ScSolve.Solution sc = ScSolve.solveNodeDrag(pcp, fc.entryFrame(), 1, tc, LOCAL);
        ScSolve.ElemParams eb = sb.params().get(1), ec = sc.params().get(1);
        double dp = Math.max(Math.max(Math.abs(eb.k0() - ec.k0()), Math.abs(eb.L() - ec.L())),
            Math.abs(ScSolve.nearest360(eb.dirDeg(), ec.dirDeg()) - ec.dirDeg()));
        check(sharpB.sub(sharpC).length() == 0 && rot <= 1e-9 && frameRt <= 1e-12 && !sb.refused() && !sc.refused() && dp <= 1e-12,
            "ScSolveGate g the same world-relative drag on the 12°-pitched fixture and its copy yawed 37° + translated (50, 3, −20): canonical sharp ends bitwise, the frame round trip 1e-12, params to 1e-12",
            f("drag rotation %.2e, frame round trip %.2e, params %.2e, status %s / %s", rot, frameRt, dp, sb.status(), sc.status()));
        // equal params ⇒ the compiled shape bitwise (the base's params onto both)
        ScSolve.apply((StraightCurveSection) base.pieces.get(0).sections.get(0), sb);
        ScSolve.apply((StraightCurveSection) copy.pieces.get(0).sections.get(0), new ScSolve.Solution(sb.params(), sb.status(), null, 0, null, 0));
        TrackCompiler.Result r2b = TrackCompiler.compile(base, DT), r2c = TrackCompiler.compile(copy, DT);
        boolean same = r2b.samples().size() == r2c.samples().size();
        double worst = 0;
        String badAt = null;
        Vec3 eb0 = new Vec3(0, 21, 0), ec0 = new Vec3(50, 24, -20);
        for (int i = 0; same && i < r2b.samples().size(); i++) {
            FVDIntegrator.Sample x = r2b.samples().get(i), y = r2c.samples().get(i);
            if (badAt == null && !(bits(x.pos().y() - 21, y.pos().y() - 24) || Math.abs((x.pos().y() - 21) - (y.pos().y() - 24)) <= 1e-9)) badAt = "height " + i;
            if (badAt == null && !(bits(x.v(), y.v()) && bits(x.s(), y.s()) && bits(x.t(), y.t()) && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl()))) badAt = "sample " + i;
            worst = Math.max(worst, y.pos().sub(ec0).sub(rotY(x.pos().sub(eb0), 37)).length());
        }
        check(same && badAt == null && worst <= 1e-9,
            "ScSolveGate g equal params ⇒ the compiled shape bitwise (v / s / t / felt bitwise, heights to the translation, positions rigid ≤ 1e-9 m)",
            f("worst pos %.2e m%s", worst, badAt == null ? "" : ", " + badAt));
    }

    // ---------------------------------------------------------------
    // h) absorb · i) the absorb floor
    // ---------------------------------------------------------------

    static List<ScSolve.ElemParams> sBend() {
        return els(curve(20, 90, 0), straight(5), curve(20, -90, 0), straight(10));
    }

    static void rowH() {
        ScSolve.Frame lv = level();
        List<ScSolve.ElemParams> s0 = sBend();
        ScSolve.Frame[] fr = ScSolve.frames(lv, s0);
        Vec3 tgt = new Vec3(22, 0, 23);
        ScSolve.Solution s = ScSolve.solveNodeDrag(s0, lv, 0, tgt, LOCAL);
        ScSolve.Frame[] nf = ScSolve.frames(lv, s.params());
        double tRot = angDeg(nf[3].T(), fr[3].T());
        boolean tBits = bits(nf[3].T(), fr[3].T());
        Vec3 d3 = nf[3].p().sub(fr[3].p()), d4 = nf[4].p().sub(fr[4].p());
        double constDelta = d4.sub(d3).length();
        double tEnd = angDeg(nf[4].T(), fr[4].T());
        boolean othersKept = same(s.params().get(1), s0.get(1)) && same(s.params().get(3), s0.get(3));
        // the integrator agrees with the frames at the far end
        ScIntegrator.Result i0 = ScIntegrator.integrate(scOf(s0), DT, V, Vec3.ZERO, Quat.IDENTITY);
        ScIntegrator.Result i1 = ScIntegrator.integrate(scOf(s.params()), DT, V, Vec3.ZERO, Quat.IDENTITY);
        Vec3 e0 = i0.samples().get(i0.samples().size() - 1).pos(), e1 = i1.samples().get(i1.samples().size() - 1).pos();
        double integ = e1.sub(e0).sub(d4).length();
        check(!s.refused() && s.residualRot() == 0 && tRot <= 1e-12 && constDelta <= 1e-12 && tEnd <= 1e-12 && othersKept && integ <= 1e-7,
            "ScSolveGate h the S-bend: node 0 dragged, the absorber (Element 3) restores its old exit T (" + (tBits ? "BITWISE" : "≤ 1e-12°") + "), later deltas constant to 1e-12 m, residualRot 0, the other elements untouched, the integrator's far end agrees to 1e-7",
            f("exit T %.2e° (bitwise %b), delta const %.2e m, end T %.2e°, residual %.2e, integ %.2e m, status %s", tRot, tBits, constDelta, tEnd, s.residualRot(), integ, s.status()));
        // the locked-absorber residual: DIR locked on the absorber, a drag out of its plane
        List<ScSolve.ElemParams> sl = sBend();
        sl.set(2, sl.get(2).withLocks(L_DIR));
        ScSolve.Solution s2 = ScSolve.solveNodeDrag(sl, lv, 0, new Vec3(22, 3, 23), LOCAL);
        check(!s2.refused() && s2.residualRot() > 1e-6 && SectionNames.absorbResidualWhy(SectionNames.elementName(2)).equals(s2.why())
                && s2.status() == ScSolve.Status.PROJECTED,
            "ScSolveGate h2 a DIR-locked absorber leaves a residual: PROJECTED with absorbResidualWhy(Element 3), residualRot > 0",
            f("residual %.4f°, why %s", s2.residualRot(), s2.why()));
    }

    static void rowI() {
        ScSolve.Frame lv = level();
        List<ScSolve.ElemParams> cur = sBend();
        double kap = 0.05;
        boolean floorHeld = true, countKept = true, kContinuous = true, reachedZero = false;
        double prevK = Double.NaN, minL = Double.MAX_VALUE, maxKJump = 0;
        int steps = 400;
        for (int i = 0; i <= steps; i++) {
            double phi = Math.toRadians(90 - 89.95 * i / steps);
            Vec3 tgt = new Vec3(Math.sin(phi) / kap, 0, (1 - Math.cos(phi)) / kap);
            ScSolve.Solution s = ScSolve.solveNodeDrag(cur, lv, 0, tgt, LOCAL);
            if (s.refused()) { floorHeld = false; break; }
            cur = s.params();
            if (cur.size() != 4) countKept = false;
            ScSolve.ElemParams ab = cur.get(2);
            minL = Math.min(minL, ab.L());
            if (ab.L() < 0.5 - 1e-12) floorHeld = false;
            if (!Double.isNaN(prevK)) maxKJump = Math.max(maxKJump, Math.abs(ab.k0() - prevK));
            prevK = ab.k0();
        }
        reachedZero = Math.abs(prevK) < 0.01;
        if (maxKJump > 0.01) kContinuous = false;
        check(floorHeld && countKept && kContinuous && reachedZero,
            "ScSolveGate i a drag that straightens the S: the absorber's L bottoms at 0.5, κ → 0 continuously through the floor, no element vanishes",
            f("min L %.3f, max κ step %.2e per 0.22° of drag, final κ %.2e, count kept %b", minL, maxKJump, prevK, countKept));
    }

    // ---------------------------------------------------------------
    // j) the feasibility catalogue
    // ---------------------------------------------------------------

    static int catN = 0;

    static void cat(String name, String want, ScSolve.Solution s) {
        catN++;
        boolean ok = s != null && s.refused() && want.equals(s.why());
        check(ok, "ScSolveGate j." + catN + " " + name + " → REFUSED with its constant",
            ok ? "" : "status " + (s == null ? "null" : s.status()) + " why \"" + (s == null ? "" : s.why()) + "\"");
    }

    static void catNotice(String name, String want, ScSolve.Solution s) {
        catN++;
        boolean ok = s != null && s.status() == ScSolve.Status.PROJECTED && want.equals(s.why());
        check(ok, "ScSolveGate j." + catN + " " + name + " → PROJECTED with its notice",
            ok ? "" : "status " + (s == null ? "null" : s.status()) + " why \"" + (s == null ? "" : s.why()) + "\"");
    }

    static void catStr(String name, String want, String got) {
        catN++;
        check(want.equals(got), "ScSolveGate j." + catN + " " + name, want.equals(got) ? "" : "got \"" + got + "\"");
    }

    static void rowJ() {
        ScSolve.Frame lv = level();
        ScSolve.ElemParams c0 = curve(20, 90, 0);
        ScSolve.ElemParams h0 = helix(10 * Math.PI, 0.05, 20);
        ScSolve.Target.Pose pose = new ScSolve.Target.Pose(new Vec3(40, 0, 30), -45, 0);
        cat("ALL_LOCKED_WHY: κ + Θ + dir locked, a drag", SectionNames.ALL_LOCKED_WHY,
            drag(els(c0.withLocks(L_K0 | L_TURN | L_DIR)), lv, 0, new Vec3(25, 0, 25)));
        cat("POS_LOCKED_WHY: x + y + z locked, a drag", SectionNames.POS_LOCKED_WHY,
            drag(els(c0.withLocks(L_X | L_Y | L_Z)), lv, 0, new Vec3(25, 0, 25)));
        cat("SEAM_OWNS_WHY: a typed heading on a follower's first straight", SectionNames.SEAM_OWNS_WHY,
            ScSolve.solveTypedBox(els(straight(10)), lv, 0, ScSolve.Box.HEADING, 30, LOCAL, true));
        cat("ANCHOR_OWNS_WHY: the same on a run head", SectionNames.ANCHOR_OWNS_WHY,
            ScSolve.solveTypedBox(els(straight(10)), lv, 0, ScSolve.Box.HEADING, 30, LOCAL, false));
        cat("NO_CURVE_BEFORE_WHY: a ring on a straight with no curve before it", SectionNames.NO_CURVE_BEFORE_WHY,
            ScSolve.solveRing(els(straight(10)), lv, 0, ScSolve.Ring.HEADING, 10, LOCAL));
        cat("STRAIGHT_ALL_LOCKED_WHY: the curve fully locked and the straight's length locked", SectionNames.STRAIGHT_ALL_LOCKED_WHY,
            drag(els(c0.withLocks(L_K0 | L_TURN | L_DIR), straight(12).withLocks(L_LEN)), lv, 1, new Vec3(25, 0, 40)));
        cat("PREV_CURVE_LOCKED_WHY: a typed heading on a straight behind a fully locked curve", SectionNames.PREV_CURVE_LOCKED_WHY,
            ScSolve.solveTypedBox(els(c0.withLocks(L_K0 | L_TURN | L_DIR), straight(12)), lv, 1, ScSolve.Box.HEADING, 100, LOCAL, true));
        cat("INSIDE_CIRCLE_WHY: a typed X inside the κ-locked previous curve", SectionNames.INSIDE_CIRCLE_WHY,
            ScSolve.solveTypedBox(els(c0.withLocks(L_K0), straight(12)), lv, 1, ScSolve.Box.X, 10, LOCAL, true));
        cat("SPHERE_WHY: a typed Z within the locked straight's own length of the curve's start", SectionNames.SPHERE_WHY,
            ScSolve.solveTypedBox(els(curve(2, 90, 0), straight(12).withLocks(L_LEN)), lv, 1, ScSolve.Box.Z, 3, LOCAL, true));
        cat("REACH_RAY_WHY: a typed X behind the start on the element's own line", SectionNames.REACH_RAY_WHY,
            ScSolve.solveTypedBox(els(planar(10, 0, 0)), lv, 0, ScSolve.Box.X, -5, LOCAL, true));
        cat("PARALLEL_WHY: the heading ring turned back onto the entry heading at lap 0", SectionNames.PARALLEL_WHY,
            ScSolve.solveRing(els(c0), lv, 0, ScSolve.Ring.HEADING, 0, LOCAL));
        cat("VERTICAL_WHY: the heading ring on a vertical end (a 90° pull-up)", SectionNames.VERTICAL_WHY,
            ScSolve.solveRing(els(curve(20, 90, 90)), lv, 0, ScSolve.Ring.HEADING, 10, LOCAL));
        cat("HELIX_VERTICAL_WHY: a helix inserted at a vertical border", SectionNames.HELIX_VERTICAL_WHY,
            ScSolve.insertAt(els(curve(20, 90, 90), straight(5)), lv, 1, HELICAL));
        cat("HELIX_VERTICAL_WHY: a change to helical at a vertical entry", SectionNames.HELIX_VERTICAL_WHY,
            ScSolve.changeType(els(curve(20, 90, 90), straight(5)), lv, 1, HELICAL));
        cat("DIR_INHERITED_WHY: a typed direction on a planar starting on vertical track", SectionNames.DIR_INHERITED_WHY,
            ScSolve.solveTypedBox(els(curve(20, 90, 90), curve(20, 30, 0)), lv, 1, ScSolve.Box.DIR, 45, LOCAL, true));
        cat("HELIX_ELEV_WHY: a typed end elevation of 95°", SectionNames.HELIX_ELEV_WHY,
            ScSolve.solveTypedBox(els(h0), lv, 0, ScSolve.Box.B1, 95, LOCAL, true));
        catStr("FULLY_CONSTRAINED_WHY: a padlock raising the rank to 4", SectionNames.FULLY_CONSTRAINED_WHY,
            ScSolve.padlockWhy(c0.withLocks(L_K0 | L_TURN | L_DIR), L_HEAD));
        catStr("padlockWhy allows the consistent dependent lock K0 + TURN + LEN", "null",
            String.valueOf(ScSolve.padlockWhy(c0.withLocks(L_K0 | L_TURN), L_LEN)));
        cat("FULLY_CONSTRAINED_WHY: a typed value into a locked box", SectionNames.FULLY_CONSTRAINED_WHY,
            ScSolve.solveTypedBox(els(c0.withLocks(L_K0)), lv, 0, ScSolve.Box.KAPPA, 0.1, LOCAL, true));
        cat("MIXED_LOCK_WHY: dir + X locked, the free value runs out (κ → 0 with a turn)", SectionNames.MIXED_LOCK_WHY,
            drag(els(c0.withLocks(L_DIR | L_X)), lv, 0, new Vec3(20, 0, 1e-5)));
        skip("ScSolveGate j ORTHO_LOCK_WHY fixture",
            "unreachable by construction under §4.12's node-plane rule — the node's own plane always holds the current end, so the in-plane search never comes back empty; the belt stays (ScSolve.solveOrthoDrag) and LabelGate 21u pins the sentence");
        cat("RAMP_SOLVE_WHY: a typed heading on an unlinked (ramped) element", SectionNames.RAMP_SOLVE_WHY,
            ScSolve.solveTypedBox(els(new ScSolve.ElemParams(PLANAR, 20, 0.05, 0.08, false, LIN, 0, 0, false, LIN, 0, 0)),
                lv, 0, ScSolve.Box.HEADING, 100, LOCAL, true));
        cat("HELIX_STRAIGHT_WHY: a typed Y on a straight after a pitch-linked helix", SectionNames.HELIX_STRAIGHT_WHY,
            ScSolve.solveTypedBox(els(helix(10 * Math.PI, 0.05, 0).withBLink(true), straight(8)), lv, 1, ScSolve.Box.Y, 5, LOCAL, true));
        {
            List<ScSolve.ElemParams> sl = sBend();
            sl.set(2, sl.get(2).withLocks(L_DIR));
            catNotice("absorbResidualWhy(Element 3): a DIR-locked absorber", SectionNames.absorbResidualWhy(SectionNames.elementName(2)),
                ScSolve.solveNodeDrag(sl, lv, 0, new Vec3(22, 3, 23), LOCAL));
            List<ScSolve.ElemParams> dl = sBend();
            dl.set(3, dl.get(3).withLocks(L_X));
            cat("downstreamLockWhy(Element 4): a locked end beyond the absorber", SectionNames.downstreamLockWhy(SectionNames.elementName(3)),
                ScSolve.solveNodeDrag(dl, lv, 0, new Vec3(22, 0, 23), LOCAL));
        }
        cat("insertLockWhy(Element 2): no straight length reaches the locked end of a 270° curve", SectionNames.insertLockWhy(SectionNames.elementName(1)),
            ScSolve.insertAt(els(straight(10), curve(20, 270, 0).withLocks(L_X | L_Y | L_Z)), lv, 1, STRAIGHT));
        cat("insertBlockedWhy(Element 3, end position): a second locked node beyond", SectionNames.insertBlockedWhy(SectionNames.elementName(2), SectionNames.LOCK_POS_QUANTITY),
            ScSolve.insertAt(els(straight(10), c0, straight(5).withLocks(L_Y)), lv, 1, STRAIGHT));
        cat("deleteLockWhy(Element 1, end position, Element 4)", SectionNames.deleteLockWhy(SectionNames.elementName(0), SectionNames.LOCK_POS_QUANTITY, SectionNames.elementName(3)),
            ScSolve.deleteAt(els(c0, straight(5), curve(20, -90, 0), straight(10).withLocks(L_X)), lv, 0));
        cat("unreachableWhy(Element 1, Turn, 1.0°): a 1° turn at κ = 10 falls under the length floor",
            SectionNames.unreachableWhy(SectionNames.elementName(0), SectionNames.boxLabel(ScSolve.Box.TURN.ordinal()), ScSolve.boxValueText(ScSolve.Box.TURN, 1)),
            ScSolve.solveTypedBox(els(planar(0.6, 10, 0)), lv, 0, ScSolve.Box.TURN, 1, LOCAL, true));
        {
            final int[] calls = { 0 };
            ScSolve.LandedProbe flip = (ps, border) -> {
                Vec3 sharp = ScSolve.frames(lv, ps)[border].p();
                calls[0]++;
                return sharp.add(new Vec3(calls[0] % 2 == 1 ? 0.005 : -0.005, 0, 0));
            };
            ScSolve.Solution s = ScSolve.solveTypedPoint(els(c0, straight(8).withBlend(2)), lv, 0, new Vec3(21, 0, 21), LOCAL, true, flip);
            catNotice("predictorOffWhy(10): a blend that moves the node by an unpredictable 5 mm", SectionNames.predictorOffWhy(10), s);
        }
        cat("TWO_CURVES_WHY: a pose on a lone curve with no trailing straight", SectionNames.TWO_CURVES_WHY,
            ScSolve.solveTail(els(c0), lv, pose, ScSolve.TailMode.ALIGN_PREV));
        cat("SCS_PLANE_WHY: a straight–curve–straight meeting a pose outside the seam's plane", SectionNames.SCS_PLANE_WHY,
            ScSolve.solveTail(els(straight(5), c0, straight(8)), lv, new ScSolve.Target.Pose(new Vec3(50, 7, 30), -90, 0), ScSolve.TailMode.ALIGN_PREV));
        cat("ALIGN_NO_CURVE_WHY: a pose on a straight-only tail", SectionNames.ALIGN_NO_CURVE_WHY,
            ScSolve.solveTail(els(straight(10)), lv, pose, ScSolve.TailMode.ALIGN_PREV));
        cat("ALIGN_LOCKED_WHY: the last curve's turn locked", SectionNames.ALIGN_LOCKED_WHY,
            ScSolve.solveTail(els(c0, straight(5), curve(20, -90, 0).withLocks(L_TURN)), lv, pose, ScSolve.TailMode.ALIGN_PREV));
        cat("TAIL_KIND_WHY: a helix at the end", SectionNames.TAIL_KIND_WHY,
            ScSolve.solveTail(els(c0, straight(5), h0), lv, pose, ScSolve.TailMode.ALIGN_PREV));
        catNotice("BIARC_FALLBACK_WHY: the kept-curvature root does not exist (κ_A,old = 10)", SectionNames.BIARC_FALLBACK_WHY,
            ScSolve.solveTail(els(planar(0.5, 10, 0), straight(5), c0), lv, pose, ScSolve.TailMode.ALIGN_PREV));
        cat("SNAP_ONE_WHY: a single snap on a lone curve", SectionNames.SNAP_ONE_WHY,
            ScSolve.solveTail(els(c0), lv, pose, ScSolve.TailMode.SNAP_HEADING));
        cat("LONE_STRAIGHT_WHY: a position-only solve on a straight-only tail", SectionNames.LONE_STRAIGHT_WHY,
            ScSolve.solveTail(els(straight(10)), lv, new ScSolve.Target.Point(new Vec3(30, 2, 5)), ScSolve.TailMode.CLOSE_POS));
        catStr("NOTHING_TO_MOVE_WHY: the node before a closed tail's trailing straight", SectionNames.NOTHING_TO_MOVE_WHY,
            ScSolve.closedTailEditWhy(els(c0, straight(5)), 0));
        cat("HEAD_VERTICAL_WHY: an align toward a vertical seam", SectionNames.HEAD_VERTICAL_WHY,
            ScSolve.solveTail(els(c0, straight(5), c0), lv, new ScSolve.Target.Pose(new Vec3(40, 10, 30), 0, 90), ScSolve.TailMode.ALIGN_PREV));
        info("ScSolveGate j B_ELEMENT_WHY", "UI-only (B on a non-last element node, §4.10 / §5.2) — LabelGate 21ao pins the sentence; no solver path");
        {
            StraightCurveSection sc = scOf(els(c0));
            sc.reversed = true;
            catStr("REVERSED_WHY: a legacy reversed section", SectionNames.REVERSED_WHY, ScSolve.reversedWhy(sc));
        }
    }

    // ---------------------------------------------------------------
    // k) the biarc · l) the single snap
    // ---------------------------------------------------------------

    static Vec3 randUnitForward(Random r, Vec3 axis, double maxDeg) {
        for (;;) {
            Vec3 v = new Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            if (v.length() == 0) continue;
            if (angDeg(v, axis) <= maxDeg) return v;
        }
    }

    /** An independent 2-D biarc (x, z plane; the tangents level) at the
     *  tie-break root or t = 1/2 — {κ_A, L_A, κ_B, L_B, t, fallback}. */
    static double[] biarc2D(double[] P0, double[] T0, double[] P1, double[] T1, double lam, double kOldA) {
        DoubleUnaryOperator kA = t -> {
            double[] bi = biarc2At(t, P0, T0, P1, T1, lam);
            return bi == null ? Double.NaN : Math.abs(bi[0]) - kOldA;
        };
        double lo = 1e-3, hi = 1 - 1e-3;
        double glo = kA.applyAsDouble(lo), ghi = kA.applyAsDouble(hi);
        double t;
        boolean fb = false;
        if (Double.isNaN(glo) || Double.isNaN(ghi) || Math.signum(glo) == Math.signum(ghi)) { t = 0.5; fb = true; }
        else {
            for (int i = 0; i < 60; i++) {
                double mid = (lo + hi) / 2;
                double gm = kA.applyAsDouble(mid);
                if (gm == 0 || hi - lo < 1e-12) { lo = hi = mid; break; }
                if (Math.signum(gm) == Math.signum(glo)) { lo = mid; glo = gm; } else hi = mid;
            }
            t = (lo + hi) / 2;
        }
        double[] bi = biarc2At(t, P0, T0, P1, T1, lam);
        return new double[]{ bi[0], bi[1], bi[2], bi[3], t, fb ? 1 : 0 };
    }

    static double dot2(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1]; }

    /** {κ_A, L_A, κ_B, L_B} — each arc by the 2-D I1 (signed κ toward the
     *  left normal (−z, x)). */
    static double[] biarc2At(double t, double[] P0, double[] T0, double[] P1, double[] T1, double lam) {
        double r = (1 - t) / t;
        double[] v = { T0[0] + r * T1[0], T0[1] + r * T1[1] };
        double[] d = { P1[0] - P0[0], P1[1] - P0[1] };
        double alpha = dot2(v, v) - (1 + r) * (1 + r);
        double bq = dot2(d, v) + lam * (1 + r);
        double cq = dot2(d, d) - lam * lam;
        double a;
        if (Math.abs(alpha) < 1e-12) a = cq / (2 * bq);
        else {
            double disc = bq * bq - alpha * cq;
            if (disc < 0) return null;
            double sd = Math.sqrt(disc);
            double a1 = (bq - sd) / alpha, a2 = (bq + sd) / alpha;
            a = Math.min(a1, a2) > 1e-12 ? Math.min(a1, a2) : Math.max(a1, a2);
        }
        if (!(a > 1e-12)) return null;
        double b = r * a;
        double[] MA = { P0[0] + a * T0[0], P0[1] + a * T0[1] };
        double[] MB = { P1[0] - b * T1[0], P1[1] - b * T1[1] };
        double[] D = { MB[0] - MA[0], MB[1] - MA[1] };
        double dl = Math.hypot(D[0], D[1]);
        double[] u = { D[0] / dl, D[1] / dl };
        double[] JA = { MA[0] + a * u[0], MA[1] + a * u[1] };
        double[] JB = { JA[0] + lam * u[0], JA[1] + lam * u[1] };
        double[] arcA = i12d(P0, T0, JA), arcB = i12d(JB, u, P1);
        return new double[]{ arcA[0], arcA[1], arcB[0], arcB[1] };
    }

    /** 2-D I1: {κ (signed, + = toward the left normal), L}. */
    static double[] i12d(double[] p0, double[] T, double[] p) {
        double[] N = { -T[1], T[0] };
        double dx = p[0] - p0[0], dz = p[1] - p0[1];
        double dT = dx * T[0] + dz * T[1], dN = dx * N[0] + dz * N[1];
        if (Math.abs(dN) < 1e-15) return new double[]{ 0, dT };
        double kap = 2 * dN / (dT * dT + dN * dN);
        double phi = 2 * Math.atan2(Math.abs(dN), dT);
        return new double[]{ kap, phi / Math.abs(kap) };
    }

    static void rowK() {
        Random r = new Random(4);
        ScSolve.Frame lv = level();
        int met = 0, fallbacks = 0, refused = 0, n = 0;
        double wp = 0, wt = 0;
        String firstBad = null;
        while (n < 200) {
            double lam = (n % 2 == 0) ? 0 : 5, ell = ((n / 2) % 2 == 0) ? 0 : 3;
            ScSolve.ElemParams A = curve(15 + r.nextDouble() * 25, 20 + r.nextDouble() * 100, r.nextDouble() * 360);
            ScSolve.ElemParams B = curve(15 + r.nextDouble() * 25, 20 + r.nextDouble() * 100, r.nextDouble() * 360);
            List<ScSolve.ElemParams> tail = new ArrayList<>();
            tail.add(A);
            if (lam > 0) tail.add(straight(lam));
            tail.add(B);
            if (ell > 0) tail.add(straight(ell));
            Vec3 d = randUnitForward(r, new Vec3(1, 0, 0), 60).scale(20 + r.nextDouble() * 30);
            Vec3 T1 = randUnitForward(r, d.normalize(), 60);
            double head = Math.toDegrees(Math.atan2(-T1.z(), T1.x())), elev = Math.toDegrees(Math.asin(T1.y()));
            if (Math.abs(elev) > 80) continue;
            ScSolve.Solution s = ScSolve.solveTail(tail, lv, new ScSolve.Target.Pose(d, head, elev), ScSolve.TailMode.ALIGN_PREV);
            n++;
            if (s.refused()) { refused++; if (firstBad == null) firstBad = "refused " + s.why(); continue; }
            if (SectionNames.BIARC_FALLBACK_WHY.equals(s.why())) fallbacks++;
            ScSolve.Frame[] nf = ScSolve.frames(lv, s.params());
            ScSolve.Frame ex = nf[tail.size()];
            double dp = ex.p().sub(d).length(), dt = Math.toRadians(angDeg(ex.T(), T1));
            wp = Math.max(wp, dp);
            wt = Math.max(wt, dt);
            if (dp <= 1e-9 && dt <= 1e-9) met++;
            else if (firstBad == null) firstBad = f("miss %.2e m / %.2e rad", dp, dt);
        }
        check(met == 200 && refused == 0,
            "ScSolveGate k 200 random pose pairs (Λ ∈ {0, 5}, ℓ ∈ {0, 3}): the pose met to 1e-9 m / 1e-9 rad, the tie-break root found or the fallback flagged",
            f("met %d, fallbacks %d, refused %d, worst %.2e m / %.2e rad%s", met, fallbacks, refused, wp, wt, firstBad == null ? "" : ", first bad " + firstBad));
        // planar pairs == the independent 2-D construction
        double w2 = 0;
        int n2 = 0, bad2 = 0;
        while (n2 < 50) {
            double lam = (n2 % 2 == 0) ? 0 : 5;
            ScSolve.ElemParams A = curve(15 + r.nextDouble() * 25, 20 + r.nextDouble() * 100, r.nextBoolean() ? 0 : 180);
            ScSolve.ElemParams B = curve(15 + r.nextDouble() * 25, 20 + r.nextDouble() * 100, r.nextBoolean() ? 0 : 180);
            List<ScSolve.ElemParams> tail = lam > 0 ? els(A, straight(lam), B) : els(A, B);
            double ang = (r.nextDouble() * 2 - 1) * 60;
            Vec3 d = new Vec3(Math.cos(Math.toRadians(ang)), 0, -Math.sin(Math.toRadians(ang))).scale(20 + r.nextDouble() * 30);
            double head = ang + (r.nextDouble() * 2 - 1) * 60;
            Vec3 T1 = ScSolve.tangentOf(head, 0);
            ScSolve.Solution s = ScSolve.solveTail(tail, lv, new ScSolve.Target.Pose(d, head, 0), ScSolve.TailMode.ALIGN_PREV);
            n2++;
            if (s.refused()) { bad2++; continue; }
            // the 2-D construction in (x, z); the left normal of (1, 0) is (0, −1)?? — use (x, −z)
            // coordinates so that +κ (rider-left at dir 0 = +Z in 3-D) maps to the 2-D left normal
            double[] P0 = { 0, 0 }, T0 = { 1, 0 }, P1 = { d.x(), d.z() }, TT1 = { T1.x(), T1.z() };
            double[] bi = biarc2D(P0, T0, P1, TT1, lam, Math.abs(A.k0()));
            ScSolve.ElemParams nA = s.params().get(0), nB = s.params().get(tail.size() - 1);
            double e = Math.max(Math.max(Math.abs(Math.abs(nA.k0()) - Math.abs(bi[0])), Math.abs(nA.L() - bi[1])),
                Math.max(Math.abs(Math.abs(nB.k0()) - Math.abs(bi[2])), Math.abs(nB.L() - bi[3])));
            boolean fbSolver = SectionNames.BIARC_FALLBACK_WHY.equals(s.why());
            if ((bi[5] == 1) != fbSolver) e = Math.max(e, 1);
            w2 = Math.max(w2, e);
        }
        check(bad2 == 0 && w2 <= 1e-12,
            "ScSolveGate k planar pairs (50, in the x/z plane) == an independent 2-D biarc construction to 1e-12 (|κ|, L of both arcs, the same fallback flag)",
            f("worst %.2e, refused %d", w2, bad2));
    }

    static void rowL() {
        Random r = new Random(11);
        ScSolve.Frame lv = level();
        double wpH = 0, waH = 0, wpE = 0, waE = 0;
        int n = 0, bad = 0;
        while (n < 40) {
            // a generated curve + straight chain: its exit is a consistent (position, angle) target
            ScSolve.ElemParams c = curve(12 + r.nextDouble() * 30, 15 + r.nextDouble() * 120, r.nextDouble() * 360);
            double ell = 2 + r.nextDouble() * 15;
            List<ScSolve.ElemParams> truth = els(c, straight(ell));
            ScSolve.Frame ex = ScSolve.frames(lv, truth)[2];
            if (ex.vertical() || Math.abs(ex.elevationDeg()) > 80) continue;
            List<ScSolve.ElemParams> cur = els(c.withK(c.k0() * 1.15).withL(c.L() * 0.9).withDir(c.dirDeg() + 7), straight(ell * 1.2));
            ScSolve.Target.Pose tgt = new ScSolve.Target.Pose(ex.p(), ex.headingDeg(), ex.elevationDeg());
            ScSolve.Solution sh = ScSolve.solveTail(cur, lv, tgt, ScSolve.TailMode.SNAP_HEADING);
            ScSolve.Solution se = ScSolve.solveTail(cur, lv, tgt, ScSolve.TailMode.SNAP_ELEVATION);
            n++;
            if (sh.refused() || se.refused()) { bad++; continue; }
            ScSolve.Frame eh = ScSolve.frames(lv, sh.params())[2], ee = ScSolve.frames(lv, se.params())[2];
            wpH = Math.max(wpH, eh.p().sub(ex.p()).length());
            waH = Math.max(waH, Math.abs(ScSolve.nearest360(eh.headingDeg() - ex.headingDeg(), 0)));
            wpE = Math.max(wpE, ee.p().sub(ex.p()).length());
            waE = Math.max(waE, Math.abs(ee.elevationDeg() - ex.elevationDeg()));
        }
        check(bad == 0 && wpH <= 1e-9 && waH <= 1e-9 && wpE <= 1e-9 && waE <= 1e-9,
            "ScSolveGate l the single snap on 40 curve+straight tails: position + heading / position + elevation to 1e-9",
            f("heading snap worst %.2e m / %.2e°, elevation snap worst %.2e m / %.2e°, refused %d", wpH, waH, wpE, waE, bad));
        // two-curve tails: the biarc with the current other angle
        double wp2 = 0, wa2 = 0;
        int n2 = 0, bad2 = 0;
        while (n2 < 40) {
            List<ScSolve.ElemParams> tail = els(curve(15 + r.nextDouble() * 25, 20 + r.nextDouble() * 100, r.nextDouble() * 360),
                curve(15 + r.nextDouble() * 25, 20 + r.nextDouble() * 100, r.nextDouble() * 360));
            ScSolve.Frame ex0 = ScSolve.frames(lv, tail)[2];
            if (ex0.vertical()) continue;
            Vec3 d = randUnitForward(r, new Vec3(1, 0, 0), 60).scale(20 + r.nextDouble() * 30);
            double head = (r.nextDouble() * 2 - 1) * 120;
            ScSolve.Solution s = ScSolve.solveTail(tail, lv, new ScSolve.Target.Pose(d, head, 0), ScSolve.TailMode.SNAP_HEADING);
            n2++;
            if (s.refused()) { bad2++; continue; }
            ScSolve.Frame ex = ScSolve.frames(lv, s.params())[2];
            wp2 = Math.max(wp2, ex.p().sub(d).length());
            wa2 = Math.max(wa2, Math.abs(ScSolve.nearest360(ex.headingDeg() - head, 0)));
        }
        check(bad2 == 0 && wp2 <= 1e-9 && wa2 <= 1e-9,
            "ScSolveGate l the single snap on 40 two-curve tails (the biarc with the current elevation kept): position + heading to 1e-9",
            f("worst %.2e m / %.2e°, refused %d", wp2, wa2, bad2));
        ScSolve.Solution lone = ScSolve.solveTail(els(curve(20, 90, 0)), lv, new ScSolve.Target.Pose(new Vec3(30, 0, 30), -45, 0), ScSolve.TailMode.SNAP_ELEVATION);
        check(lone.refused() && SectionNames.SNAP_ONE_WHY.equals(lone.why()),
            "ScSolveGate l a single snap on a lone curve refuses with SNAP_ONE_WHY", String.valueOf(lone.why()));
    }

    // ---------------------------------------------------------------
    // m) bounded Newton · n) golden section
    // ---------------------------------------------------------------

    static void rowM() {
        Random r = new Random(5);
        ScSolve.Frame lv = level();
        int n = 0, fails = 0, maxSteps = 0;
        double wp = 0;
        boolean bitwise = true;
        while (n < 100) {
            double k0 = (r.nextDouble() * 2 - 1) * 5, k1 = (r.nextDouble() * 2 - 1) * 5;
            if (Math.abs(k1 - k0) < 0.05) continue;
            double L = 2 + r.nextDouble() * 28;
            // a realistic ramp: neither end's curvature winds more than 270°
            // over the element (a multi-lap double spiral has no Newton basin
            // a 20 % perturbation reaches — RAMP_SOLVE_WHY is its honest answer)
            if (Math.max(Math.abs(k0), Math.abs(k1)) * L > 1.5 * Math.PI) continue;
            ScSolve.ElemParams ex = new ScSolve.ElemParams(PLANAR, L, k0, k1, false, n % 2 == 0 ? LIN : SIN,
                r.nextDouble() * 360, 0, false, LIN, 0, 0);
            ScSolve.Frame fe = n % 3 == 0 ? level() : entryAt(new Vec3(1, 1, 1), 40, 15);
            Vec3 tgt = fwd(fe, ex).p();
            ScSolve.ElemParams cur = ex.withK1(k1 * (1 + 0.2 * (r.nextDouble() * 2 - 1))).withL(L * (1 + 0.2 * (r.nextDouble() * 2 - 1)))
                .withDir(ex.dirDeg() + 4);
            ScSolve.Solution s1 = drag(els(cur), fe, 0, tgt);
            ScSolve.Solution s2 = drag(els(cur), fe, 0, tgt);
            n++;
            if (s1.refused() || s1.status() != ScSolve.Status.EXACT) { fails++; continue; }
            maxSteps = Math.max(maxSteps, s1.iterations());
            wp = Math.max(wp, fwd(fe, s1.params().get(0)).p().sub(tgt).length());
            if (!same(s1.params(), s2.params())) bitwise = false;
        }
        check(fails == 0 && maxSteps <= 20 && wp <= 1e-9 && bitwise,
            "ScSolveGate m 100 ramped planar targets (LINEAR / SINUSOIDAL κ, k0 held, (k1, L) by Newton from the constant-κ seed): ≤ 20 steps to 1e-9 m, 0 failures, bitwise across two runs",
            f("max steps %d, worst %.2e m, failures %d, bitwise %b", maxSteps, wp, fails, bitwise));
        int m = 0, failsH = 0, maxStepsH = 0;
        double wph = 0;
        boolean bitwiseH = true;
        while (m < 50) {
            double b0 = (r.nextDouble() * 2 - 1) * 50, b1 = (r.nextDouble() * 2 - 1) * 50;
            double ch = (r.nextDouble() * 2 - 1) * 4;
            if (Math.abs(ch) < 0.02 || Math.abs(b1 - b0) < 2) continue;
            double L = 4 + r.nextDouble() * 40;
            double fac = Math.cos(Math.toRadians((b0 + b1) / 2)) * ElementChain.sinc(Math.toRadians(b1 - b0) / 2);
            if (Math.abs(ch) * L * fac > 2.5 * Math.PI) continue;
            ScSolve.ElemParams ex = helix(L, ch, b1).withBInterp(SIN);
            ScSolve.Frame fe = entryAt(new Vec3(0, 3, 0), m % 2 == 0 ? 0 : 150, b0);
            Vec3 tgt = fwd(fe, ex).p();
            ScSolve.ElemParams cur = ex.withB1(Math.max(-80, Math.min(80, b1 + 0.2 * (b1 - b0) * (r.nextDouble() * 2 - 1))))
                .withL(L * (1 + 0.2 * (r.nextDouble() * 2 - 1)));
            ScSolve.Solution s1 = drag(els(cur), fe, 0, tgt);
            ScSolve.Solution s2 = drag(els(cur), fe, 0, tgt);
            m++;
            if (s1.refused() || s1.status() != ScSolve.Status.EXACT) { failsH++; continue; }
            maxStepsH = Math.max(maxStepsH, s1.iterations());
            wph = Math.max(wph, fwd(fe, s1.params().get(0)).p().sub(tgt).length());
            if (!same(s1.params(), s2.params())) bitwiseH = false;
        }
        check(failsH == 0 && maxStepsH <= 20 && wph <= 1e-9 && bitwiseH,
            "ScSolveGate m 50 SINUSOIDAL-pitch helix targets ((β1, L) by Newton from the J0 seed against ElementChain's quadrature): ≤ 20 steps to 1e-9 m, 0 failures, bitwise across two runs",
            f("max steps %d, worst %.2e m, failures %d, bitwise %b", maxStepsH, wph, failsH, bitwiseH));
    }

    static void rowN() {
        Random r = new Random(9);
        ScSolve.Frame lv = level();
        ScSolve.ElemParams c0 = curve(20, 60, 20);
        for (int which = 0; which < 2; which++) {
            int lock = which == 0 ? L_HEAD : L_Y;
            ScSolve.ElemParams e = c0.withLocks(L_K0 | lock);
            ScSolve.Frame end0 = fwd(lv, e);
            ScSolve.Constraint cons = which == 0 ? new ScSolve.Constraint(L_HEAD, end0.headingDeg())
                : new ScSolve.Constraint(L_Y, end0.p().y());
            int maxEv = 0, idem = 0, n = 0;
            double worstGap = 0;
            for (int i = 0; i < 50; i++) {
                Vec3 tgt = new Vec3(20 + (r.nextDouble() * 2 - 1) * 15, (r.nextDouble() * 2 - 1) * 6, 20 + (r.nextDouble() * 2 - 1) * 15);
                ScSolve.Solution s = drag(els(e), lv, 0, tgt);
                if (s.refused()) continue;
                n++;
                maxEv = Math.max(maxEv, s.iterations());
                double dg = s.landedPos().sub(tgt).length();
                // the dense sample of the same 1-D set: θ over the ±90° bracket
                double theta0 = e.turnRad();
                double best = Double.MAX_VALUE;
                int N = 200000;
                for (int j = 0; j <= N; j++) {
                    double th = theta0 - Math.PI / 2 + Math.PI * j / N;
                    double[] dirs = which == 0 ? ScSolve.dirsForAngle(lv, th, cons) : ScSolve.dirsForCoord(lv, th, e.k0(), cons);
                    for (double dd : dirs) {
                        if (Double.isNaN(dd)) continue;
                        Vec3 p = ScSolve.planarEnd(lv, e.k0(), th, dd);
                        double L = Math.abs(th / e.k0());
                        if (L < 0.5) continue;
                        best = Math.min(best, p.sub(tgt).length());
                    }
                }
                worstGap = Math.max(worstGap, dg - best);
                ScSolve.Solution s2 = drag(s.params(), lv, 0, s.landedPos());
                if (same(s.params(), s2.params()) && s2.status() == ScSolve.Status.EXACT) idem++;
            }
            check(n == 50 && maxEv <= 40 && worstGap <= 1e-6 && idem == 50,
                "ScSolveGate n κ + " + (which == 0 ? "heading" : "Y") + " locked, 50 targets: ≤ 40 evaluations, within 1e-6 m of a dense-sample (200001) minimum, idempotent",
                f("solved %d, max evaluations %d, worst distance gap %.2e m, idempotent %d", n, maxEv, worstGap, idem));
        }
    }

    // ---------------------------------------------------------------
    // o) insert against a lock
    // ---------------------------------------------------------------

    static void rowO() {
        ScSolve.Frame lv = level();
        // 1 root: the locked end of a 90° R20 curve after a 10 m straight
        List<ScSolve.ElemParams> one = els(straight(10), curve(20, 90, 0).withLocks(L_X | L_Y | L_Z));
        Vec3 lockPt = endOf(lv, one, 1);
        ScSolve.Solution s1 = ScSolve.insertAt(one, lv, 1, STRAIGHT);
        double hold1 = s1.refused() ? Double.NaN : endOf(lv, s1.params(), 2).sub(lockPt).length();
        check(!s1.refused() && s1.insertedAt() == 1 && s1.params().size() == 3 && Math.abs(s1.params().get(1).L() - 40) <= 1e-9 && hold1 <= 1e-12,
            "ScSolveGate o a STRAIGHT inserted before a curve whose end is locked: the one root (L = 2 sin Θ/κ = 40 m), the locked end holds to 1e-12",
            f("status %s, L %.6f, hold %.2e m, why %s", s1.status(), s1.refused() ? Double.NaN : s1.params().get(1).L(), hold1, s1.why()));
        // 0 roots: a 270° curve (its locked end sits behind the straight's line)
        ScSolve.Solution s0 = ScSolve.insertAt(els(straight(10), curve(20, 270, 0).withLocks(L_X | L_Y | L_Z)), lv, 1, STRAIGHT);
        check(s0.refused() && SectionNames.insertLockWhy(SectionNames.elementName(1)).equals(s0.why()),
            "ScSolveGate o a STRAIGHT insert with no root (a 270° curve) refuses with insertLockWhy", String.valueOf(s0.why()));
        info("ScSolveGate o the 2-root case", "unreachable through insertAt: the locked end always lies on element b's own arc, so L = Δ_T ∓ √(2Δ_N/κ − Δ_N²) = {0, 2 sin Θ/κ} — one admissible root at most (documented)");
        // a CURVE insert: the default R20 90°, the next curve re-solves by I1 to keep its locked end
        List<ScSolve.ElemParams> cv = els(straight(10), curve(30, 120, 0).withLocks(L_X | L_Y | L_Z));
        Vec3 lockC = endOf(lv, cv, 1);
        ScSolve.Solution sc = ScSolve.insertAt(cv, lv, 1, PLANAR);
        double holdC = sc.refused() ? Double.NaN : endOf(lv, sc.params(), 2).sub(lockC).length();
        ScSolve.ElemParams def = ScSolve.ElemParams.defaultOf(PLANAR, 0);
        boolean defKept = !sc.refused() && bits(sc.params().get(1).k0(), def.k0()) && bits(sc.params().get(1).L(), def.L());
        check(!sc.refused() && holdC <= 1e-12 && defKept && sc.params().size() == 3,
            "ScSolveGate o a CURVE inserted before a locked end: the new element keeps its default shape, the next curve re-solves by I1 (κ free), the lock holds to 1e-12, the exit-T change posted as the residual",
            f("status %s, hold %.2e m, residual %.4f°, why %s", sc.status(), holdC, sc.residualRot(), sc.why()));
        // a plain insert (no lock): the absorb restores the exit tangent of the curve after the border
        List<ScSolve.ElemParams> pl = els(straight(10), curve(20, 90, 0), straight(5));
        ScSolve.Frame[] fr0 = ScSolve.frames(lv, pl);
        ScSolve.Solution sp = ScSolve.insertAt(pl, lv, 1, PLANAR);
        ScSolve.Frame[] fr1 = sp.refused() ? null : ScSolve.frames(lv, sp.params());
        check(!sp.refused() && sp.params().size() == 4 && angDeg(fr1[3].T(), fr0[2].T()) <= 1e-9 && sp.residualRot() == 0,
            "ScSolveGate o a plain insert at a border: the curve after it absorbs the default's turn (its exit tangent restored), no residual",
            f("status %s, exit T %.2e°, residual %.2e", sp.status(), sp.refused() ? Double.NaN : angDeg(fr1[3].T(), fr0[2].T()), sp.residualRot()));
    }

    // ---------------------------------------------------------------
    // p) the helix inverse vs a 64-node quadrature incl. J0
    // ---------------------------------------------------------------

    static final double[] GLX = { -0.9602898564975363, -0.7966664774136267, -0.5255324099163290,
        -0.1834346424956498, 0.1834346424956498, 0.5255324099163290, 0.7966664774136267, 0.9602898564975363 };
    static final double[] GLW = { 0.1012285362903763, 0.2223810344533745, 0.3137066458778873,
        0.3626837833783620, 0.3626837833783620, 0.3137066458778873, 0.2223810344533745, 0.1012285362903763 };

    /** ∫₀ᴸ g(β(s)) ds with 8 panels × 8 nodes (64 evaluations). */
    static double quad64(double L, DoubleUnaryOperator gOfS) {
        int panels = 8;
        double h = L / panels, sum = 0;
        for (int p = 0; p < panels; p++) {
            double s0 = p * h;
            for (int g = 0; g < 8; g++) sum += GLW[g] * h / 2 * gOfS.applyAsDouble(s0 + h * (GLX[g] + 1) / 2);
        }
        return sum;
    }

    static void rowP() {
        Random r = new Random(13);
        double wLin = 0, wJ0 = 0, wSin = 0;
        int n = 0;
        while (n < 60) {
            double b0 = (r.nextDouble() * 2 - 1) * 70, b1 = (r.nextDouble() * 2 - 1) * 70;
            double ch = (r.nextDouble() * 2 - 1) * 3;
            if (Math.abs(ch) < 0.02) continue;
            double L = 5 + r.nextDouble() * 40;
            final double B0 = Math.toRadians(b0), DB = Math.toRadians(b1 - b0), LL = L;
            boolean sinus = n % 2 == 1;
            DoubleUnaryOperator beta = s -> B0 + DB * (sinus ? (1 - Math.cos(Math.PI * s / LL)) / 2 : s / LL);
            double Lh = quad64(L, s -> Math.cos(beta.applyAsDouble(s)));
            double dy = quad64(L, s -> Math.sin(beta.applyAsDouble(s)));
            if (Math.abs(ch) * Lh > 2.5 * Math.PI || Lh < 1) continue;
            // the closed plan factor vs the quadrature
            double fac = ScSolve.planFactor(B0, B0 + DB, sinus);
            double facErr = Math.abs(fac * L - Lh) / L;
            if (sinus) wJ0 = Math.max(wJ0, facErr); else wLin = Math.max(wLin, facErr);
            // the quadrature's end point on the plan circle (entry heading 0 at the origin)
            double dpsi = ch * Lh;
            Vec3 pq = new Vec3(Math.sin(dpsi) / ch, dy, (1 - Math.cos(dpsi)) / ch);
            ScSolve.Frame fe = entryAt(Vec3.ZERO, 0, b0);
            ScSolve.ElemParams hint = helix(L * 1.1, ch * 0.95, Math.max(-80, Math.min(80, b1 + 4)));
            if (sinus) hint = hint.withBInterp(SIN);
            ScSolve.Solution s = drag(els(hint), fe, 0, pq);
            double dp = s.refused() ? Double.NaN : fwd(fe, s.params().get(0)).p().sub(pq).length();
            if (sinus) wSin = Math.max(wSin, dp); else wLin = Math.max(wLin, dp);
            n++;
        }
        check(wLin <= 1e-9 && wJ0 <= 1e-12 && wSin <= 1e-9,
            "ScSolveGate p the helix inverse (I9) against a 64-node quadrature of §4.3 (LINEAR pitch, 30 draws) and the SINUSOIDAL-pitch J0 closed form vs the quadrature (1e-12 relative) + its inverse through the Newton polish (30 draws) — 1e-9 m",
            f("linear worst %.2e m, J0 factor worst %.2e rel, sinusoidal worst %.2e m", wLin, wJ0, wSin));
    }

    // ---------------------------------------------------------------
    // q) determinism
    // ---------------------------------------------------------------

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    static String resultDiff(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return "sample counts " + a.samples().size() + " vs " + b.samples().size();
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            if (!(bits(x.pos(), y.pos()) && bits(x.T(), y.T()) && bits(x.U(), y.U()) && bits(x.v(), y.v())
                    && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl()) && bits(x.t(), y.t()) && bits(x.s(), y.s()))) {
                return "sample " + i;
            }
            if (!bits(a.trueV()[i], b.trueV()[i])) return "trueV " + i;
        }
        return a.spans().size() == b.spans().size() ? null : "span counts";
    }

    static long[] vdBits(Coaster c) {
        List<Long> l = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) l.add(Double.doubleToRawLongBits(s.vDesign()));
        long[] out = new long[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }

    static Coaster qFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(scOf(els(straight(10), curve(20, 90, 0), straight(8))));
        c.pieces.add(p);
        // a force hold after the s/c (an automatic follower the reconcile re-freezes)
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        FVDIntegrator.Sample end = r.samples().get(r.samples().size() - 1);
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        for (double v : new double[]{ end.Gv(), end.Gl(), 0 }) {
            List<BezierBaker.Node> l = new ArrayList<>();
            l.add(new BezierBaker.Node(0, v));
            l.add(new BezierBaker.Node(1, v));
            BezierBaker.applyDefaultHandles(l);
            ls.add(l);
        }
        c.appendSectionWithInvariant(0, new ForceSection(ls, end.v()), DT);
        return c;
    }

    static String save(Coaster c) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, DesignIO.Cosmetics.defaults(), pw); }
        return sw.toString();
    }

    static void rowQ() throws Exception {
        Coaster c = qFixture();
        TrackCompiler.Result r0 = rebuild(c);
        SectionFrame sf = SectionFrame.of(r0, c, 0, 0);
        StraightCurveSection sc = (StraightCurveSection) c.pieces.get(0).sections.get(0);
        List<ScSolve.ElemParams> ps = ScSolve.ElemParams.of(sc);
        Vec3 tgt = sf.bounds().get(2).sharpPos().add(new Vec3(1.5, 0.4, -2.0));
        ScSolve.Solution s = ScSolve.solveNodeDrag(ps, sf.entryFrame(), 1, tgt, LOCAL);
        boolean applied = ScSolve.apply(sc, s);
        TrackCompiler.Result r1 = rebuild(c);
        long[] vd1 = vdBits(c);
        TrackCompiler.Result r2 = rebuild(c);
        long[] vd2 = vdBits(c);
        String d12 = resultDiff(r1, r2);
        TrackCompiler.Result r3 = TrackCompiler.compile(c, DT);
        String d13 = resultDiff(r1, r3);
        Vec3 landedW = r1.spans().get(0).scBounds().get(2).sharpPos();
        check(applied && s.status() == ScSolve.Status.EXACT && d12 == null && Arrays.equals(vd1, vd2) && d13 == null
                && landedW.sub(tgt).length() <= 1e-9,
            "ScSolveGate q one node edit on the default fixture (s/c + an automatic force follower) applied through ScSolve.apply, then rebuild: the rebuild is a BITWISE no-op (samples / trueV / vDesign), a third compile equal, the compile's sharp border lands on the target",
            f("status %s, rebuild diff %s, vDesign stable %b, landing %.2e m", s.status(), d12, Arrays.equals(vd1, vd2), landedW.sub(tgt).length()));
        String t1 = save(c);
        DesignIO.LoadResult lr = DesignIO.load(new BufferedReader(new StringReader(t1)));
        Coaster l = lr.coasters().get(0);
        TrackCompiler.Result rl = rebuild(l);
        String dl = resultDiff(r1, rl);
        String t2 = save(l);
        check(dl == null && t1.equals(t2),
            "ScSolveGate q save → load → rebuild BITWISE == the session (and the re-save byte-identical)",
            f("diff %s, byte-identical %b", dl, t1.equals(t2)));
    }

    // ---------------------------------------------------------------
    // r) the predictor
    // ---------------------------------------------------------------

    static void rowR() {
        ScSolve.Frame lv = level();
        List<ScSolve.ElemParams> blended = els(straight(10), curve(20, 90, 0).withBlend(2), straight(8));
        List<ScSolve.ElemParams> sharp = els(straight(10), curve(20, 90, 0), straight(8));
        ScSolve.LandedProbe probe = (ps, border) ->
            ScIntegrator.integrate(scOf(ps), DT, V, Vec3.ZERO, Quat.IDENTITY).bounds().get(border).pos();
        Vec3 landed0 = probe.landed(blended, 2), sharp0 = ScSolve.frames(lv, blended)[2].p();
        double delta0 = landed0.sub(sharp0).length();
        info("ScSolveGate r δ at the curve's end under a 2 m blend", f("%.2f mm", delta0 * 1000));
        Vec3 tgt = landed0.add(new Vec3(0, 0.5, 0));   // a typed Y: +0.5 m, x / z held at the LANDED position
        ScSolve.Solution s = ScSolve.solveTypedPoint(blended, lv, 1, tgt, LOCAL, false, probe);
        double miss = s.refused() ? Double.NaN : probe.landed(s.params(), 2).sub(tgt).length();
        check(!s.refused() && s.iterations() <= 3 && miss < 1e-3 && delta0 > 1e-3,
            "ScSolveGate r the typed corrector under a 2 m blend on R20 90° (δ > 1 mm): the LANDED border meets the typed point under 1 mm in ≤ 3 rounds",
            f("δ %.2f mm, rounds %d, final miss %.3f mm, status %s, why %s", delta0 * 1000, s.iterations(), miss * 1000, s.status(), s.why()));
        // δ = 0: the sharp path bitwise
        Vec3 tgtS = ScSolve.frames(lv, sharp)[2].p().add(new Vec3(0, 0.5, 0));
        ScSolve.Solution sp = ScSolve.solveTypedPoint(sharp, lv, 1, tgtS, LOCAL, false, probe);
        ScSolve.Solution s0 = ScSolve.solveTypedPoint(sharp, lv, 1, tgtS, LOCAL, false, null);
        ScSolve.Solution sPlain = ScSolve.solveNodeDrag(sharp, lv, 1, tgtS, LOCAL);
        check(!sp.refused() && same(sp.params(), s0.params()) && same(sp.params(), sPlain.params()) && sp.iterations() == 1,
            "ScSolveGate r δ = 0 (no blend): the corrector's params BITWISE the sharp solve's (probe and no probe), one round",
            f("rounds %d, bitwise %b", sp.iterations(), same(sp.params(), sPlain.params())));
    }

    // ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        rowA();
        rowB();
        rowC();
        rowD();
        rowE();
        rowF();
        rowG();
        rowH();
        rowI();
        rowJ();
        rowK();
        rowL();
        rowM();
        rowN();
        rowO();
        rowP();
        rowQ();
        rowR();
        skip("ScSolveGate s the auto-follower RIG (ring / arrow / ball == box bitwise in the upstream exit frame)",
            "Stage 3a — the element rig does not exist yet; SectionFrame.of already hands an automatic follower the upstream span's pair + inherited tilt (row g exercises the frame on a run head)");
        System.out.println();
        System.out.println("ScSolveGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
