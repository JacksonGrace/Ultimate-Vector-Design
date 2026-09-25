package uvd;

/**
 * Unit quaternion for track orientation (Phase 1, D50). Pure Java, no
 * dependencies beyond Vec3.
 *
 * This is the INTERNAL orientation representation of the section model:
 * poses and piece placements carry a Quat; T/U/R frames are derived on
 * demand (Euler angles are a display convention only, never a math
 * path). The force-section integrator itself still steps T/U as vectors
 * — its math is pinned bit-for-bit by the Phase-1 gate (see D50) — so
 * quaternions enter and leave at section boundaries via
 * {@link #fromFrame} / {@link #rotate}.
 *
 * Identity paths are FLOAT-EXACT by construction: {@code IDENTITY}
 * rotates any vector to itself with zero arithmetic error (the formula
 * degenerates to v + 0 + 0), and {@code aboutY(0)} is exactly IDENTITY.
 * The bit-for-bit gate depends on this — do not "simplify" rotate()
 * into the matrix form, which multiplies every component.
 */
public record Quat(double w, double x, double y, double z) {

    public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

    /** Rotation about the world Y (up) axis — piece placement yaw. */
    public static Quat aboutY(double deg) {
        double h = Math.toRadians(deg) / 2;
        return new Quat(Math.cos(h), 0, Math.sin(h), 0);
    }

    /** Rotation of rad about an arbitrary UNIT axis (world coords) —
     *  the s/c global-bank projection rolls about the current T. */
    public static Quat aboutAxis(Vec3 axis, double rad) {
        double h = rad / 2;
        double s = Math.sin(h);
        return new Quat(Math.cos(h), axis.x() * s, axis.y() * s, axis.z() * s);
    }

    /** Hamilton product: this THEN other applied... composition q1.mul(q2)
     *  rotates by q2 first, then q1 (standard convention: (q1·q2)·v). */
    public Quat mul(Quat o) {
        return new Quat(
            w * o.w - x * o.x - y * o.y - z * o.z,
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w);
    }

    public Quat conjugate() { return new Quat(w, -x, -y, -z); }

    /** Conjugation by the z-negating reflection Mz = diag(1,1,−1)
     *  (v19.8, D237 — the Mirror-piece op): the rotation of the
     *  horizontally mirrored frame, Mz·R·Mz. aboutY(θ) ↦ aboutY(−θ)
     *  (heading negates), roll about X negates, rotation about Z
     *  (canonical pitch) is preserved. Exact — pure sign flips. */
    public Quat mirrorZ() { return new Quat(w, -x, -y, z); }

    // component-wise helpers for RK4 quaternion integration (GeoIntegrator
    // steps q' = ½·(0,ω)·q); results are NOT unit — normalize after combining
    public Quat add(Quat o) { return new Quat(w + o.w, x + o.x, y + o.y, z + o.z); }

    public Quat scale(double k) { return new Quat(w * k, x * k, y * k, z * k); }

    public Quat normalize() {
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        return n < 1e-12 ? IDENTITY : new Quat(w / n, x / n, y / n, z / n);
    }

    /** Rotate a vector: v + 2w(q×v) + 2(q×(q×v)). Exact for IDENTITY. */
    public Vec3 rotate(Vec3 v) {
        Vec3 qv = new Vec3(x, y, z);
        Vec3 t = qv.cross(v).scale(2);
        return v.add(t.scale(w)).add(qv.cross(t));
    }

    /** Orientation whose rotation maps the reference frame (T=(1,0,0),
     *  U=(0,1,0)) onto the given frame. Third column is T×U (which is
     *  −R under the project's R = U×T convention — proper rotation,
     *  det +1). Shepperd's method for robustness in all trace cases. */
    public static Quat fromFrame(Vec3 T, Vec3 U) {
        Vec3 W = T.cross(U);
        double m00 = T.x(), m01 = U.x(), m02 = W.x();
        double m10 = T.y(), m11 = U.y(), m12 = W.y();
        double m20 = T.z(), m21 = U.z(), m22 = W.z();
        double tr = m00 + m11 + m22;
        double qw, qx, qy, qz;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1.0) * 2;
            qw = 0.25 * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            qw = (m21 - m12) / s;
            qx = 0.25 * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25 * s;
            qz = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25 * s;
        }
        return new Quat(qw, qx, qy, qz).normalize();
    }

    /** Derived frame vectors (reference T is +X, U is +Y). */
    public Vec3 T() { return rotate(new Vec3(1, 0, 0)); }
    public Vec3 U() { return rotate(new Vec3(0, 1, 0)); }
}
