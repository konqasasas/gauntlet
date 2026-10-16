package konqasasas.gauntlet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Remembers even a brief lava contact and tolerates delayed vanilla ignition. */
final class LavaExtinguishTracker {
    private record State(boolean touching,int expiresAt) {}
    private final Map<UUID,State> players=new HashMap<>();
    void contact(UUID player) { players.put(player,new State(true,0)); }
    boolean hasContact(UUID player) { return players.containsKey(player); }
    boolean shouldExtinguish(UUID player,boolean eligible,boolean inLava,int tick) {
        if(!eligible) { forget(player); return false; }
        if(inLava) { contact(player); return false; }
        State state=players.get(player);
        if(state==null) return false;
        if(state.touching()) {
            state=new State(false,tick+3); players.put(player,state);
        }
        // Subtraction also handles the server tick counter wrapping around.
        if(tick-state.expiresAt()>0) { forget(player); return false; }
        return true;
    }
    void forget(UUID player) { players.remove(player); }
    void clear() { players.clear(); }
}
