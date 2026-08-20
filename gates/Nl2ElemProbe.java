import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** .nl2elem export verification (v18.29, D201 — the FVD++ "NoLimits 2
 *  Exporter" format):
 *   - XML well-formed (JDK DOM parser) with the FVD++ structure:
 *     root > element > description, then vertex blocks, then roll
 *     blocks; %e values all parseable;
 *   - vertices are the VERIFIED CSV export's points under one rigid
 *     anchor transform (subtract first point, rotate heading onto -Z,
 *     y preserved) — recomputed independently here and compared;
 *   - chord lengths equal the CSV's (rigidity), first vertex is the
 *     origin, endpoints strict / interior not;
 *   - rolls: coords exactly k/nSeg, up/right unit + orthogonal, up
 *     matches the CSV up under the same rotation, right = front x up,
 *     and the flat station start reads up=(0,1,0), right=(1,0,0) —
 *     the handedness witness (facing -Z, right is +X);
 *   - both lines (spine/heartline) at two spacings.
 */
public class Nl2ElemProbe {

    static final String F1 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26.fvd";
    static final String OUT = "C:/Users/jacks/AppData/Local/Temp/claude/"
        + "C--Users-jacks-OneDrive-Documents-AI-Fun-coaster/"
        + "0dd94887-c791-43f5-9d55-a714762b968a/scratchpad/"
        + "FULL TEST COASTER 7.30.26.nl2elem";
    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

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

    static double[] tag(Element e, String name) {
        NodeList nl = e.getElementsByTagName(name);
        double[] out = new double[nl.getLength()];
        for (int i = 0; i < out.length; i++)
            out[i] = Double.parseDouble(nl.item(i).getTextContent());
        return out;
    }

    static void verify(String label, List<FVDIntegrator.Sample> samples,
                       boolean heartline, double heart, double segLen)
            throws Exception {
        Nl2ElemExporter.Result r =
            Nl2ElemExporter.export(samples, heartline, heart, segLen);
        int nSeg = r.points() - 1;

        // the independent reference: the VERIFIED CSV export at the
        // same settings (world positions + frames, z already negated)
        Nl2CsvExporter.Result csv =
            Nl2CsvExporter.export(samples, heartline, heart, segLen);
        String[] lines = csv.csv().split("\n");
        double[][] cw = new double[csv.points()][13];
        for (int i = 0; i < csv.points(); i++) {
            String[] f = lines[i + 1].split("\t");
            for (int j = 0; j < 13; j++) cw[i][j] = Double.parseDouble(f[j]);
        }

        Document doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(r.xml().getBytes(StandardCharsets.UTF_8)));
        Element root = doc.getDocumentElement();
        NodeList elems = root.getElementsByTagName("element");
        NodeList verts = root.getElementsByTagName("vertex");
        NodeList rolls = root.getElementsByTagName("roll");
        NodeList descs = root.getElementsByTagName("description");
        check(label + ": XML structure",
            root.getTagName().equals("root") && elems.getLength() == 1
                && descs.getLength() == 1
                && verts.getLength() == r.points()
                && rolls.getLength() == r.points()
                && csv.points() == r.points(),
            r.points() + " vertices + rolls, len "
                + String.format(Locale.US, "%.2f m", r.length()));

        // the probe's own anchor transform from the CSV data: rotate
        // about Y taking the first front's horizontal onto (0,0,-1),
        // after subtracting the first position
        double dx = cw[0][4], dz = cw[0][6];
        double h = Math.hypot(dx, dz);
        dx /= h; dz /= h;
        double worstV = 0, worstC = 0, worstY = 0;
        boolean strictOk = true;
        for (int k = 0; k < r.points(); k++) {
            Element v = (Element) verts.item(k);
            double vx = tag(v, "x")[0], vy = tag(v, "y")[0], vz = tag(v, "z")[0];
            double px = cw[k][1] - cw[0][1], py = cw[k][2] - cw[0][2],
                   pz = cw[k][3] - cw[0][3];
            double ex = -dz * px + dx * pz;
            double ey = py;
            double ez = -dx * px - dz * pz;
            worstV = Math.max(worstV, Math.abs(vx - ex) + Math.abs(vy - ey)
                + Math.abs(vz - ez));
            worstY = Math.max(worstY, Math.abs(vy - py));
            if (k > 0) {
                Element p = (Element) verts.item(k - 1);
                double cx = vx - tag(p, "x")[0], cy = vy - tag(p, "y")[0],
                       cz = vz - tag(p, "z")[0];
                double dxw = cw[k][1] - cw[k - 1][1],
                       dyw = cw[k][2] - cw[k - 1][2],
                       dzw = cw[k][3] - cw[k - 1][3];
                worstC = Math.max(worstC, Math.abs(
                    Math.sqrt(cx * cx + cy * cy + cz * cz)
                    - Math.sqrt(dxw * dxw + dyw * dyw + dzw * dzw)));
            }
            String st = v.getElementsByTagName("strict").item(0).getTextContent();
            boolean expect = k == 0 || k == nSeg;
            if (!st.equals(String.valueOf(expect))) strictOk = false;
        }
        Element v0 = (Element) verts.item(0);
        check(label + ": vertices = CSV under the rigid anchor transform",
            worstV < 5e-4,
            String.format(Locale.US, "worst %.2e", worstV));
        check(label + ": heights preserved + chords rigid + origin start",
            worstY < 5e-4 && worstC < 5e-4
                && Math.abs(tag(v0, "x")[0]) + Math.abs(tag(v0, "y")[0])
                   + Math.abs(tag(v0, "z")[0]) < 1e-9,
            String.format(Locale.US, "dy %.2e, chords %.2e", worstY, worstC));
        check(label + ": strict flags (endpoints only)", strictOk, "");

        double worstCoord = 0, worstUnit = 0, worstDot = 0, worstU = 0, worstR = 0;
        for (int k = 0; k < r.points(); k++) {
            Element rl = (Element) rolls.item(k);
            double ux = tag(rl, "ux")[0], uy = tag(rl, "uy")[0], uz = tag(rl, "uz")[0];
            double rx = tag(rl, "rx")[0], ry = tag(rl, "ry")[0], rz = tag(rl, "rz")[0];
            worstCoord = Math.max(worstCoord,
                Math.abs(tag(rl, "coord")[0] - (double) k / nSeg));
            worstUnit = Math.max(worstUnit, Math.abs(
                Math.sqrt(ux * ux + uy * uy + uz * uz) - 1));
            worstUnit = Math.max(worstUnit, Math.abs(
                Math.sqrt(rx * rx + ry * ry + rz * rz) - 1));
            worstDot = Math.max(worstDot, Math.abs(ux * rx + uy * ry + uz * rz));
            // CSV up (cols 10-12) + front (4-6) under the same rotation
            double aux = -dz * cw[k][10] + dx * cw[k][12];
            double auy = cw[k][11];
            double auz = -dx * cw[k][10] - dz * cw[k][12];
            worstU = Math.max(worstU, Math.abs(ux - aux) + Math.abs(uy - auy)
                + Math.abs(uz - auz));
            double afx = -dz * cw[k][4] + dx * cw[k][6];
            double afy = cw[k][5];
            double afz = -dx * cw[k][4] - dz * cw[k][6];
            // right = front x up, computed from the CSV reference
            double erx = afy * auz - afz * auy;
            double ery = afz * aux - afx * auz;
            double erz = afx * auy - afy * aux;
            worstR = Math.max(worstR, Math.abs(rx - erx) + Math.abs(ry - ery)
                + Math.abs(rz - erz));
        }
        check(label + ": roll coords exactly k/nSeg, u/r unit + orthogonal",
            worstCoord < 1e-6 && worstUnit < 5e-6 && worstDot < 5e-6,
            String.format(Locale.US, "coord %.1e unit %.1e dot %.1e",
                worstCoord, worstUnit, worstDot));
        check(label + ": up matches CSV; right = front x up (FVD++ vLat)",
            worstU < 5e-4 && worstR < 5e-4,
            String.format(Locale.US, "u %.2e r %.2e", worstU, worstR));

        // handedness witness at the flat station start: facing -Z with
        // up +Y, rider-right must be +X
        Element rl0 = (Element) rolls.item(0);
        double u0y = tag(rl0, "uy")[0], r0x = tag(rl0, "rx")[0];
        check(label + ": flat start reads up=+Y, right=+X (handedness)",
            u0y > 0.95 && r0x > 0.95,
            String.format(Locale.US, "uy %.3f rx %.3f", u0y, r0x));
    }

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        DesignIO.LoadResult lr =
            DesignIO.load(new BufferedReader(new StringReader(text)));
        Coaster c = lr.coaster();
        double heart = lr.cosmetics().heart();
        TrackCompiler.Result compiled = (TrackCompiler.Result) rebuild(c)[0];

        verify("spine 1.0m", compiled.samples(), false, heart, 1.0);
        verify("spine 0.5m", compiled.samples(), false, heart, 0.5);
        verify("heartline 1.0m", compiled.samples(), true, heart, 1.0);

        // JITTER regression (v18.30, D202): the 0.2 m export's
        // high-frequency curvature noise must stay in the Hermite
        // regime (lerp aliasing measured rms 0.0053, worst 0.0051 at
        // the user's 935 m; the fix landed 0.0024 / 0.0008 — the
        // residual is real authored content like the 367 m bank-node
        // kink)
        {
            Nl2ElemExporter.Result r02 =
                Nl2ElemExporter.export(compiled.samples(), false, heart, 0.2);
            Document d2 = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(new ByteArrayInputStream(
                    r02.xml().getBytes(StandardCharsets.UTF_8)));
            NodeList vs = d2.getElementsByTagName("vertex");
            int n = vs.getLength();
            double[][] p = new double[n][3];
            for (int i = 0; i < n; i++) {
                Element v = (Element) vs.item(i);
                p[i][0] = tag(v, "x")[0];
                p[i][1] = tag(v, "y")[0];
                p[i][2] = tag(v, "z")[0];
            }
            double step = r02.step();
            double[] kap = new double[n];
            for (int k = 1; k < n - 1; k++) {
                double ax = p[k][0] - p[k - 1][0], ay = p[k][1] - p[k - 1][1],
                       az = p[k][2] - p[k - 1][2];
                double bx = p[k + 1][0] - p[k][0], by = p[k + 1][1] - p[k][1],
                       bz = p[k + 1][2] - p[k][2];
                double la = Math.sqrt(ax * ax + ay * ay + az * az);
                double lb = Math.sqrt(bx * bx + by * by + bz * bz);
                kap[k] = Math.acos(Math.min(1, Math.max(-1,
                    (ax * bx + ay * by + az * bz) / (la * lb)))) / step;
            }
            double rms = 0, w935 = 0;
            int cnt = 0;
            for (int k = 3; k < n - 3; k++) {
                double mean = (kap[k - 2] + kap[k - 1] + kap[k + 1] + kap[k + 2]) / 4;
                double nz = Math.abs(kap[k] - mean);
                rms += nz * nz;
                cnt++;
                if (Math.abs(k * step - 935) < 15) w935 = Math.max(w935, nz);
            }
            rms = Math.sqrt(rms / cnt);
            check("0.2m jitter: hi-freq curvature noise in the Hermite regime",
                rms < 0.0035 && w935 < 0.002,
                String.format(Locale.US,
                    "rms %.5f (lerp was 0.0053), @935m %.5f (was 0.0051)",
                    rms, w935));
        }

        Nl2ElemExporter.Result def =
            Nl2ElemExporter.export(compiled.samples(), false, heart, 1.0);
        Files.write(Paths.get(OUT), def.xml().getBytes(StandardCharsets.UTF_8));
        System.out.println("INFO  wrote " + OUT);

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
