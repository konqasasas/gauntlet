package konqasasas.gauntlet.ui;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Takes the next chat line as text input instead of broadcasting it. */
public final class ChatPrompts implements Listener {
    private static final Set<String> CANCEL=Set.of("cancel","キャンセル");
    private final Plugin plugin;
    private final Map<UUID,Consumer<String>> pending=new ConcurrentHashMap<>();
    public ChatPrompts(Plugin plugin) { this.plugin=plugin; }
    public void ask(Player player,String question,Consumer<String> answer) {
        player.closeInventory();
        pending.put(player.getUniqueId(),answer);
        Icons.tell(player,question+"（チャットに入力。「キャンセル」で中止）");
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void chat(AsyncChatEvent event) {
        Player player=event.getPlayer();
        Consumer<String> answer=pending.remove(player.getUniqueId());
        if(answer==null) return;
        event.setCancelled(true);
        String text=PlainTextComponentSerializer.plainText().serialize(event.originalMessage()).strip();
        Bukkit.getScheduler().runTask(plugin,() -> {
            if(!player.isOnline()) return;
            if(CANCEL.contains(text.toLowerCase(Locale.ROOT))) Icons.tell(player,"入力を中止しました。");
            else answer.accept(text);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    public void clear() { pending.clear(); }
}
