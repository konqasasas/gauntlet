package konqasasas.gauntlet.course;

import java.util.*;

/** Per-builder wand corners, kept in memory only. */
final class Selections {
    record Corner(UUID world,int x,int y,int z) {}
    record Selected(UUID world,Region region) {}
    private final Map<UUID,Corner[]> corners=new HashMap<>();
    void set(UUID player,int index,Corner corner) { corners.computeIfAbsent(player,k -> new Corner[2])[index]=corner; }
    Corner corner(UUID player,int index) { Corner[] pair=corners.get(player); return pair==null ? null : pair[index]; }
    /** Both corners in the same world, or null. */
    Selected selected(UUID player) {
        Corner a=corner(player,0),b=corner(player,1);
        if(a==null || b==null || !a.world().equals(b.world())) return null;
        return new Selected(a.world(),Region.of(a.x(),a.y(),a.z(),b.x(),b.y(),b.z()));
    }
    void forget(UUID player) { corners.remove(player); }
    void clear() { corners.clear(); }
}
