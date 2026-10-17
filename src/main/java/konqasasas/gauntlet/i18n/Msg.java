package konqasasas.gauntlet.i18n;

import java.util.*;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Renders message keys for one viewer, in that viewer's interface language.
 * Arguments are named MiniMessage placeholders: Msg.arg("time","12.35s") fills <time>.
 */
public final class Msg {
    private static final MiniMessage MINI=MiniMessage.miniMessage();
    /** Language used for the console and other non-player senders. */
    public static final String CONSOLE="ja";
    private static volatile Messages messages=new Messages(Map.of(),null);
    private static volatile Function<Player,String> language=p -> Lang.fromLocale(p.locale());

    public static void init(Messages loaded,Function<Player,String> languageOf) { messages=loaded; language=languageOf; }
    public static void reload(Messages loaded) { messages=loaded; }
    public static Messages messages() { return messages; }

    public static String lang(CommandSender viewer) { return viewer instanceof Player p ? language.apply(p) : CONSOLE; }
    public static TagResolver arg(String name,Object value) { return Placeholder.unparsed(name,String.valueOf(value)); }
    public static TagResolver arg(String name,ComponentLike value) { return Placeholder.component(name,value); }

    public static Component text(CommandSender viewer,String key,TagResolver... args) { return text(lang(viewer),key,args); }
    public static Component text(String lang,String key,TagResolver... args) {
        String template=messages.template(lang,key);
        return template==null ? Component.text(key) : render(template,args);
    }
    /** One component per line of the template (a YAML list, or text with line breaks). */
    public static List<Component> lines(CommandSender viewer,String key,TagResolver... args) { return lines(lang(viewer),key,args); }
    public static List<Component> lines(String lang,String key,TagResolver... args) {
        String template=messages.template(lang,key);
        if(template==null) return List.of(Component.text(key));
        if(template.isEmpty()) return List.of();
        return Arrays.stream(template.split("\n",-1)).map(line -> render(line,args)).toList();
    }
    public static String plain(CommandSender viewer,String key,TagResolver... args) {
        return PlainTextComponentSerializer.plainText().serialize(text(viewer,key,args));
    }
    /** Chat message with the [Gauntlet] prefix. */
    public static void send(CommandSender viewer,String key,TagResolver... args) {
        viewer.sendMessage(text(viewer,"prefix").append(text(viewer,key,args)));
    }
    /** Every line of the key as its own chat message, prefixed. */
    public static void sendLines(CommandSender viewer,String key,TagResolver... args) {
        Component prefix=text(viewer,"prefix");
        for(Component line:lines(viewer,key,args)) viewer.sendMessage(prefix.append(line));
    }
    public static void actionBar(Player viewer,String key,TagResolver... args) { viewer.sendActionBar(text(viewer,key,args)); }

    /** GUI button: name from "<key>.name", lore from "<key>.lore" followed by the extra lines. */
    public static ItemStack icon(CommandSender viewer,Material material,String key,List<Component> extra,TagResolver... args) {
        ItemStack item=new ItemStack(material);
        List<Component> lore=new ArrayList<>();
        if(messages.template(lang(viewer),key+".lore")!=null) lore.addAll(lines(viewer,key+".lore",args));
        extra.forEach(line -> lore.add(line.decorationIfAbsent(TextDecoration.ITALIC,TextDecoration.State.FALSE)));
        item.editMeta(meta -> { meta.displayName(text(viewer,key+".name",args)); meta.lore(lore); });
        return item;
    }
    public static ItemStack icon(CommandSender viewer,Material material,String key,TagResolver... args) {
        return icon(viewer,material,key,List.of(),args);
    }
    private static Component render(String template,TagResolver... args) {
        return MINI.deserialize(template,TagResolver.resolver(args)).decorationIfAbsent(TextDecoration.ITALIC,TextDecoration.State.FALSE);
    }
    private Msg() {}
}
