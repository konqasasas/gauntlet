package konqasasas.gauntlet.course;

import java.util.*;
import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /module: modules, the pieces of generated courses (builders only).
 *
 *   /module                                   the module list (create, edit: the course editor)
 *   /module generate <profile> [seed]          build gen_<profile> at the test place
 *   /module play <profile> | practice <profile>  play the generated course (official / practice)
 *   /module settings                           what generation uses now
 *   /module place                              the test place: the block under your feet
 *   /module set <key> <value>                  hard-from, max-hard-streak, joint, joint-length, joint-block
 *   /module profile <name> <modules> <from> <to> | /module profile remove <name>
 *
 * Settings are written to config.yml (generator section) and used by the next generation.
 */
public final class ModuleCommands implements CommandExecutor,TabCompleter {
    private static final List<String> KEYS=List.of("hard-from","max-hard-streak","joint","joint-length","joint-block");
    private final JavaPlugin plugin;
    private final CourseEditor editor;
    private final GeneratedCourses generated;
    public ModuleCommands(JavaPlugin plugin,CourseEditor editor,GeneratedCourses generated) { this.plugin=plugin; this.editor=editor; this.generated=generated; }
    public void start() {
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("module"));
        command.setExecutor(this); command.setTabCompleter(this);
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        if(!editor.admin(player)) return true;
        if(args.length==0) { editor.openModules(player); return true; }
        switch(args[0].toLowerCase(Locale.ROOT)) {
            case "generate" -> {
                if(args.length<2 || args.length>3) { Msg.send(player,"editor.modules.usage"); return true; }
                Long seed=null;
                if(args.length==3) try { seed=Long.parseLong(args[2]); } catch(NumberFormatException e) { seed=(long)args[2].hashCode(); }
                generated.generate(player,args[1],seed);
            }
            case "play","practice" -> {
                if(args.length!=2) { Msg.send(player,"editor.modules.usage"); return true; }
                String id=args[1].toLowerCase(Locale.ROOT); if(!id.startsWith("gen_")) id="gen_"+id;
                Course course=editor.store.get(id);
                if(course==null || course.segments().isEmpty()) { Msg.send(player,"editor.modules.not-generated",Msg.arg("profile",args[1])); return true; }
                editor.participants.enter(player,id,args[0].equalsIgnoreCase("practice"));
            }
            case "settings" -> settings(player);
            case "place" -> {
                Block under=player.getLocation().subtract(0,.01,0).getBlock();
                String place=under.getWorld().getName()+" "+under.getX()+" "+under.getY()+" "+under.getZ();
                save("generator.place",place);
                Msg.send(player,"editor.modules.place-set",Msg.arg("place",place));
            }
            case "set" -> {
                if(args.length!=3 || !KEYS.contains(args[1].toLowerCase(Locale.ROOT))) { Msg.send(player,"editor.modules.usage"); return true; }
                String key=args[1].toLowerCase(Locale.ROOT),value=args[2];
                Object stored;
                switch(key) {
                    case "joint" -> {
                        if(!value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("platform")) { Msg.send(player,"editor.modules.bad-value",Msg.arg("setting",key)); return true; }
                        stored=value.toLowerCase(Locale.ROOT);
                    }
                    case "joint-block" -> {
                        try { Bukkit.createBlockData(value); } catch(IllegalArgumentException e) { Msg.send(player,"editor.modules.bad-value",Msg.arg("setting",key)); return true; }
                        stored=value;
                    }
                    default -> {
                        try { stored=Integer.parseInt(value); } catch(NumberFormatException e) { Msg.send(player,"editor.modules.bad-value",Msg.arg("setting",key)); return true; }
                        if((Integer)stored<0) { Msg.send(player,"editor.modules.bad-value",Msg.arg("setting",key)); return true; }
                    }
                }
                save("generator."+key,stored);
                Msg.send(player,"editor.modules.set",Msg.arg("setting",key),Msg.arg("value",String.valueOf(stored)));
            }
            case "profile" -> {
                if(args.length==3 && args[1].equalsIgnoreCase("remove")) {
                    save("generator.profiles."+args[2],null);
                    Msg.send(player,"editor.modules.profile-removed",Msg.arg("profile",args[2])); return true;
                }
                if(args.length!=5 || !args[1].matches("[a-z0-9_-]{1,24}")) { Msg.send(player,"editor.modules.usage"); return true; }
                int modules,from,to;
                try { modules=Integer.parseInt(args[2]); from=Integer.parseInt(args[3]); to=Integer.parseInt(args[4]); }
                catch(NumberFormatException e) { Msg.send(player,"editor.modules.usage"); return true; }
                try { new Generator.Profile(modules,from,to); } catch(IllegalArgumentException e) { Msg.send(player,"editor.generate.bad-profile",Msg.arg("profile",args[1])); return true; }
                save("generator.profiles."+args[1]+".modules",modules);
                save("generator.profiles."+args[1]+".difficulty",List.of(from,to));
                Msg.send(player,"editor.modules.profile-set",Msg.arg("profile",args[1]),Msg.arg("modules",modules),Msg.arg("from",from),Msg.arg("to",to));
            }
            default -> Msg.send(player,"editor.modules.usage");
        }
        return true;
    }
    private void save(String path,Object value) {
        plugin.getConfig().set(path,value);
        plugin.saveConfig();
    }
    private void settings(Player player) {
        ConfigurationSection g=plugin.getConfig().getConfigurationSection("generator");
        if(g==null) { Msg.send(player,"editor.generate.no-place"); return; }
        Msg.send(player,"editor.modules.settings",Msg.arg("place",g.getString("place","")),Msg.arg("hard",g.getInt("hard-from",4)),Msg.arg("streak",g.getInt("max-hard-streak",2)),
                Msg.arg("joint",g.getString("joint","none")),Msg.arg("length",g.getInt("joint-length",3)),Msg.arg("block",g.getString("joint-block","minecraft:smooth_stone")));
        ConfigurationSection profiles=g.getConfigurationSection("profiles");
        if(profiles!=null) for(String name:profiles.getKeys(false)) {
            List<Integer> range=profiles.getIntegerList(name+".difficulty");
            player.sendMessage(Msg.text(player,"editor.modules.profile-line",Msg.arg("profile",name),Msg.arg("modules",profiles.getInt(name+".modules",5)),
                    Msg.arg("from",range.isEmpty() ? 1 : range.getFirst()),Msg.arg("to",range.isEmpty() ? 5 : range.getLast())));
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> all=new ArrayList<>();
        ConfigurationSection profiles=plugin.getConfig().getConfigurationSection("generator.profiles");
        if(args.length==1) all.addAll(List.of("generate","play","practice","settings","place","set","profile"));
        else if(args.length==2 && Set.of("generate","play","practice").contains(args[0].toLowerCase(Locale.ROOT)) && profiles!=null) all.addAll(profiles.getKeys(false));
        else if(args.length==2 && args[0].equalsIgnoreCase("set")) all.addAll(KEYS);
        else if(args.length==3 && args[0].equalsIgnoreCase("set") && args[1].equalsIgnoreCase("joint")) all.addAll(List.of("none","platform"));
        else if(args.length==2 && args[0].equalsIgnoreCase("profile")) { all.add("remove"); if(profiles!=null) all.addAll(profiles.getKeys(false)); }
        else if(args.length==3 && args[0].equalsIgnoreCase("profile") && args[1].equalsIgnoreCase("remove") && profiles!=null) all.addAll(profiles.getKeys(false));
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
        return all.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
