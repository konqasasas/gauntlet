package konqasasas.gauntlet.course;

import java.util.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.Menu;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Chest GUIs for the generator's settings (config.yml, generator section), from the module list:
 * the shared settings and the profiles, a profile (modules, distribution, bumpiness, bands,
 * and generating or playing it), and a band. Numbers go up with a left click and down with a
 * right click (with Shift, by more). Everything is saved at once and used by the next generation.
 */
public final class GeneratorMenus {
    private static final int MAX_PROFILES=18,MAX_BANDS=8,SAMPLES=4,MAX_MATCH_COURSES=18;
    private static final Material[] LEVEL_ICONS={Material.LIME_CONCRETE,Material.YELLOW_CONCRETE,Material.ORANGE_CONCRETE,Material.RED_CONCRETE,Material.PURPLE_CONCRETE};
    private final JavaPlugin plugin;
    private final CourseEditor editor;
    private final GeneratedCourses generated;
    public GeneratorMenus(JavaPlugin plugin,CourseEditor editor,GeneratedCourses generated) { this.plugin=plugin; this.editor=editor; this.generated=generated; }

    private ConfigurationSection config() {
        ConfigurationSection g=plugin.getConfig().getConfigurationSection("generator");
        return g!=null ? g : plugin.getConfig().createSection("generator");
    }
    private void save(String path,Object value) { plugin.getConfig().set("generator."+path,value); plugin.saveConfig(); }
    /** Ticks typed as 247 or as seconds 12.35s; -1 when malformed. */
    static int parseTicks(String text) {
        String t=text.strip().toLowerCase(Locale.ROOT);
        try {
            return t.endsWith("s") || t.endsWith("秒") ? (int)Math.round(Double.parseDouble(t.replace("秒","").replace("s","").strip())*20)
                    : Integer.parseInt(t.replace("t","").strip());
        } catch(NumberFormatException e) { return -1; }
    }
    /** +1 / -1 by left / right click, times big with Shift. */
    private static int step(InventoryClickEvent e,int big) { return (e.isRightClick() ? -1 : 1)*(e.isShiftClick() ? big : 1); }

    // ---- Settings ----
    public void openSettings(Player player) {
        if(!editor.admin(player)) return;
        ConfigurationSection g=config();
        Menu menu=new Menu(4,Msg.plain(player,"editor.generator.title"));
        menu.button(0,Msg.icon(player,Material.COMPASS,"editor.generator.place",Msg.arg("place",g.getString("place","—"))),e -> {
            Block under=player.getLocation().subtract(0,.01,0).getBlock();
            save("place",under.getWorld().getName()+" "+under.getX()+" "+under.getY()+" "+under.getZ());
            save("start-yaw",Math.round(player.getLocation().getYaw()));
            openSettings(player);
        });
        String offset=g.getString("start-offset","0 0 0");
        menu.button(1,Msg.icon(player,Material.LEAD,"editor.generator.offset",Msg.arg("value",offset)),
                e -> editor.prompts.ask(player,"editor.prompt.start-offset",text -> {
                    BlockPos d=GeneratedCourses.parseOffset(text);
                    if(d==null) { editor.tell(player,"editor.generate.bad-offset"); return; }
                    save("start-offset",d.x()+" "+d.y()+" "+d.z());
                    openSettings(player);
                }));
        int startTicks=Math.max(0,g.getInt("start-ticks",0));
        menu.button(4,Msg.icon(player,Material.CLOCK,"editor.generator.start-ticks",Msg.arg("time",konqasasas.gauntlet.play.RunClock.seconds(startTicks)),Msg.arg("ticks",startTicks)),e -> {
            if(e.getClick()==org.bukkit.event.inventory.ClickType.DROP || e.getClick()==org.bukkit.event.inventory.ClickType.CONTROL_DROP) {
                editor.prompts.ask(player,"editor.prompt.start-ticks",text -> {
                    int ticks=parseTicks(text);
                    if(ticks<0 || ticks>Module.MAX_TICKS) { editor.tell(player,"editor.generator.start-ticks-invalid",Msg.arg("max",Module.MAX_TICKS)); return; }
                    save("start-ticks",ticks); openSettings(player);
                });
                return;
            }
            save("start-ticks",Math.clamp(startTicks+(e.isShiftClick() ? 1 : 20)*(e.isRightClick() ? -1 : 1),0,Module.MAX_TICKS)); openSettings(player);
        });
        int hard=g.getInt("hard-from",4),streak=g.getInt("max-hard-streak",2);
        menu.button(2,Msg.icon(player,Material.BLAZE_POWDER,"editor.generator.hard-from",Msg.arg("value",hard)),e -> {
            save("hard-from",Math.clamp(hard+step(e,1),Module.MIN_DIFFICULTY,Module.MAX_DIFFICULTY)); openSettings(player);
        });
        menu.button(3,Msg.icon(player,Material.IRON_BARS,"editor.generator.streak",Msg.arg("value",streak)),e -> {
            save("max-hard-streak",Math.clamp(streak+step(e,1),0,99)); openSettings(player);
        });
        boolean joint=g.getString("joint","none").equalsIgnoreCase("platform");
        int length=g.getInt("joint-length",3);
        String block=g.getString("joint-block","minecraft:smooth_stone");
        menu.button(5,Msg.icon(player,joint ? Material.LIME_DYE : Material.GRAY_DYE,"editor.generator.joint",
                Msg.arg("value",Msg.plain(player,joint ? "editor.generator.on" : "editor.generator.off"))),e -> {
            save("joint",joint ? "none" : "platform"); openSettings(player);
        });
        menu.button(6,Msg.icon(player,Material.RAIL,"editor.generator.joint-length",Msg.arg("value",length)),e -> {
            save("joint-length",Math.clamp(length+step(e,5),1,64)); openSettings(player);
        });
        Material shown;
        try { shown=Bukkit.createBlockData(block).getMaterial(); } catch(IllegalArgumentException ex) { shown=Material.BARRIER; }
        menu.button(7,Msg.icon(player,shown.isItem() ? shown : Material.BARRIER,"editor.generator.joint-block",Msg.arg("value",block)),e -> {
            ItemStack hand=e.getCursor().getType().isAir() ? player.getInventory().getItemInMainHand() : e.getCursor();
            if(!hand.getType().isBlock() || hand.getType().isAir()) { editor.tell(player,"editor.generator.need-block"); return; }
            save("joint-block",hand.getType().createBlockData().getAsString());
            openSettings(player);
        });
        menu.button(8,Msg.icon(player,Material.GOLDEN_HELMET,"editor.generator.match",Msg.arg("count",g.getStringList("match.courses").size())),e -> openMatch(player));
        List<String> names=profileNames();
        for(int i=0;i<Math.min(names.size(),MAX_PROFILES);i++) {
            String name=names.get(i);
            ConfigurationSection p=g.getConfigurationSection("profiles."+name);
            List<Component> lore=new ArrayList<>();
            try { lore.addAll(summary(player,GeneratedCourses.profile(p))); }
            catch(RuntimeException ex) { lore.add(Msg.text(player,"editor.generator.profile-broken")); }
            menu.button(9+i,Msg.icon(player,Material.MAP,"editor.generator.profile",lore,Msg.arg("profile",name)),e -> openProfile(player,name));
        }
        if(names.size()<MAX_PROFILES) menu.button(9+names.size(),Msg.icon(player,Material.WRITABLE_BOOK,"editor.generator.new-profile"),e -> askNewProfile(player));
        menu.button(27,Msg.icon(player,Material.ARROW,"editor.modules.back"),e -> editor.openModules(player));
        menu.label(31,Msg.icon(player,Material.COMPARATOR,"editor.modules.counts",editor.difficultyCounts(player)));
        menu.label(35,Msg.icon(player,Material.BOOK,"editor.generator.help"));
        menu.open(player);
    }
    private List<String> profileNames() {
        ConfigurationSection profiles=config().getConfigurationSection("profiles");
        if(profiles==null) return List.of();
        return profiles.getKeys(false).stream().sorted(NaturalOrder::compare).toList();
    }
    private void askNewProfile(Player player) {
        editor.prompts.ask(player,"editor.generator.prompt-profile",text -> {
            if(!editor.admin(player)) return;
            String name=text.toLowerCase(Locale.ROOT);
            if(!name.matches("[a-z0-9_-]{1,24}") || config().isConfigurationSection("profiles."+name)) { editor.tell(player,"editor.generator.bad-name",Msg.arg("text",text)); return; }
            write(name,new Generator.Profile(5,List.of(3.0,3.0,2.0,1.0,1.0),1));
            openProfile(player,name);
        });
    }

    // ---- A profile ----
    private Generator.Profile read(Player player,String name) {
        ConfigurationSection p=config().getConfigurationSection("profiles."+name);
        if(p==null) { editor.tell(player,"editor.generate.no-profile",Msg.arg("profile",name)); return null; }
        try { return GeneratedCourses.profile(p); }
        catch(RuntimeException e) { editor.tell(player,"editor.generate.bad-profile",Msg.arg("profile",name)); return null; }
    }
    /** Writes the whole profile (also turns an old difficulty: [from, to] profile into a distribution). */
    private void write(String name,Generator.Profile profile) {
        String path="generator.profiles."+name;
        plugin.getConfig().set(path+".difficulty",null);
        plugin.getConfig().set(path+".modules",profile.modules());
        plugin.getConfig().set(path+".distribution",profile.weights());
        plugin.getConfig().set(path+".bumpiness",profile.bumpiness());
        plugin.getConfig().set(path+".bands",profile.bands().isEmpty() ? null : profile.bands().stream().map(Generator.Band::text).toList());
        plugin.saveConfig();
    }
    /** Changes the profile, saves it and opens it again; a change that makes it invalid is ignored. */
    private void change(Player player,String name,java.util.function.UnaryOperator<Generator.Profile> how) {
        change(player,name,how,() -> openProfile(player,name));
    }
    private void change(Player player,String name,java.util.function.UnaryOperator<Generator.Profile> how,Runnable reopen) {
        if(!editor.admin(player)) return;
        Generator.Profile p=read(player,name);
        if(p==null) { openSettings(player); return; }
        try { write(name,how.apply(p)); } catch(IllegalArgumentException ignored) {}
        reopen.run();
    }
    void openProfile(Player player,String name) {
        if(!editor.admin(player)) return;
        Generator.Profile p=read(player,name);
        if(p==null) { openSettings(player); return; }
        Menu menu=new Menu(5,Msg.plain(player,"editor.generator.profile-title",Msg.arg("profile",name)));
        menu.label(0,Msg.icon(player,Material.MAP,"editor.generator.profile",summary(player,p),Msg.arg("profile",name)));
        menu.button(2,Msg.icon(player,Material.CHEST,"editor.generator.modules",Msg.arg("value",p.modules())),
                e -> change(player,name,q -> new Generator.Profile(Math.clamp(q.modules()+step(e,5),1,200),q.weights(),q.bumpiness(),q.bands())));
        menu.button(4,Msg.icon(player,Material.SLIME_BALL,"editor.generator.bumpiness",Msg.arg("value",p.bumpiness())),
                e -> change(player,name,q -> new Generator.Profile(q.modules(),q.weights(),Math.clamp(q.bumpiness()+step(e,4)*.5,0,20),q.bands())));
        menu.button(6,Msg.icon(player,Material.SPYGLASS,"editor.generator.samples",samples(player,p)),e -> openProfile(player,name));
        ConfigurationSection section=config().getConfigurationSection("profiles."+name);
        boolean own=section!=null && section.contains("buffer-percent");
        int buffer=(int)Math.round(own ? section.getDouble("buffer-percent") : config().getDouble("buffer-percent",10));
        menu.button(8,Msg.icon(player,Material.HONEY_BOTTLE,own ? "editor.generator.buffer" : "editor.generator.buffer-default",Msg.arg("value",buffer)),e -> {
            if(!editor.admin(player)) return;
            if(e.getClick()==org.bukkit.event.inventory.ClickType.DROP || e.getClick()==org.bukkit.event.inventory.ClickType.CONTROL_DROP) save("profiles."+name+".buffer-percent",null);
            else save("profiles."+name+".buffer-percent",Math.clamp(buffer+step(e,10),0,500));
            openProfile(player,name);
        });
        double total=p.weights().stream().mapToDouble(Double::doubleValue).sum();
        for(int l=0;l<Generator.Profile.LEVELS;l++) {
            int level=l;
            double w=p.weights().get(l);
            ItemStack icon=Msg.icon(player,LEVEL_ICONS[l],"editor.generator.weight",Msg.arg("difficulty",l+Module.MIN_DIFFICULTY),
                    Msg.arg("weight",number(w)),Msg.arg("percent",Math.round(w*100/total)));
            icon.setAmount(Math.clamp((int)Math.round(w),1,64));
            menu.button(11+l,icon,e -> change(player,name,q -> {
                List<Double> ws=new ArrayList<>(q.weights());
                ws.set(level,Math.max(0,ws.get(level)+step(e,5)));
                return new Generator.Profile(q.modules(),ws,q.bumpiness(),q.bands());
            }));
        }
        for(int i=0;i<Math.min(p.bands().size(),MAX_BANDS);i++) {
            int index=i;
            menu.button(18+i,Msg.icon(player,Material.NAME_TAG,"editor.generator.band",Msg.arg("band",describe(player,p.bands().get(i)))),e -> openBand(player,name,index));
        }
        if(p.bands().size()<MAX_BANDS) menu.button(18+p.bands().size(),Msg.icon(player,Material.WRITABLE_BOOK,"editor.generator.new-band"),e -> change(player,name,q -> {
            List<Generator.Band> bands=new ArrayList<>(q.bands());
            bands.add(new Generator.Band(4,5,0,q.modules()));
            return new Generator.Profile(q.modules(),q.weights(),q.bumpiness(),bands);
        }));
        menu.button(36,Msg.icon(player,Material.ARROW,"editor.generator.back"),e -> openSettings(player));
        menu.button(38,Msg.icon(player,Material.EMERALD,"editor.generator.generate",Msg.arg("profile",name)),e -> {
            player.closeInventory(); generated.generate(player,name,null);
        });
        menu.button(39,Msg.icon(player,Material.WHEAT_SEEDS,"editor.generator.generate-seed"),e -> editor.prompts.ask(player,"editor.generator.prompt-seed",text -> {
            if(!editor.admin(player)) return;
            long seed;
            try { seed=Long.parseLong(text); } catch(NumberFormatException ex) { seed=text.hashCode(); }
            generated.generate(player,name,seed);
        }));
        menu.button(40,Msg.icon(player,Material.SPYGLASS,"editor.generator.measure",Msg.arg("passes",editor.autoPasses())),e -> {
            player.closeInventory(); generated.generate(player,name,null,true);
        });
        menu.button(41,Msg.icon(player,Material.FEATHER,"editor.generator.play"),e -> play(player,name,false));
        menu.button(42,Msg.icon(player,Material.CLOCK,"editor.generator.practice"),e -> play(player,name,true));
        menu.button(44,Msg.icon(player,Material.LAVA_BUCKET,"editor.generator.delete",Msg.arg("profile",name)),e -> {
            if(!e.isShiftClick() || !editor.admin(player)) return;
            save("profiles."+name,null);
            editor.tell(player,"editor.modules.profile-removed",Msg.arg("profile",name));
            openSettings(player);
        });
        menu.open(player);
    }
    // ---- Match: which profiles become courses 1, 2, ... ----
    void openMatch(Player player) {
        if(!editor.admin(player)) return;
        List<String> order=new ArrayList<>(config().getStringList("match.courses"));
        List<String> names=profileNames();
        Menu menu=new Menu(3,Msg.plain(player,"editor.match.title"));
        for(int i=0;i<Math.min(order.size(),MAX_MATCH_COURSES);i++) {
            int index=i; String name=order.get(i);
            ItemStack icon=Msg.icon(player,Material.FILLED_MAP,"editor.match.course",Msg.arg("n",i+1),Msg.arg("profile",name));
            icon.setAmount(i+1);
            menu.button(i,icon,e -> {
                List<String> next=new ArrayList<>(config().getStringList("match.courses"));
                if(index>=next.size()) { openMatch(player); return; }
                if(e.isShiftClick()) next.remove(index);
                else if(!names.isEmpty()) {
                    int at=names.indexOf(next.get(index));
                    next.set(index,names.get(Math.floorMod(at+(e.isRightClick() ? -1 : 1),names.size())));
                }
                save("match.courses",next); openMatch(player);
            });
        }
        if(order.size()<MAX_MATCH_COURSES && !names.isEmpty()) menu.button(Math.min(order.size(),MAX_MATCH_COURSES-1),Msg.icon(player,Material.WRITABLE_BOOK,"editor.match.add"),e -> {
            List<String> next=new ArrayList<>(config().getStringList("match.courses"));
            next.add(next.isEmpty() ? names.getFirst() : next.getLast());
            save("match.courses",next); openMatch(player);
        });
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.generator.back"),e -> openSettings(player));
        String place=config().getString("match.place","");
        menu.button(19,Msg.icon(player,Material.COMPASS,"editor.match.place",Msg.arg("place",place.isBlank() ? Msg.plain(player,"editor.match.no-place") : place)),e -> {
            player.closeInventory(); player.performCommand("match place");
        });
        menu.button(21,Msg.icon(player,Material.ENDER_EYE,"editor.match.roll",Msg.arg("rolled",config().getStringList("match.rolled").size())),e -> {
            if(!e.isShiftClick() || !editor.admin(player)) return;
            player.closeInventory(); generated.rollMatch(player);
        });
        menu.button(22,Msg.icon(player,Material.EMERALD_BLOCK,"editor.match.start",Msg.arg("count",config().getStringList("match.rolled").size())),e -> {
            if(!e.isShiftClick() || !editor.admin(player)) return;
            player.closeInventory(); generated.startMatch(player);
        });
        menu.button(24,Msg.icon(player,Material.REDSTONE_BLOCK,"editor.match.end",Msg.arg("open",generated.matchCourses().size())),e -> {
            if(!e.isShiftClick() || !editor.admin(player)) return;
            player.closeInventory(); generated.endMatch(player);
        });
        menu.label(26,Msg.icon(player,Material.BOOK,"editor.match.help"));
        menu.open(player);
    }
    private void play(Player player,String name,boolean practice) {
        String id="gen_"+name;
        Course course=editor.store.get(id);
        player.closeInventory();
        if(course==null || course.segments().isEmpty()) { editor.tell(player,"editor.modules.not-generated",Msg.arg("profile",name)); return; }
        editor.participants.enter(player,id,practice);
    }

    // ---- A band ----
    private void changeBand(Player player,String name,int index,java.util.function.UnaryOperator<Generator.Band> how) {
        change(player,name,q -> {
            if(index>=q.bands().size()) return q;
            List<Generator.Band> bands=new ArrayList<>(q.bands());
            bands.set(index,how.apply(bands.get(index)));
            return new Generator.Profile(q.modules(),q.weights(),q.bumpiness(),bands);
        },() -> openBand(player,name,index));
    }
    void openBand(Player player,String name,int index) {
        if(!editor.admin(player)) return;
        Generator.Profile p=read(player,name);
        if(p==null) { openSettings(player); return; }
        if(index>=p.bands().size()) { openProfile(player,name); return; }
        Generator.Band b=p.bands().get(index);
        int lo=Module.MIN_DIFFICULTY,hi=Module.MAX_DIFFICULTY;
        Menu menu=new Menu(3,Msg.plain(player,"editor.generator.band-title",Msg.arg("profile",name)));
        menu.label(4,Msg.icon(player,Material.NAME_TAG,"editor.generator.band",Msg.arg("band",describe(player,b))));
        menu.button(10,Msg.icon(player,LEVEL_ICONS[b.from()-lo],"editor.generator.band-from",Msg.arg("value",b.from())),e -> changeBand(player,name,index,x -> {
            int from=Math.clamp(x.from()+step(e,1),lo,hi);
            return new Generator.Band(from,Math.max(from,x.to()),x.min(),x.max());
        }));
        menu.button(11,Msg.icon(player,LEVEL_ICONS[b.to()-lo],"editor.generator.band-to",Msg.arg("value",b.to())),e -> changeBand(player,name,index,x -> {
            int to=Math.clamp(x.to()+step(e,1),lo,hi);
            return new Generator.Band(Math.min(x.from(),to),to,x.min(),x.max());
        }));
        menu.button(15,Msg.icon(player,Material.IRON_INGOT,"editor.generator.band-min",Msg.arg("value",b.min())),e -> changeBand(player,name,index,x -> {
            int min=Math.clamp(x.min()+step(e,5),0,200);
            return new Generator.Band(x.from(),x.to(),min,Math.max(min,x.max()));
        }));
        menu.button(16,Msg.icon(player,Material.GOLD_INGOT,"editor.generator.band-max",Msg.arg("value",b.max())),e -> changeBand(player,name,index,x -> {
            int max=Math.clamp(x.max()+step(e,5),0,200);
            return new Generator.Band(x.from(),x.to(),Math.min(x.min(),max),max);
        }));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.generator.back-profile"),e -> openProfile(player,name));
        menu.button(26,Msg.icon(player,Material.LAVA_BUCKET,"editor.generator.band-delete"),e -> change(player,name,q -> {
            List<Generator.Band> bands=new ArrayList<>(q.bands());
            if(index<bands.size()) bands.remove(index);
            return new Generator.Profile(q.modules(),q.weights(),q.bumpiness(),bands);
        }));
        menu.open(player);
    }

    // ---- Text ----
    /** "難易度4〜5を1〜2個" */
    static String describe(org.bukkit.command.CommandSender player,Generator.Band band) {
        String levels=band.from()==band.to() ? String.valueOf(band.from()) : band.from()+"〜"+band.to();
        String count=band.min()==band.max() ? String.valueOf(band.min()) : band.min()+"〜"+band.max();
        return Msg.plain(player,"editor.generator.band-text",Msg.arg("levels",levels),Msg.arg("count",count));
    }
    private List<Component> summary(Player player,Generator.Profile p) {
        double total=p.weights().stream().mapToDouble(Double::doubleValue).sum();
        List<String> parts=new ArrayList<>();
        for(int i=0;i<p.weights().size();i++) parts.add((i+Module.MIN_DIFFICULTY)+":"+Math.round(p.weights().get(i)*100/total)+"%");
        List<Component> lines=new ArrayList<>(List.of(
                Msg.text(player,"editor.generator.summary",Msg.arg("modules",p.modules()),Msg.arg("bumpiness",number(p.bumpiness()))),
                Msg.text(player,"editor.generator.summary-distribution",Msg.arg("distribution",String.join(" ",parts)))));
        for(Generator.Band b:p.bands()) lines.add(Msg.text(player,"editor.generator.summary-band",Msg.arg("band",describe(player,b))));
        return lines;
    }
    /** A few drawn difficulty orders, or why the bands cannot hold. */
    private List<Component> samples(Player player,Generator.Profile p) {
        List<Component> lines=new ArrayList<>();
        Random random=new Random();
        for(int i=0;i<SAMPLES;i++) {
            try {
                List<Integer> t=p.targets(random);
                lines.add(Msg.text(player,"editor.generator.sample",Msg.arg("order",String.join(" ",t.stream().map(String::valueOf).toList()))));
            } catch(Generator.BandConflict e) {
                return List.of(Msg.text(player,"editor.generator.conflict",Msg.arg("band",describe(player,e.band))));
            }
        }
        return lines;
    }
    private static String number(double value) { return value==Math.rint(value) ? String.valueOf((long)value) : String.valueOf(value); }
}
