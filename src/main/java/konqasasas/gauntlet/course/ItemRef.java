package konqasasas.gauntlet.course;

/** A course item: a template id (or the built-in scaffold) and, for a mace, its Wind Burst level. */
public record ItemRef(String template,int level) {
    public static final String SCAFFOLD="scaffold";
    public static final int MAX_LEVEL=3;
    public ItemRef {
        if(template==null || template.isBlank() || level<0 || level>MAX_LEVEL) throw new IllegalArgumentException("Invalid item ref");
    }
    public static ItemRef scaffold() { return new ItemRef(SCAFFOLD,0); }
    public boolean isScaffold() { return SCAFFOLD.equals(template); }
    /** Value of the tag put on given items; exact equality means "already has it". */
    public String tag() { return template+":"+level; }
    public static ItemRef fromTag(String tag) {
        if(tag==null) return null;
        int split=tag.lastIndexOf(':');
        try { return split<0 ? null : new ItemRef(tag.substring(0,split),Integer.parseInt(tag.substring(split+1))); }
        catch(IllegalArgumentException e) { return null; }
    }
}
