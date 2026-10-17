package konqasasas.gauntlet.course;

import java.util.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Registering iron pressure plates as triggers and editing their actions. */
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
    private final CourseEditor editor;
    private final ItemMenus items;
    TriggerMenus(CourseEditor editor,ItemMenus items) { this.editor=editor; this.items=items; }

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
        openTrigger(player,id,pos);
    }
    void openList(Player player,String id) {
        Course course=editor.store.get(id); if(course==null) { editor.openList(player); return; }
        Menu menu=new Menu(6,Msg.plain(player,"editor.triggers.title",Msg.arg("name",course.name())));
        List<Trigger> triggers=course.triggers();
        for(int i=0;i<Math.min(triggers.size(),45);i++) {
            Trigger t=triggers.get(i);
            List<Component> lore=new ArrayList<>();
            t.actions().stream().limit(6).forEach(a -> lore.add(Component.text("・"+describe(player,a))));
            menu.button(i,Msg.icon(player,Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"editor.triggers.entry",lore,
                    Msg.arg("index",i+1),Msg.arg("pos",t.pos().describe()),Msg.arg("count",t.actions().size())),e -> openTrigger(player,id,t.pos()));
        }
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back"),e -> editor.openEdit(player,id));
        menu.open(player);
    }
    private void openTrigger(Player player,String id,BlockPos pos) {
        Course course=editor.store.get(id); if(course==null) { editor.openList(player); return; }
        Trigger trigger=course.trigger(pos);
        if(trigger==null) { editor.tell(player,"editor.trigger.deleted"); openList(player,id); return; }
        Menu menu=new Menu(4,Msg.plain(player,"editor.trigger.title",Msg.arg("pos",pos.describe())));
        for(int i=0;i<trigger.actions().size();i++) {
            int index=i; TriggerAction action=trigger.actions().get(i);
            menu.button(i,actionIcon(player,action,Msg.lines(player,"editor.trigger.action-entry",Msg.arg("index",i+1))),e -> openAction(player,id,pos,index));
        }
        if(trigger.actions().size()<Trigger.MAX_ACTIONS)
            menu.button(27,Msg.icon(player,Material.LIME_DYE,"editor.trigger.add"),e -> openAddAction(player,id,pos));
        menu.button(28,Msg.icon(player,Material.ARROW,"editor.back"),e -> editor.openEdit(player,id));
        menu.button(31,Msg.icon(player,Material.ENDER_PEARL,"editor.trigger.go"),e -> editor.teleport(player,course,pos.x()+.5,pos.y(),pos.z()+.5,player.getYaw(),player.getPitch()));
        menu.button(35,Msg.icon(player,Material.BARRIER,"editor.trigger.delete"),e -> {
            if(!e.isShiftClick()) { editor.tell(player,"editor.trigger.delete-shift"); return; }
            if(editor.save(player,id,c -> c.edit().removeTrigger(pos).build())) { editor.tell(player,"editor.trigger.delete-done"); openList(player,id); }
        });
        menu.open(player);
    }
    private void addAction(Player player,String id,BlockPos pos,TriggerAction action) {
        if(editor.save(player,id,c -> {
            Trigger t=c.trigger(pos);
            if(t==null || t.actions().size()>=Trigger.MAX_ACTIONS) throw new IllegalStateException("Trigger deleted or full");
            return c.edit().trigger(t.withAction(action)).build();
        })) openTrigger(player,id,pos);
    }
    private void replaceAction(Player player,String id,BlockPos pos,int index,TriggerAction action) {
        if(editor.save(player,id,c -> c.edit().trigger(c.trigger(pos).withAction(index,action)).build())) openAction(player,id,pos,index);
    }
    private void openAddAction(Player player,String id,BlockPos pos) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.action.add-title"));
        Runnable back=() -> openAddAction(player,id,pos);
        menu.button(10,Msg.icon(player,Material.CHEST_MINECART,"editor.action.give"),
                e -> items.pick(player,Msg.plain(player,"editor.action.give-pick"),ItemMenus.PickMode.GIVE,(ref,all) -> pickPlacement(player,id,pos,ref,back),back));
        menu.button(12,Msg.icon(player,Material.HOPPER_MINECART,"editor.action.remove"),
                e -> items.pick(player,Msg.plain(player,"editor.action.remove-pick"),ItemMenus.PickMode.REMOVE,(ref,all) -> addAction(player,id,pos,TriggerAction.remove(ref,all)),back));
        menu.button(14,Msg.icon(player,Material.ZOMBIE_HEAD,"editor.action.mob"),e -> askMob(player,id,pos,-1));
        menu.button(16,Msg.icon(player,Material.NOTE_BLOCK,"editor.action.sound"),e -> pickSound(player,id,pos,-1));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> openTrigger(player,id,pos));
        menu.open(player);
    }
    private void pickPlacement(Player player,String id,BlockPos pos,ItemRef ref,Runnable back) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.placement.title",Msg.arg("item",editor.items.name(player,ref))));
        List<TriggerAction.Placement> all=TriggerAction.Placement.CHOICES;
        for(int i=0;i<all.size();i++) {
            TriggerAction.Placement p=all.get(i);
            String detail=switch(p) { case SLOT1 -> "editor.placement.slot1"; case EMPTY -> "editor.placement.empty"; default -> "editor.placement.other"; };
            ItemStack icon=new ItemStack(p==TriggerAction.Placement.EMPTY ? Material.GLASS_PANE : Material.ITEM_FRAME);
            Component label=Msg.text(player,p.key());
            icon.editMeta(meta -> { meta.displayName(label); meta.lore(Msg.lines(player,detail,Msg.arg("place",label))); });
            menu.button(11+i,icon,e -> addAction(player,id,pos,TriggerAction.give(ref,p)));
        }
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> back.run());
        menu.open(player);
    }
    private void askMob(Player player,String id,BlockPos pos,int replace) {
        editor.prompts.ask(player,"editor.prompt.mob",text -> {
            MobSpec spec=MobSpec.parse(text);
            if(spec==null) { editor.tell(player,"editor.mob.invalid"); return; }
            Location at=player.getLocation();
            Spot spot=spec.position()==null ? new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch())
                    : new Spot(spec.position()[0],spec.position()[1],spec.position()[2],0,0);
            TriggerAction action=TriggerAction.mob(spec.stored(),spot);
            if(replace<0) addAction(player,id,pos,action); else replaceAction(player,id,pos,replace,action);
            editor.tell(player,"editor.mob.position",Msg.arg("spot",spot.describe()));
        });
    }
    private void pickSound(Player player,String id,BlockPos pos,int replace) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.sound.title"));
        for(int i=0;i<SOUNDS.size();i++) {
            SoundPreset s=SOUNDS.get(i);
            menu.button(i,Msg.icon(player,s.icon(),"editor.sound.preset",Msg.arg("name",Msg.text(player,s.label())),Msg.arg("sound",s.key())),e -> {
                if(e.isRightClick()) addOrReplaceSound(player,id,pos,replace,s.key());
                else player.playSound(player.getLocation(),s.key(),SoundCategory.PLAYERS,1f,1f);
            });
        }
        menu.button(22,Msg.icon(player,Material.WRITABLE_BOOK,"editor.sound.input"),e ->
                editor.prompts.ask(player,"editor.prompt.sound",text -> {
                    String key=text.toLowerCase(Locale.ROOT);
                    if(!key.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) { editor.tell(player,"editor.sound.invalid"); return; }
                    addOrReplaceSound(player,id,pos,replace,key.contains(":") ? key : "minecraft:"+key);
                }));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> { if(replace<0) openAddAction(player,id,pos); else openAction(player,id,pos,replace); });
        menu.open(player);
    }
    private void addOrReplaceSound(Player player,String id,BlockPos pos,int replace,String key) {
        if(replace<0) addAction(player,id,pos,TriggerAction.sound(key,1,1));
        else {
            Course course=editor.store.get(id); if(course==null || course.trigger(pos)==null) return;
            TriggerAction old=course.trigger(pos).actions().get(replace);
            replaceAction(player,id,pos,replace,TriggerAction.sound(key,old.volume(),old.pitch()));
        }
    }
    private void openAction(Player player,String id,BlockPos pos,int index) {
        Course course=editor.store.get(id); if(course==null) { editor.openList(player); return; }
        Trigger trigger=course.trigger(pos);
        if(trigger==null || index>=trigger.actions().size()) { openTrigger(player,id,pos); return; }
        TriggerAction action=trigger.actions().get(index);
        Menu menu=new Menu(3,Msg.plain(player,"editor.action.title",Msg.arg("index",index+1)));
        menu.label(4,actionIcon(player,action,List.of()));
        switch(action.type()) {
            case SOUND -> {
                menu.button(10,Msg.icon(player,Material.NOTE_BLOCK,"editor.action.sound-change",Msg.arg("sound",action.sound())),e -> pickSound(player,id,pos,index));
                menu.button(11,Msg.icon(player,Material.JUKEBOX,"editor.action.volume",Msg.arg("value",String.format(Locale.ROOT,"%.1f",action.volume()))),
                        e -> replaceAction(player,id,pos,index,action.withSound(round(action.volume()+(e.isRightClick() ? -.1f : .1f)),action.pitch())));
                menu.button(12,Msg.icon(player,Material.AMETHYST_SHARD,"editor.action.pitch",Msg.arg("value",String.format(Locale.ROOT,"%.2f",action.pitch()))),
                        e -> replaceAction(player,id,pos,index,action.withSound(action.volume(),round(action.pitch()+(e.isRightClick() ? -.05f : .05f)))));
                menu.button(13,Msg.icon(player,Material.BELL,"editor.action.listen"),
                        e -> player.playSound(player.getLocation(),action.sound(),SoundCategory.PLAYERS,action.volume(),action.pitch()));
            }
            case MOB -> {
                menu.button(10,Msg.icon(player,Material.WRITABLE_BOOK,"editor.action.mob-input",Msg.arg("mob",action.mob())),e -> askMob(player,id,pos,index));
                menu.button(11,Msg.icon(player,Material.COMPASS,"editor.action.mob-here",Msg.arg("spot",action.mobAt().describe())),e -> {
                    Location at=player.getLocation();
                    replaceAction(player,id,pos,index,TriggerAction.mob(action.mob(),new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch())));
                });
                menu.button(12,Msg.icon(player,Material.ENDER_PEARL,"editor.action.mob-go"),
                        e -> editor.teleport(player,course,action.mobAt().x(),action.mobAt().y(),action.mobAt().z(),action.mobAt().yaw(),action.mobAt().pitch()));
            }
            case GIVE -> menu.button(10,Msg.icon(player,Material.ITEM_FRAME,"editor.action.placement",Msg.arg("place",Msg.text(player,action.placement().key()))),
                    e -> pickPlacement(player,id,pos,action.item(),() -> openAction(player,id,pos,index)));
            case REMOVE -> {}
        }
        if(index>0) menu.button(15,Msg.icon(player,Material.SPECTRAL_ARROW,"editor.action.move-up"),e -> move(player,id,pos,index,index-1));
        menu.button(16,Msg.icon(player,Material.BARRIER,"editor.action.delete"),e -> {
            if(editor.save(player,id,c -> c.edit().trigger(c.trigger(pos).withoutAction(index)).build())) openTrigger(player,id,pos);
        });
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> openTrigger(player,id,pos));
        menu.open(player);
    }
    private void move(Player player,String id,BlockPos pos,int from,int to) {
        if(editor.save(player,id,c -> {
            List<TriggerAction> actions=new ArrayList<>(c.trigger(pos).actions());
            actions.add(to,actions.remove(from));
            return c.edit().trigger(new Trigger(pos,actions)).build();
        })) openAction(player,id,pos,to);
    }
    private static float round(float value) { return Math.round(value*100)/100f; }
    String describe(Player player,TriggerAction a) {
        return switch(a.type()) {
            case GIVE -> Msg.plain(player,"editor.describe.give",Msg.arg("item",editor.items.name(player,a.item())),Msg.arg("place",Msg.text(player,a.placement().key())));
            case REMOVE -> a.allLevels() && !a.item().isScaffold() && editor.items.isMace(a.item().template())
                    ? Msg.plain(player,"editor.describe.remove-all",Msg.arg("item",editor.items.name(player,new ItemRef(a.item().template(),0))))
                    : Msg.plain(player,"editor.describe.remove",Msg.arg("item",editor.items.name(player,a.item())));
            case MOB -> Msg.plain(player,"editor.describe.mob",Msg.arg("mob",a.mob().split(" ",2)[0]));
            case SOUND -> Msg.plain(player,"editor.describe.sound",Msg.arg("sound",a.sound()),
                    Msg.arg("volume",String.format(Locale.ROOT,"%.1f",a.volume())),Msg.arg("pitch",String.format(Locale.ROOT,"%.2f",a.pitch())));
        };
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
