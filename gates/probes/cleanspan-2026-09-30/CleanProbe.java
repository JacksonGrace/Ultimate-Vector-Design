import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Old-vs-new dump for the clean-span change: run once per classpath,
 *  compare the two dumps. READ-ONLY on every design file.
 *  usage: CleanProbe <out file> <design dir> */
public class CleanProbe {
    static final double DT = Coaster.INTEGRATE_DT;

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

    static String hx(double d) {
        return Long.toHexString(Double.doubleToLongBits(d));
    }

    static long fold(long h, double d) {
        return h * 1000003L + Double.doubleToLongBits(d);
    }

    static void dump(PrintWriter out, String name, Coaster c) {
        TrackCompiler.Result r;
        try {
            r = rebuild(c);
        } catch (RuntimeException e) {
            out.println("DOC " + name + " ERROR " + e);
            return;
        }
        out.println("DOC " + name + " fric " + hx(c.fricRoll) + " "
            + hx(c.fricAir) + " n " + r.samples().size());
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            for (int k = 0; k < tp.sections.size(); k++) {
                Section s = tp.sections.get(k);
                out.println("SEC " + p + " " + k + " "
                    + s.getClass().getSimpleName() + " " + hx(s.vDesign())
                    + " pin " + s.vPinned());
            }
            out.println("PIECE " + p + " " + hx(tp.x) + " " + hx(tp.y)
                + " " + hx(tp.z) + " " + hx(tp.yawDeg) + " "
                + hx(tp.pitchDeg) + " " + hx(tp.rollDeg));
        }
        for (int k = 0; k < r.spans().size(); k++) {
            TrackCompiler.Span sp = r.spans().get(k);
            out.println("SPAN " + k + " " + sp.i0() + " " + sp.i1());
        }
        long h = 17;
        List<FVDIntegrator.Sample> ss = r.samples();
        for (FVDIntegrator.Sample s : ss) {
            h = fold(h, s.pos().x()); h = fold(h, s.pos().y());
            h = fold(h, s.pos().z());
            h = fold(h, s.T().x()); h = fold(h, s.T().y());
            h = fold(h, s.T().z());
            h = fold(h, s.U().x()); h = fold(h, s.U().y());
            h = fold(h, s.U().z());
            h = fold(h, s.v()); h = fold(h, s.Gv()); h = fold(h, s.Gl());
            h = fold(h, s.t()); h = fold(h, s.s());
        }
        out.println("GEOM " + Long.toHexString(h));
        for (int i = 0; i < ss.size(); i++) {
            out.println("V " + i + " " + hx(ss.get(i).v()) + " "
                + hx(r.trueV()[i]));
        }
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> lanes(double[][] a, double[][] b,
                                              double[][] c) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(a)); ls.add(lane(b)); ls.add(lane(c));
        return ls;
    }

    static List<List<BezierBaker.Node>> hill() {
        return lanes(new double[][]{ {0, 1}, {1.5, 2.6}, {3, 0.2}, {5, 1} },
            new double[][]{ {0, 0}, {5, 0} },
            new double[][]{ {0, 0}, {5, 0} });
    }

    static List<List<BezierBaker.Node>> turn() {
        return lanes(new double[][]{ {0, 1}, {2, 1.4}, {4, 1} },
            new double[][]{ {0, 0}, {2, 0.4}, {4, 0} },
            new double[][]{ {0, 0}, {1, 25}, {3, -25}, {4, 0} });
    }

    /** Three chained force sections + a geo tail, optional friction,
     *  optional mid-chain pin (a conflicted section). */
    static Coaster chain(boolean fric, double pin, boolean ctl) {
        Coaster c = new Coaster();
        if (fric) { c.fricRoll = 0.03; c.fricAir = 1.0e-4; }
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection a = new ForceSection(turn(), 24);
        if (ctl) {
            SpeedControl sc = new SpeedControl(1.0, 1.5);
            sc.speed = 20;
            a.speedCtls.add(sc);
        }
        p.sections.add(a);
        ForceSection b = new ForceSection(hill(), 24);
        p.sections.add(b);
        ForceSection d = new ForceSection(turn(), 24);
        p.sections.add(d);
        List<List<BezierBaker.Node>> gl = lanes(
            new double[][]{ {0, 0}, {1.5, 8}, {3, -4} },
            new double[][]{ {0, 0}, {1.5, 12}, {3, 5} },
            new double[][]{ {0, 0}, {1.5, 15}, {3, 0} });
        p.sections.add(new GeoSection(gl, 24));
        c.pieces.add(p);
        c.v0 = 24;
        if (pin != 0) {
            TrackCompiler.Result r = rebuild(c);
            d.vDesign(d.vDesign() + pin);
            d.vPinned(true);
        }
        return c;
    }

    public static void main(String[] a) throws Exception {
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(a[0]), StandardCharsets.UTF_8))) {
            dump(out, "syn-chain-nofric", chain(false, 0, false));
            dump(out, "syn-chain-fric", chain(true, 0, false));
            dump(out, "syn-chain-fric-ctl", chain(true, 0, true));
            dump(out, "syn-chain-fric-pin3", chain(true, 3, false));
            dump(out, "syn-chain-fric-pin0.05", chain(true, 0.05, false));
            dump(out, "syn-chain-nofric-pin3", chain(false, 3, false));
            dump(out, "copy-force", CopyGate.forceFixture());
            dump(out, "copy-sc", CopyGate.scFixture());
            dump(out, "copy-infl", CopyGate.inflFixture());
            dump(out, "copy-tilt", CopyGate.tiltFixture());
            for (int v = 0; v < 3; v++) {
                dump(out, "copy-strict" + v, CopyGate.strictFixture(v));
                Coaster f = CopyGate.strictFixture(v);
                f.fricRoll = 0.03; f.fricAir = 1.0e-4;
                dump(out, "copy-strict" + v + "-fric", f);
            }
            dump(out, "copy-bridge", CopyGate.bridgeFixture());
            Coaster bf = CopyGate.bridgeFixture();
            bf.fricRoll = 0.03; bf.fricAir = 1.0e-4;
            dump(out, "copy-bridge-fric", bf);
            File dir = new File(a[1]);
            File[] fs = dir.listFiles((d, n) -> n.endsWith(".fvd")
                || n.endsWith(".uvd"));
            Arrays.sort(fs);
            for (File f : fs) {
                List<Coaster> cs;
                try (BufferedReader in = Files.newBufferedReader(
                        f.toPath(), StandardCharsets.UTF_8)) {
                    cs = DesignIO.load(in).coasters();
                } catch (Exception e) {
                    out.println("DOC " + f.getName() + " LOADERROR " + e);
                    continue;
                }
                for (int i = 0; i < cs.size(); i++) {
                    dump(out, f.getName() + "#" + i, cs.get(i));
                }
            }
        }
        System.out.println("done " + a[0]);
    }
}
