import java.util.ArrayList;
import java.util.List;
import uvd.*;

/**
 * STAGE 1a STEP 0 (D259.1a; design doc §1.9 h, §10.6): the LEGACY-AXIS
 * checksum record. Run on the UNMODIFIED bfc70fd build (classes wiped and
 * rebuilt) BEFORE Stage 1a's first source edit; the recorded lines live
 * beside this file in legacy-checksums.txt. ScElemGate h re-runs the same
 * fixtures on the new build under -Dfvd.sc.legacyAxis=1 and must reproduce
 * every line BITWISE (switch ON = today's body-frame curve axis verbatim);
 * with the switch OFF the roll-free law must agree to <= 1e-9 m on the
 * cardinal level fixtures (the two laws coincide there) and may differ
 * elsewhere (the pitched/banked entries — today's defect, PLAN §0 item 2).
 *
 * Fixtures = Phase2Gate :130-199's cardinal elements (straight 50; R20
 * -90 dir 0; R30 45 dir 90; R20 +90 dir 0; the 15/R20 -90/15 smoothing
 * chain at 0 and 6 m) x roll {RATE 0, GLOBAL 0} x entry {level, pitched
 * 20 deg, banked 30 deg, pitched 20 + banked 30}, at 26 m/s from (0,21,0),
 * dt 0.01, no friction, no controls — the plain integrate overload.
 *
 * Compile + run (from the project root, after `rm -rf target/classes/uvd;
 * mvn -q compile`):
 *   javac -encoding UTF-8 -cp target/classes -d gates/probes/legacy-axis-2026-10-21/out gates/probes/legacy-axis-2026-10-21/LegacyAxisProbe.java
 *   java -cp "target/classes;gates/probes/legacy-axis-2026-10-21/out" LegacyAxisProbe > gates/probes/legacy-axis-2026-10-21/legacy-checksums.txt
 */
public final class LegacyAxisProbe {

    private LegacyAxisProbe() {}

    /** FNV-1a over the raw double bits — order-sensitive, bit-exact. */
    static long mix(long h, double d) {
        long b = Double.doubleToRawLongBits(d);
        for (int i = 0; i < 8; i++) {
            h ^= (b >>> (8 * i)) & 0xFF;
            h *= 0x100000001b3L;
        }
        return h;
    }

    static long checksum(List<FVDIntegrator.Sample> samples) {
        long h = 0xcbf29ce484222325L;
        for (FVDIntegrator.Sample sm : samples) {
            h = mix(h, sm.t()); h = mix(h, sm.s());
            h = mix(h, sm.pos().x()); h = mix(h, sm.pos().y()); h = mix(h, sm.pos().z());
            h = mix(h, sm.T().x()); h = mix(h, sm.T().y()); h = mix(h, sm.T().z());
            h = mix(h, sm.U().x()); h = mix(h, sm.U().y()); h = mix(h, sm.U().z());
            h = mix(h, sm.v()); h = mix(h, sm.Gv()); h = mix(h, sm.Gl());
        }
        return h;
    }

    record Fixture(String name, List<StraightCurveSection.Element> els) {}

    static StraightCurveSection.Element rolled(StraightCurveSection.Element e, boolean rate) {
        e.rollMode = rate ? StraightCurveSection.RollMode.RATE
                          : StraightCurveSection.RollMode.GLOBAL;
        e.rollVal = 0;
        return e;
    }

    static List<Fixture> fixtures(boolean rate) {
        List<Fixture> f = new ArrayList<>();
        String r = rate ? "RATE0" : "GLOBAL0";
        f.add(new Fixture("straight50/" + r, List.of(
            rolled(StraightCurveSection.Element.straight(50, 0), rate))));
        f.add(new Fixture("R20-90dir0/" + r, List.of(
            rolled(StraightCurveSection.Element.curve(20, -90, 0, 0), rate))));
        f.add(new Fixture("R30+45dir90/" + r, List.of(
            rolled(StraightCurveSection.Element.curve(30, 45, 90, 0), rate))));
        f.add(new Fixture("R20+90dir0/" + r, List.of(
            rolled(StraightCurveSection.Element.curve(20, 90, 0, 0), rate))));
        f.add(new Fixture("R20+90dir30/" + r, List.of(
            rolled(StraightCurveSection.Element.curve(20, 90, 30, 0), rate))));
        f.add(new Fixture("chain15-R20-15/sharp/" + r, List.of(
            rolled(StraightCurveSection.Element.straight(15, 0), rate),
            rolled(StraightCurveSection.Element.curve(20, -90, 0, 0), rate),
            rolled(StraightCurveSection.Element.straight(15, 0), rate))));
        f.add(new Fixture("chain15-R20-15/smooth6/" + r, List.of(
            rolled(StraightCurveSection.Element.straight(15, 0), rate),
            rolled(StraightCurveSection.Element.curve(20, -90, 0, 6), rate),
            rolled(StraightCurveSection.Element.straight(15, 6), rate))));
        return f;
    }

    /** Entry orientation: identity, then pitched up about Z (right-hand
     *  about −Z gives a nose-up pitch with T=(cos,sin,0)), then banked
     *  about the pitched T. Built from frames so the record is explicit. */
    static Quat entry(double pitchDeg, double bankDeg) {
        double p = Math.toRadians(pitchDeg);
        Vec3 T = new Vec3(Math.cos(p), Math.sin(p), 0);
        Vec3 U = new Vec3(-Math.sin(p), Math.cos(p), 0);
        Quat q = Quat.fromFrame(T, U);
        if (bankDeg != 0) q = Quat.aboutAxis(T, Math.toRadians(bankDeg)).mul(q).normalize();
        return q;
    }

    public static void main(String[] args) {
        System.out.println("# LegacyAxisProbe - recorded on the unmodified build (v19.29, bfc70fd); dt 0.01, v 26, pos0 (0,21,0)");
        System.out.println("# fixture | entry | n | checksum | last pos x y z | last T x y z | last U x y z | qEnd w x y z");
        double[][] entries = { {0, 0}, {20, 0}, {0, 30}, {20, 30} };
        String[] entryNames = { "level", "pitch20", "bank30", "pitch20bank30" };
        for (boolean rate : new boolean[] { true, false }) {
            for (Fixture fx : fixtures(rate)) {
                for (int e = 0; e < entries.length; e++) {
                    List<StraightCurveSection.Element> els = new ArrayList<>();
                    for (StraightCurveSection.Element el : fx.els()) els.add(el.copy());
                    StraightCurveSection sc = new StraightCurveSection(els, 26);
                    ScIntegrator.Result r = ScIntegrator.integrate(sc, 0.01, 26,
                        new Vec3(0, 21, 0), entry(entries[e][0], entries[e][1]));
                    List<FVDIntegrator.Sample> s = r.samples();
                    FVDIntegrator.Sample last = s.get(s.size() - 1);
                    Quat qe = r.qEnd();
                    System.out.println(fx.name() + " | " + entryNames[e] + " | " + s.size()
                        + " | " + Long.toHexString(checksum(s))
                        + " | " + last.pos().x() + " " + last.pos().y() + " " + last.pos().z()
                        + " | " + last.T().x() + " " + last.T().y() + " " + last.T().z()
                        + " | " + last.U().x() + " " + last.U().y() + " " + last.U().z()
                        + " | " + qe.w() + " " + qe.x() + " " + qe.y() + " " + qe.z());
                }
            }
        }
    }
}
