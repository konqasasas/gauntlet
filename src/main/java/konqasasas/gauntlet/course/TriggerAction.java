package konqasasas.gauntlet.course;

import java.util.*;

/**
 * One action of a trigger. Flat record so it stays plain JSON; only the fields
 * of its type are set. Placement SLOT1..SLOT3 are hotbar slots 1-3, EMPTY is the
 * first empty slot among 2-3. SLOT4 is from when courses had four item slots and
 * now behaves like EMPTY. Display names are the message keys "placement.<NAME>".
 *
 * WATER puts water sources on waterCells (air cells only) for waterTicks server
 * ticks; the water flows, but only into air.
 */
public record TriggerAction(Type type,ItemRef item,Placement placement,boolean allLevels,String mob,Spot mobAt,
                            String sound,float volume,float pitch,List<BlockPos> waterCells,Integer waterTicks) {
    public enum Type { GIVE,REMOVE,MOB,SOUND,WATER }
    public enum Placement {
        SLOT1,SLOT2,SLOT3,SLOT4,EMPTY;
        /** Choices offered in the editor. */
        public static final List<Placement> CHOICES=List.of(SLOT1,SLOT2,SLOT3,EMPTY);
        public String key() { return "placement."+(this==SLOT4 ? EMPTY : this).name(); }
        /** Hotbar index, or -1 for the first empty slot among 2-3. */
        public int slot() { return this==EMPTY || this==SLOT4 ? -1 : ordinal(); }
    }
    public static final float MIN_VOLUME=0,MAX_VOLUME=4,MIN_PITCH=.5f,MAX_PITCH=2;
    /** Water: up to 64 source cells, 1 tick to 5 minutes. */
    public static final int MAX_WATER_CELLS=64,MAX_WATER_TICKS=6000,DEFAULT_WATER_TICKS=60;
    public TriggerAction {
        if(type==Type.WATER) waterCells=waterCells==null ? List.of() : List.copyOf(new LinkedHashSet<>(waterCells));
        boolean valid=type!=null && switch(type) {
            case GIVE -> item!=null && placement!=null;
            case REMOVE -> item!=null;
            case MOB -> MobSpec.parse(mob)!=null && mobAt!=null;
            case SOUND -> sound!=null && !sound.isBlank() && volume>=MIN_VOLUME && volume<=MAX_VOLUME && pitch>=MIN_PITCH && pitch<=MAX_PITCH;
            case WATER -> waterCells.size()<=MAX_WATER_CELLS && waterTicks!=null && waterTicks>=1 && waterTicks<=MAX_WATER_TICKS;
        };
        if(!valid) throw new IllegalArgumentException("Invalid trigger action");
    }
    public static TriggerAction give(ItemRef item,Placement placement) { return new TriggerAction(Type.GIVE,item,placement,false,null,null,null,0,0,null,null); }
    public static TriggerAction remove(ItemRef item,boolean allLevels) { return new TriggerAction(Type.REMOVE,item,null,allLevels,null,null,null,0,0,null,null); }
    public static TriggerAction mob(String mob,Spot at) { return new TriggerAction(Type.MOB,null,null,false,mob,at,null,0,0,null,null); }
    public static TriggerAction sound(String sound,float volume,float pitch) { return new TriggerAction(Type.SOUND,null,null,false,null,null,sound,volume,pitch,null,null); }
    public static TriggerAction water(List<BlockPos> cells,int ticks) { return new TriggerAction(Type.WATER,null,null,false,null,null,null,0,0,cells,ticks); }
    public TriggerAction withSound(float newVolume,float newPitch) {
        return sound(sound,Math.clamp(newVolume,MIN_VOLUME,MAX_VOLUME),Math.clamp(newPitch,MIN_PITCH,MAX_PITCH));
    }
    public TriggerAction withWaterTicks(int ticks) { return water(waterCells,Math.clamp(ticks,1,MAX_WATER_TICKS)); }
    /** Adds the cell, or removes it when already registered. */
    public TriggerAction toggleWaterCell(BlockPos cell) {
        List<BlockPos> next=new ArrayList<>(waterCells);
        if(!next.remove(cell)) next.add(cell);
        return water(next,waterTicks);
    }
    public TriggerAction clearWaterCells() { return water(List.of(),waterTicks); }
}
