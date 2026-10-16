package konqasasas.gauntlet.course;

/**
 * One action of a trigger. Flat record so it stays plain JSON; only the fields
 * of its type are set. Placement SLOT1..SLOT4 are hotbar slots 1-4, EMPTY is the
 * first empty slot among 2-4.
 */
public record TriggerAction(Type type,ItemRef item,Placement placement,boolean allLevels,String mob,Spot mobAt,
                            String sound,float volume,float pitch) {
    public enum Type { GIVE,REMOVE,MOB,SOUND }
    public enum Placement {
        SLOT1("1番を上書き"),SLOT2("2番"),SLOT3("3番"),SLOT4("4番"),EMPTY("2〜4番の空き");
        public final String label;
        Placement(String label) { this.label=label; }
        public int slot() { return this==EMPTY ? -1 : ordinal(); }
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
