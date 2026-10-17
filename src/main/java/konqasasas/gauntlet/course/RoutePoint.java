package konqasasas.gauntlet.course;

/** One point of a course route (the line players are expected to follow). */
public record RoutePoint(double x,double y,double z) {
    public RoutePoint {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Invalid route point");
    }
    public String describe() { return String.format("%.1f, %.1f, %.1f",x,y,z); }
}
