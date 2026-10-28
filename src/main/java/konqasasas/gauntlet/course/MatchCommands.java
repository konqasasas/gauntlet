package konqasasas.gauntlet.course;

import java.util.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.Participants;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /match: the asynchronous match. A builder rolls its courses (match_1, match_2, ...) from the profiles
 * of generator.match.courses and starts it; course n+1 opens once course n was cleared, and the match
 * place shows the course being played (one at a time, until lanes): clearing course n builds course n+1
 * there and moves the player on.
 *
 *   /match                       the furthest course open to you (official)
 *   /match play <n> | practice <n>  course n (official / practice)
 *   /match list                  the courses and which are open to you
 *   /match place                 builders: the match place, the block under your feet, facing as you do
 *   /match roll | start | end    builders: choose the courses (nothing built) / open them and build course 1 / clear and close
 *   /match courses <profile>...  builders: the profiles of courses 1, 2, ... in order
 *   /match menu                  builders: the same as a chest GUI
 */
public final class MatchCommands implements CommandExecutor,TabCompleter {
    private final JavaPlugin plugin;
    private final CourseEditor editor;
    private final GeneratedCourses generated;
    private final Participants participants;
    public MatchCommands(JavaPlugin plugin,CourseEditor editor,GeneratedCourses generated,Participants participants) {
        this.plugin=plugin; this.editor=editor; this.generated=generated; this.participants=participants;
    }
    public void start() {
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("match"));
        command.setExecutor(this); command.setTabCompleter(this);
        participants.onMatchClear(this::cleared);
    }
    /** Course n cleared: on to the next one after a moment (built at the place), or done. */
    private void cleared(Player player,int n) {
        List<Course> open=generated.matchCourses();
        if(n>=open.size()) { Msg.send(player,"match.all-cleared"); return; }
        Msg.send(player,"match.next",Msg.arg("n",n+1));
        Bukkit.getScheduler().runTaskLater(plugin,() -> { if(player.isOnline()) play(player,n+1,false); },NEXT_DELAY_TICKS);
    }
    private static final long NEXT_DELAY_TICKS=40;
    /** Course n, built at the place first when another one is there. */
    private void play(Player player,int n,boolean practice) {
        List<Course> open=generated.matchCourses();
        if(n<1 || n>open.size()) { Msg.send(player,"match.no-course",Msg.arg("n",n)); return; }
        Course course=open.get(n-1);
        if(!participants.mayStart(player,course)) { Msg.send(player,"match.locked",Msg.arg("n",n),Msg.arg("before",n-1)); return; }
        if(!generated.buildMatch(player,n)) return;
        participants.enter(player,course.id(),practice);
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        String sub=args.length==0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch(sub) {
            case "" -> {
                List<Course> open=generated.matchCourses();
                if(open.isEmpty()) { Msg.send(player,"match.none"); return true; }
                int furthest=1;
                // By clears, for builders too (who may enter any course with play <n>).
                for(int i=0;i<open.size();i++) if(participants.matchReached(player,open.get(i))) furthest=i+1;
                play(player,furthest,false);
            }
            case "play","practice" -> {
                if(args.length!=2) { Msg.send(player,"match.usage"); return true; }
                int n;
                try { n=Integer.parseInt(args[1]); } catch(NumberFormatException e) { Msg.send(player,"match.usage"); return true; }
                play(player,n,sub.equals("practice"));
            }
            case "list" -> {
                List<Course> open=generated.matchCourses();
                if(open.isEmpty()) { Msg.send(player,"match.none"); return true; }
                for(int i=0;i<open.size();i++)
                    Msg.send(player,participants.matchReached(player,open.get(i)) ? "match.list-open" : "match.list-locked",Msg.arg("n",i+1));
            }
            case "roll" -> { if(editor.admin(player)) generated.rollMatch(player); }
            case "start" -> { if(editor.admin(player)) generated.startMatch(player); }
            case "place" -> {
                if(!editor.admin(player)) return true;
                Block under=player.getLocation().subtract(0,.01,0).getBlock();
                String place=under.getWorld().getName()+" "+under.getX()+" "+under.getY()+" "+under.getZ();
                plugin.getConfig().set("generator.match.place",place);
                plugin.getConfig().set("generator.match.start-yaw",Math.round(player.getLocation().getYaw()));
                plugin.saveConfig();
                Msg.send(player,"match.place-set",Msg.arg("place",place));
            }
            case "end" -> { if(editor.admin(player)) generated.endMatch(player); }
            case "menu" -> { if(editor.admin(player)) editor.generatorMenus.openMatch(player); }
            case "courses" -> {
                if(!editor.admin(player)) return true;
                if(args.length<2) { Msg.send(player,"match.usage"); return true; }
                List<String> names=new ArrayList<>(Arrays.asList(args).subList(1,args.length));
                for(String name:names) if(!plugin.getConfig().isConfigurationSection("generator.profiles."+name)) {
                    Msg.send(player,"editor.generate.no-profile",Msg.arg("profile",name)); return true;
                }
                plugin.getConfig().set("generator.match.courses",names); plugin.saveConfig();
                Msg.send(player,"match.courses-set",Msg.arg("courses",String.join(" → ",names)));
            }
            default -> Msg.send(player,"match.usage");
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        boolean admin=sender instanceof Player p && editor.admin(p);
        List<String> all=new ArrayList<>();
        if(args.length==1) {
            all.addAll(List.of("play","practice","list"));
            if(admin) all.addAll(List.of("place","roll","start","end","courses","menu"));
        } else if(args.length==2 && Set.of("play","practice").contains(args[0].toLowerCase(Locale.ROOT))) {
            for(int n=1;n<=generated.matchCourses().size();n++) all.add(String.valueOf(n));
        } else if(args.length>=2 && admin && args[0].equalsIgnoreCase("courses")) {
            var profiles=plugin.getConfig().getConfigurationSection("generator.profiles");
            if(profiles!=null) all.addAll(profiles.getKeys(false));
        }
        String last=args.length==0 ? "" : args[args.length-1].toLowerCase(Locale.ROOT);
        return all.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
