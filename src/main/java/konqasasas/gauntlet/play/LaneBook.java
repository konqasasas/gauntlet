package konqasasas.gauntlet.play;

import java.util.*;

/**
 * Who plays in which lane, who waits for one, and which lanes still need a copy
 * of lane 1 (sync). Lanes are numbered from 0 here; lane 0 is the course itself.
 * A lane that waits for its sync, or is being copied, is never handed out; a lane
 * in use is copied only after its player has left.
 */
final class LaneBook {
    record Seat(String course,int lane) {}
    record Waiting(String course,boolean practice) {}
    private final Map<UUID,Seat> seats=new HashMap<>();
    private final Map<Seat,UUID> occupants=new HashMap<>();
    private final LinkedHashMap<UUID,Waiting> waiting=new LinkedHashMap<>();
    private final Map<String,TreeSet<Integer>> pending=new TreeMap<>();
    private Seat copying;

    /** The player's lane in the course (kept if they already have one there), or -1 when every lane is taken or not ready. */
    int claim(String course,int laneCount,UUID player) {
        Seat seat=seats.get(player);
        if(seat!=null && seat.course().equals(course) && seat.lane()<laneCount) return seat.lane();
        for(int lane=0;lane<laneCount;lane++) {
            Seat free=new Seat(course,lane);
            if(occupants.containsKey(free) || waitsForSync(course,lane)) continue;
            release(player);
            seats.put(player,free); occupants.put(free,player);
            return lane;
        }
        return -1;
    }
    void release(UUID player) { Seat seat=seats.remove(player); if(seat!=null) occupants.remove(seat); }
    Seat seat(UUID player) { return seats.get(player); }
    UUID occupant(String course,int lane) { return occupants.get(new Seat(course,lane)); }
    boolean inUse(String course,int lane) { return occupants.containsKey(new Seat(course,lane)); }
    /** True when some lane of the course could be handed out now. */
    boolean hasFree(String course,int laneCount) {
        for(int lane=0;lane<laneCount;lane++) if(!inUse(course,lane) && !waitsForSync(course,lane)) return true;
        return false;
    }

    // ---- Waiting for a lane ----
    /** Queues the player (moving them to the end if already queued); their place among those waiting for that course, from 1. */
    int enqueue(UUID player,String course,boolean practice) {
        waiting.remove(player); waiting.put(player,new Waiting(course,practice));
        return position(player);
    }
    Waiting dequeue(UUID player) { return waiting.remove(player); }
    Waiting waiting(UUID player) { return waiting.get(player); }
    /** Place in the queue of that course, from 1; 0 when not waiting. */
    int position(UUID player) {
        Waiting mine=waiting.get(player); if(mine==null) return 0;
        int position=0;
        for(var e:waiting.entrySet()) {
            if(e.getValue().course().equals(mine.course())) position++;
            if(e.getKey().equals(player)) return position;
        }
        return 0;
    }
    /** Everyone waiting, first come first. */
    List<Map.Entry<UUID,Waiting>> queue() { return List.copyOf(waiting.entrySet()); }
    int waitingFor(String course) { return (int)waiting.values().stream().filter(w -> w.course().equals(course)).count(); }

    // ---- Sync ----
    void markPending(String course,Collection<Integer> lanes) {
        for(int lane:lanes) if(lane>0) pending.computeIfAbsent(course,k -> new TreeSet<>()).add(lane);
    }
    boolean pending(String course,int lane) { var set=pending.get(course); return set!=null && set.contains(lane); }
    boolean copying(String course,int lane) { return new Seat(course,lane).equals(copying); }
    boolean waitsForSync(String course,int lane) { return pending(course,lane) || copying(course,lane); }
    /** The next lane to copy: pending and nobody in it. Null when there is none. */
    Seat nextSync() {
        for(var e:pending.entrySet()) for(int lane:e.getValue()) if(!inUse(e.getKey(),lane)) return new Seat(e.getKey(),lane);
        return null;
    }
    /** Takes the lane out of pending while it is copied. */
    void startCopy(Seat seat) { removePending(seat); copying=seat; }
    /** Ends the copy; a failed or interrupted copy goes back to pending. */
    void endCopy(boolean done) {
        if(copying!=null && !done) markPending(copying.course(),List.of(copying.lane()));
        copying=null;
    }
    Seat copying() { return copying; }
    private void removePending(Seat seat) {
        var set=pending.get(seat.course()); if(set==null) return;
        set.remove(seat.lane()); if(set.isEmpty()) pending.remove(seat.course());
    }
    /** Lanes from laneCount on no longer exist: forget their pending copies. */
    void trim(String course,int laneCount) {
        var set=pending.get(course); if(set==null) return;
        set.removeIf(lane -> lane>=laneCount); if(set.isEmpty()) pending.remove(course);
    }
    Map<String,Set<Integer>> pending() {
        Map<String,Set<Integer>> copy=new TreeMap<>();
        pending.forEach((course,lanes) -> copy.put(course,Set.copyOf(lanes)));
        return copy;
    }
}
