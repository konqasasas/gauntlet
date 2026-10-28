package konqasasas.gauntlet.play;

/**
 * Counts received client tick ends (Paper ClientTickEndEvent). The client tick in
 * which the player first moves or uses an item is tick 0; stop() in the goal's
 * tick includes that tick, so result = goalTick - startTick.
 */
public final class RunClock {
    public enum Phase { WAITING,RUNNING,FINISHED }
    private Phase phase=Phase.WAITING;
    private long count,startCount,result;
    private boolean pendingAction;
    /** False until one client tick has ended since the reset. */
    private boolean armed;

    public Phase phase() { return phase; }
    /**
     * An item use/swing. Ignored until the client has finished the tick in which
     * it was reset, so the swing that accompanies a Q-drop does not start the run.
     */
    public void action() { if(phase==Phase.WAITING && armed) pendingAction=true; }
    /** One received client tick end. True when the run started in this tick. */
    public boolean tick(boolean moved) {
        count++;
        armed=true;
        if(phase!=Phase.WAITING || !(moved || pendingAction)) return false;
        phase=Phase.RUNNING; startCount=count; pendingAction=false; return true;
    }
    /** Freezes the time at the current tick (goal, or the end of a practice segment). */
    public void stop() { if(phase==Phase.RUNNING) { phase=Phase.FINISHED; result=count-startCount; } }
    /** Client ticks since the start (final result once finished). */
    public long elapsed() {
        return switch(phase) { case WAITING -> 0; case RUNNING -> count-startCount; case FINISHED -> result; };
    }
    /** Every client tick end received so far, never reset (for timing parts of a run, such as a module). */
    public long received() { return count; }
    public void reset() { phase=Phase.WAITING; pendingAction=false; armed=false; }
    public static String seconds(long ticks) { return String.format("%d.%02d",ticks/20,(ticks%20)*5); }
}
