package konqasasas.gauntlet.pack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player is with each resource pack we sent: recorded when packs are sent, updated from
 * the client's status reports, forgotten on quit. No Bukkit, so it is tested on its own.
 */
public final class PackStatus {
    /** What a screen that needs a pack should do. */
    public enum State {
        /** Loaded, or we never sent that pack (nothing to wait for). */
        READY,
        /** Sent and not finished yet. */
        LOADING,
        DECLINED,
        /** Download or apply failed, or the client dropped it. */
        FAILED
    }
    /** The client's reports, as PlayerResourcePackStatusEvent.Status names them. */
    public enum Report { ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED, DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED }

    private final Map<UUID,Map<UUID,State>> players=new ConcurrentHashMap<>();

    /** The packs just sent to a player replace what was sent before (the request replaces all packs). */
    public void sent(UUID player,Collection<UUID> packs) {
        Map<UUID,State> states=new ConcurrentHashMap<>();
        for(UUID pack:packs) states.put(pack,State.LOADING);
        players.put(player,states);
    }
    /** A status report for a pack we sent; reports for other packs are ignored. */
    public void report(UUID player,UUID pack,Report report) {
        Map<UUID,State> states=players.get(player);
        if(states==null || !states.containsKey(pack)) return;
        states.put(pack,switch(report) {
            case ACCEPTED,DOWNLOADED -> State.LOADING;
            case SUCCESSFULLY_LOADED -> State.READY;
            case DECLINED -> State.DECLINED;
            case FAILED_DOWNLOAD,INVALID_URL,FAILED_RELOAD,DISCARDED -> State.FAILED;
        });
    }
    public void forget(UUID player) { players.remove(player); }

    public State state(UUID player,UUID pack) {
        Map<UUID,State> states=players.get(player);
        return states==null ? State.READY : states.getOrDefault(pack,State.READY);
    }
}
