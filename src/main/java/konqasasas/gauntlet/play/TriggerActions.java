package konqasasas.gauntlet.play;

import java.util.*;
import java.util.function.BiFunction;
import konqasasas.gauntlet.course.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/** Runs one trigger action, for participants in a course and for builders trying a section with the kit. */
final class TriggerActions {
    private final CourseItems items;
    private final CourseMobs mobs;
    private final WaterFlows water;
    TriggerActions(CourseItems items,CourseMobs mobs,WaterFlows water) { this.items=items; this.mobs=mobs; this.water=water; }

    /**
     * key identifies the action (plate and index) in spawned, which holds the mobs
     * each action has out. decorate adjusts a given item for its hotbar slot.
     */
    void run(Player player,World world,String key,TriggerAction action,Map<String,List<UUID>> spawned,BiFunction<Integer,ItemStack,ItemStack> decorate) {
        PlayerInventory inventory=player.getInventory();
        switch(action.type()) {
            case GIVE -> {
                ItemStack item=items.create(action.item());
                if(item==null) { builderWarning(player,"editor.warn.template-deleted"); return; }
                for(int slot=0;slot<inventory.getSize();slot++) if(action.item().equals(items.ref(inventory.getItem(slot)))) return;
                int slot=action.placement().slot();
                if(slot<0) for(int i=1;i<Course.COURSE_SLOTS && slot<0;i++) { ItemStack there=inventory.getItem(i); if(there==null || there.getType().isAir()) slot=i; }
                if(slot<0) { builderWarning(player,"editor.warn.no-empty-slot"); return; }
                Inventories.put(inventory,slot,decorate.apply(slot,item));
            }
            case REMOVE -> {
                for(int slot=0;slot<inventory.getSize();slot++) {
                    ItemRef ref=items.ref(inventory.getItem(slot));
                    if(ref!=null && ref.template().equals(action.item().template()) && (action.allLevels() || ref.level()==action.item().level())) inventory.setItem(slot,null);
                }
            }
            case MOB -> {
                List<UUID> alive=spawned.getOrDefault(key,List.of());
                if(mobs.anyAlive(alive)) return;
                MobSpec spec=MobSpec.parse(action.mob());
                if(world==null || spec==null) return;
                List<UUID> ids=mobs.spawn(world,action.mobAt(),spec);
                if(ids.isEmpty()) builderWarning(player,"editor.warn.mob-failed",Msg.arg("mob",action.mob()));
                spawned.put(key,ids);
            }
            case SOUND -> Sounds.course(player,action.sound(),action.volume(),action.pitch());
            case WATER -> { if(world!=null) water.start(player,world,key,action.waterCells(),action.waterTicks()); }
        }
    }
    private static void builderWarning(Player player,String key,TagResolver... args) {
        if(player.hasPermission("gauntlet.admin")) Msg.send(player,key,args);
    }
}
