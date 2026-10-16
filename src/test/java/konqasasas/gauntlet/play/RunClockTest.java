package konqasasas.gauntlet.play;

import static org.junit.jupiter.api.Assertions.*;
import konqasasas.gauntlet.play.RunClock.Phase;
import org.junit.jupiter.api.Test;

class RunClockTest {
    @Test void startTickIsZeroAndGoalTickIsIncluded() {
        // Same counting as the experiment: start event = 0, goal 12 client ticks later = 12.
        RunClock clock=new RunClock();
        for(int i=0;i<5;i++) assertFalse(clock.tick(false));
        assertTrue(clock.tick(true));
        assertEquals(0,clock.elapsed());
        for(int i=1;i<12;i++) { assertFalse(clock.tick(true)); assertEquals(i,clock.elapsed()); }
        clock.tick(true); clock.stop();
        assertEquals(Phase.FINISHED,clock.phase()); assertEquals(12,clock.elapsed());
        clock.tick(true); clock.stop(); assertEquals(12,clock.elapsed(),"Result is frozen after the goal");
    }
    @Test void stopBeforeStartingDoesNothing() {
        RunClock clock=new RunClock(); clock.tick(false); clock.stop();
        assertEquals(Phase.WAITING,clock.phase());
    }
    @Test void itemUseStartsOnTheNextTickEnd() {
        RunClock clock=new RunClock(); clock.tick(false);
        clock.action();
        assertEquals(Phase.WAITING,clock.phase());
        assertTrue(clock.tick(false));
    }
    @Test void swingInTheResetTickDoesNotStart() {
        // Q-drop resets and the client swings in that same tick.
        RunClock clock=new RunClock(); clock.tick(true); clock.tick(false);
        clock.reset(); clock.action();
        assertFalse(clock.tick(false));
        assertEquals(Phase.WAITING,clock.phase());
        clock.action();
        assertTrue(clock.tick(false));
    }
    @Test void secondsFormatting() {
        assertEquals("0.00",RunClock.seconds(0)); assertEquals("0.05",RunClock.seconds(1));
        assertEquals("12.35",RunClock.seconds(247)); assertEquals("30.00",RunClock.seconds(600));
    }
}
