package konqasasas.gauntlet.play;

import java.util.UUID;

/** One finished attempt. ticks are client ticks; the position is where the run ended. */
public record RunRecord(String course,UUID player,Result result,long ticks,double x,double y,double z,long time) {
    public enum Result { CLEAR,RETIRE,FAIL }
    public RunRecord {
        if(course==null || player==null || result==null || ticks<0) throw new IllegalArgumentException("Invalid run record");
    }
}
