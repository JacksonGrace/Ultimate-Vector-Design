import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Before/after jitter measurement THROUGH the real exporter: the old
 *  lerp construction (reproduced locally) vs the shipped .nl2elem
 *  file's parsed vertices/ups (heart-Hermite + frame-slerp + %e).
 *  Metrics: high-frequency curvature noise (position channel) and
 *  high-frequency twist noise (bank channel), globally and at the
 *  user's 935 m. */
public class JitterProbe3 {

    static final String F1 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26.fvd";
    static final double DT = 0.01;

    static Object[] rebuild(Coaster c) {
        TrackCompiler.Result compiled = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            compiled = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled, true);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            compiled = TrackCompiler.compile(c, DT);
        }
        return new Object[]{ compiled };
    }

    /** the OLD lerp construction (pre-D202), for the before side */
    static Vec3[][] lerpExport(List<FVDIntegrator.Sample> S, double heart,
                               double step) {
        int n = S.size();
        Vec3[] p = new Vec3[n];
        Vec3[] t = new Vec3[n];
        Vec3[] u = new Vec3[n];
        double[] arc = new double[n];
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = S.get(i);
            p[i] = sm.pos().sub(sm.U().scale(heart));
            t[i] = sm.T();
            u[i] = sm.U();
            if (i > 0) arc[i] = arc[i - 1] + p[i].sub(p[i - 1]).length();
        }
        double total = arc[n - 1];
        int nSeg = Math.max(1, (int) Math.round(total / step));
        double d = total / nSeg;
        Vec3[] pos = new Vec3[nSeg + 1];
        Vec3[] up = new Vec3[nSeg + 1];
        int idx = 0;
        for (int k = 0; k <= nSeg; k++) {
            double target = Math.min(k * d, total);
            while (idx < n - 2 && arc[idx + 1] < target) idx++;
            double span = arc[idx + 1] - arc[idx];
            double a = span < 1e-12 ? 0 : (target - arc[idx]) / span;
            pos[k] = p[idx].add(p[idx + 1].sub(p[idx]).scale(a));
            Vec3 f = t[idx].add(t[idx + 1].sub(t[idx]).scale(a)).normalize();
            Vec3 ur = u[idx].add(u[idx + 1].sub(u[idx]).scale(a));
            up[k] = ur.sub(f.scale(ur.dot(f))).normalize();
        }
        return new Vec3[][]{ pos, up };
    }

    /** {posNoise max, at, rms, bankNoise max, at, rms, pos@935, bank@935} */
    static double[] noise(Vec3[] pos, Vec3[] up, double step) {
        int n = pos.length;
        double[] kap = new double[n];
        double[] tw = new double[n];
        for (int k = 1; k < n - 1; k++) {
            Vec3 a = pos[k].sub(pos[k - 1]);
            Vec3 b = pos[k + 1].sub(pos[k]);
            kap[k] = Math.acos(Math.min(1, Math.max(-1,
                a.dot(b) / (a.length() * b.length())))) / step;
            tw[k] = Math.toDegrees(Math.acos(Math.min(1,
                up[k - 1].dot(up[k + 1]) ))) / (2 * step);
        }
        double wp = 0, ap = 0, rp = 0, wb = 0, ab = 0, rb = 0, p935 = 0, b935 = 0;
        int cnt = 0;
        for (int k = 3; k < n - 3; k++) {
            double mk = (kap[k - 2] + kap[k - 1] + kap[k + 1] + kap[k + 2]) / 4;
            double mt = (tw[k - 2] + tw[k - 1] + tw[k + 1] + tw[k + 2]) / 4;
            double np = Math.abs(kap[k] - mk);
            double nb = Math.abs(tw[k] - mt);
            if (np > wp) { wp = np; ap = k * step; }
            if (nb > wb) { wb = nb; ab = k * step; }
            rp += np * np;
            rb += nb * nb;
            cnt++;
            if (Math.abs(k * step - 935) < 15) {
                p935 = Math.max(p935, np);
                b935 = Math.max(b935, nb);
            }
        }
        return new double[]{ wp, ap, Math.sqrt(rp / cnt),
                             wb, ab, Math.sqrt(rb / cnt), p935, b935 };
    }

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        DesignIO.LoadResult lr =
            DesignIO.load(new BufferedReader(new StringReader(text)));
        Coaster c = lr.coaster();
        double heart = lr.cosmetics().heart();
        TrackCompiler.Result r = (TrackCompiler.Result) rebuild(c)[0];

        Vec3[][] before = lerpExport(r.samples(), heart, 0.2);
        double stepB = 0;
        {   // recover the exact step used
            int nSeg = before[0].length - 1;
            double total = 0;
            Vec3[] p = before[0];
            for (int i = 1; i <= nSeg; i++) total += p[i].sub(p[i - 1]).length();
            stepB = 0.2;   // metric scale; exact step is ~0.2
        }
        double[] nb = noise(before[0], before[1], 0.2);

        // AFTER: the real shipped .nl2elem, parsed back (%e included)
        Nl2ElemExporter.Result ex =
            Nl2ElemExporter.export(r.samples(), false, heart, 0.2);
        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(new ByteArrayInputStream(
                ex.xml().getBytes(StandardCharsets.UTF_8)));
        NodeList verts = doc.getElementsByTagName("vertex");
        NodeList rolls = doc.getElementsByTagName("roll");
        int n = verts.getLength();
        Vec3[] pos = new Vec3[n];
        Vec3[] up = new Vec3[n];
        for (int i = 0; i < n; i++) {
            Element v = (Element) verts.item(i);
            pos[i] = new Vec3(
                Double.parseDouble(v.getElementsByTagName("x").item(0).getTextContent()),
                Double.parseDouble(v.getElementsByTagName("y").item(0).getTextContent()),
                Double.parseDouble(v.getElementsByTagName("z").item(0).getTextContent()));
            Element rl = (Element) rolls.item(i);
            up[i] = new Vec3(
                Double.parseDouble(rl.getElementsByTagName("ux").item(0).getTextContent()),
                Double.parseDouble(rl.getElementsByTagName("uy").item(0).getTextContent()),
                Double.parseDouble(rl.getElementsByTagName("uz").item(0).getTextContent()));
        }
        double[] na = noise(pos, up, ex.step());

        System.out.println("metric: high-frequency noise (deviation from 4-neighbor mean), 0.2 m export");
        System.out.printf(Locale.US,
            "BEFORE (lerp):     curv %.5f max at %6.1f m (rms %.5f) | twist %.3f deg/m max at %6.1f m (rms %.4f) | @935: %.5f, %.3f%n",
            nb[0], nb[1], nb[2], nb[3], nb[4], nb[5], nb[6], nb[7]);
        System.out.printf(Locale.US,
            "AFTER  (shipped):  curv %.5f max at %6.1f m (rms %.5f) | twist %.3f deg/m max at %6.1f m (rms %.4f) | @935: %.5f, %.3f%n",
            na[0], na[1], na[2], na[3], na[4], na[5], na[6], na[7]);
    }
}
