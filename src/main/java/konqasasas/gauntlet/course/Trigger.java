package konqasasas.gauntlet.course;

import java.util.*;

/**
 * A registered iron pressure plate and what happens when it is stepped on (once per attempt).
 * name is an optional label for builders (null = none); it does not change how the course plays.
 */
public record Trigger(BlockPos pos,List<TriggerAction> actions,String name) {
    public static final int MAX_ACTIONS=18,MAX_NAME_LENGTH=32;
    public Trigger {
        if(pos==null) throw new IllegalArgumentException("Trigger without position");
        actions=actions==null ? List.of() : List.copyOf(actions);
        if(actions.size()>MAX_ACTIONS) throw new IllegalArgumentException("Too many actions");
        if(name!=null && name.isBlank()) name=null;
        if(name!=null && name.length()>MAX_NAME_LENGTH) throw new IllegalArgumentException("Trigger name too long");
    }
    public Trigger(BlockPos pos,List<TriggerAction> actions) { this(pos,actions,null); }
    public Trigger withAction(TriggerAction action) {
        List<TriggerAction> next=new ArrayList<>(actions); next.add(action); return new Trigger(pos,next,name);
    }
    public Trigger withAction(int index,TriggerAction action) {
        List<TriggerAction> next=new ArrayList<>(actions); next.set(index,action); return new Trigger(pos,next,name);
    }
    public Trigger withoutAction(int index) {
        List<TriggerAction> next=new ArrayList<>(actions); next.remove(index); return new Trigger(pos,next,name);
    }
    public Trigger withActions(List<TriggerAction> value) { return new Trigger(pos,value,name); }
    public Trigger withName(String value) { return new Trigger(pos,actions,value); }
    /** The plate and every positioned action moved by d. */
    public Trigger moved(BlockPos d) { return new Trigger(pos.plus(d),actions.stream().map(a -> a.moved(d)).toList(),name); }
}
