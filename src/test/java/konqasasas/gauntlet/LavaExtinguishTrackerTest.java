package konqasasas.gauntlet;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LavaExtinguishTrackerTest {
    @Test void briefContactBetweenEndOfTickSamplesIsRemembered() {
        var tracker=new LavaExtinguishTracker(); UUID player=UUID.randomUUID();
        assertFalse(tracker.shouldExtinguish(player,true,false,0));
        tracker.contact(player); // Event sees lava although the final sample does not.
        assertTrue(tracker.shouldExtinguish(player,true,false,1));
    }
    @Test void lateIgnitionIsClearedForThreeFollowingTicksThenTrackingExpires() {
        var tracker=new LavaExtinguishTracker(); UUID player=UUID.randomUUID();
        assertFalse(tracker.shouldExtinguish(player,true,true,99));
        for(int tick=100;tick<=103;tick++) assertTrue(tracker.shouldExtinguish(player,true,false,tick));
        assertFalse(tracker.shouldExtinguish(player,true,false,104));
    }
    @Test void anotherPlayersLavaAndOrdinaryFireAreUnaffected() {
        var tracker=new LavaExtinguishTracker(); UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        tracker.contact(a); assertTrue(tracker.shouldExtinguish(a,true,false,10));
        assertFalse(tracker.shouldExtinguish(b,true,false,10));
        assertFalse(tracker.shouldExtinguish(a,true,true,11));
        tracker.forget(a); // New independent fire source or logout.
        assertFalse(tracker.shouldExtinguish(a,true,false,12));
    }
    @Test void SwitchingModesClearsTrackingAndReentryRestartsTheWindow() {
        var tracker=new LavaExtinguishTracker(); UUID player=UUID.randomUUID();
        tracker.contact(player); assertFalse(tracker.shouldExtinguish(player,false,false,1));
        assertFalse(tracker.shouldExtinguish(player,true,false,2));
        tracker.contact(player); assertTrue(tracker.shouldExtinguish(player,true,false,10));
        assertFalse(tracker.shouldExtinguish(player,true,true,13));
        assertTrue(tracker.shouldExtinguish(player,true,false,14));
        assertTrue(tracker.shouldExtinguish(player,true,false,17));
        assertFalse(tracker.shouldExtinguish(player,true,false,18));
    }
    @Test void TickCounterWrapDoesNotBreakExpiry() {
        var tracker=new LavaExtinguishTracker(); UUID player=UUID.randomUUID(); tracker.contact(player);
        int exit=Integer.MAX_VALUE-1;
        assertTrue(tracker.shouldExtinguish(player,true,false,exit));
        assertTrue(tracker.shouldExtinguish(player,true,false,exit+3));
        assertFalse(tracker.shouldExtinguish(player,true,false,exit+4));
    }
}
