package konqasasas.gauntlet.course;

/**
 * One action of a trigger. Flat record so it stays plain JSON; only the fields
 * of its type are set. Placement SLOT1..SLOT3 are hotbar slots 1-3, EMPTY is the
 * first empty slot among 2-3. SLOT4 is from when courses had four item slots and
 * now behaves like EMPTY. Display names are the message keys "placement.<NAME>".
 */
public record TriggerAction(Type type,ItemRef item,Placement placement,boolean allLevels,String mob,Spot mobAt,
                            String sound,float volume,float pitch) {
    public enum Type { GIVE,REMOVE,MOB,SOUND }
    public enum Placement {
        SLOT1,SLOT2,SLOT3,SLOT4,EMPTY;
        /** Choices offered in the editor. */
        public static final java.util.List<Placement> CHOICES=java.util.List.of(SLOT1,SLOT2,SLOT3,EMPTY);
        public String key() { return "placement."+(this==SLOT4 ? EMPTY : this).name(); }
        /** Hotbar index, or -1 for the first empty slot among 2-3. */
        public int slot() { return this==EMPTY || this==SLOT4 ? -1 : ordinal(); }
    }
    public static final float MIN_VOLUME=0,MAX_VOLUME=4,MIN_PITCH=.5f,MAX_PITCH=2;
    public TriggerAction {
        boolean valid=type!=null && switch(type) {
            case GIVE -> item!=null && placement!=null;
            case REMOVE -> item!=null;
            case MOB -> MobSpec.parse(mob)!=null && mobAt!=null;
            case SOUND -> sound!=null && !sound.isBlank() && volume>=MIN_VOLUME && volume<=MAX_VOLUME && pitch>=MIN_PITCH && pitch<=MAX_PITCH;
        };
        if(!valid) throw new IllegalArgumentException("Invalid trigger action");
    }
    public static TriggerAction give(ItemRef item,Placement placement) { return new TriggerAction(Type.GIVE,item,placement,false,null,null,null,0,0); }
    public static TriggerAction remove(ItemRef item,boolean allLevels) { return new TriggerAction(Type.REMOVE,item,null,allLevels,null,null,null,0,0); }
    public static TriggerAction mob(String mob,Spot at) { return new TriggerAction(Type.MOB,null,null,false,mob,at,null,0,0); }
    public static TriggerAction sound(String sound,float volume,float pitch) { return new TriggerAction(Type.SOUND,null,null,false,null,null,sound,volume,pitch); }
    public TriggerAction withSound(float newVolume,float newPitch) {
        return sound(sound,Math.clamp(newVolume,MIN_VOLUME,MAX_VOLUME),Math.clamp(newPitch,MIN_PITCH,MAX_PITCH));
    }
}
