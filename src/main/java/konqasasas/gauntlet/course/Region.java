package konqasasas.gauntlet.course;

/** Inclusive block box. */
public record Region(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
    public Region {
        if(minX>maxX || minY>maxY || minZ>maxZ) throw new IllegalArgumentException("Region min exceeds max");
    }
    public static Region of(int x1,int y1,int z1,int x2,int y2,int z2) {
        return new Region(Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2),Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2));
    }
    public int sizeX() { return maxX-minX+1; }
    public int sizeY() { return maxY-minY+1; }
    public int sizeZ() { return maxZ-minZ+1; }
    public boolean contains(double x,double y,double z) {
        int bx=(int)Math.floor(x),by=(int)Math.floor(y),bz=(int)Math.floor(z);
        return bx>=minX && bx<=maxX && by>=minY && by<=maxY && bz>=minZ && bz<=maxZ;
    }
    public Region moved(BlockPos d) { return new Region(minX+d.x(),minY+d.y(),minZ+d.z(),maxX+d.x(),maxY+d.y(),maxZ+d.z()); }
    /** True when the other box lies wholly inside this one (false for null). */
    public boolean contains(Region o) {
        return o!=null && minX<=o.minX && minY<=o.minY && minZ<=o.minZ && o.maxX<=maxX && o.maxY<=maxY && o.maxZ<=maxZ;
    }
    public boolean overlaps(Region o) {
        return minX<=o.maxX && o.minX<=maxX && minY<=o.maxY && o.minY<=maxY && minZ<=o.maxZ && o.minZ<=maxZ;
    }
    public long volume() { return (long)sizeX()*sizeY()*sizeZ(); }
    public String describe() { return sizeX()+"×"+sizeY()+"×"+sizeZ()+" ("+minX+", "+minY+", "+minZ+" ~ "+maxX+", "+maxY+", "+maxZ+")"; }
}
