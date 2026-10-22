package konqasasas.gauntlet.course;

public record BlockPos(int x,int y,int z) {
    public static final BlockPos ZERO=new BlockPos(0,0,0);
    public String describe() { return x+", "+y+", "+z; }
    public BlockPos plus(BlockPos d) { return new BlockPos(x+d.x,y+d.y,z+d.z); }
    public BlockPos minus(BlockPos d) { return new BlockPos(x-d.x,y-d.y,z-d.z); }
    /** Left and right swapped (going along +Z): x mirrored around block column a. */
    public BlockPos mirrorX(int a) { return new BlockPos(2*a-x,y,z); }
}
