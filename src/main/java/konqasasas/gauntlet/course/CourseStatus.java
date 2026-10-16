package konqasasas.gauntlet.course;

public enum CourseStatus {
    DRAFT("下書き"),TESTING("テスト中"),DONE("完成");
    public final String label;
    CourseStatus(String label) { this.label=label; }
    public CourseStatus next() { return values()[(ordinal()+1)%values().length]; }
}
