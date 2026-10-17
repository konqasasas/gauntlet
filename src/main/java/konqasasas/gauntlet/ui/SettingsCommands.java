package konqasasas.gauntlet.ui;

import java.util.*;
import konqasasas.gauntlet.chat.Languages;
import konqasasas.gauntlet.data.*;
import konqasasas.gauntlet.display.Visibility;
import konqasasas.gauntlet.i18n.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /settings and /lang: text access to personal settings until the settings
 * screen (Dialog) exists. The screen should list Settings.ALL the same way.
 */
public final class SettingsCommands implements CommandExecutor,TabCompleter {
    private final JavaPlugin plugin;
    private final PlayerData data;
    private final Visibility visibility;

    public SettingsCommands(JavaPlugin plugin,PlayerData data,Visibility visibility) { this.plugin=plugin; this.data=data; this.visibility=visibility; }
    public void start() {
        for(String name:List.of("settings","lang")) {
            PluginCommand command=Objects.requireNonNull(plugin.getCommand(name));
            command.setExecutor(this); command.setTabCompleter(this);
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        if(command.getName().equals("lang")) {
            if(args.length==1) change(player,Settings.UI_LANG,args[0].toLowerCase(Locale.ROOT));
            else Msg.send(player,"settings.lang.usage",Msg.arg("current",value(player,Settings.UI_LANG,data.get(player,Settings.UI_LANG))));
            return true;
        }
        if(args.length==2) {
            Setting<?> setting=Settings.byKey(args[0]);
            if(setting==null || !setting.menu()) { Msg.send(player,"settings.unknown",Msg.arg("setting",args[0])); return true; }
            change(player,setting,args[1]);
            return true;
        }
        Msg.send(player,"settings.header");
        for(Setting<?> setting:Settings.ALL) if(setting.menu()) player.sendMessage(line(player,setting));
        Msg.send(player,"settings.usage");
        return true;
    }
    private <T> Component line(Player player,Setting<T> setting) {
        return Msg.text(player,"settings.line",Msg.arg("setting",setting.key()),Msg.arg("name",Msg.text(player,"setting."+setting.key()+".name")),
                Msg.arg("value",value(player,setting,data.get(player,setting))),Msg.arg("desc",Msg.text(player,"setting."+setting.key()+".desc")));
    }
    private <T> void change(Player player,Setting<T> setting,String raw) {
        T value=setting.parse(raw);
        if(value==null || !data.set(player.getUniqueId(),setting,value)) {
            Msg.send(player,"settings.invalid",Msg.arg("value",raw),Msg.arg("choices",String.join(" | ",setting.choices().stream().map(setting::format).toList())));
            return;
        }
        if(setting==Settings.PLAYERS_VISIBLE) visibility.apply(player);
        // Shown in the new language when the interface language changed.
        Msg.send(player,"settings.changed",Msg.arg("name",Msg.text(player,"setting."+setting.key()+".name")),Msg.arg("value",value(player,setting,value)));
    }
    /** How a value is shown: on/off, percent, language names. */
    static <T> Component value(Player player,Setting<T> setting,T value) {
        if(value instanceof Boolean b) return Msg.text(player,b ? "setting.value.on" : "setting.value.off");
        if(setting==Settings.SOUND_SYSTEM || setting==Settings.SOUND_COURSE) return Component.text(value+"%");
        if("auto".equals(value)) return Msg.text(player,"setting.value.auto");
        if("ui".equals(value)) return Msg.text(player,"setting.value.ui");
        return Component.text(Languages.label(String.valueOf(value))+" ("+value+")");
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(command.getName().equals("lang")) return args.length==1 ? filter(Settings.UI_LANG.choices(),args[0]) : List.of();
        if(args.length==1) return filter(Settings.ALL.stream().filter(Setting::menu).map(Setting::key).toList(),args[0]);
        if(args.length==2) {
            Setting<?> setting=Settings.byKey(args[0]);
            if(setting!=null && setting.menu()) return filter(setting.choices().stream().map(String::valueOf).toList(),args[1]);
        }
        return List.of();
    }
    private static List<String> filter(List<String> options,String prefix) {
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
