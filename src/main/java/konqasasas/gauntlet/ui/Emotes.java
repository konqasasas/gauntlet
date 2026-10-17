package konqasasas.gauntlet.ui;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityHideOption;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Emotes and poses: a BetterModel player animation (limb model + animation),
 * played once on the player like /bettermodel play &lt;model&gt; &lt;id&gt;
 * without giving players that command.
 */
public final class Emotes {
    public enum Result { PLAYED, NO_PLUGIN, NOT_FOUND }

    public static Result play(Player player,String model,String animation) {
        if(model==null || animation==null) return Result.NOT_FOUND;
        // Touch BetterModel classes only when it is running (softdepend).
        if(!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) return Result.NO_PLUGIN;
        return BetterModelBridge.play(player,model,animation);
    }

    private static final class BetterModelBridge {
        static Result play(Player player,String model,String animation) {
            var renderer=BetterModel.limb(model).orElse(null); if(renderer==null) return Result.NOT_FOUND;
            var blueprint=renderer.animation(animation).orElse(null); if(blueprint==null) return Result.NOT_FOUND;
            // Same as the play command: hide the vanilla body while the model shows, close after one play.
            EntityTracker tracker=renderer.getOrCreate(BukkitAdapter.adapt(player),TrackerModifier.DEFAULT,t -> t.hideOption(EntityHideOption.DEFAULT));
            if(!tracker.animate(blueprint,new AnimationModifier(0,0,AnimationIterator.Type.PLAY_ONCE),tracker::close)) {
                tracker.close(); return Result.NOT_FOUND;
            }
            return Result.PLAYED;
        }
    }
    private Emotes() {}
}
