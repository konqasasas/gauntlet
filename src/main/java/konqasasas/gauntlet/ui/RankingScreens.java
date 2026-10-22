package konqasasas.gauntlet.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.*;
import java.util.logging.Level;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.data.Rankings;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.RunClock;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /ranking and the ranking screens (Dialog, also opened from the menu with the
 * custom click dcg:ranking {course:"..."} or {rank:"..."}).
 *
 * /ranking <course> | /ranking rank <n>: the top 10 and your own place. Builders also:
 * runs, void, void-run, restore, restore-run, carry, uncarry (see the usage message).
 * Rows look as the ranking_row slot's standard theme (lang ranking.row.<theme>);
 * names keep their rank colors.
 */
public final class RankingScreens implements CommandExecutor,TabCompleter {
    private static final String ADMIN="gauntlet.admin";
    private final JavaPlugin plugin;
    private final Rankings rankings;
    private final CourseStore courses;
    /** A player's name in their rank's colors, with the badge. */
    private final Function<UUID,Component> name;
    /** The theme of the ranking_row slot's standard item. */
    private final Supplier<String> rowTheme;

    public RankingScreens(JavaPlugin plugin,Rankings rankings,CourseStore courses,Function<UUID,Component> name,Supplier<String> rowTheme) {
        this.plugin=plugin; this.rankings=rankings; this.courses=courses; this.name=name; this.rowTheme=rowTheme;
    }
    public void start() {
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("ranking"));
        command.setExecutor(this); command.setTabCompleter(this);
    }

    // ---- Screens ----
    public void openCourse(Player player,String id) {
        Course course=courses.get(id);
        if(course==null || (!player.hasPermission(ADMIN) && course.status()!=CourseStatus.DONE)) { Msg.send(player,"play.enter.not-found",Msg.arg("id",id)); return; }
        async(player,() -> rankings.course(course.id(),course.rev(),player.getUniqueId()),board -> {
            Component title=Msg.text(player,"ranking.course.title",Msg.arg("course",course.name()));
            List<Component> lines=new ArrayList<>();
            if(board.revs().size()>1) lines.add(Msg.text(player,"ranking.course.carried",Msg.arg("revs",board.revs().stream().map(String::valueOf).reduce((a,b) -> a+", "+b).orElse(""))));
            for(Rankings.Row row:board.top()) lines.add(row(player,row,time(player,row.ticks())));
            if(board.top().isEmpty()) lines.add(Msg.text(player,"ranking.empty"));
            lines.add(board.mine()==null ? Msg.text(player,"ranking.mine-none")
                    : Msg.text(player,"ranking.mine",Msg.arg("place",board.mine().place()),Msg.arg("entries",board.entries()),Msg.arg("value",time(player,board.mine().ticks()))));
            show(player,title,lines);
        });
    }
    public void openRank(Player player,int rank) {
        if(rank<1 || rank>11) { Msg.send(player,"ranking.usage"); return; }
        async(player,() -> rankings.rank(rank,player.getUniqueId()),board -> {
            SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd HH:mm");
            Component title=Msg.text(player,"ranking.rank.title",Msg.arg("rank",rank));
            List<Component> lines=new ArrayList<>();
            for(Rankings.Row row:board.top()) lines.add(row(player,row,Component.text(date.format(new Date(row.at())))));
            if(board.top().isEmpty()) lines.add(Msg.text(player,"ranking.empty"));
            lines.add(board.mine()==null ? Msg.text(player,"ranking.mine-none")
                    : Msg.text(player,"ranking.mine",Msg.arg("place",board.mine().place()),Msg.arg("entries",board.entries()),Msg.arg("value",date.format(new Date(board.mine().at())))));
            show(player,title,lines);
        });
    }
    /** One row, in the ranking_row slot's standard look. */
    private Component row(Player viewer,Rankings.Row row,Component value) {
        String key="ranking.row."+rowTheme.get();
        if(!Msg.messages().has(key)) key="ranking.row.standard";
        return Msg.text(viewer,key,Msg.arg("place",row.place()),Msg.arg("name",name.apply(row.player())),Msg.arg("value",value));
    }
    private static Component time(Player player,long ticks) { return Msg.text(player,"time.seconds",Msg.arg("value",RunClock.seconds(ticks))); }
    private void show(Player player,Component title,List<Component> lines) {
        DialogBase base=DialogBase.builder(title).canCloseWithEscape(true)
                .body(List.of(DialogBody.plainMessage(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(),lines),300))).build();
        player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.notice())));
    }
    private interface Read<T> { T get() throws IOException; }
    /** Reads off the main thread, answers on it. */
    private <T> void async(Player player,Read<T> read,Consumer<T> then) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> {
            try {
                T value=read.get();
                Bukkit.getScheduler().runTask(plugin,() -> { if(player.isOnline()) then.accept(value); });
            } catch(IOException|RuntimeException e) {
                plugin.getLogger().log(Level.WARNING,"Cannot read the ranking",e);
                Bukkit.getScheduler().runTask(plugin,() -> Msg.send(player,"ranking.read-failed"));
            }
        });
    }

    // ---- Command ----
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        if(args.length==1) { openCourse(player,args[0].toLowerCase(Locale.ROOT)); return true; }
        if(args.length==2 && args[0].equalsIgnoreCase("rank")) {
            try { openRank(player,Integer.parseInt(args[1])); } catch(NumberFormatException e) { Msg.send(player,"ranking.usage"); }
            return true;
        }
        if(args.length>=1 && player.hasPermission(ADMIN) && admin(player,args)) return true;
        Msg.send(player,player.hasPermission(ADMIN) ? "editor.ranking.usage-admin" : "ranking.usage");
        return true;
    }
    /** Builders: runs, void, void-run, restore, restore-run, carry, uncarry. False when it is none of these. */
    private boolean admin(Player player,String[] args) {
        String sub=args[0].toLowerCase(Locale.ROOT);
        String reason=args.length>3 ? String.join(" ",Arrays.copyOfRange(args,3,args.length)) : null;
        switch(sub) {
            case "runs","void","restore" -> {
                if(args.length<3 || (!sub.equals("void") && args.length!=3)) return false;
                OfflinePlayer target=Bukkit.getOfflinePlayerIfCached(args[1]);
                if(target==null) { Msg.send(player,"editor.rank.unknown",Msg.arg("player",args[1])); return true; }
                String course=args[2].toLowerCase(Locale.ROOT);
                if(sub.equals("runs")) async(player,() -> rankings.runs(target.getUniqueId(),course),runs -> listRuns(player,target,course,runs));
                else if(sub.equals("void")) async(player,() -> rankings.voidPlayer(target.getUniqueId(),course,player.getUniqueId(),reason),n -> Msg.send(player,"editor.ranking.voided",Msg.arg("count",n)));
                else async(player,() -> rankings.restorePlayer(target.getUniqueId(),course),n -> Msg.send(player,"editor.ranking.restored",Msg.arg("count",n)));
                return true;
            }
            case "void-run","restore-run" -> {
                if(args.length<2 || args[1].length()<8) return false;
                String id=args[1]; String why=args.length>2 ? String.join(" ",Arrays.copyOfRange(args,2,args.length)) : null;
                if(sub.equals("void-run")) async(player,() -> rankings.voidRun(id,player.getUniqueId(),why),n -> Msg.send(player,"editor.ranking.voided",Msg.arg("count",n)));
                else async(player,() -> rankings.restoreRun(id),n -> Msg.send(player,"editor.ranking.restored",Msg.arg("count",n)));
                return true;
            }
            case "carry","uncarry" -> {
                if(args.length!=2) return false;
                Course course=courses.get(args[1].toLowerCase(Locale.ROOT));
                if(course==null) { Msg.send(player,"play.enter.not-found",Msg.arg("id",args[1])); return true; }
                if(sub.equals("carry") && course.rev()==0) { Msg.send(player,"editor.ranking.carry-first"); return true; }
                Integer from=sub.equals("carry") ? course.rev()-1 : null;
                async(player,() -> { rankings.carry(course.id(),course.rev(),from,player.getUniqueId()); return 0; },
                        n -> Msg.send(player,from==null ? "editor.ranking.uncarried" : "editor.ranking.carried",Msg.arg("course",course.name()),Msg.arg("rev",course.rev()),Msg.arg("from",from==null ? 0 : from)));
                return true;
            }
            default -> { return false; }
        }
    }
    private void listRuns(Player player,OfflinePlayer target,String course,List<Rankings.Run> runs) {
        Msg.send(player,"editor.ranking.runs",Msg.arg("player",String.valueOf(target.getName())),Msg.arg("course",course),Msg.arg("count",runs.size()));
        SimpleDateFormat date=new SimpleDateFormat("MM-dd HH:mm");
        for(Rankings.Run r:runs.subList(0,Math.min(runs.size(),20)))
            player.sendMessage(Msg.text(player,r.voided() ? "editor.ranking.run-voided" : "editor.ranking.run",Msg.arg("id",r.id().substring(0,8)),Msg.arg("rev",r.rev()),
                    Msg.arg("time",time(player,r.ticks())),Msg.arg("at",date.format(new Date(r.at()))),Msg.arg("reason",r.reason()==null ? "-" : r.reason())));
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        boolean admin=sender.hasPermission(ADMIN);
        List<String> all=new ArrayList<>();
        if(args.length==1) {
            courses.all().stream().filter(c -> admin || c.status()==CourseStatus.DONE).map(Course::id).forEach(all::add);
            all.add("rank");
            if(admin) all.addAll(List.of("runs","void","void-run","restore","restore-run","carry","uncarry"));
        } else if(args.length==2 && Set.of("runs","void","restore").contains(args[0].toLowerCase(Locale.ROOT)) && admin) {
            Bukkit.getOnlinePlayers().forEach(p -> all.add(p.getName()));
        } else if((args.length==3 && Set.of("runs","void","restore").contains(args[0].toLowerCase(Locale.ROOT)) && admin)
                || (args.length==2 && Set.of("carry","uncarry").contains(args[0].toLowerCase(Locale.ROOT)) && admin)) {
            courses.all().forEach(c -> all.add(c.id()));
        }
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
        return all.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
