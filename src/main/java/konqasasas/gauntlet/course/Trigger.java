package konqasasas.gauntlet.course;

import java.util.*;

/** A registered iron pressure plate and what happens when it is stepped on (once per attempt). */
public record Trigger(BlockPos pos,List<TriggerAction> actions) {
    public static final int MAX_ACTIONS=18;
    public Trigger {
        if(pos==null) throw new IllegalArgumentException("Trigger without position");
        actions=actions==null ? List.of() : List.copyOf(actions);
        if(actions.size()>MAX_ACTIONS) throw new IllegalArgumentException("Too many actions");
    }
    public Trigger withAction(TriggerAction action) {
        List<TriggerAction> next=new ArrayList<>(actions); next.add(action); return new Trigger(pos,next);
    }
    public Trigger withAction(int index,TriggerAction action) {
        List<TriggerAction> next=new ArrayList<>(actions); next.set(index,action); return new Trigger(pos,next);
    }
    public Trigger withoutAction(int index) {
        List<TriggerAction> next=new ArrayList<>(actions); next.remove(index); return new Trigger(pos,next);
    }
}
