package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import konqasasas.gauntlet.GroundGeometry;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.*;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * /course: GUI for creating and editing course definitions. Builders only.
 * Texts are the editor.* message keys (Japanese only for now).
 */
public final class CourseEditor implements Listener,CommandExecutor,TabCompleter {
    static final String ADMIN="gauntlet.admin";
    private static final Particle.DustOptions SELECTION=new Particle.DustOptions(Color.YELLOW,1f),
            COURSE=new Particle.DustOptions(Color.AQUA,1f),START=new Particle.DustOptions(Color.LIME,1.5f),
            GOAL=new Particle.DustOptions(Color.WHITE,.8f),TRIGGER=new Particle.DustOptions(Color.ORANGE,.8f),
            ROUTE=new Particle.DustOptions(Color.FUCHSIA,.7f),ROUTE_POINT=new Particle.DustOptions(Color.FUCHSIA,1.6f),
            WATER=new Particle.DustOptions(Color.fromRGB(0x3f76e4),.8f),LANE=new Particle.DustOptions(Color.SILVER,1f),
            ERA=new Particle.DustOptions(Color.fromRGB(0xb8924a),1.2f);
    /** Lanes whose outline is drawn: those within this many blocks of the builder. */
    private static final double LANE_OUTLINE_DISTANCE=96;
    final JavaPlugin plugin;
    final CourseStore store;
    final TemplateStore templates;
    final FreeTriggerStore freeTriggers;
    final CourseItems items;
    final ChatPrompts prompts;
    final Participants participants;
    final Lanes lanes;
    final Spaces spaces;
    final FreePlaceableStore freePlaceable;
    private final CourseWand wand;
    private final PlaceMarker marker,waterMarker;
    private final Selections selections=new Selections();
    private final ItemMenus itemMenus;
    private final TriggerMenus triggerMenus;
    private final EraMenus eraMenus;
    /** The generator's settings, set by /module once it starts. */
    GeneratorMenus generatorMenus;
    /** Times through modules, shown next to each module's time (null: not shown). */
    public konqasasas.gauntlet.data.ModuleTimes moduleTimes;
    /** The course each builder last opened, shown while holding the wand. */
    private final Map<UUID,String> editing=new HashMap<>();
    private BukkitTask outlines;

    public CourseEditor(JavaPlugin plugin,CourseStore store,TemplateStore templates,FreeTriggerStore freeTriggers,CourseItems items,NamespacedKey toolKey,Participants participants,Lanes lanes,Spaces spaces,
                        FreePlaceableStore freePlaceable) {
        this.freePlaceable=freePlaceable;
        this.plugin=plugin; this.store=store; this.templates=templates; this.freeTriggers=freeTriggers; this.items=items; this.participants=participants; this.lanes=lanes;
        this.spaces=spaces;
        wand=new CourseWand(toolKey); marker=new PlaceMarker(toolKey,"PLACE_MARKER",Material.LIME_WOOL,"editor.marker.item");
        waterMarker=new PlaceMarker(toolKey,"WATER_MARKER",Material.BLUE_WOOL,"editor.water-marker.item"); prompts=new ChatPrompts(plugin);
        itemMenus=new ItemMenus(this); triggerMenus=new TriggerMenus(this,itemMenus); eraMenus=new EraMenus(this);
    }
    public void start() {
        var pm=plugin.getServer().getPluginManager();
        pm.registerEvents(this,plugin); pm.registerEvents(prompts,plugin); pm.registerEvents(new Menu.Events(),plugin);
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("course"));
        command.setExecutor(this); command.setTabCompleter(this);
        outlines=Bukkit.getScheduler().runTaskTimer(plugin,this::showOutlines,10,10);
        for(String invalid:store.invalidFiles()) plugin.getLogger().severe("Skipped invalid course file (left untouched): "+invalid);
    }
    public void stop() {
        if(outlines!=null) outlines.cancel();
        // Menus hold lambdas from this plugin instance; never leave them open across a reload.
        for(Player player:Bukkit.getOnlinePlayers())
            if(player.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu) player.closeInventory();
        prompts.clear(); selections.clear(); editing.clear();
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        if(args.length==0) { if(admin(player)) openList(player); }
        // Temporary entry points until the course selection feature exists.
        else if(args.length==2 && args[0].equalsIgnoreCase("play")) participants.enter(player,args[1].toLowerCase(Locale.ROOT),false);
        else if(args.length==2 && args[0].equalsIgnoreCase("practice")) participants.enter(player,args[1].toLowerCase(Locale.ROOT),true);
        else if(args.length==1 && args[0].equalsIgnoreCase("leave")) participants.leave(player);
        else if(args.length==1 && args[0].equalsIgnoreCase("undosave")) participants.undoSave(player);
        // Choosing where to start in an era: a course reached before (the first one for everyone).
        else if(args.length==2 && args[0].equalsIgnoreCase("era")) eraMenus.openStart(player,args[1].toLowerCase(Locale.ROOT));
        else if(args.length==3 && args[0].equalsIgnoreCase("era")) {
            Era era=spaces.eras().get(args[1].toLowerCase(Locale.ROOT)); String course=args[2].toLowerCase(Locale.ROOT);
            if(era==null || !era.courses().contains(course)) Msg.send(player,"play.era.not-found",Msg.arg("id",args[1]));
            else participants.enter(player,course,false);
        }
        else Msg.send(player,player.hasPermission(ADMIN) ? "course.usage-admin" : "course.usage");
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return List.of("play","practice","leave","undosave","era").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if(args.length==2 && args[0].equalsIgnoreCase("era"))
            return spaces.eras().all().stream().map(Era::id).filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if(args.length==3 && args[0].equalsIgnoreCase("era")) {
            Era era=spaces.eras().get(args[1].toLowerCase(Locale.ROOT));
            return era==null ? List.of() : era.courses().stream().filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if(args.length==2 && (args[0].equalsIgnoreCase("play") || args[0].equalsIgnoreCase("practice"))) {
            boolean admin=sender.hasPermission(ADMIN);
            return store.all().stream().filter(c -> (admin || c.status()==CourseStatus.DONE) && c.module()==null && c.segments().isEmpty()).map(Course::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
    boolean admin(Player player) {
        if(player.hasPermission(ADMIN)) return true;
        Msg.send(player,"editor.admin-only"); return false;
    }
    /** Editor messages are always sent with the [Gauntlet] prefix. */
    void tell(Player player,String key,TagResolver... args) { Msg.send(player,key,args); }
    static Component line(Player player,String key,TagResolver... args) { return Msg.text(player,key,args); }

    // ---- Course list ----
    void openList(Player player) {
        Menu menu=new Menu(6,Msg.plain(player,"editor.list.title"));
        // Modules (pieces of generated courses) have their own list: /module.
        List<Course> courses=store.all().stream().filter(c -> c.module()==null && c.segments().isEmpty()).toList();
        for(int i=0;i<Math.min(courses.size(),45);i++) {
            Course course=courses.get(i);
            menu.button(i,courseIcon(player,course),e -> openEdit(player,course.id()));
        }
        List<Component> createLore=new ArrayList<>();
        if(courses.size()>45) createLore.add(line(player,"editor.list.more",Msg.arg("count",courses.size())));
        menu.button(49,Msg.icon(player,Material.WRITABLE_BOOK,"editor.list.create",createLore),e -> askNewId(player,false));
        menu.button(47,Msg.icon(player,Material.CLOCK,"editor.eras-button",Msg.arg("count",spaces.eras().all().size())),e -> eraMenus.openList(player));
        menu.button(51,Msg.icon(player,Material.CHEST,"editor.templates-button"),e -> itemMenus.openTemplates(player,() -> openList(player)));
        menu.button(53,Msg.icon(player,Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"editor.free-triggers-button",Msg.arg("count",freeTriggers.all().size())),
                e -> triggerMenus.openFreeList(player));
        menu.open(player);
    }
    private ItemStack courseIcon(Player player,Course course) {
        Material material=switch(course.status()) { case DRAFT -> Material.PAPER; case TESTING -> Material.MAP; case DONE -> Material.FILLED_MAP; };
        List<Component> lore=new ArrayList<>();
        lore.add(line(player,"editor.list.course.id",Msg.arg("id",course.id())));
        lore.add(line(player,"editor.list.course.status",Msg.arg("status",Msg.text(player,course.status().key()))));
        lore.add(line(player,"editor.list.course.author",Msg.arg("author",authorName(course))));
        warnings(player,course).forEach(p -> lore.add(line(player,"editor.warning",Msg.arg("text",p))));
        lore.add(line(player,"editor.list.course.click"));
        ItemStack icon=new ItemStack(material);
        icon.editMeta(meta -> { meta.displayName(Icons.text(course.name())); meta.lore(lore); });
        return icon;
    }
    /** The modules (/module): courses marked as pieces of generated courses, by name (numbers in order: m1-2 before m1-10), 45 a page. */
    public void openModules(Player player) { openModules(player,0); }
    void openModules(Player player,int page) {
        if(!admin(player)) return;
        List<Course> modules=store.all().stream().filter(c -> c.module()!=null).sorted((a,b) -> NaturalOrder.compare(a.name(),b.name())).toList();
        int pages=Math.max(1,(modules.size()+44)/45); int shown=Math.clamp(page,0,pages-1);
        Menu menu=new Menu(6,Msg.plain(player,"editor.modules.title-page",Msg.arg("page",shown+1),Msg.arg("pages",pages)));
        for(int i=0;i<45 && shown*45+i<modules.size();i++) {
            Course module=modules.get(shown*45+i);
            ItemStack icon=courseIcon(player,module);
            icon.editMeta(meta -> {
                List<Component> lore=new ArrayList<>(Objects.requireNonNullElse(meta.lore(),List.of()));
                lore.addFirst(line(player,"editor.modules.entry",Msg.arg("difficulty",module.module().difficulty()),Msg.arg("time",RunClock.seconds(module.module().ticks()))));
                meta.lore(lore);
            });
            menu.button(i,icon,e -> openEdit(player,module.id()));
        }
        if(shown>0) menu.button(45,Msg.icon(player,Material.ARROW,"editor.modules.previous"),e -> openModules(player,shown-1));
        if(shown<pages-1) menu.button(53,Msg.icon(player,Material.ARROW,"editor.modules.next"),e -> openModules(player,shown+1));
        menu.button(49,Msg.icon(player,Material.WRITABLE_BOOK,"editor.modules.create"),e -> askNewId(player,true));
        menu.label(51,Msg.icon(player,Material.BOOK,"editor.module.help"));
        menu.label(47,Msg.icon(player,Material.COMPARATOR,"editor.modules.counts",difficultyCounts(player)));
        if(generatorMenus!=null) menu.button(46,Msg.icon(player,Material.CRAFTER,"editor.modules.generator"),e -> generatorMenus.openSettings(player));
        menu.open(player);
    }
    /** One line per difficulty: how many modules, and how many of them are complete (used by generation). */
    public List<Component> difficultyCounts(Player player) {
        List<Component> lines=new ArrayList<>();
        List<Course> modules=store.all().stream().filter(c -> c.module()!=null).toList();
        for(int d=Module.MIN_DIFFICULTY;d<=Module.MAX_DIFFICULTY;d++) {
            int level=d;
            long all=modules.stream().filter(c -> c.module().difficulty()==level).count();
            long usable=modules.stream().filter(c -> c.module().difficulty()==level && c.problems().isEmpty()).count();
            lines.add(line(player,"editor.modules.count-line",Msg.arg("difficulty",d),Msg.arg("count",all),Msg.arg("usable",usable)));
        }
        return lines;
    }
    private void askNewId(Player player,boolean module) {
        prompts.ask(player,module ? "editor.prompt.new-module" : "editor.prompt.new-id",text -> {
            if(!admin(player)) return;
            String id=text.toLowerCase(Locale.ROOT);
            if(!CourseStore.validId(id)) { tell(player,"editor.new.invalid-id",Msg.arg("text",text)); return; }
            Course course=Course.create(id,player.getUniqueId(),player.getWorld().getUID());
            if(module) course=course.edit().module(Module.standard()).build();
            Selections.Selected selected=selections.selected(player.getUniqueId());
            if(selected!=null) course=course.withRegion(selected.world(),selected.region());
            try { store.create(course); }
            catch(IOException e) { failure(player,"editor.new.failed",e,Msg.arg("id",id)); return; }
            tell(player,"editor.new.done",Msg.arg("id",id));
            openEdit(player,id);
        });
    }
    /** Course problems plus references to deleted templates, as text for this player. */
    List<String> warnings(Player player,Course course) {
        List<String> warnings=new ArrayList<>(course.problems().stream().map(k -> Msg.plain(player,k)).toList());
        boolean missing=course.loadout().stream().anyMatch(r -> r!=null && !items.exists(r))
                || course.triggers().stream().flatMap(t -> t.actions().stream()).anyMatch(a -> a.item()!=null && !items.exists(a.item()));
        if(missing) warnings.add(Msg.plain(player,"editor.warn.uses-deleted-template"));
        return warnings;
    }

    // ---- Course editing ----
    void openEdit(Player player,String id) {
        Course course=store.get(id);
        if(course==null) { tell(player,"editor.deleted",Msg.arg("id",id)); openList(player); return; }
        editing.put(player.getUniqueId(),id);
        Menu menu=new Menu(4,Msg.plain(player,"editor.edit.title",Msg.arg("name",course.name())));
        List<Component> info=new ArrayList<>(List.of(
                line(player,"editor.list.course.id",Msg.arg("id",course.id())),
                line(player,"editor.list.course.author",Msg.arg("author",authorName(course))),
                line(player,"editor.edit.world",Msg.arg("world",worldName(player,course))),
                line(player,"editor.edit.rev",Msg.arg("rev",course.rev()))));
        Era inEra=spaces.era(course);
        if(inEra!=null) info.add(line(player,"editor.edit.era",Msg.arg("era",inEra.name()),Msg.arg("index",inEra.courses().indexOf(course.id())+1)));
        if(course.problems().isEmpty()) info.add(line(player,"editor.edit.playable"));
        warnings(player,course).forEach(p -> info.add(line(player,"editor.warning",Msg.arg("text",p))));
        ItemStack book=new ItemStack(Material.BOOK);
        book.editMeta(meta -> { meta.displayName(Icons.text(course.name())); meta.lore(info); });
        menu.label(4,book);
        menu.button(10,Msg.icon(player,Material.NAME_TAG,"editor.edit.name",Msg.arg("current",course.name())),
                e -> prompts.ask(player,"editor.prompt.name",text -> {
                    if(text.isBlank() || text.length()>Course.MAX_NAME_LENGTH) { tell(player,"editor.name-length",Msg.arg("max",Course.MAX_NAME_LENGTH)); return; }
                    update(player,id,c -> c.withName(text));
                },Msg.arg("max",Course.MAX_NAME_LENGTH)));
        menu.button(11,Msg.icon(player,Material.MAP,"editor.edit.region",
                Msg.arg("current",course.region()==null ? Msg.text(player,"editor.unset") : Component.text(course.region().describe()))),
                e -> setRegion(player,id));
        menu.button(12,Msg.icon(player,Material.RECOVERY_COMPASS,"editor.edit.start",
                Msg.arg("current",course.start()==null ? Msg.text(player,"editor.unset") : Component.text(course.start().describe()))),
                e -> setStart(player,id));
        menu.button(13,Msg.icon(player,Material.CLOCK,"editor.edit.time",
                Msg.arg("current",course.timeLimitSeconds()==0 ? Msg.text(player,"editor.none") : Msg.text(player,"time.seconds",Msg.arg("value",course.timeLimitSeconds())))),
                e -> adjustTime(player,id,e));
        menu.button(14,Msg.icon(player,statusIcon(course.status()),"editor.edit.status",Msg.arg("status",Msg.text(player,course.status().key()))),
                e -> update(player,id,c -> c.withStatus(c.status().next())));
        menu.button(15,Msg.icon(player,Material.ENDER_PEARL,"editor.edit.go-start"),e -> teleportToStart(player,id));
        menu.button(16,Msg.icon(player,Material.BREEZE_ROD,"editor.edit.wand"),e -> giveWand(player));
        List<Component> goals=new ArrayList<>();
        course.goals().stream().limit(5).forEach(g -> goals.add(Component.text("・"+g.describe())));
        menu.button(19,Msg.icon(player,Material.DIAMOND_BLOCK,"editor.edit.goal",goals,Msg.arg("count",course.goals().size())),e -> toggleGoal(player,id));
        menu.button(20,Msg.icon(player,Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"editor.edit.trigger-here"),e -> triggerMenus.openAtFeet(player,id));
        menu.button(21,Msg.icon(player,Material.REPEATER,"editor.edit.triggers",Msg.arg("count",course.triggers().size())),e -> triggerMenus.openList(player,id));
        List<Component> loadout=new ArrayList<>();
        for(int i=0;i<Course.COURSE_SLOTS;i++) loadout.add(line(player,"editor.edit.loadout-slot",Msg.arg("slot",i+1),Msg.arg("item",items.name(player,course.loadout().get(i)))));
        menu.button(22,Msg.icon(player,Material.ARMOR_STAND,"editor.edit.loadout",loadout),e -> itemMenus.openLoadout(player,id));
        menu.button(23,Msg.icon(player,course.failLava() ? Material.LAVA_BUCKET : Material.BUCKET,"editor.edit.fail-lava",Msg.arg("state",onOff(player,course.failLava()))),
                e -> update(player,id,c -> c.edit().failLava(!c.failLava()).build()));
        menu.button(24,Msg.icon(player,course.failOutside() ? Material.STRUCTURE_VOID : Material.GLASS,"editor.edit.fail-outside",Msg.arg("state",onOff(player,course.failOutside()))),
                e -> update(player,id,c -> c.edit().failOutside(!c.failOutside()).build()));
        menu.button(25,Msg.icon(player,Material.CHEST,"editor.templates-button"),e -> itemMenus.openTemplates(player,() -> openEdit(player,id)));
        boolean modular=course.module()!=null || !course.segments().isEmpty();
        menu.button(27,Msg.icon(player,Material.ARROW,modular ? "editor.modules.back" : "editor.back-to-list"),
                e -> { if(modular) openModules(player); else openList(player); });
        menu.button(28,Msg.icon(player,Material.LEAD,"editor.edit.route",Msg.arg("count",course.route().size()),
                Msg.arg("length",String.format(Locale.ROOT,"%.1f",new RouteProgress(course.route()).total()))),e -> editRoute(player,id,e));
        menu.button(29,Msg.icon(player,Material.LIME_WOOL,"editor.edit.placeable",Msg.arg("count",course.placeable().size())),e -> placeableButton(player,id,e));
        Space space=spaces.of(course);
        Era era=spaces.era(course);
        menu.button(30,Msg.icon(player,Material.RAIL,era==null ? "editor.edit.lanes" : "editor.edit.lanes-era",Msg.arg("count",space.laneCount()),
                Msg.arg("waiting",lanes.waitingFor(space)),Msg.arg("era",era==null ? "" : era.name())),
                e -> { if(era!=null && e.isRightClick()) eraMenus.openEra(player,era.id()); else openLanes(player,space.key(),() -> openEdit(player,id)); });
        menu.button(32,Msg.icon(player,course.module()==null ? Material.GRAY_DYE : Material.PURPLE_DYE,course.module()==null ? "editor.edit.module-off" : "editor.edit.module",
                Msg.arg("difficulty",course.module()==null ? 0 : course.module().difficulty()),Msg.arg("time",RunClock.seconds(course.module()==null ? 0 : course.module().ticks()))),
                e -> openModule(player,id));
        menu.button(31,Msg.icon(player,Material.NETHER_STAR,course.problems().isEmpty() ? "editor.edit.play" : "editor.edit.play-blocked"),
                e -> { if(admin(player)) participants.enter(player,id,e.isRightClick()); });
        menu.button(35,Msg.icon(player,Material.BARRIER,"editor.edit.delete"),e -> openDelete(player,id));
        menu.open(player);
    }
    private static Component onOff(Player player,boolean on) { return Msg.text(player,on ? "setting.value.on" : "setting.value.off"); }
    private static Material statusIcon(CourseStatus status) {
        return switch(status) { case DRAFT -> Material.GRAY_DYE; case TESTING -> Material.YELLOW_DYE; case DONE -> Material.LIME_DYE; };
    }
    /**
     * Saves a change; false (already reported) when the course is gone or the save failed.
     * An edit that changes how the course plays gets a new revision (records are per revision).
     */
    boolean save(Player player,String id,UnaryOperator<Course> change) {
        if(!admin(player)) return false;
        Course course=store.get(id);
        if(course==null) { tell(player,"editor.deleted",Msg.arg("id",id)); openList(player); return false; }
        try {
            Course next=change.apply(course);
            if(!next.sameGameplay(course)) next=next.edit().rev(course.rev()+1).build();
            store.save(next); return true;
        }
        catch(IOException|RuntimeException e) { failure(player,"editor.save-failed",e,Msg.arg("id",id)); return false; }
    }
    private void update(Player player,String id,UnaryOperator<Course> change) {
        if(save(player,id,change)) openEdit(player,id);
    }
    private void setRegion(Player player,String id) {
        Selections.Selected selected=selections.selected(player.getUniqueId());
        if(selected==null) { tell(player,"editor.region.no-selection"); return; }
        Course before=store.get(id);
        update(player,id,c -> c.withRegion(selected.world(),selected.region()));
        tell(player,"editor.region.done",Msg.arg("region",selected.region().describe()));
        Course after=store.get(id);
        if(after!=null && !after.lanes().isEmpty() && before!=null && before.world().equals(after.world())) tell(player,"editor.lanes.region-changed");
        Region eraBox=after==null || spaces.era(after)==null ? null : spaces.of(after).region();
        if(eraBox!=null && !eraBox.contains(after.region())) tell(player,"editor.era.course-outside");
    }
    /**
     * Only lane 1 is edited: true (and told) when the position is in lane 2 or later.
     * Lane 1's copies follow it by sync.
     */
    boolean inCopy(Player player,Course course,double x,double y,double z) {
        Space space=spaces.of(course);
        if(space.lanes().isEmpty() || !player.getWorld().getUID().equals(course.world())) return false;
        int lane=space.laneAt((int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));
        if(lane<=0) return false;
        tell(player,"editor.lanes.edit-lane1",Msg.arg("lane",lane+1)); return true;
    }
    private void setStart(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        Location at=player.getLocation();
        if(!at.getWorld().getUID().equals(course.world())) { tell(player,"editor.same-world",Msg.arg("world",worldName(player,course))); return; }
        if(course.region()==null) { tell(player,"editor.start.no-region"); return; }
        if(inCopy(player,course,at.getX(),at.getY(),at.getZ())) return;
        Spot spot=new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch());
        if(!course.contains(spot)) { tell(player,"editor.start.outside"); return; }
        if(!player.isOnGround()) { tell(player,"editor.start.not-ground"); return; }
        update(player,id,c -> c.withStart(spot));
        tell(player,"editor.start.done",Msg.arg("spot",spot.describe()));
    }
    private void toggleGoal(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        if(!player.getWorld().getUID().equals(course.world())) { tell(player,"editor.same-world",Msg.arg("world",worldName(player,course))); return; }
        if(!player.isOnGround()) { tell(player,"editor.goal.not-ground"); return; }
        Location at=player.getLocation();
        Block goal=GroundGeometry.supporting(at.getWorld(),player.getBoundingBox(),at.getY()).stream()
                .filter(b -> b.getType()==Material.DIAMOND_BLOCK).findFirst().orElse(null);
        if(goal==null) { tell(player,"editor.goal.not-diamond"); return; }
        if(inCopy(player,course,goal.getX(),goal.getY(),goal.getZ())) return;
        if(course.region()==null || !course.region().contains(goal.getX(),goal.getY(),goal.getZ())) { tell(player,"editor.goal.outside"); return; }
        BlockPos pos=new BlockPos(goal.getX(),goal.getY(),goal.getZ());
        boolean removing=course.goals().contains(pos);
        update(player,id,c -> c.toggleGoal(pos));
        tell(player,removing ? "editor.goal.removed" : "editor.goal.added",Msg.arg("pos",pos.describe()));
    }
    private void adjustTime(Player player,String id,InventoryClickEvent event) {
        int delta=(event.isShiftClick() ? 10 : 1)*(event.isRightClick() ? -1 : 1);
        update(player,id,c -> c.withTimeLimit(c.timeLimitSeconds()+delta));
    }
    /** Click: receive the marker. Shift+right: remove every placeable cell. */
    private void placeableButton(Player player,String id,InventoryClickEvent event) {
        if(event.isRightClick() && event.isShiftClick()) {
            update(player,id,c -> c.edit().clearPlaceable().build()); tell(player,"editor.placeable.cleared"); return;
        }
        // Shift+left: the cells registered outside the courses (for the kit) inside this course's box become the course's.
        if(event.isLeftClick() && event.isShiftClick()) {
            Course course=store.get(id); if(course==null || !admin(player)) return;
            if(course.region()==null) { tell(player,"editor.start.no-region"); return; }
            List<BlockPos> taken;
            try { taken=freePlaceable.take(course.world(),course.region()); }
            catch(IOException e) { failure(player,"editor.placeable.free-save-failed",e); return; }
            if(taken.isEmpty()) { tell(player,"editor.placeable.import-none"); return; }
            update(player,id,c -> { var b=c.edit(); for(BlockPos p:taken) if(!c.placeable().contains(p)) b.togglePlaceable(p); return b.build(); });
            tell(player,"editor.placeable.imported",Msg.arg("count",taken.size()));
            return;
        }
        giveMarker(player);
    }
    /** The placeable cell marker (also /gauntlet marker, for cells outside the courses). */
    public void giveMarker(Player player) {
        if(!admin(player)) return;
        for(ItemStack item:player.getInventory().getContents()) if(marker.is(item)) { tell(player,"editor.marker.already"); return; }
        if(!player.getInventory().addItem(marker.create(player)).isEmpty()) tell(player,"editor.kit.full");
        else tell(player,"editor.marker.given");
    }
    /** Left: add the current position, right: remove the last point, Shift+right: clear. */
    private void editRoute(Player player,String id,InventoryClickEvent event) {
        Course course=store.get(id); if(course==null) return;
        if(event.isRightClick() && event.isShiftClick()) { update(player,id,c -> c.edit().clearRoute().build()); tell(player,"editor.route.cleared"); return; }
        if(event.isRightClick()) {
            if(course.route().isEmpty()) { tell(player,"editor.route.empty"); return; }
            update(player,id,c -> c.edit().removeLastRoutePoint().build()); tell(player,"editor.route.removed"); return;
        }
        if(!player.getWorld().getUID().equals(course.world())) { tell(player,"editor.same-world",Msg.arg("world",worldName(player,course))); return; }
        if(course.route().size()>=Course.MAX_ROUTE_POINTS) { tell(player,"editor.route.full",Msg.arg("max",Course.MAX_ROUTE_POINTS)); return; }
        Location at=player.getLocation();
        if(inCopy(player,course,at.getX(),at.getY(),at.getZ())) return;
        // Body center, so the route runs where the player actually is.
        RoutePoint point=new RoutePoint(at.getX(),at.getY()+.9,at.getZ());
        update(player,id,c -> c.edit().addRoutePoint(point).build());
        tell(player,"editor.route.added",Msg.arg("index",course.route().size()+1),Msg.arg("point",point.describe()));
    }
    private void teleportToStart(Player player,String id) {
        Course course=store.get(id); if(course==null) return;
        if(course.start()==null) { tell(player,"editor.start.unset"); return; }
        Spot s=course.start();
        teleport(player,course,s.x(),s.y(),s.z(),s.yaw(),s.pitch());
    }
    void teleport(Player player,Course course,double x,double y,double z,float yaw,float pitch) {
        teleport(player,course.world(),x,y,z,yaw,pitch);
    }
    Selections.Selected selection(Player player) { return selections.selected(player.getUniqueId()); }
    void teleport(Player player,UUID worldId,double x,double y,double z,float yaw,float pitch) {
        World world=worldId==null ? null : Bukkit.getWorld(worldId);
        if(world==null) { tell(player,"editor.world-unloaded"); return; }
        player.closeInventory();
        if(!Teleports.to(player,new Location(world,x,y,z,yaw,pitch))) tell(player,"editor.teleport-denied");
    }
    private void giveWand(Player player) {
        if(!admin(player)) return;
        for(ItemStack item:player.getInventory().getContents()) if(wand.is(item)) { tell(player,"editor.wand.already"); return; }
        if(!player.getInventory().addItem(wand.create(player)).isEmpty()) tell(player,"editor.kit.full");
        else tell(player,"editor.wand.given");
    }
    private void openDelete(Player player,String id) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.delete.title",Msg.arg("id",id)));
        menu.button(11,Msg.icon(player,Material.RED_CONCRETE,"editor.delete.confirm",Msg.arg("id",id)),e -> {
            if(!admin(player)) return;
            try { store.delete(id); tell(player,"editor.delete.done",Msg.arg("id",id)); editing.values().removeIf(id::equals); }
            catch(IOException e2) { failure(player,"editor.delete.failed",e2,Msg.arg("id",id)); }
            openList(player);
        });
        menu.button(15,Msg.icon(player,Material.LIME_CONCRETE,"editor.delete.cancel"),e -> openEdit(player,id));
        menu.open(player);
    }

    // ---- Module (a piece of generated courses) ----
    /** On/off, difficulty, time, and how many of each loadout item it hands out. */
    void openModule(Player player,String id) {
        Course course=store.get(id);
        if(course==null) { tell(player,"editor.deleted",Msg.arg("id",id)); openList(player); return; }
        Menu menu=new Menu(3,Msg.plain(player,"editor.module.title",Msg.arg("name",course.name())));
        Module m=course.module();
        menu.button(10,Msg.icon(player,m==null ? Material.GRAY_DYE : Material.PURPLE_DYE,m==null ? "editor.module.off" : "editor.module.on"),e -> {
            if(save(player,id,c -> c.edit().module(c.module()==null ? Module.standard() : null).build())) openModule(player,id);
        });
        if(m!=null) {
            menu.button(11,Msg.icon(player,Material.BLAZE_POWDER,"editor.module.difficulty",Msg.arg("value",m.difficulty())),
                    e -> changeModule(player,id,x -> x.withDifficulty(x.difficulty()+(e.isRightClick() ? -1 : 1))));
            menu.button(12,Msg.icon(player,Material.CLOCK,"editor.module.time",moduleTimeLines(player,course),
                    Msg.arg("time",RunClock.seconds(m.ticks())),Msg.arg("ticks",m.ticks())),e -> {
                if(e.getClick()==ClickType.DROP || e.getClick()==ClickType.CONTROL_DROP) askModuleTicks(player,id);
                else changeModule(player,id,x -> x.withTicks(x.ticks()+(e.isShiftClick() ? 1 : 20)*(e.isRightClick() ? -1 : 1)));
            });
            for(int slot=0;slot<Course.COURSE_SLOTS;slot++) {
                int index=slot; int count=m.counts().get(slot);
                menu.button(14+slot,Msg.icon(player,Material.CHEST,"editor.module.count",Msg.arg("slot",slot+1),Msg.arg("item",items.name(player,course.loadout().get(slot))),
                        Msg.arg("value",count==0 ? Msg.text(player,"editor.module.unlimited") : Component.text(count))),
                        e -> changeModule(player,id,x -> x.withCount(index,x.counts().get(index)+(e.isShiftClick() ? 10 : 1)*(e.isRightClick() ? -1 : 1))));
            }
        }
        menu.label(22,Msg.icon(player,Material.BOOK,"editor.module.help"));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> openEdit(player,id));
        menu.open(player);
    }
    /** The clean passes through this revision of the module, as a guide for its time. */
    private List<Component> moduleTimeLines(Player player,Course course) {
        var summary=moduleTimes==null ? null : moduleTimes.summary(course.id(),course.rev());
        if(summary==null) return List.of(line(player,"editor.module.times-none"));
        return List.of(line(player,"editor.module.times-count",Msg.arg("passes",summary.passes()),Msg.arg("players",summary.players())),
                line(player,"editor.module.times-values",Msg.arg("mean",ticksText(summary.mean())),Msg.arg("median",ticksText(summary.median())),
                        Msg.arg("slow",ticksText(summary.slowQuarter()))));
    }
    /** A time in ticks (possibly fractional) as seconds with its ticks: 12.35s (247t). */
    private static String ticksText(double ticks) { return String.format(Locale.ROOT,"%.2fs (%dt)",ticks/20,Math.round(ticks)); }
    /** Q on the time: type it, in ticks (247) or seconds (12.35s). */
    private void askModuleTicks(Player player,String id) {
        prompts.ask(player,"editor.prompt.module-ticks",text -> {
            String t=text.strip().toLowerCase(Locale.ROOT);
            int ticks;
            try {
                ticks=t.endsWith("s") || t.endsWith("秒") ? (int)Math.round(Double.parseDouble(t.replace("秒","").replace("s","").strip())*20)
                        : Integer.parseInt(t.replace("t","").strip());
            } catch(NumberFormatException e) { ticks=-1; }
            if(ticks<1 || ticks>Module.MAX_TICKS) { tell(player,"editor.module.ticks-invalid",Msg.arg("max",Module.MAX_TICKS)); return; }
            int value=ticks;
            changeModule(player,id,x -> x.withTicks(value));
        },Msg.arg("max",Module.MAX_TICKS));
    }
    private void changeModule(Player player,String id,UnaryOperator<Module> change) {
        if(save(player,id,c -> c.module()==null ? c : c.edit().module(change.apply(c.module())).build())) openModule(player,id);
    }

    // ---- Lanes (of an era, or of a course outside the eras) ----
    /** Lane list (state, who plays it), adding lanes, sync from lane 1, removing the last lane. back: where "back" goes. */
    void openLanes(Player player,String key,Runnable back) {
        Space space=spaces.get(key);
        if(space==null) { tell(player,"editor.lanes.gone"); back.run(); return; }
        Menu menu=new Menu(6,Msg.plain(player,"editor.lanes.title",Msg.arg("name",space.name())));
        for(int lane=0;lane<Math.min(space.laneCount(),45);lane++) {
            int index=lane;
            menu.button(lane,laneIcon(player,space,lane),e -> {
                Space now=spaces.get(key); if(now==null || index>=now.laneCount()) return;
                Spot s=laneStart(now);
                if(s==null) { tell(player,"editor.start.unset"); return; }
                BlockPos d=now.laneOffset(index);
                teleport(player,now.world(),s.x()+d.x(),s.y()+d.y(),s.z()+d.z(),s.yaw(),s.pitch());
            });
        }
        Runnable again=() -> openLanes(player,key,back);
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back"),e -> back.run());
        menu.label(46,Msg.icon(player,Material.BOOK,"editor.lanes.help"));
        menu.button(47,Msg.icon(player,Material.LIME_DYE,"editor.lanes.add",Msg.arg("max",Course.MAX_LANES)),e -> askLanes(player,key,again));
        menu.button(49,Msg.icon(player,Material.ECHO_SHARD,"editor.lanes.sync"),e -> {
            if(!admin(player)) return;
            Space now=spaces.get(key); if(now==null) return;
            if(now.lanes().isEmpty()) { tell(player,"editor.lanes.none"); return; }
            if(now.region()==null) { tell(player,"editor.start.no-region"); return; }
            List<Integer> all=new ArrayList<>(); for(int lane=1;lane<now.laneCount();lane++) all.add(lane);
            lanes.sync(now,all,player);
            tell(player,"editor.lanes.sync-started",Msg.arg("count",all.size()));
            again.run();
        });
        menu.button(51,Msg.icon(player,Material.BARRIER,"editor.lanes.remove"),e -> {
            if(!e.isShiftClick()) { tell(player,"editor.lanes.remove-shift"); return; }
            Space now=spaces.get(key); if(now==null) return;
            if(now.lanes().isEmpty()) { tell(player,"editor.lanes.none"); return; }
            int last=now.laneCount()-1;
            if(lanes.state(now,last)==Lanes.State.IN_USE) { tell(player,"editor.lanes.remove-in-use",Msg.arg("lane",last+1)); return; }
            List<BlockPos> rest=new ArrayList<>(now.lanes()); rest.removeLast();
            if(!saveLanes(player,now,rest)) return;
            lanes.trim(spaces.get(key));
            tell(player,"editor.lanes.removed",Msg.arg("lane",last+1));
            again.run();
        });
        menu.button(53,Msg.icon(player,Material.CLOCK,"editor.lanes.refresh",Msg.arg("waiting",lanes.waitingFor(space))),e -> again.run());
        menu.open(player);
    }
    /** Where a lane's button takes builders: the course's start, or the era's first course's start. */
    private Spot laneStart(Space space) {
        if(!space.isEra()) { Course c=store.get(space.key().substring(Space.COURSE.length())); return c==null ? null : c.start(); }
        Era era=spaces.eras().get(space.key().substring(Space.ERA.length()));
        if(era==null) return null;
        for(String id:era.courses()) { Course c=store.get(id); if(c!=null && c.start()!=null) return c.start(); }
        return null;
    }
    private ItemStack laneIcon(Player player,Space space,int lane) {
        Lanes.State state=lanes.state(space,lane);
        Material material=switch(state) { case FREE -> Material.LIME_CONCRETE; case IN_USE -> Material.ORANGE_CONCRETE; case PENDING -> Material.YELLOW_CONCRETE; case COPYING -> Material.LIGHT_BLUE_CONCRETE; };
        UUID occupant=lanes.occupant(space,lane);
        String who=occupant==null ? "" : Optional.ofNullable(Bukkit.getOfflinePlayer(occupant).getName()).orElse(occupant.toString());
        List<Component> lore=new ArrayList<>();
        lore.add(line(player,"editor.lanes.state."+state.name(),Msg.arg("player",who),Msg.arg("percent",lanes.percent(space,lane))));
        lore.add(line(player,"editor.lanes.offset",Msg.arg("offset",space.laneOffset(lane).describe())));
        Region region=space.laneRegion(lane);
        if(region!=null) lore.add(line(player,"editor.lanes.region",Msg.arg("region",region.describe())));
        lore.add(line(player,lane==0 ? "editor.lanes.lane1" : "editor.lanes.click"));
        ItemStack icon=new ItemStack(material);
        icon.editMeta(meta -> { meta.displayName(Msg.text(player,"editor.lanes.entry",Msg.arg("lane",lane+1))); meta.lore(lore); });
        return icon;
    }
    /** Saves the lanes of an era or of a course; false (already told) on failure. */
    private boolean saveLanes(Player player,Space space,List<BlockPos> next) {
        if(!admin(player)) return false;
        if(space.isEra()) {
            Era era=spaces.eras().get(space.key().substring(Space.ERA.length()));
            if(era==null) { tell(player,"editor.lanes.gone"); return false; }
            try { spaces.eras().save(era.withLanes(next)); return true; }
            catch(IOException|RuntimeException e) { failure(player,"editor.era.save-failed",e,Msg.arg("id",era.id())); return false; }
        }
        return save(player,space.key().substring(Space.COURSE.length()),c -> c.edit().lanes(next).build());
    }
    /** "4 x 200": four lanes, each 200 further along x than the one before. "4 0 0 -150": the step given directly. */
    private void askLanes(Player player,String key,Runnable again) {
        prompts.ask(player,"editor.prompt.lanes",text -> {
            Space space=spaces.get(key); if(space==null || !admin(player)) return;
            if(space.region()==null) { tell(player,"editor.start.no-region"); return; }
            String[] parts=text.strip().toLowerCase(Locale.ROOT).split("\\s+");
            int count; BlockPos step;
            try {
                count=Integer.parseInt(parts[0]);
                if(parts.length==3 && parts[1].matches("[xyz]")) {
                    int d=Integer.parseInt(parts[2]);
                    step=switch(parts[1]) { case "x" -> new BlockPos(d,0,0); case "y" -> new BlockPos(0,d,0); default -> new BlockPos(0,0,d); };
                } else if(parts.length==4) step=new BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
                else { tell(player,"editor.lanes.input-invalid"); return; }
            } catch(NumberFormatException e) { tell(player,"editor.lanes.input-invalid"); return; }
            if(count<1 || space.laneCount()+count>Course.MAX_LANES) { tell(player,"editor.lanes.too-many",Msg.arg("max",Course.MAX_LANES)); return; }
            if(step.equals(BlockPos.ZERO)) { tell(player,"editor.lanes.input-invalid"); return; }
            List<BlockPos> next=new ArrayList<>(space.lanes());
            BlockPos last=space.laneOffset(space.laneCount()-1);
            for(int i=1;i<=count;i++) next.add(new BlockPos(last.x()+step.x()*i,last.y()+step.y()*i,last.z()+step.z()*i));
            if(new HashSet<>(next).size()!=next.size() || next.contains(BlockPos.ZERO)) { tell(player,"editor.lanes.overlap"); return; }
            Space planned=new Space(space.key(),space.name(),space.world(),space.region(),next);
            String problem=laneProblem(planned);
            if(problem!=null) { tell(player,problem); return; }
            if(!saveLanes(player,space,next)) return;
            List<Integer> added=new ArrayList<>(); for(int lane=space.laneCount();lane<planned.laneCount();lane++) added.add(lane);
            lanes.sync(planned,added,player);
            tell(player,"editor.lanes.added",Msg.arg("count",count),Msg.arg("total",planned.laneCount()));
            again.run();
        },Msg.arg("max",Course.MAX_LANES-1));
    }
    /** Why these lanes cannot be placed (message key), or null: overlapping, outside the world's height, or on another era or course. */
    String laneProblem(Space space) {
        if(space.lanesOverlap()) return "editor.lanes.overlap";
        World world=Bukkit.getWorld(space.world());
        for(int lane=1;lane<space.laneCount();lane++) {
            Region r=space.laneRegion(lane);
            if(world!=null && (r.minY()<world.getMinHeight() || r.maxY()>=world.getMaxHeight())) return "editor.lanes.height";
            for(Space other:spaces.all()) {
                if(other.key().equals(space.key()) || other.region()==null || !other.world().equals(space.world())) continue;
                for(int o=0;o<other.laneCount();o++) if(other.laneRegion(o).overlaps(r)) return "editor.lanes.other-course";
            }
        }
        return null;
    }

    // ---- Wand ----
    @EventHandler(priority=EventPriority.HIGHEST)
    public void wandClick(PlayerInteractEvent event) {
        // The off-hand pass after a main-hand wand click must not place or use the off-hand item.
        if(event.getHand()==EquipmentSlot.OFF_HAND && wand.is(event.getPlayer().getInventory().getItemInMainHand())) { event.setCancelled(true); return; }
        if(!wand.is(event.getItem())) return;
        event.setCancelled(true);
        Block block=event.getClickedBlock(); Player player=event.getPlayer();
        if(event.getHand()!=EquipmentSlot.HAND || block==null) return;
        int index=event.getAction()==Action.LEFT_CLICK_BLOCK ? 0 : event.getAction()==Action.RIGHT_CLICK_BLOCK ? 1 : -1;
        if(index<0 || !admin(player)) return;
        selections.set(player.getUniqueId(),index,new Selections.Corner(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()));
        Selections.Selected selected=selections.selected(player.getUniqueId());
        tell(player,"editor.wand.corner",Msg.arg("index",index+1),Msg.arg("pos",block.getX()+", "+block.getY()+", "+block.getZ()),
                Msg.arg("size",selected==null ? "" : "  "+selected.region().sizeX()+"×"+selected.region().sizeY()+"×"+selected.region().sizeZ()));
    }
    /** The marker is never placed: the cell is registered (or unregistered) and stays air. */
    @EventHandler(priority=EventPriority.HIGHEST)
    public void markerPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if(!marker.is(event.getItemInHand())) return;
        event.setCancelled(true);
        Player player=event.getPlayer(); Block block=event.getBlockPlaced();
        if(!admin(player)) return;
        BlockPos at=new BlockPos(block.getX(),block.getY(),block.getZ());
        // Outside every course (and its lanes): a cell for trying out with the kit.
        Course owner=courseAt(block.getWorld().getUID(),at);
        if(owner==null) { toggleFreeCell(player,block.getWorld().getUID(),at); return; }
        String id=editing.get(player.getUniqueId());
        Course course=id==null ? null : store.get(id);
        if(course==null) { tell(player,"editor.placeable.no-course"); return; }
        if(!owner.id().equals(course.id())) { tell(player,"editor.placeable.other-course",Msg.arg("name",owner.name()),Msg.arg("editing",course.name())); return; }
        if(inCopy(player,course,block.getX(),block.getY(),block.getZ())) return;
        if(!block.getWorld().getUID().equals(course.world()) || course.region()==null || !course.region().contains(block.getX(),block.getY(),block.getZ())) {
            tell(player,"editor.placeable.outside",Msg.arg("name",course.name())); return;
        }
        BlockPos cell=new BlockPos(block.getX(),block.getY(),block.getZ());
        boolean removing=course.placeable().contains(cell);
        if(save(player,id,c -> c.edit().togglePlaceable(cell).build()))
            Msg.actionBar(player,removing ? "editor.placeable.removed" : "editor.placeable.added",Msg.arg("pos",cell.describe()),
                    Msg.arg("count",course.placeable().size()+(removing ? -1 : 1)));
    }
    /** The course whose box (in any of its lanes) holds the cell, or null. */
    private Course courseAt(UUID world,BlockPos at) {
        for(Course c:store.all()) {
            if(c.region()==null || !c.world().equals(world)) continue;
            Space space=spaces.of(c);
            for(int lane=0;lane<space.laneCount();lane++) if(c.region().moved(space.laneOffset(lane)).contains(at.x(),at.y(),at.z())) return c;
        }
        return null;
    }
    private void toggleFreeCell(Player player,UUID world,BlockPos cell) {
        try {
            boolean added=freePlaceable.toggle(world,cell);
            Msg.actionBar(player,added ? "editor.placeable.free-added" : "editor.placeable.free-removed",Msg.arg("pos",cell.describe()),
                    Msg.arg("count",freePlaceable.cells(world).size()));
        } catch(IOException e) { failure(player,"editor.placeable.free-save-failed",e); }
    }
    /** The water marker is never placed either: the cell joins (or leaves) the water action opened last. */
    @EventHandler(priority=EventPriority.HIGHEST)
    public void waterMarkerPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if(!waterMarker.is(event.getItemInHand())) return;
        event.setCancelled(true);
        Player player=event.getPlayer();
        if(admin(player)) triggerMenus.toggleWaterCell(player,event.getBlockPlaced());
    }
    void giveWaterMarker(Player player) {
        if(!admin(player)) return;
        for(ItemStack item:player.getInventory().getContents()) if(waterMarker.is(item)) { tell(player,"editor.water-marker.already"); return; }
        if(!player.getInventory().addItem(waterMarker.create(player)).isEmpty()) tell(player,"editor.kit.full");
        else tell(player,"editor.water-marker.given");
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void wandBreak(BlockBreakEvent event) {
        // Creative instant-breaking must not destroy the clicked corner block.
        if(wand.is(event.getPlayer().getInventory().getItemInMainHand())) event.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        selections.forget(event.getPlayer().getUniqueId()); editing.remove(event.getPlayer().getUniqueId());
        triggerMenus.forget(event.getPlayer().getUniqueId());
    }

    // ---- Outlines (only to builders holding the wand) ----
    private void showOutlines() {
        for(Player player:Bukkit.getOnlinePlayers()) {
            if(waterMarker.is(player.getInventory().getItemInMainHand())) {
                // The cells of the water action the marker registers into.
                TriggerMenus.WaterView view=triggerMenus.waterCells(player);
                if(view!=null && view.world().equals(player.getWorld().getUID()))
                    for(BlockPos c:view.cells()) box(player,c.x()+.1,c.y()+.1,c.z()+.1,c.x()+.9,c.y()+.9,c.z()+.9,WATER);
                continue;
            }
            if(!wand.is(player.getInventory().getItemInMainHand())) continue;
            UUID world=player.getWorld().getUID(),id=player.getUniqueId();
            for(int i=0;i<2;i++) {
                Selections.Corner c=selections.corner(id,i);
                if(c!=null && c.world().equals(world)) box(player,c.x(),c.y(),c.z(),c.x()+1,c.y()+1,c.z()+1,SELECTION);
            }
            Selections.Selected selected=selections.selected(id);
            if(selected!=null && selected.world().equals(world)) box(player,selected.region(),SELECTION);
            Course course=editing.containsKey(id) ? store.get(editing.get(id)) : null;
            if(course==null || !course.world().equals(world)) continue;
            if(course.region()!=null) box(player,course.region(),COURSE);
            Space space=spaces.of(course);
            if(space.isEra() && space.region()!=null) box(player,space.region(),ERA);
            Location here=player.getLocation();
            for(int lane=1;lane<space.laneCount();lane++) {
                Region r=space.laneRegion(lane);
                if(r==null) break;
                double dx=Math.max(0,Math.max(r.minX()-here.getX(),here.getX()-r.maxX()-1)),dz=Math.max(0,Math.max(r.minZ()-here.getZ(),here.getZ()-r.maxZ()-1));
                if(dx*dx+dz*dz<=LANE_OUTLINE_DISTANCE*LANE_OUTLINE_DISTANCE) box(player,r,LANE);
            }
            Spot s=course.start();
            if(s!=null) for(double y=0;y<=2;y+=.25) dust(player,s.x(),s.y()+y,s.z(),START);
            for(BlockPos g:course.goals()) box(player,g.x()-.05,g.y()-.05,g.z()-.05,g.x()+1.05,g.y()+1.05,g.z()+1.05,GOAL);
            for(Trigger t:course.triggers()) { BlockPos p=t.pos(); box(player,p.x(),p.y(),p.z(),p.x()+1,p.y()+.5,p.z()+1,TRIGGER); }
            route(player,course.route());
        }
    }
    private void route(Player player,List<RoutePoint> route) {
        for(int i=0;i<route.size();i++) {
            RoutePoint p=route.get(i);
            dust(player,p.x(),p.y(),p.z(),ROUTE_POINT);
            if(i==0) continue;
            RoutePoint a=route.get(i-1);
            double length=Math.sqrt((p.x()-a.x())*(p.x()-a.x())+(p.y()-a.y())*(p.y()-a.y())+(p.z()-a.z())*(p.z()-a.z()));
            int steps=(int)Math.min(80,Math.ceil(length/.5));
            for(int k=1;k<steps;k++) {
                double t=k/(double)steps;
                dust(player,a.x()+(p.x()-a.x())*t,a.y()+(p.y()-a.y())*t,a.z()+(p.z()-a.z())*t,ROUTE);
            }
        }
    }
    private void box(Player player,Region r,Particle.DustOptions color) {
        box(player,r.minX(),r.minY(),r.minZ(),r.maxX()+1,r.maxY()+1,r.maxZ()+1,color);
    }
    private void box(Player player,double x1,double y1,double z1,double x2,double y2,double z2,Particle.DustOptions color) {
        double[][] bounds={{x1,x2},{y1,y2},{z1,z2}};
        double step=Math.max(.5,Math.max(x2-x1,Math.max(y2-y1,z2-z1))/48);
        for(int axis=0;axis<3;axis++) for(int edge=0;edge<4;edge++) {
            int a=(axis+1)%3,b=(axis+2)%3; double[] p=new double[3];
            p[a]=bounds[a][edge&1]; p[b]=bounds[b][edge>>1];
            for(double t=bounds[axis][0];t<=bounds[axis][1]+1e-9;t+=step) { p[axis]=t; dust(player,p[0],p[1],p[2],color); }
        }
    }
    private static void dust(Player player,double x,double y,double z,Particle.DustOptions color) {
        player.spawnParticle(Particle.DUST,x,y,z,1,0,0,0,0,color,true);
    }

    private String authorName(Course course) {
        String name=Bukkit.getOfflinePlayer(course.author()).getName(); return name==null ? course.author().toString() : name;
    }
    static String worldName(Player player,Course course) {
        World world=Bukkit.getWorld(course.world());
        return world==null ? Msg.plain(player,"editor.world-not-loaded",Msg.arg("id",course.world())) : world.getName();
    }
    /** /gauntlet trigger: the free trigger under the player (registered if new), or the list of free triggers. */
    public void freeTriggers(Player player) { if(admin(player)) triggerMenus.openFreeAtFeet(player); }
    public boolean holdsWand(Player player) { return wand.is(player.getInventory().getItemInMainHand()); }
    public boolean holdsMarker(Player player) { return marker.is(player.getInventory().getItemInMainHand()); }
    /**
     * Labels above the plates this builder should see: free triggers in their world
     * while holding the kit block or the wand, and the triggers of the shown course.
     */
    public List<TriggerLabels.Label> triggerLabels(Player player,boolean holdingKit) {
        if(!player.hasPermission(ADMIN)) return List.of();
        List<TriggerLabels.Label> labels=new ArrayList<>();
        UUID world=player.getWorld().getUID();
        if(holdingKit || holdsWand(player)) for(FreeTriggerStore.Entry e:freeTriggers.all())
            if(e.world().equals(world)) labels.add(label("free",world,e.trigger()));
        String id=shownCourse(player);
        Course course=id==null ? null : store.get(id);
        // Every lane shows the labels at its own plates.
        Space space=course==null ? null : spaces.of(course);
        if(course!=null && course.world().equals(world)) for(int lane=0;lane<space.laneCount();lane++)
            for(Trigger t:course.triggers()) labels.add(label(course.id(),world,t.moved(space.laneOffset(lane))));
        return labels;
    }
    private TriggerLabels.Label label(String owner,UUID world,Trigger t) {
        BlockPos p=t.pos();
        return new TriggerLabels.Label(owner+"@"+world+":"+p.describe(),world,p.x()+.5,p.y()+1.1,p.z()+.5,triggerMenus.label(t));
    }
    /** The course whose outlines and placeable cells this builder sees: the last opened one, while holding the wand or marker. */
    public String shownCourse(Player player) {
        ItemStack hand=player.getInventory().getItemInMainHand();
        if(!wand.is(hand) && !marker.is(hand)) return null;
        return editing.get(player.getUniqueId());
    }
    void failure(Player player,String key,Exception e,TagResolver... args) {
        tell(player,key,args); plugin.getLogger().log(Level.SEVERE,Msg.plain(player,key,args),e);
    }
}
