package konqasasas.gauntlet.data;

import java.util.*;

/**
 * One personal setting: stored as text under its key. A stored value that no
 * longer parses (or is not among the choices) reads as the default.
 * menu=false settings are changed elsewhere (e.g. the visibility hotbar item).
 */
public record Setting<T>(String key,Class<T> type,T defaultValue,List<T> choices,boolean menu) {
    public Setting {
        choices=List.copyOf(choices);
        if(!choices.contains(defaultValue)) throw new IllegalArgumentException("Default not among choices: "+key);
    }
    public T parse(String raw) {
        if(raw==null) return null;
        Object value;
        if(type==Boolean.class) value=raw.equalsIgnoreCase("true") ? Boolean.TRUE : raw.equalsIgnoreCase("false") ? Boolean.FALSE : null;
        else if(type==Integer.class) { try { value=Integer.valueOf(raw.strip()); } catch(NumberFormatException e) { value=null; } }
        else value=raw;
        return value!=null && choices.contains(value) ? type.cast(value) : null;
    }
    public String format(T value) { return String.valueOf(value); }
    /** Next choice after the given one (wraps), for simple toggles. */
    public T next(T current) { return choices.get((choices.indexOf(current)+1)%choices.size()); }
    static <T> Setting<T> of(String key,Class<T> type,T defaultValue,List<T> choices,boolean menu) {
        return new Setting<>(key,type,defaultValue,choices,menu);
    }
    static List<Integer> percent() {
        List<Integer> values=new ArrayList<>();
        for(int v=0;v<=100;v+=10) values.add(v);
        return values;
    }
    static <T> List<T> concat(T first,Collection<T> rest) { List<T> out=new ArrayList<>(); out.add(first); out.addAll(rest); return out; }
}
