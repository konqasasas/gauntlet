package konqasasas.gauntlet;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/** Support requires real horizontal overlap and a surface immediately below the feet. */
public final class GroundGeometry {
    static boolean supports(double minX,double maxX,double minZ,double maxZ,double feetY,
                            double blockMinX,double blockMaxX,double blockMinZ,double blockMaxZ,double top) {
        double gap=feetY-top;
        return maxX>blockMinX+1e-6 && minX<blockMaxX-1e-6
                && maxZ>blockMinZ+1e-6 && minZ<blockMaxZ-1e-6 && gap>=-1e-5 && gap<=.03125;
    }
    /** Blocks whose collision shape holds up a player box whose feet are at y. */
    public static List<Block> supporting(World world,BoundingBox feet,double y) {
        List<Block> result=new ArrayList<>();
        for(int x=(int)Math.floor(feet.getMinX());x<=Math.floor(feet.getMaxX()-1e-6);x++)
            for(int z=(int)Math.floor(feet.getMinZ());z<=Math.floor(feet.getMaxZ()-1e-6);z++)
                // Fences/walls have collision tops above their block cell.
                for(int by=(int)Math.floor(y-.03125)-1;by<=Math.floor(y);by++) {
                    Block block=world.getBlockAt(x,by,z);
                    for(BoundingBox shape:block.getCollisionShape().getBoundingBoxes()) {
                        if(supports(feet.getMinX(),feet.getMaxX(),feet.getMinZ(),feet.getMaxZ(),y,
                                x+shape.getMinX(),x+shape.getMaxX(),z+shape.getMinZ(),z+shape.getMaxZ(),by+shape.getMaxY())) {
                            result.add(block); break;
                        }
                    }
                }
        return result;
    }
    private GroundGeometry() {}
}
