package konqasasas.gauntlet.ui;

import konqasasas.gauntlet.data.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Sounds for one player, scaled by that player's volume settings. */
public final class Sounds {
    private static volatile PlayerData data;
    public static void init(PlayerData playerData) { data=playerData; }
    /** Plugin cues (checkpoint, clear, failure...). */
    public static void system(Player player,Sound sound,float volume,float pitch) {
        float scale=scale(player,Settings.SOUND_SYSTEM);
        if(scale>0) player.playSound(player.getLocation(),sound,SoundCategory.PLAYERS,volume*scale,pitch);
    }
    /** Sounds of course triggers. */
    public static void course(Player player,String sound,float volume,float pitch) {
        float scale=scale(player,Settings.SOUND_COURSE);
        if(scale>0) player.playSound(player.getLocation(),sound,SoundCategory.PLAYERS,volume*scale,pitch);
    }
    private static float scale(Player player,Setting<Integer> setting) {
        PlayerData d=data;
        return d==null ? 1 : d.get(player,setting)/100f;
    }
    private Sounds() {}
}
