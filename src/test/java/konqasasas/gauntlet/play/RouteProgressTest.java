package konqasasas.gauntlet.play;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import konqasasas.gauntlet.course.RoutePoint;
import org.junit.jupiter.api.Test;

class RouteProgressTest {
    @Test void projectsOntoTheRouteAndMeasuresAlongIt() {
        // An L: 10 blocks east, then 10 blocks up.
        RouteProgress route=new RouteProgress(List.of(new RoutePoint(0,0,0),new RoutePoint(10,0,0),new RoutePoint(10,10,0)));
        assertTrue(route.usable()); assertEquals(20,route.total(),1e-9);
        assertEquals(5,route.locate(5,1,0,0),1e-9,"Offset sideways still counts by projection");
        assertEquals(15,route.locate(10.5,5,0,10),1e-9);
        assertEquals(.75,route.fraction(15),1e-9);
    }
    @Test void aWallCannotMakeTheProgressJump() {
        // A U-turn: out 30 blocks east, back west 2 blocks to the north.
        RouteProgress route=new RouteProgress(List.of(new RoutePoint(0,0,0),new RoutePoint(30,0,0),new RoutePoint(30,0,2),new RoutePoint(0,0,2)));
        // Standing near the start: the return leg (distance ~62) is right next to us, but out of reach.
        assertEquals(1,route.locate(1,0,1.5,0),1e-9);
        // Near the end of the trip the return leg is found.
        assertEquals(60,route.locate(2,0,2,58),1e-9);
    }
    @Test void percentStopsAt99UntilTheGoal() {
        assertEquals(99,RouteProgress.percent(.999,false)); assertEquals(100,RouteProgress.percent(.999,true));
        assertEquals(63,RouteProgress.percent(.63,false)); assertEquals(0,RouteProgress.percent(0,false));
    }
    @Test void tooShortRoutesAreNotUsable() {
        assertFalse(new RouteProgress(List.of()).usable());
        assertFalse(new RouteProgress(List.of(new RoutePoint(1,2,3))).usable());
        assertFalse(new RouteProgress(List.of(new RoutePoint(1,2,3),new RoutePoint(1,2,3))).usable());
        assertEquals(0,new RouteProgress(List.of()).locate(5,5,5,0));
    }
}
