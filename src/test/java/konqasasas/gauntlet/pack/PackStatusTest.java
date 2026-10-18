package konqasasas.gauntlet.pack;

import static konqasasas.gauntlet.pack.PackStatus.State.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PackStatusTest {
    private final UUID player=UUID.randomUUID(), menu=UUID.randomUUID(), model=UUID.randomUUID();

    @Test void nothingSentMeansNothingToWaitFor() {
        assertEquals(READY,new PackStatus().state(player,menu));
    }
    @Test void loadingUntilTheClientReportsLoaded() {
        PackStatus s=new PackStatus();
        s.sent(player,List.of(model,menu));
        assertEquals(LOADING,s.state(player,menu));
        s.report(player,menu,PackStatus.Report.ACCEPTED);
        s.report(player,menu,PackStatus.Report.DOWNLOADED);
        assertEquals(LOADING,s.state(player,menu));
        s.report(player,menu,PackStatus.Report.SUCCESSFULLY_LOADED);
        assertEquals(READY,s.state(player,menu));
        assertEquals(LOADING,s.state(player,model),"Each pack on its own");
    }
    @Test void declinedAndFailed() {
        PackStatus s=new PackStatus();
        s.sent(player,List.of(menu,model));
        s.report(player,menu,PackStatus.Report.DECLINED);
        s.report(player,model,PackStatus.Report.INVALID_URL);
        assertEquals(DECLINED,s.state(player,menu));
        assertEquals(FAILED,s.state(player,model));
        for(PackStatus.Report r:List.of(PackStatus.Report.FAILED_DOWNLOAD,PackStatus.Report.FAILED_RELOAD,PackStatus.Report.DISCARDED)) {
            s.report(player,model,r);
            assertEquals(FAILED,s.state(player,model),r.name());
        }
    }
    @Test void sendingAgainStartsOverAndQuitForgets() {
        PackStatus s=new PackStatus();
        s.sent(player,List.of(menu));
        s.report(player,menu,PackStatus.Report.SUCCESSFULLY_LOADED);
        s.sent(player,List.of(menu));
        assertEquals(LOADING,s.state(player,menu),"A new version has to load again");
        s.report(player,UUID.randomUUID(),PackStatus.Report.DECLINED);
        assertEquals(LOADING,s.state(player,menu),"Reports for packs we did not send are ignored");
        s.forget(player);
        assertEquals(READY,s.state(player,menu));
    }
}
