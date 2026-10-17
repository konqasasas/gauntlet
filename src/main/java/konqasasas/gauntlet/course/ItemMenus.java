package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.play.CourseItems;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Item templates, the item picker and the initial loadout. */
final class ItemMenus {
    enum PickMode { GIVE,REMOVE,LOADOUT }
    interface Picked { void accept(ItemRef ref,boolean allLevels); }
    /** One-click additions; anything else is registered from the hand. The name is a message key. */
    private record Preset(Material material,String key,boolean unbreakable) {}
    private static final List<Preset> PRESETS=List.of(
            new Preset(Material.MACE,"editor.preset.mace",true),new Preset(Material.WIND_CHARGE,"editor.preset.wind-charge",false),
            new Preset(Material.ELYTRA,"editor.preset.elytra",true),new Preset(Material.FIREWORK_ROCKET,"editor.preset.rocket",false),
            new Preset(Material.ENDER_PEARL,"editor.preset.ender-pearl",false),new Preset(Material.TRIDENT,"editor.preset.trident",true));
    private final CourseEditor editor;
    ItemMenus(CourseEditor editor) { this.editor=editor; }

    // ---- Templates ----
    void openTemplates(Player player,Runnable back) {
        Menu menu=new Menu(6,Msg.plain(player,"editor.templates.title"));
        List<TemplateStore.Template> all=editor.templates.all();
        for(int i=0;i<Math.min(all.size(),36);i++) {
            TemplateStore.Template t=all.get(i);
            ItemStack icon=preview(player,new ItemRef(t.id(),0),Msg.lines(player,"editor.templates.entry",Msg.arg("state",onOff(player,t.unbreakable()))));
            menu.button(i,icon,e -> {
                if(!editor.admin(player)) return;
                if(e.isShiftClick() && e.isRightClick()) { change(player,() -> editor.templates.remove(t.id()),"editor.templates.deleted",Msg.arg("name",t.name())); openTemplates(player,back); }
                else if(e.isRightClick()) editor.prompts.ask(player,"editor.prompt.template-rename",text -> {
                    if(!validName(player,text)) return;
                    change(player,() -> editor.templates.put(t.withName(text)),"editor.templates.renamed"); openTemplates(player,back);
                });
                else { change(player,() -> editor.templates.put(t.withUnbreakable(!t.unbreakable())),null); openTemplates(player,back); }
            });
        }
        if(all.size()>36) menu.label(44,Msg.icon(player,Material.PAPER,"editor.templates.more"));
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back"),e -> back.run());
        menu.button(47,Msg.icon(player,Material.ITEM_FRAME,"editor.templates.from-hand"),e -> {
            if(!editor.admin(player)) return;
            ItemStack hand=player.getInventory().getItemInMainHand();
            if(hand.getType().isAir()) { editor.tell(player,"editor.templates.hand-empty"); return; }
            ItemStack copy=hand.clone();
            editor.prompts.ask(player,"editor.prompt.template-name",text -> {
                if(!validName(player,text)) return;
                change(player,() -> editor.templates.add(text,CourseItems.encode(copy),true),"editor.templates.added-hand",Msg.arg("name",text));
                openTemplates(player,back);
            });
        });
        for(int i=0;i<PRESETS.size();i++) {
            Preset p=PRESETS.get(i);
            String name=Msg.plain(player,p.key());
            menu.button(48+i,Msg.icon(player,p.material(),"editor.templates.preset",Msg.arg("name",name),Msg.arg("state",onOff(player,p.unbreakable()))),e -> {
                if(!editor.admin(player)) return;
                change(player,() -> editor.templates.add(name,CourseItems.encode(new ItemStack(p.material())),p.unbreakable()),"editor.templates.added",Msg.arg("name",name));
                openTemplates(player,back);
            });
        }
        menu.open(player);
    }
    private static Component onOff(Player player,boolean on) { return Msg.text(player,on ? "setting.value.on" : "setting.value.off"); }
    private interface Change { void run() throws IOException; }
    private void change(Player player,Change change,String doneKey,TagResolver... args) {
        try { change.run(); if(doneKey!=null) editor.tell(player,doneKey,args); }
        catch(IOException|RuntimeException e) { editor.failure(player,"editor.templates.save-failed",e); }
    }
    private boolean validName(Player player,String text) {
        if(!text.isBlank() && text.length()<=Course.MAX_NAME_LENGTH) return true;
        editor.tell(player,"editor.name-length",Msg.arg("max",Course.MAX_NAME_LENGTH)); return false;
    }
    ItemStack preview(Player player,ItemRef ref,List<Component> lore) {
        ItemStack item=editor.items.create(ref);
        if(item==null) item=new ItemStack(Material.BARRIER);
        else { item=item.clone(); item.setAmount(Math.max(1,item.getAmount())); }
        String name=editor.items.name(player,ref);
        item.editMeta(meta -> { meta.displayName(Icons.text(name)); meta.lore(lore); });
        return item;
    }

    // ---- Picker ----
    void pick(Player player,String title,PickMode mode,Picked picked,Runnable back) {
        Menu menu=new Menu(6,title);
        int slot=0;
        menu.button(slot++,preview(player,ItemRef.scaffold(),Msg.lines(player,"editor.pick.scaffold")),e -> picked.accept(ItemRef.scaffold(),true));
        for(TemplateStore.Template t:editor.templates.all()) {
            if(slot>=45) break;
            ItemRef base=new ItemRef(t.id(),0);
            boolean mace=editor.items.isMace(t.id());
            menu.button(slot++,preview(player,base,Msg.lines(player,mace ? "editor.pick.choose-level" : "editor.pick.choose")),e -> {
                if(mace) pickLevel(player,t,mode,picked,() -> pick(player,title,mode,picked,back));
                else picked.accept(base,true);
            });
        }
        if(mode==PickMode.LOADOUT) menu.button(49,Msg.icon(player,Material.GLASS_PANE,"editor.pick.empty"),e -> picked.accept(null,true));
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back"),e -> back.run());
        menu.button(53,Msg.icon(player,Material.CHEST,"editor.pick.manage"),e -> openTemplates(player,() -> pick(player,title,mode,picked,back)));
        menu.open(player);
    }
    private void pickLevel(Player player,TemplateStore.Template t,PickMode mode,Picked picked,Runnable back) {
        Menu menu=new Menu(3,Msg.plain(player,"editor.pick.level-title",Msg.arg("name",t.name())));
        for(int level=1;level<=ItemRef.MAX_LEVEL;level++) {
            ItemRef ref=new ItemRef(t.id(),level);
            ItemStack icon=preview(player,ref,Msg.lines(player,"editor.pick.choose")); icon.setAmount(level);
            menu.button(10+level,icon,e -> picked.accept(ref,false));
        }
        if(mode==PickMode.REMOVE) menu.button(15,Msg.icon(player,Material.HOPPER,"editor.pick.all-levels"),e -> picked.accept(new ItemRef(t.id(),0),true));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> back.run());
        menu.open(player);
    }

    // ---- Loadout ----
    void openLoadout(Player player,String id) {
        Course course=editor.store.get(id);
        if(course==null) { editor.openList(player); return; }
        Menu menu=new Menu(3,Msg.plain(player,"editor.loadout.title",Msg.arg("name",course.name())));
        for(int i=0;i<Course.COURSE_SLOTS;i++) {
            int slot=i; ItemRef ref=course.loadout().get(i);
            List<Component> lore=Msg.lines(player,i==0 ? "editor.loadout.slot-first" : "editor.loadout.slot-other",Msg.arg("slot",i+1));
            ItemStack icon=ref==null ? Msg.icon(player,Material.GLASS_PANE,"editor.loadout.empty",lore,Msg.arg("slot",i+1)) : preview(player,ref,lore);
            menu.button(11+i,icon,e -> pick(player,Msg.plain(player,"editor.loadout.pick-title",Msg.arg("slot",slot+1)),PickMode.LOADOUT,(picked,all) -> {
                if(editor.save(player,id,c -> c.edit().loadout(slot,picked).build())) openLoadout(player,id);
            },() -> openLoadout(player,id)));
        }
        menu.label(15,Msg.icon(player,Material.BOOK,"editor.loadout.system"));
        menu.button(18,Msg.icon(player,Material.ARROW,"editor.back"),e -> editor.openEdit(player,id));
        menu.open(player);
    }
}
