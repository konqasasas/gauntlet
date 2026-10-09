package konqasasas.gauntlet;

import java.util.UUID;

record Checkpoint(UUID world,double x,double y,double z,float yaw,float pitch) {
    Checkpoint {
        if(world==null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Invalid checkpoint");
    }
}
record BlockKey(UUID world,int x,int y,int z) {
    BlockKey { if(world==null) throw new IllegalArgumentException("Missing world"); }
}
record PlacedBlock(UUID owner,BlockKey block,String before,String after) {
    PlacedBlock {
        if(owner==null || block==null || before==null || after==null || before.isBlank() || after.isBlank())
            throw new IllegalArgumentException("Invalid block journal entry");
    }
}
