package konqasasas.gauntlet.play;

import java.util.function.IntFunction;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The moment a rank goes up (an era boss beaten in an official run). For now one
 * chat line and a sound; the staging for this moment goes here later.
 */
public final class RankUps {
    /** The rank badge in its colors. */
    private final IntFunction<Component> badge;
    public RankUps(IntFunction<Component> badge) { this.badge=badge; }
    public void rankedUp(Player player,int from,int to) {
        Msg.send(player,"play.rank.up",Msg.arg("rank",badge.apply(to)),Msg.arg("number",to));
        Sounds.system(player,Sound.UI_TOAST_CHALLENGE_COMPLETE,1f,1.2f);
    }
}
