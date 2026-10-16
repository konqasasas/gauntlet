package konqasasas.gauntlet.course;

/** A position with view direction, in the course's world. */
public record Spot(double x,double y,double z,float yaw,float pitch) {
    public Spot {
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Invalid spot");
    }
    public String describe() { return String.format("%.2f, %.2f, %.2f（向き %.1f / %.1f）",x,y,z,yaw,pitch); }
}
