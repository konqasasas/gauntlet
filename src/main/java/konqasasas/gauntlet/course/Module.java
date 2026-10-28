package konqasasas.gauntlet.course;

import java.util.*;

/**
 * A course marked as a module for generated courses: its difficulty (1-5), its time
 * (ticks; a generated course's limit is the sum of its modules' times plus the buffer),
 * and how many of each loadout item it hands out (counts[i] for hotbar slot i+1;
 * 0 = as usual, the scaffold refilled to 64). locked: its time is kept as set by hand;
 * otherwise it follows the median of the times through it (see ModuleTimes).
 * Entrance and exit are the course's start and goal; generated courses go along +Z.
 * Files written before ticks existed have seconds instead (CourseStore converts them).
 */
public record Module(int difficulty,int ticks,List<Integer> counts,Boolean locked) {
    public static final int MIN_DIFFICULTY=1,MAX_DIFFICULTY=5,MAX_TICKS=600*20,MAX_COUNT=64;
    public Module {
        counts=counts==null ? List.of(0,0,0) : new ArrayList<>(counts);
        while(counts.size()<Course.COURSE_SLOTS) counts.add(0);
        while(counts.size()>Course.COURSE_SLOTS) counts.removeLast();
        counts=List.copyOf(counts);
        locked=locked!=null && locked;
        if(difficulty<MIN_DIFFICULTY || difficulty>MAX_DIFFICULTY || ticks<1 || ticks>MAX_TICKS || counts.stream().anyMatch(c -> c==null || c<0 || c>MAX_COUNT))
            throw new IllegalArgumentException("Invalid module");
    }
    public static Module standard() { return new Module(1,200,null,false); }
    public Module withDifficulty(int v) { return new Module(Math.clamp(v,MIN_DIFFICULTY,MAX_DIFFICULTY),ticks,counts,locked); }
    public Module withTicks(int v) { return new Module(difficulty,Math.clamp(v,1,MAX_TICKS),counts,locked); }
    public Module withCount(int slot,int v) { List<Integer> next=new ArrayList<>(counts); next.set(slot,Math.clamp(v,0,MAX_COUNT)); return new Module(difficulty,ticks,next,locked); }
    public Module withLocked(boolean v) { return new Module(difficulty,ticks,counts,v); }
}
