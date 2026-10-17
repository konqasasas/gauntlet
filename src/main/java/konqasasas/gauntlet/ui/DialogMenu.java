package konqasasas.gauntlet.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import com.google.gson.*;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.set.RegistrySet;
import konqasasas.gauntlet.data.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.lobby.Lobby;
import konqasasas.gauntlet.play.Participants;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The menu screens made with the Dialog editor (dialog-custom-gui). Screens
 * are sent by this plugin from the editor's templates (plugins/ParkourGauntlet/
 * menu/&lt;screen&gt;.json), keeping only the states that match the player's
 * settings; moving between screens is the custom click dcg:open, so a screen
 * is always drawn for the player who opens it. A screen without a template
 * falls back to the datapack's registered dcg:&lt;screen&gt;.
 * Every screen has after_action none, so each click either re-sends a screen
 * or closes it. See docs/design/menu-dialog.md.
 */
public final class DialogMenu implements Listener,CommandExecutor {
    public static final String NAMESPACE="dcg";
    private final JavaPlugin plugin;
    private final PlayerData data;
    private final Lobby lobby;
    private final Participants participants;
    private DialogTemplates templates=DialogTemplates.empty(NAMESPACE);

    public DialogMenu(JavaPlugin plugin,PlayerData data,Lobby lobby,Participants participants) {
        this.plugin=plugin; this.data=data; this.lobby=lobby; this.participants=participants;
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        Objects.requireNonNull(plugin.getCommand("menu")).setExecutor(this);
        reload();
    }
    /** Reads the templates again (/gauntlet reload menu). False when they cannot be read; the old ones stay. */
    public boolean reload() {
        Path dir=plugin.getDataFolder().toPath().resolve("menu");
        try {
            templates=DialogTemplates.load(NAMESPACE,dir);
            if(templates.screens().isEmpty()) plugin.getLogger().warning("No menu templates in "+dir+"; copy the Dialog editor's exports/latest/templates there");
            else plugin.getLogger().info("Menu templates: "+templates.screens().size()+" screens");
            return true;
        } catch(IOException e) {
            plugin.getLogger().log(Level.SEVERE,"Cannot read menu templates",e);
            return false;
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(sender instanceof Player player) open(player,"top_menu");
        else Msg.send(sender,"settings.console");
        return true;
    }

    /** Shows a screen drawn for this player; tells the player when it exists nowhere. */
    public boolean open(Player player,String screen) {
        Dialog dialog=null;
        JsonObject json=templates.select(screen,values(player));
        if(json!=null) {
            try { dialog=build(json); }
            catch(RuntimeException e) { plugin.getLogger().log(Level.WARNING,"Cannot build menu screen "+screen,e); }
        }
        // A template this cannot build (an input screen) or no template: the datapack's screen.
        if(dialog==null) dialog=RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG).get(Key.key(NAMESPACE,screen));
        if(dialog==null) {
            Msg.send(player,"menu.missing",Msg.arg("dialog",NAMESPACE+":"+screen));
            plugin.getLogger().warning("Menu screen "+screen+" has no template and no registered dialog "+NAMESPACE+":"+screen);
            return false;
        }
        player.showDialog(dialog);
        return true;
    }

    /** The values a screen's states are chosen by: the player's settings, on/off for switches. */
    static Map<String,String> values(PlayerData data,Player player) {
        Map<String,String> values=new HashMap<>();
        for(Setting<?> setting:Settings.ALL) {
            Object value=data.get(player,setting);
            values.put(setting.key(),value instanceof Boolean b ? (b ? "on" : "off") : String.valueOf(value));
        }
        return values;
    }
    private Map<String,String> values(Player player) { return values(data,player); }

    /** A footer-less dialog_list screen from its JSON; null for other kinds, which are left to the datapack. */
    private static Dialog build(JsonObject json) {
        if(!"minecraft:dialog_list".equals(text(json,"type")) || json.has("inputs") || json.has("exit_action")) return null;
        GsonComponentSerializer gson=GsonComponentSerializer.gson();
        List<DialogBody> body=new ArrayList<>();
        for(JsonElement element:json.getAsJsonArray("body")) {
            JsonObject b=element.getAsJsonObject();
            if(!"minecraft:plain_message".equals(text(b,"type"))) return null;
            Component contents=gson.deserializeFromTree(b.get("contents"));
            body.add(b.has("width") ? DialogBody.plainMessage(contents,b.get("width").getAsInt()) : DialogBody.plainMessage(contents));
        }
        DialogBase base=DialogBase.builder(json.has("title") ? gson.deserializeFromTree(json.get("title")) : Component.empty())
                .externalTitle(json.has("external_title") ? gson.deserializeFromTree(json.get("external_title")) : null)
                .canCloseWithEscape(!json.has("can_close_with_escape") || json.get("can_close_with_escape").getAsBoolean())
                .pause(json.has("pause") && json.get("pause").getAsBoolean())
                .afterAction(DialogBase.DialogAfterAction.NAMES.valueOrThrow(json.has("after_action") ? text(json,"after_action") : "close"))
                .body(body)
                .build();
        int columns=json.has("columns") ? json.get("columns").getAsInt() : 2;
        int buttonWidth=json.has("button_width") ? json.get("button_width").getAsInt() : 150;
        DialogType type=DialogType.dialogList(RegistrySet.keySet(RegistryKey.DIALOG,List.<TypedKey<Dialog>>of()),null,columns,buttonWidth);
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }
    private static String text(JsonObject o,String key) { JsonElement e=o.get(key); return e!=null && e.isJsonPrimitive() ? e.getAsString() : null; }

    @EventHandler
    public void click(PlayerCustomClickEvent event) {
        Key id=event.getIdentifier();
        if(!id.namespace().equals(NAMESPACE) || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player=connection.getPlayer();
        BinaryTagHolder tag=event.getTag();
        DialogPayload payload=DialogPayload.parse(tag==null ? null : tag.string());
        if(Bukkit.isPrimaryThread()) handle(player,id.value(),payload);
        else Bukkit.getScheduler().runTask(plugin,() -> { if(player.isOnline()) handle(player,id.value(),payload); });
    }
    private void handle(Player player,String action,DialogPayload payload) {
        switch(action) {
            case "open" -> {
                String screen=payload.get("screen");
                if(screen==null || !screen.matches("[a-z0-9_]{1,48}")) { Msg.send(player,"menu.unavailable"); return; }
                open(player,screen);
            }
            case "close" -> player.closeDialog();
            case "lobby" -> toLobby(player);
            case "emote_play" -> emote(player,payload.get("model"),payload.get("id"));
            // Every card fits on one page until the editor exports lists.
            case "emote_page" -> {}
            case "settings_set","settings_toggle","settings_step" -> {
                Setting<?> setting=Settings.byKey(String.valueOf(payload.get("key")));
                if(setting==null || !setting.menu()) { Msg.send(player,"menu.unavailable"); return; }
                edit(player,setting,action,payload);
                // Also from the translation target screen: back to the settings.
                open(player,"settings");
            }
            case "settings_reset" -> {
                for(Setting<?> setting:Settings.ALL) if(setting.menu()) reset(player,setting);
                Sounds.system(player,Sound.UI_BUTTON_CLICK,0.5f,1f);
                open(player,"settings");
            }
            default -> Msg.send(player,"menu.unavailable");
        }
    }

    private void toLobby(Player player) {
        Location spawn=lobby.spawn();
        if(spawn==null) { Msg.send(player,"menu.lobby.off"); return; }
        player.closeDialog();
        if(participants.active(player)) participants.leave(player);
        if(!Teleports.to(player,spawn)) Msg.send(player,"play.teleport.denied");
    }
    private void emote(Player player,String model,String animation) {
        if(participants.active(player)) { Msg.send(player,"menu.emote.in-course"); return; }
        player.closeDialog();
        switch(Emotes.play(player,model,animation)) {
            case PLAYED -> {}
            case NO_PLUGIN -> {
                Msg.send(player,"menu.emote.unavailable");
                plugin.getLogger().warning("Emote "+model+"/"+animation+" needs the BetterModel plugin");
            }
            case NOT_FOUND -> {
                Msg.send(player,"menu.emote.unavailable");
                plugin.getLogger().warning("BetterModel has no player animation "+model+"/"+animation);
            }
        }
    }
    private <T> void edit(Player player,Setting<T> setting,String action,DialogPayload payload) {
        T current=data.get(player,setting);
        T value=switch(action) {
            case "settings_set" -> SettingEdits.set(setting,payload.get("value"));
            case "settings_toggle" -> SettingEdits.toggle(setting,current);
            default -> SettingEdits.step(setting,current,payload.integer("delta",0));
        };
        if(value==null || value.equals(current) || !data.set(player.getUniqueId(),setting,value)) return;
        // After the change, so a new cue volume is heard at its new level.
        Sounds.system(player,Sound.UI_BUTTON_CLICK,0.5f,1f);
    }
    private <T> void reset(Player player,Setting<T> setting) { data.set(player.getUniqueId(),setting,setting.defaultValue()); }
}
