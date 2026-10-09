package konqasasas.gauntlet;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class GroundGeometryTest {
    @Test void standingOnFullBlocksAndSlabsCountsAsSupport() {
        assertTrue(GroundGeometry.supports(.2,.8,.2,.8,1,0,1,0,1,1));
        assertTrue(GroundGeometry.supports(.2,.8,.2,.8,.5,0,1,0,1,.5));
    }
    @Test void airJumpAndBlocksAboveFeetDoNotCount() {
        assertFalse(GroundGeometry.supports(.2,.8,.2,.8,1.1,0,1,0,1,1));
        assertFalse(GroundGeometry.supports(.2,.8,.2,.8,1,0,1,0,1,1.5));
    }
    @Test void touchingAnEdgeWithoutOverlapIsNotGround() {
        assertFalse(GroundGeometry.supports(1,1.6,.2,.8,1,0,1,0,1,1));
        assertTrue(GroundGeometry.supports(.999,1.6,.2,.8,1,0,1,0,1,1));
    }
}
