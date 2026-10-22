package konqasasas.gauntlet.course;

import java.util.*;

/**
 * A course marked as a module for generated courses: its difficulty (1-5), the
 * time it adds when entered (seconds), and how many of each loadout item it hands
 * out (counts[i] for hotbar slot i+1; 0 = as usual, the scaffold refilled to 64).
 * Entrance and exit are the course's start and goal; generated courses go along +Z.
 */
public record Module(int difficulty,int seconds,List<Integer> counts) {
    public static final int MIN_DIFFICULTY=1,MAX_DIFFICULTY=5,MAX_SECONDS=600,MAX_COUNT=64;
    public Module {
        counts=counts==null ? List.of(0,0,0) : new ArrayList<>(counts);
        while(counts.size()<Course.COURSE_SLOTS) counts.add(0);
        while(counts.size()>Course.COURSE_SLOTS) counts.removeLast();
        counts=List.copyOf(counts);
        if(difficulty<MIN_DIFFICULTY || difficulty>MAX_DIFFICULTY || seconds<1 || seconds>MAX_SECONDS || counts.stream().anyMatch(c -> c==null || c<0 || c>MAX_COUNT))
            throw new IllegalArgumentException("Invalid module");
    }
    public static Module standard() { return new Module(1,10,null); }
    public Module withDifficulty(int v) { return new Module(Math.clamp(v,MIN_DIFFICULTY,MAX_DIFFICULTY),seconds,counts); }
    public Module withSeconds(int v) { return new Module(difficulty,Math.clamp(v,1,MAX_SECONDS),counts); }
    public Module withCount(int slot,int v) { List<Integer> next=new ArrayList<>(counts); next.set(slot,Math.clamp(v,0,MAX_COUNT)); return new Module(difficulty,seconds,next); }
}
