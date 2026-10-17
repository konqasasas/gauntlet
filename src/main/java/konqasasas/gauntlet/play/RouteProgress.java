package konqasasas.gauntlet.play;

import java.util.List;
import konqasasas.gauntlet.course.RoutePoint;

/**
 * Progress along a course route (a polyline). A position is projected onto the
 * nearest segment, but only segments near the current distance are considered,
 * so a wall between two parts of the route cannot make the progress jump ahead.
 */
public final class RouteProgress {
    /** Route length around the current distance that is searched (blocks). */
    static final double WINDOW=12;
    private final double[] xs,ys,zs,along;
    private final double total;

    public RouteProgress(List<RoutePoint> points) {
        int n=points.size();
        xs=new double[n]; ys=new double[n]; zs=new double[n]; along=new double[n];
        for(int i=0;i<n;i++) {
            RoutePoint p=points.get(i); xs[i]=p.x(); ys[i]=p.y(); zs[i]=p.z();
            if(i>0) along[i]=along[i-1]+Math.sqrt(sq(xs[i]-xs[i-1])+sq(ys[i]-ys[i-1])+sq(zs[i]-zs[i-1]));
        }
        total=n<2 ? 0 : along[n-1];
    }
    /** False without at least two distinct points. */
    public boolean usable() { return total>1e-9; }
    public double total() { return total; }
    /** Distance along the route of the nearest point to (x,y,z), searched around current. */
    public double locate(double x,double y,double z,double current) {
        if(!usable()) return 0;
        double bestDistance=Double.POSITIVE_INFINITY,bestAlong=current;
        for(int i=0;i+1<xs.length;i++) {
            if(along[i+1]<current-WINDOW || along[i]>current+WINDOW) continue;
            double dx=xs[i+1]-xs[i],dy=ys[i+1]-ys[i],dz=zs[i+1]-zs[i],length2=dx*dx+dy*dy+dz*dz;
            double t=length2<1e-12 ? 0 : Math.clamp(((x-xs[i])*dx+(y-ys[i])*dy+(z-zs[i])*dz)/length2,0,1);
            double px=xs[i]+t*dx,py=ys[i]+t*dy,pz=zs[i]+t*dz,distance=sq(x-px)+sq(y-py)+sq(z-pz);
            double at=along[i]+t*(along[i+1]-along[i]);
            // Never search outside the window, even within a long segment.
            at=Math.clamp(at,Math.max(0,current-WINDOW),Math.min(total,current+WINDOW));
            if(distance<bestDistance) { bestDistance=distance; bestAlong=at; }
        }
        return bestAlong;
    }
    /** 0..1 */
    public double fraction(double distance) { return usable() ? Math.clamp(distance/total,0,1) : 0; }
    /** Whole percent, at most 99 unless the goal was reached. */
    public static int percent(double fraction,boolean cleared) {
        return cleared ? 100 : Math.min(99,(int)Math.floor(fraction*100+1e-9));
    }
    private static double sq(double v) { return v*v; }
}
