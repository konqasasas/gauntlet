package konqasasas.gauntlet.course;

import java.util.Locale;
import java.util.regex.*;

/**
 * Parsed mob text: "[summon] <type> [x y z] [{snbt}]". Coordinates, when given,
 * become the default spawn position. The SNBT itself is passed to /summon as is;
 * Tags are reserved because the plugin adds its own to track the mob.
 */
public record MobSpec(String type,double[] position,String nbt) {
    private static final Pattern FORMAT=Pattern.compile(
            "^(?:/?summon\\s+)?([a-z0-9_.-]+(?::[a-z0-9_./-]+)?)(?:\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?))?(?:\\s+(\\{.*}))?\\s*$",
            Pattern.DOTALL);
    public static MobSpec parse(String text) {
        if(text==null) return null;
        Matcher m=FORMAT.matcher(text.strip());
        if(!m.matches()) return null;
        String nbt=m.group(5)==null ? "" : m.group(5);
        if(nbt.contains("Tags:") || nbt.contains("Tags :")) return null;
        String type=m.group(1).toLowerCase(Locale.ROOT);
        if(!type.contains(":")) type="minecraft:"+type;
        double[] position=m.group(2)==null ? null
                : new double[]{Double.parseDouble(m.group(2)),Double.parseDouble(m.group(3)),Double.parseDouble(m.group(4))};
        return new MobSpec(type,position,nbt);
    }
    /** The text to store: type and SNBT only; the position is stored separately. */
    public String stored() { return nbt.isEmpty() ? type : type+" "+nbt; }
    /** SNBT with the tracking tags added as the first entry. */
    public String nbtWithTags(String... tags) {
        String list="Tags:[\""+String.join("\",\"",tags)+"\"]";
        if(nbt.isEmpty()) return "{"+list+"}";
        String inner=nbt.substring(1,nbt.length()-1).strip();
        return "{"+list+(inner.isEmpty() ? "" : ","+inner)+"}";
    }
}
