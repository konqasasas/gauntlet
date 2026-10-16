package konqasasas.gauntlet.course;

public record BlockPos(int x,int y,int z) {
    public String describe() { return x+", "+y+", "+z; }
}
