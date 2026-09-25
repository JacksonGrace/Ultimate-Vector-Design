package uvd;
/**
 * Simple 3D vector value type (immutable) — no external dependencies.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x
        );
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public Vec3 normalize() {
        double len = length();
        return len < 1e-12 ? ZERO : scale(1.0 / len);
    }

    /** Rotate this vector about a unit axis by angle radians (Rodrigues' formula). */
    public Vec3 rotate(Vec3 axis, double angleRad) {
        double cosA = Math.cos(angleRad);
        double sinA = Math.sin(angleRad);
        double dot = this.dot(axis);
        Vec3 cross = axis.cross(this);
        return new Vec3(
            x * cosA + cross.x * sinA + axis.x * dot * (1 - cosA),
            y * cosA + cross.y * sinA + axis.y * dot * (1 - cosA),
            z * cosA + cross.z * sinA + axis.z * dot * (1 - cosA)
        );
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
