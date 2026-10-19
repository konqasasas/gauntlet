package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Registering iron pressure plates as triggers and editing their actions, for a
 * course or outside every course (the builder kit's free triggers).
 */
final class TriggerMenus {
    /** label is a message key. */
    private record SoundPreset(String key,String label,Material icon) {}
    private static final List<SoundPreset> SOUNDS=List.of(
            new SoundPreset("minecraft:entity.experience_orb.pickup","editor.sound.xp",Material.EXPERIENCE_BOTTLE),
            new SoundPreset("minecraft:entity.player.levelup","editor.sound.levelup",Material.ENCHANTING_TABLE),
            new SoundPreset("minecraft:block.note_block.pling","editor.sound.pling",Material.NOTE_BLOCK),
            new SoundPreset("minecraft:block.note_block.bell","editor.sound.bell",Material.BELL),
            new SoundPreset("minecraft:block.note_block.chime","editor.sound.chime",Material.PACKED_ICE),
            new SoundPreset("minecraft:block.amethyst_block.chime","editor.sound.amethyst",Material.AMETHYST_BLOCK),
            new SoundPreset("minecraft:entity.item.pickup","editor.sound.pickup",Material.STICK),
            new SoundPreset("minecraft:item.armor.equip_elytra","editor.sound.elytra",Material.ELYTRA),
            new SoundPreset("minecraft:entity.wind_charge.wind_burst","editor.sound.wind-burst",Material.WIND_CHARGE),
            new SoundPreset("minecraft:block.beacon.activate","editor.sound.beacon",Material.BEACON),
            new SoundPreset("minecraft:entity.firework_rocket.launch","editor.sound.rocket",Material.FIREWORK_ROCKET),
            new SoundPreset("minecraft:entity.ender_dragon.growl","editor.sound.dragon",Material.DRAGON_HEAD),
            new SoundPreset("minecraft:entity.wither.spawn","editor.sound.wither",Material.WITHER_SKELETON_SKULL),
            new SoundPreset("minecraft:block.anvil.land","editor.sound.anvil",Material.ANVIL),
            new SoundPreset("minecraft:ui.toast.challenge_complete","editor.sound.challenge",Material.GOLD_INGOT),
            new SoundPreset("minecraft:block.bell.use","editor.sound.bell-use",Material.LIGHTNING_ROD));
    /** Where a trigger lives: a course, or the free triggers of one world. */
    private interface Host {
        /** False when the course was deleted. */
        boolean exists();
        Trigger get(BlockPos pos);
        /** Applies the change to the existing trigger and saves; false (already reported) on failure. */
        boolean change(Player player,BlockPos pos,UnaryOperator<Trigger> change);
        boolean delete(Player player,BlockPos pos);
        UUID world();
        /** The trigger list this trigger belongs to. */
        void list(Player player);
        /** Where "back" goes from the trigger screen. */
        void back(Player player);
    }
    private final CourseEditor editor;
    private final ItemMenus items;
    /** Copied actions per builder, for pasting onto another plate. */
    private final Map<UUID,List<TriggerAction>> clipboard=new HashMap<>();
    TriggerMenus(CourseEditor editor,ItemMenus items) { this.editor=editor; this.items=items; }
    void forget(UUID player) { clipboard.remove(player); }

    private Host course(String id) {
        return new Host() {
            @Override public boolean exists() { return editor.store.get(id)!=null; }
            @Override public Trigger get(BlockPos pos) { Course c=editor.store.get(id); return c==null ? null : c.trigger(pos); }
            @Override public boolean change(Player player,BlockPos pos,UnaryOperator<Trigger> change) {
                return editor.save(player,id,c -> {
                    Trigger t=c.trigger(pos);
                    if(t==null) throw new IllegalStateException("Trigger deleted");
                    return c.edit().trigger(change.apply(t)).build();
                });
            }
            @Override public boolean delete(Player player,BlockPos pos) { return editor.save(player,id,c -> c.edit().removeTrigger(pos).build()); }
            @Override public UUID world() { Course c=editor.store.get(id); return c==null ? null : c.world(); }
            @Override public void list(Player player) { openList(player,id); }
            @Override public void back(Player player) { editor.openEdit(player,id); }
        };
    }
    private Host free(UUID world) {
        return new Host() {
            @Override public boolean exists() { return true; }
            @Override public Trigger get(BlockPos pos) { return editor.freeTriggers.get(world,pos); }
            @Override public boolean change(Player player,BlockPos pos,UnaryOperator<Trigger> change) {
                if(!editor.admin(player)) return false;
                Trigger t=get(pos);
                if(t==null) { editor.tell(player,"editor.trigger.deleted"); return false; }
                try { editor.freeTriggers.put(world,change.apply(t)); return true; }
                catch(IOException|RuntimeException e) { editor.failure(player,"editor.free-trigger.save-failed",e); return false; }
            }
            @Override public boolean delete(Player player,BlockPos pos) {
                if(!editor.admin(player)) return false;
                try { editor.freeTriggers.remove(List.of(new FreeTriggerStore.Key(world,pos))); return true; }
                catch(IOException e) { editor.failure(player,"editor.free-trigger.save-failed",e); return false; }
            }
            @Override public UUID world() { return world; }
            @Override public void list(Player player) { openFreeList(player); }
            @Override public void back(Player player) { openFreeList(player); }
        };
    }

    // ---- Course triggers ----
    /** Registers the iron pressure plate under the player if needed, then opens it. */
    void openAtFeet(Player player,String id) {
        Course course=editor.store.get(id); if(course==null) return;
        if(!player.getWorld().getUID().equals(course.world())) { editor.tell(player,"editor.same-world",Msg.arg("world",CourseEditor.worldName(player,course))); return; }
        Block feet=player.getLocation().getBlock();
        if(feet.getType()!=Material.HEAVY_WEIGHTED_PRESSURE_PLATE) { editor.tell(player,"editor.trigger.not-plate"); return; }
        BlockPos pos=new BlockPos(feet.getX(),feet.getY(),feet.getZ());
        if(course.region()==null || !course.region().contains(pos.x(),pos.y(),pos.z())) { editor.tell(player,"editor.trigger.outside"); return; }
        if(course.trigger(pos)==null) {
            if(!editor.save(player,id,c -> c.edit().trigger(new Trigger(pos,List.of())).build())) return;
            editor.tell(player,"editor.trigger.registered",Msg.arg("pos",pos.describe()));
        }
        openTrigger(player,course(id),pos);
    }
    void openList(Player player,String id) {
        Course course=editor.store.get(id); if(course==null) { editor.openList(player); return; }
        Menu menu=new Menu(6,Msg.plain(player,"editor.triggers.title",Msg.arg("name",course.name())));
        List<Trigger> triggers=course.triggers();
        Host host=course(id);
        for(int i=0;i<Math.min(triggers.size(),45);i++) {
            Trigger t=triggers.get(i);
            menu.button(i,entryIcon(player,course.world(),t,i,List.of()),e -> openTrigger(player,host,t.pos()));
        }
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back"),e -> editor.openEdit(player,id));
        menu.open(player);
    }

    // ---- Free triggers (outside every course) ----
    /** On an iron pressure plate outside every course: register it and open it. Elsewhere: the list. */
    void openFreeAtFeet(Player player) {
        Block feet=player.getLocation().getBlock();
        if(feet.getType()!=Material.HEAVY_WEIGHTED_PRESSURE_PLATE) { openFreeList(player); return; }
        UUID world=feet.getWorld().getUID();
        BlockPos pos=new BlockPos(feet.getX(),feet.getY(),feet.getZ());
        Course inside=editor.store.all().stream()
                .filter(c -> c.world().equals(world) && c.region()!=null && c.region().contains(pos.x(),pos.y(),pos.z())).findFirst().orElse(null);
        if(inside!=null) { editor.tell(player,"editor.free-trigger.in-course",Msg.arg("name",inside.name())); return; }
        if(editor.freeTriggers.get(world,pos)==null) {
            if(!editor.admin(player)) return;
            try { editor.freeTriggers.put(world,new Trigger(pos,List.of())); }
            catch(IOException e) { editor.failure(player,"editor.free-trigger.save-failed",e); return; }
            editor.tell(player,"editor.trigger.registered",Msg.arg("pos",pos.describe()));
        }
        openTrigger(player,free(world),pos);
    }
    /** Nearest first (same world), then the other worlds. */
    void openFreeList(Player player) {
        Menu menu=new Menu(6,Msg.plain(player,"editor.free-triggers.title"));
        Location at=player.getLocation(); UUID here=at.getWorld().getUID();
        List<FreeTriggerStore.Entry> all=new ArrayList<>(editor.freeTriggers.all());
        all.sort(Comparator.comparing((FreeTriggerStore.Entry e) -> !e.world().equals(here))
                .thenComparingDouble(e -> e.world().equals(here) ? distanceSquared(at,e.trigger().pos()) : 0)
                .thenComparing(e -> worldName(player,e.world())));
        for(int i=0;i<Math.min(all.size(),45);i++) {
            FreeTriggerStore.Entry entry=all.get(i);
            List<Component> extra=new ArrayList<>();
            extra.add(Msg.text(player,"editor.free-triggers.world",Msg.arg("world",worldName(player,entry.world()))));
            if(entry.world().equals(here)) extra.add(Msg.text(player,"editor.free-triggers.distance",
                    Msg.arg("distance",Math.round(Math.sqrt(distanceSquared(at,entry.trigger().pos()))))));
            Host host=free(entry.world());
            menu.button(i,entryIcon(player,entry.world(),entry.trigger(),i,extra),e -> openTrigger(player,host,entry.trigger().pos()));
        }
        if(all.size()>45) menu.label(44,Msg.icon(player,Material.PAPER,"editor.free-triggers.more",Msg.arg("count",all.size())));
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back-to-list"),e -> editor.openList(player));
        menu.label(48,Msg.icon(player,Material.BOOK,"editor.free-triggers.help"));
        menu.button(50,Msg.icon(player,Material.LAVA_BUCKET,"editor.free-triggers.clean"),e -> {
            if(!e.isShiftClick()) { editor.tell(player,"editor.free-triggers.clean-shift"); return; }
            if(!editor.admin(player)) return;
            // Loads the chunks: a plate in an unloaded chunk is not "missing".
            List<FreeTriggerStore.Key> missing=editor.freeTriggers.all().stream()
                    .filter(f -> Boolean.FALSE.equals(plate(f.world(),f.trigger().pos(),true))).map(FreeTriggerStore.Entry::key).toList();
            try { editor.freeTriggers.remove(missing); editor.tell(player,"editor.free-triggers.cleaned",Msg.arg("count",missing.size())); }
            catch(IOException ex) { editor.failure(player,"editor.free-trigger.save-failed",ex); }
            openFreeList(player);
        });
        menu.open(player);
    }
    private static double distanceSquared(Location at,BlockPos pos) {
        double dx=at.getX()-(pos.x()+.5),dy=at.getY()-pos.y(),dz=at.getZ()-(pos.z()+.5);
        return dx*dx+dy*dy+dz*dz;
    }
    private static String worldName(CommandSender viewer,UUID world) {
        World w=Bukkit.getWorld(world);
        return w==null ? Msg.plain(viewer,"editor.world-not-loaded",Msg.arg("id",world)) : w.getName();
    }
    /** True/false when the plate is (not) there, null when that cannot be told without loading the chunk. */
    static Boolean plate(UUID world,BlockPos pos,boolean load) {
        World w=Bukkit.getWorld(world);
        if(w==null || (!load && !w.isChunkLoaded(pos.x()>>4,pos.z()>>4))) return null;
        return w.getBlockAt(pos.x(),pos.y(),pos.z()).getType()==Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
    }
    /** A list entry: name (or number), position, actions and a warning when the plate is gone. */
    private ItemStack entryIcon(Player player,UUID world,Trigger t,int index,List<Component> extra) {
        List<Component> lore=new ArrayList<>(extra);
        t.actions().stream().limit(6).forEach(a -> lore.add(Component.text("・"+describe(player,a))));
        Boolean plate=plate(world,t.pos(),false);
        if(Boolean.FALSE.equals(plate)) lore.add(Msg.text(player,"editor.trigger.plate-missing"));
        else if(plate==null) lore.add(Msg.text(player,"editor.trigger.plate-unknown"));
        ItemStack icon=Msg.icon(player,Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"editor.triggers.entry",lore,
                Msg.arg("index",index+1),Msg.arg("pos",t.pos().describe()),Msg.arg("count",t.actions().size()));
        if(t.name()!=null) icon.editMeta(meta -> meta.displayName(Icons.text(t.name())));
        return icon;
    }

    // ---- One trigger ----
    private void openTrigger(Player player,Host host,BlockPos pos) {
        if(!host.exists()) { editor.openList(player); return; }
        Trigger trigger=host.get(pos);
        if(trigger==null) { editor.tell(player,"editor.trigger.deleted"); host.list(player); return; }
        String title=trigger.name()==null ? Msg.plain(player,"editor.trigger.title",Msg.arg("pos",pos.describe()))
                : Msg.plain(player,"editor.trigger.title-named",Msg.arg("name",trigger.name()));
        Menu menu=new Menu(4,title);
        for(int i=0;i<trigger.actions().size();i++) {
            int index=i; TriggerAction action=trigger.actions().get(i);
            menu.button(i,actionIcon(player,action,Msg.lines(player,"editor.trigger.action-entry",Msg.arg("index",i+1))),e -> openAction(player,host,pos,index));
        }
        if(trigger.actions().size()<Trigger.MAX_ACTIONS)
            menu.button(27,Msg.icon(player,Material.LIME_DYE,"editor.trigger.add"),e -> openAddAction(player,host,pos));
        menu.button(28,Msg.icon(player,Material.ARROW,"editor.back"),e -> host.back(player));
        menu.button(29,Msg.icon(player,Material.NAME_TAG,"editor.trigger.name",
                Msg.arg("current",trigger.name()==null ? Msg.text(player,"editor.none") : Component.text(trigger.name()))),e -> {
            if(e.isShiftClick() && e.isRightClick()) { if(host.change(player,pos,t -> t.withName(null))) openTrigger(player,host,pos); return; }
            editor.prompts.ask(player,"editor.prompt.trigger-name",text -> {
                if(text.isBlank() || text.length()>Trigger.MAX_NAME_LENGTH) { editor.tell(player,"editor.name-length",Msg.arg("max",Trigger.MAX_NAME_LENGTH)); return; }
                if(host.change(player,pos,t -> t.withName(text))) openTrigger(player,host,pos);
            },Msg.arg("max",Trigger.MAX_NAME_LENGTH));
        });
        menu.button(31,Msg.icon(player,Material.ENDER_PEARL,"editor.trigger.go"),e -> editor.teleport(player,host.world(),pos.x()+.5,pos.y(),pos.z()+.5,player.getYaw(),player.getPitch()));
        menu.button(33,Msg.icon(player,Material.PAPER,"editor.trigger.copy",Msg.arg("count",trigger.actions().size())),e -> {
            clipboard.put(player.getUniqueId(),trigger.actions());
            editor.tell(player,"editor.trigger.copied",Msg.arg("count",trigger.actions().size()));
        });
        List<TriggerAction> copied=clipboard.get(player.getUniqueId());
        menu.button(34,Msg.icon(player,Material.MAP,copied==null ? "editor.trigger.paste-empty" : "editor.trigger.paste",
                Msg.arg("count",copied==null ? 0 : copied.size())),e -> {
            List<TriggerAction> actions=clipboard.get(player.getUniqueId());
            if(actions==null) { editor.tell(player,"editor.trigger.paste-none"); return; }
            if(!trigger.actions().isEmpty() && !e.isShiftClick()) { editor.tell(player,"editor.trigger.paste-shift"); return; }
            if(host.change(player,pos,t -> t.withActions(actions))) { editor.tell(player,"editor.trigger.pasted",Msg.arg("count",actions.size())); openTrigger(player,host,pos); }
        });
        menu.button(35,Msg.icon(player,Material.BARRIER,"editor.trigger.delete"),e -> {
            if(!e.isShiftClick()) { editor.tell(player,"editor.trigger.delete-shift"); return; }
            if(host.delete(player,pos)) { editor.tell(player,"editor.trigger.delete-done"); host.list(player); }
        });
        menu.open(player);
    }
    private void addAction(Player player,Host host,BlockPos pos,TriggerAction action) {
        if(host.change(player,pos,t -> {
            if(t.actions().size()>=Trigger.MAX_ACTIONS) throw new IllegalStateException("Trigger full");
            return t.withAction(action);
        })) openTrigger(player,host,pos);
    }
    private void replaceAction(Player player,Host host,BlockPos pos,int index,TriggerAction action) {
        if(host.change(player,pos,t -> t.withAction(index,action))) openAction(player,host,pos,index);
    }
    private void openAddAction(Player player,Host host,BlockPos pos) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.action.add-title"));
        Runnable back=() -> openAddAction(player,host,pos);
        menu.button(10,Msg.icon(player,Material.CHEST_MINECART,"editor.action.give"),
                e -> items.pick(player,Msg.plain(player,"editor.action.give-pick"),ItemMenus.PickMode.GIVE,(ref,all) -> pickPlacement(player,host,pos,ref,back),back));
        menu.button(12,Msg.icon(player,Material.HOPPER_MINECART,"editor.action.remove"),
                e -> items.pick(player,Msg.plain(player,"editor.action.remove-pick"),ItemMenus.PickMode.REMOVE,(ref,all) -> addAction(player,host,pos,TriggerAction.remove(ref,all)),back));
        menu.button(14,Msg.icon(player,Material.ZOMBIE_HEAD,"editor.action.mob"),e -> askMob(player,host,pos,-1));
        menu.button(16,Msg.icon(player,Material.NOTE_BLOCK,"editor.action.sound"),e -> pickSound(player,host,pos,-1));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> openTrigger(player,host,pos));
        menu.open(player);
    }
    private void pickPlacement(Player player,Host host,BlockPos pos,ItemRef ref,Runnable back) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.placement.title",Msg.arg("item",editor.items.name(player,ref))));
        List<TriggerAction.Placement> all=TriggerAction.Placement.CHOICES;
        for(int i=0;i<all.size();i++) {
            TriggerAction.Placement p=all.get(i);
            String detail=switch(p) { case SLOT1 -> "editor.placement.slot1"; case EMPTY -> "editor.placement.empty"; default -> "editor.placement.other"; };
            ItemStack icon=new ItemStack(p==TriggerAction.Placement.EMPTY ? Material.GLASS_PANE : Material.ITEM_FRAME);
            Component label=Msg.text(player,p.key());
            icon.editMeta(meta -> { meta.displayName(label); meta.lore(Msg.lines(player,detail,Msg.arg("place",label))); });
            menu.button(11+i,icon,e -> addAction(player,host,pos,TriggerAction.give(ref,p)));
        }
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> back.run());
        menu.open(player);
    }
    private void askMob(Player player,Host host,BlockPos pos,int replace) {
        editor.prompts.ask(player,"editor.prompt.mob",text -> {
            MobSpec spec=MobSpec.parse(text);
            if(spec==null) { editor.tell(player,"editor.mob.invalid"); return; }
            Location at=player.getLocation();
            Spot spot=spec.position()==null ? new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch())
                    : new Spot(spec.position()[0],spec.position()[1],spec.position()[2],0,0);
            TriggerAction action=TriggerAction.mob(spec.stored(),spot);
            if(replace<0) addAction(player,host,pos,action); else replaceAction(player,host,pos,replace,action);
            editor.tell(player,"editor.mob.position",Msg.arg("spot",spot.describe()));
        });
    }
    private void pickSound(Player player,Host host,BlockPos pos,int replace) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.sound.title"));
        for(int i=0;i<SOUNDS.size();i++) {
            SoundPreset s=SOUNDS.get(i);
            menu.button(i,Msg.icon(player,s.icon(),"editor.sound.preset",Msg.arg("name",Msg.text(player,s.label())),Msg.arg("sound",s.key())),e -> {
                if(e.isRightClick()) addOrReplaceSound(player,host,pos,replace,s.key());
                else player.playSound(player.getLocation(),s.key(),SoundCategory.PLAYERS,1f,1f);
            });
        }
        menu.button(22,Msg.icon(player,Material.WRITABLE_BOOK,"editor.sound.input"),e ->
                editor.prompts.ask(player,"editor.prompt.sound",text -> {
                    String key=text.toLowerCase(Locale.ROOT);
                    if(!key.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) { editor.tell(player,"editor.sound.invalid"); return; }
                    addOrReplaceSound(player,host,pos,replace,key.contains(":") ? key : "minecraft:"+key);
                }));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> { if(replace<0) openAddAction(player,host,pos); else openAction(player,host,pos,replace); });
        menu.open(player);
    }
    private void addOrReplaceSound(Player player,Host host,BlockPos pos,int replace,String key) {
        if(replace<0) addAction(player,host,pos,TriggerAction.sound(key,1,1));
        else {
            Trigger trigger=host.get(pos); if(trigger==null || replace>=trigger.actions().size()) return;
            TriggerAction old=trigger.actions().get(replace);
            replaceAction(player,host,pos,replace,TriggerAction.sound(key,old.volume(),old.pitch()));
        }
    }
    private void openAction(Player player,Host host,BlockPos pos,int index) {
        if(!host.exists()) { editor.openList(player); return; }
        Trigger trigger=host.get(pos);
        if(trigger==null || index>=trigger.actions().size()) { openTrigger(player,host,pos); return; }
        TriggerAction action=trigger.actions().get(index);
        Menu menu=new Menu(3,Msg.plain(player,"editor.action.title",Msg.arg("index",index+1)));
        menu.label(4,actionIcon(player,action,List.of()));
        switch(action.type()) {
            case SOUND -> {
                menu.button(10,Msg.icon(player,Material.NOTE_BLOCK,"editor.action.sound-change",Msg.arg("sound",action.sound())),e -> pickSound(player,host,pos,index));
                menu.button(11,Msg.icon(player,Material.JUKEBOX,"editor.action.volume",Msg.arg("value",String.format(Locale.ROOT,"%.1f",action.volume()))),
                        e -> replaceAction(player,host,pos,index,action.withSound(round(action.volume()+(e.isRightClick() ? -.1f : .1f)),action.pitch())));
                menu.button(12,Msg.icon(player,Material.AMETHYST_SHARD,"editor.action.pitch",Msg.arg("value",String.format(Locale.ROOT,"%.2f",action.pitch()))),
                        e -> replaceAction(player,host,pos,index,action.withSound(action.volume(),round(action.pitch()+(e.isRightClick() ? -.05f : .05f)))));
                menu.button(13,Msg.icon(player,Material.BELL,"editor.action.listen"),
                        e -> player.playSound(player.getLocation(),action.sound(),SoundCategory.PLAYERS,action.volume(),action.pitch()));
            }
            case MOB -> {
                menu.button(10,Msg.icon(player,Material.WRITABLE_BOOK,"editor.action.mob-input",Msg.arg("mob",action.mob())),e -> askMob(player,host,pos,index));
                menu.button(11,Msg.icon(player,Material.COMPASS,"editor.action.mob-here",Msg.arg("spot",action.mobAt().describe())),e -> {
                    Location at=player.getLocation();
                    replaceAction(player,host,pos,index,TriggerAction.mob(action.mob(),new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch())));
                });
                menu.button(12,Msg.icon(player,Material.ENDER_PEARL,"editor.action.mob-go"),
                        e -> editor.teleport(player,host.world(),action.mobAt().x(),action.mobAt().y(),action.mobAt().z(),action.mobAt().yaw(),action.mobAt().pitch()));
            }
            case GIVE -> menu.button(10,Msg.icon(player,Material.ITEM_FRAME,"editor.action.placement",Msg.arg("place",Msg.text(player,action.placement().key()))),
                    e -> pickPlacement(player,host,pos,action.item(),() -> openAction(player,host,pos,index)));
            case REMOVE -> {}
        }
        if(index>0) menu.button(15,Msg.icon(player,Material.SPECTRAL_ARROW,"editor.action.move-up"),e -> move(player,host,pos,index,index-1));
        menu.button(16,Msg.icon(player,Material.BARRIER,"editor.action.delete"),e -> {
            if(host.change(player,pos,t -> t.withoutAction(index))) openTrigger(player,host,pos);
        });
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> openTrigger(player,host,pos));
        menu.open(player);
    }
    private void move(Player player,Host host,BlockPos pos,int from,int to) {
        if(host.change(player,pos,t -> {
            List<TriggerAction> actions=new ArrayList<>(t.actions());
            actions.add(to,actions.remove(from));
            return t.withActions(actions);
        })) openAction(player,host,pos,to);
    }
    private static float round(float value) { return Math.round(value*100)/100f; }
    String describe(CommandSender viewer,TriggerAction a) {
        return switch(a.type()) {
            case GIVE -> Msg.plain(viewer,"editor.describe.give",Msg.arg("item",editor.items.name(viewer,a.item())),Msg.arg("place",Msg.text(viewer,a.placement().key())));
            case REMOVE -> a.allLevels() && !a.item().isScaffold() && editor.items.isMace(a.item().template())
                    ? Msg.plain(viewer,"editor.describe.remove-all",Msg.arg("item",editor.items.name(viewer,new ItemRef(a.item().template(),0))))
                    : Msg.plain(viewer,"editor.describe.remove",Msg.arg("item",editor.items.name(viewer,a.item())));
            case MOB -> Msg.plain(viewer,"editor.describe.mob",Msg.arg("mob",a.mob().split(" ",2)[0]));
            case SOUND -> Msg.plain(viewer,"editor.describe.sound",Msg.arg("sound",a.sound()),
                    Msg.arg("volume",String.format(Locale.ROOT,"%.1f",a.volume())),Msg.arg("pitch",String.format(Locale.ROOT,"%.2f",a.pitch())));
        };
    }
    /** The floating label above a plate: the name (if any), then up to three actions. */
    List<String> label(Trigger t) {
        CommandSender viewer=Bukkit.getConsoleSender();
        List<String> lines=new ArrayList<>();
        if(t.name()!=null) lines.add(t.name());
        if(t.actions().isEmpty()) lines.add(Msg.plain(viewer,"editor.trigger.label-empty"));
        else {
            String summary=String.join(" / ",t.actions().stream().limit(3).map(a -> describe(viewer,a)).toList());
            lines.add(t.actions().size()>3 ? summary+" …" : summary);
        }
        return lines;
    }
    private ItemStack actionIcon(Player player,TriggerAction a,List<Component> extra) {
        List<Component> lore=new ArrayList<>();
        Material icon=switch(a.type()) {
            case GIVE -> Material.CHEST_MINECART;
            case REMOVE -> Material.HOPPER_MINECART;
            case MOB -> {
                lore.add(Component.text(a.mob().length()>60 ? a.mob().substring(0,60)+"…" : a.mob()));
                lore.add(Msg.text(player,"editor.describe.mob-at",Msg.arg("spot",a.mobAt().describe())));
                yield Material.ZOMBIE_HEAD;
            }
            case SOUND -> Material.NOTE_BLOCK;
        };
        lore.addAll(extra);
        if(a.item()!=null && !editor.items.exists(a.item())) lore.add(Msg.text(player,"editor.warn.template-deleted-short"));
        ItemStack item=new ItemStack(icon);
        String name=describe(player,a);
        item.editMeta(meta -> { meta.displayName(Icons.text(name)); meta.lore(lore.stream().map(c -> c.decorationIfAbsent(net.kyori.adventure.text.format.TextDecoration.ITALIC,net.kyori.adventure.text.format.TextDecoration.State.FALSE)).toList()); });
        return item;
    }
}
