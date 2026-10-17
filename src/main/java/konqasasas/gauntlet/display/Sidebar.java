package konqasasas.gauntlet.display;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.*;
import java.util.function.Function;
import konqasasas.gauntlet.data.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

/**
 * The sidebar, one scoreboard per player (always shown). Headings are English
 * only. The course part is shown only during a course, so a video shows which
 * course is being played. Each board also holds the team with every player:
 * it hides vanilla name tags (NameTags draws them instead), turns off pushing
 * between players, and keeps invisible players fully invisible to each other.
 */
public final class Sidebar implements Listener {
    /** What the course part shows; best is already formatted ("87%", "Clear ✓ 24.10s" or "—"). */
    public record CourseView(String name,String best,int attempts) {}
    private static final long UPDATE_TICKS=10;
    private static final int MAX_LINES=15;
    private static final String HIDE_TEAM="gauntlet_names";
    private final JavaPlugin plugin;
    private final PlayerDisplay display;
    private final PlayerData data;
    private final Function<Player,CourseView> course;
    private final Component title;
    private final String address;
    private final Map<UUID,Scoreboard> boards=new HashMap<>();
    private final Map<UUID,List<Component>> shown=new HashMap<>();
    private BukkitTask task;

    public Sidebar(JavaPlugin plugin,PlayerDisplay display,PlayerData data,Component title,Function<Player,CourseView> course) {
        this.plugin=plugin; this.display=display; this.data=data; this.title=title; this.course=course;
        address=plugin.getConfig().getString("server.address","play.example.net");
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        for(Player player:Bukkit.getOnlinePlayers()) create(player);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::update,1,UPDATE_TICKS);
    }
    public void stop() {
        if(task!=null) task.cancel();
        Scoreboard main=Bukkit.getScoreboardManager().getMainScoreboard();
        for(Player player:Bukkit.getOnlinePlayers()) if(boards.containsKey(player.getUniqueId())) player.setScoreboard(main);
        boards.clear(); shown.clear();
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { create(event.getPlayer()); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        String name=event.getPlayer().getName();
        boards.remove(event.getPlayer().getUniqueId()); shown.remove(event.getPlayer().getUniqueId());
        for(Scoreboard board:boards.values()) { Team team=board.getTeam(HIDE_TEAM); if(team!=null) team.removeEntry(name); }
    }
    private void create(Player player) {
        Scoreboard board=Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective=board.registerNewObjective("gauntlet",Criteria.DUMMY,title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        Team team=board.registerNewTeam(HIDE_TEAM);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY,Team.OptionStatus.NEVER);
        team.setOption(Team.Option.COLLISION_RULE,Team.OptionStatus.NEVER);
        // Teammates would otherwise see each other's invisibility as translucent.
        team.setCanSeeFriendlyInvisibles(false);
        for(Player other:Bukkit.getOnlinePlayers()) team.addEntry(other.getName());
        boards.put(player.getUniqueId(),board); shown.remove(player.getUniqueId());
        player.setScoreboard(board);
        for(Scoreboard other:boards.values()) { Team t=other.getTeam(HIDE_TEAM); if(t!=null) t.addEntry(player.getName()); }
        render(player);
    }
    private void update() { for(Player player:Bukkit.getOnlinePlayers()) render(player); }
    private void render(Player player) {
        Scoreboard board=boards.get(player.getUniqueId());
        Objective objective=board==null ? null : board.getObjective("gauntlet");
        if(objective==null) return;
        List<Component> lines=lines(player);
        List<Component> before=shown.getOrDefault(player.getUniqueId(),List.of());
        for(int i=0;i<MAX_LINES;i++) {
            // Entries starting with # are hidden from the sidebar by the client.
            String entry="line"+i;
            if(i>=lines.size()) { if(i<before.size()) board.resetScores(entry); continue; }
            if(i<before.size() && lines.get(i).equals(before.get(i))) continue;
            Score score=objective.getScore(entry);
            score.setScore(MAX_LINES-i);
            score.customName(lines.get(i));
        }
        shown.put(player.getUniqueId(),lines);
    }
    private List<Component> lines(Player player) {
        List<Component> lines=new ArrayList<>();
        lines.add(Component.empty());
        lines.add(heading("Player")); lines.add(value(Component.text(player.getName())));
        lines.add(heading("Rank")); lines.add(value(display.badge(player)));
        lines.add(heading("Playtime")); lines.add(value(Component.text(hours(data.playtimeMs(player)))));
        lines.add(Component.empty());
        CourseView view=course.apply(player);
        if(view!=null) {
            lines.add(Component.text("───────────",NamedTextColor.GRAY));
            lines.add(heading("Course")); lines.add(value(Component.text(view.name())));
            lines.add(heading("Best").append(Component.text(" "+view.best(),NamedTextColor.WHITE)));
            lines.add(heading("Attempts").append(Component.text(" "+view.attempts(),NamedTextColor.WHITE)));
            lines.add(Component.empty());
        }
        lines.add(Component.text(address,NamedTextColor.YELLOW));
        return lines;
    }
    /** Headings in a strong color: gray was hard to read on the light sidebar background. */
    private static Component heading(String text) { return Component.text(text+":",NamedTextColor.GOLD); }
    private static Component value(Component text) { return Component.text(" ").append(text.colorIfAbsent(NamedTextColor.WHITE)); }
    /** "12.5h" */
    public static String hours(long millis) { return String.format(Locale.ROOT,"%.1fh",millis/3_600_000.0); }
}
