package konqasasas.gauntlet;

/** Support requires real horizontal overlap and a surface immediately below the feet. */
final class GroundGeometry {
    static boolean supports(double minX,double maxX,double minZ,double maxZ,double feetY,
                            double blockMinX,double blockMaxX,double blockMinZ,double blockMaxZ,double top) {
        double gap=feetY-top;
        return maxX>blockMinX+1e-6 && minX<blockMaxX-1e-6
                && maxZ>blockMinZ+1e-6 && minZ<blockMaxZ-1e-6 && gap>=-1e-5 && gap<=.03125;
    }
    private GroundGeometry() {}
}
