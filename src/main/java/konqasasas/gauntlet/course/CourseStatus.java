package konqasasas.gauntlet.course;

/** Display names are the message keys "status.<NAME>". */
public enum CourseStatus {
    DRAFT,TESTING,DONE;
    public String key() { return "status."+name(); }
    public CourseStatus next() { return values()[(ordinal()+1)%values().length]; }
}
