package konqasasas.gauntlet.display;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import konqasasas.gauntlet.i18n.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * The look of names, chat, TAB and the sidebar (MiniMessage templates). The
 * bundled display.yml, overridden key by key by plugins/ParkourGauntlet/display.yml;
 * /gauntlet reload display reads both again. Immutable, so the async chat
 * renderer can use it. The web editor (web/editor/) writes the same file.
 */
public final class DisplayStyle {
    public static final int RANKS=13;
    /** A sidebar line that is replaced by the course lines during a course. */
    public static final String COURSE_BLOCK="<course_block>";
    static final List<String> KEYS=List.of("ranks.badge","ranks.styles","names.admin-tag","names.full","names.nametag","chat.format",
            "tab.header","tab.ping.good","tab.ping.ok","tab.ping.bad","sidebar.title","sidebar.lines","sidebar.course");
    private static final MiniMessage MINI=MiniMessage.miniMessage();
    private final Map<String,String> values;
    private final List<String> rankStyles;

    DisplayStyle(Map<String,String> values) {
        for(String key:KEYS) if(!values.containsKey(key)) throw new IllegalArgumentException("Missing "+key);
        rankStyles=List.of(values.get("ranks.styles").split("\n",-1));
        if(rankStyles.size()!=RANKS) throw new IllegalArgumentException("ranks.styles needs "+RANKS+" entries, found "+rankStyles.size());
        for(int i=0;i<RANKS;i++) if(!rankStyles.get(i).contains("<text>")) throw new IllegalArgumentException("ranks.styles entry "+(i+1)+" has no <text>");
        this.values=Map.copyOf(values);
    }

    /** Bundled display.yml with the override file (null: none) on top; unknown keys in the override are logged and ignored. */
    public static DisplayStyle load(ClassLoader resources,Path override,Logger logger) throws IOException {
        Map<String,String> values=new HashMap<>();
        try(InputStream in=resources.getResourceAsStream("display.yml")) {
            if(in==null) throw new FileNotFoundException("Bundled display.yml missing");
            values.putAll(Messages.parse(new InputStreamReader(in,StandardCharsets.UTF_8)));
        }
        if(override!=null && Files.isRegularFile(override)) {
            try(Reader reader=Files.newBufferedReader(override,StandardCharsets.UTF_8)) {
                Messages.parse(reader).forEach((key,value) -> {
                    if(KEYS.contains(key)) values.put(key,value);
                    else if(logger!=null) logger.warning("Unknown key in "+override.getFileName()+": "+key);
                });
            } catch(RuntimeException e) { throw new IOException("Invalid display file "+override+": "+e.getMessage(),e); }
        }
        try { return new DisplayStyle(values); }
        catch(IllegalArgumentException e) { throw new IOException("Invalid display file "+override+": "+e.getMessage(),e); }
    }

    /** Text in the rank's color or gradient (text is shown as is, never as tags). */
    public Component rank(int rank,String text) {
        return MINI.deserialize(rankStyles.get(Math.clamp(rank,1,RANKS)-1).replace("<text>",MINI.escapeTags(text)));
    }
    /** "[ 1 ]" for one digit, "[13]" for two, with the default badge. */
    public String badgeText(int rank) {
        String padded=rank<10 ? " "+rank+" " : String.valueOf(rank);
        return values.get("ranks.badge").replace("<rank_padded>",padded).replace("<rank>",String.valueOf(rank));
    }
    public Component badge(int rank) { return rank(rank,badgeText(rank)); }
    /** Chat and TAB: badge, admin tag when admin, name. */
    public Component full(Component badge,boolean admin,Component name) {
        Component tag=admin ? render("names.admin-tag") : Component.empty();
        return render("names.full",Placeholder.component("badge",badge),Placeholder.component("admin",tag),Placeholder.component("name",name));
    }
    public Component nametag(Component badge,Component name) {
        return render("names.nametag",Placeholder.component("badge",badge),Placeholder.component("name",name));
    }
    public Component chat(Component player,Component message) {
        return render("chat.format",Placeholder.component("player",player),Placeholder.component("message",message));
    }
    public Component tabHeader(String server) { return render("tab.header",Placeholder.unparsed("server",server)); }
    public Component sidebarTitle(String server) { return render("sidebar.title",Placeholder.unparsed("server",server)); }
    /** The ping number in the color for its range. */
    public Component ping(int ms,int good,int ok) {
        return render(ms<good ? "tab.ping.good" : ms<ok ? "tab.ping.ok" : "tab.ping.bad",Placeholder.unparsed("ping",String.valueOf(ms)));
    }

    /** What the sidebar shows; course is null outside a course. */
    public record SidebarValues(String player,Component badge,String playtime,String address,CourseValues course) {}
    public record CourseValues(String name,String best,int attempts) {}
    public List<Component> sidebar(SidebarValues v) {
        TagResolver args=TagResolver.resolver(
                Placeholder.unparsed("player",v.player()),Placeholder.component("badge",v.badge()),
                Placeholder.unparsed("playtime",v.playtime()),Placeholder.unparsed("address",v.address()),
                Placeholder.unparsed("course",v.course()==null ? "" : v.course().name()),
                Placeholder.unparsed("best",v.course()==null ? "" : v.course().best()),
                Placeholder.unparsed("attempts",v.course()==null ? "" : String.valueOf(v.course().attempts())));
        List<Component> out=new ArrayList<>();
        for(String line:lines("sidebar.lines")) {
            if(!line.strip().equals(COURSE_BLOCK)) out.add(MINI.deserialize(line,args));
            else if(v.course()!=null) for(String courseLine:lines("sidebar.course")) out.add(MINI.deserialize(courseLine,args));
        }
        return out;
    }

    private List<String> lines(String key) {
        String value=values.get(key);
        return value.isEmpty() ? List.of() : List.of(value.split("\n",-1));
    }
    private Component render(String key,TagResolver... args) { return MINI.deserialize(values.get(key),TagResolver.resolver(args)); }
}
