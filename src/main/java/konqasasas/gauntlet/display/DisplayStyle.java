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
    public static final int RANKS=11;
    /** A sidebar line that is replaced by the course lines during a course. */
    public static final String COURSE_BLOCK="<course_block>";
    static final List<String> KEYS=List.of("ranks.badge","ranks.styles","ranks.zero-badge","ranks.zero-style","names.admin-tag","names.full","names.nametag","chat.format",
            "tab.header","tab.ping.good","tab.ping.ok","tab.ping.bad","sidebar.title","sidebar.lines","sidebar.course","motd");
    /** The server list shows two lines of description. */
    public static final int MOTD_LINES=2;
    private static final MiniMessage MINI=MiniMessage.miniMessage();
    private final Map<String,String> values;
    private final List<String> rankStyles;

    DisplayStyle(Map<String,String> values) {
        for(String key:KEYS) if(!values.containsKey(key)) throw new IllegalArgumentException("Missing "+key);
        rankStyles=List.of(values.get("ranks.styles").split("\n",-1));
        if(rankStyles.size()!=RANKS) throw new IllegalArgumentException("ranks.styles needs "+RANKS+" entries, found "+rankStyles.size());
        for(int i=0;i<RANKS;i++) if(!rankStyles.get(i).contains("<text>")) throw new IllegalArgumentException("ranks.styles entry "+(i+1)+" has no <text>");
        if(!values.get("ranks.zero-style").contains("<text>")) throw new IllegalArgumentException("ranks.zero-style has no <text>");
        this.values=Map.copyOf(values);
        if(lines("motd").size()>MOTD_LINES) throw new IllegalArgumentException("motd has "+lines("motd").size()+" lines; the server list shows "+MOTD_LINES);
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

    /** Text in the rank's color or gradient (text is shown as is, never as tags). Rank 0 uses ranks.zero-style. */
    public Component rank(int rank,String text) {
        String style=rank<=0 ? values.get("ranks.zero-style") : rankStyles.get(Math.min(rank,RANKS)-1);
        return MINI.deserialize(style.replace("<text>",MINI.escapeTags(text)));
    }
    /** "VII" with the default badge; "[<rank_padded>]" gives "[ 7 ]" and "[11]". Rank 0 shows ranks.zero-badge. */
    public String badgeText(int rank) {
        if(rank<=0) return values.get("ranks.zero-badge");
        String padded=rank<10 ? " "+rank+" " : String.valueOf(rank);
        return values.get("ranks.badge").replace("<rank_roman>",roman(rank)).replace("<rank_padded>",padded).replace("<rank>",String.valueOf(rank));
    }
    /** Roman numerals in ASCII letters (the game font has them; the Unicode numerals may fall back to another font). */
    static String roman(int n) {
        StringBuilder out=new StringBuilder();
        int[] values={1000,900,500,400,100,90,50,40,10,9,5,4,1};
        String[] letters={"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        for(int i=0;i<values.length;i++) for(;n>=values[i];n-=values[i]) out.append(letters[i]);
        return out.toString();
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

    /** The server list description, or null to keep server.properties' motd (an empty motd list). */
    public Component motd(String server,int online,int max) {
        if(values.get("motd").isEmpty()) return null;
        return render("motd",Placeholder.unparsed("server",server),
                Placeholder.unparsed("online",String.valueOf(online)),Placeholder.unparsed("max",String.valueOf(max)));
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
