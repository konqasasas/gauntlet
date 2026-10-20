package konqasasas.gauntlet.play;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LaneBookTest {
    private final UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();

    @Test void eachPlayerGetsTheirOwnLaneAndKeepsIt() {
        LaneBook book=new LaneBook();
        assertEquals(0,book.claim("x",2,a));
        assertEquals(1,book.claim("x",2,b));
        assertEquals(-1,book.claim("x",2,c),"Both lanes taken");
        assertEquals(0,book.claim("x",2,a),"Asking again keeps the lane");
        assertEquals(a,book.occupant("x",0));
        book.release(a);
        assertFalse(book.inUse("x",0));
        assertEquals(0,book.claim("x",2,c));
    }
    @Test void movingToAnotherCourseFreesTheOldLaneOnlyWhenTheNewOneIsGiven() {
        LaneBook book=new LaneBook();
        book.claim("x",1,a); book.claim("y",1,b);
        assertEquals(-1,book.claim("y",1,a));
        assertTrue(book.inUse("x",0),"Still playing x while waiting for y");
        book.release(b);
        assertEquals(0,book.claim("y",1,a));
        assertFalse(book.inUse("x",0));
        assertEquals(new LaneBook.Seat("y",0),book.seat(a));
    }
    @Test void waitingIsFirstComeFirstPerCourse() {
        LaneBook book=new LaneBook();
        assertEquals(1,book.enqueue(a,"x",false));
        assertEquals(1,book.enqueue(b,"y",true),"Another course has its own line");
        assertEquals(2,book.enqueue(c,"x",false));
        assertEquals(2,book.waitingFor("x"));
        assertEquals(new LaneBook.Waiting("y",true),book.waiting(b));
        assertNotNull(book.dequeue(a));
        assertEquals(1,book.position(c));
        assertEquals(0,book.position(a));
        assertNull(book.dequeue(a));
        assertEquals(List.of(b,c),book.queue().stream().map(Map.Entry::getKey).toList());
    }
    @Test void lanesWaitingForSyncAreNotGivenAndLanesInUseAreCopiedLater() {
        LaneBook book=new LaneBook();
        book.claim("x",3,a);                       // lane 0
        assertEquals(1,book.claim("x",3,b));       // lane 1
        book.markPending("x",List.of(0,1,2));      // lane 0 is never copied
        assertFalse(book.pending("x",0));
        assertEquals(new LaneBook.Seat("x",2),book.nextSync(),"Lane 1 is in use; lane 2 goes first");
        assertEquals(-1,book.claim("x",3,c),"Lane 2 waits for its copy");
        assertFalse(book.hasFree("x",3));
        book.startCopy(new LaneBook.Seat("x",2));
        assertTrue(book.copying("x",2)); assertFalse(book.pending("x",2));
        assertNull(book.nextSync(),"Lane 1 still in use");
        book.endCopy(true);
        assertEquals(2,book.claim("x",3,c));
        book.release(b);
        assertEquals(new LaneBook.Seat("x",1),book.nextSync());
        book.startCopy(book.nextSync());
        book.endCopy(false);
        assertTrue(book.pending("x",1),"An interrupted copy is pending again");
        book.trim("x",1);
        assertEquals(Map.of(),book.pending());
    }
}
