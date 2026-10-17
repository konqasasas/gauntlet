package konqasasas.gauntlet.play;

import java.util.UUID;

/**
 * One official attempt (the runs table). ticks are client ticks. progress is the
 * best route progress of the attempt (0..1), null when the course has no route.
 * rev is the course revision the attempt was played on.
 */
public record RunRecord(UUID id,String course,int rev,UUID player,Result result,String reason,long ticks,Long wallMs,
                        Double progress,Long startedAt,long endedAt,double x,double y,double z,Integer lane,String plugin) {
    /** QUIT: logout / leaving mid-run. INVALID: the client stopped reporting ticks. */
    public enum Result { CLEAR,FAIL,RETIRE,QUIT,INVALID }
    /** FAIL causes. PLACE: a block placed outside the course's placeable cells. */
    public enum Reason { LAVA,OUT,TIMEOUT,DEATH,PLACE }
    public RunRecord {
        if(id==null || course==null || player==null || result==null || ticks<0 || rev<0) throw new IllegalArgumentException("Invalid run record");
        if(progress!=null && !(progress>=0 && progress<=1)) throw new IllegalArgumentException("Progress out of range");
    }
}
