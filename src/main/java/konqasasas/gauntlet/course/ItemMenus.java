package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import konqasasas.gauntlet.play.CourseItems;
import konqasasas.gauntlet.ui.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Item templates, the item picker and the initial loadout. */
final class ItemMenus {
    enum PickMode { GIVE,REMOVE,LOADOUT }
    interface Picked { void accept(ItemRef ref,boolean allLevels); }
    /** One-click additions; anything else is registered from the hand. */
    private record Preset(Material material,String name,boolean unbreakable) {}
    private static final List<Preset> PRESETS=List.of(
            new Preset(Material.MACE,"ウィンドバーストメイス",true),new Preset(Material.WIND_CHARGE,"ウィンドチャージ",false),
            new Preset(Material.ELYTRA,"エリトラ",true),new Preset(Material.FIREWORK_ROCKET,"ロケット花火",false),
            new Preset(Material.ENDER_PEARL,"エンダーパール",false),new Preset(Material.TRIDENT,"トライデント",true));
    private final CourseEditor editor;
    ItemMenus(CourseEditor editor) { this.editor=editor; }

    // ---- Templates ----
    void openTemplates(Player player,Runnable back) {
        Menu menu=new Menu(6,"アイテムテンプレート");
        List<TemplateStore.Template> all=editor.templates.all();
        for(int i=0;i<Math.min(all.size(),36);i++) {
            TemplateStore.Template t=all.get(i);
            ItemStack icon=preview(new ItemRef(t.id(),0),List.of("耐久値無限："+(t.unbreakable() ? "オン" : "オフ"),
                    "左クリック：耐久値無限の切り替え","右クリック：名前を変更","Shift＋右クリック：削除"));
            menu.button(i,icon,e -> {
                if(!editor.admin(player)) return;
                if(e.isShiftClick() && e.isRightClick()) { change(player,() -> editor.templates.remove(t.id()),"テンプレート「"+t.name()+"」を削除しました。"); openTemplates(player,back); }
                else if(e.isRightClick()) editor.prompts.ask(player,"新しい名前を入力してください",text -> {
                    if(!validName(player,text)) return;
                    change(player,() -> editor.templates.put(t.withName(text)),"名前を変更しました。"); openTemplates(player,back);
                });
                else { change(player,() -> editor.templates.put(t.withUnbreakable(!t.unbreakable())),null); openTemplates(player,back); }
            });
        }
        if(all.size()>36) menu.label(44,Icons.icon(Material.PAPER,"※37件目以降は未表示",List.of()));
        menu.button(45,Icons.icon(Material.ARROW,"戻る",List.of()),e -> back.run());
        menu.button(47,Icons.icon(Material.ITEM_FRAME,"手持ちのアイテムを登録",List.of("メインハンドのアイテムを個数ごと登録","メイスならレベルは渡すときに選ぶ")),e -> {
            if(!editor.admin(player)) return;
            ItemStack hand=player.getInventory().getItemInMainHand();
            if(hand.getType().isAir()) { Icons.tell(player,"登録するアイテムをメインハンドに持ってください。"); return; }
            ItemStack copy=hand.clone();
            editor.prompts.ask(player,"テンプレートの名前を入力してください",text -> {
                if(!validName(player,text)) return;
                change(player,() -> editor.templates.add(text,CourseItems.encode(copy),true),"テンプレート「"+text+"」を登録しました（耐久値無限：オン）。");
                openTemplates(player,back);
            });
        });
        for(int i=0;i<PRESETS.size();i++) {
            Preset p=PRESETS.get(i);
            menu.button(48+i,Icons.icon(p.material(),"追加："+p.name(),List.of("クリックでテンプレートに追加","耐久値無限："+(p.unbreakable() ? "オン" : "オフ"))),e -> {
                if(!editor.admin(player)) return;
                change(player,() -> editor.templates.add(p.name(),CourseItems.encode(new ItemStack(p.material())),p.unbreakable()),"テンプレート「"+p.name()+"」を追加しました。");
                openTemplates(player,back);
            });
        }
        menu.open(player);
    }
    private interface Change { void run() throws IOException; }
    private void change(Player player,Change change,String done) {
        try { change.run(); if(done!=null) Icons.tell(player,done); }
        catch(IOException|RuntimeException e) { editor.failure(player,"テンプレートを保存できませんでした。",e); }
    }
    private static boolean validName(Player player,String text) {
        if(!text.isBlank() && text.length()<=Course.MAX_NAME_LENGTH) return true;
        Icons.tell(player,"名前は1〜"+Course.MAX_NAME_LENGTH+"文字にしてください。"); return false;
    }
    ItemStack preview(ItemRef ref,List<String> lore) {
        ItemStack item=editor.items.create(ref);
        if(item==null) return Icons.icon(Material.BARRIER,editor.items.name(ref),lore);
        item=item.clone(); item.setAmount(Math.max(1,item.getAmount()));
        item.editMeta(meta -> { meta.displayName(Icons.text(editor.items.name(ref))); meta.lore(lore.stream().map(Icons::text).toList()); });
        return item;
    }

    // ---- Picker ----
    void pick(Player player,String title,PickMode mode,Picked picked,Runnable back) {
        Menu menu=new Menu(6,title);
        int slot=0;
        menu.button(slot++,preview(ItemRef.scaffold(),List.of("専用の設置用ブロック（64個を維持）")),e -> picked.accept(ItemRef.scaffold(),true));
        for(TemplateStore.Template t:editor.templates.all()) {
            if(slot>=45) break;
            ItemRef base=new ItemRef(t.id(),0);
            boolean mace=editor.items.isMace(t.id());
            menu.button(slot++,preview(base,List.of(mace ? "クリック：レベルを選ぶ" : "クリックで選ぶ")),e -> {
                if(mace) pickLevel(player,t,mode,picked,() -> pick(player,title,mode,picked,back));
                else picked.accept(base,true);
            });
        }
        if(mode==PickMode.LOADOUT) menu.button(49,Icons.icon(Material.GLASS_PANE,"空にする",List.of()),e -> picked.accept(null,true));
        menu.button(45,Icons.icon(Material.ARROW,"戻る",List.of()),e -> back.run());
        menu.button(53,Icons.icon(Material.CHEST,"テンプレートを管理",List.of()),e -> openTemplates(player,() -> pick(player,title,mode,picked,back)));
        menu.open(player);
    }
    private void pickLevel(Player player,TemplateStore.Template t,PickMode mode,Picked picked,Runnable back) {
        Menu menu=new Menu(3,t.name()+"：レベル");
        for(int level=1;level<=ItemRef.MAX_LEVEL;level++) {
            ItemRef ref=new ItemRef(t.id(),level);
            ItemStack icon=preview(ref,List.of("クリックで選ぶ")); icon.setAmount(level);
            menu.button(10+level,icon,e -> picked.accept(ref,false));
        }
        if(mode==PickMode.REMOVE) menu.button(15,Icons.icon(Material.HOPPER,"全レベル",List.of("どのレベルでも消す")),e -> picked.accept(new ItemRef(t.id(),0),true));
        menu.button(18,Icons.icon(Material.ARROW,"戻る",List.of()),e -> back.run());
        menu.open(player);
    }

    // ---- Loadout ----
    void openLoadout(Player player,String id) {
        Course course=editor.store.get(id);
        if(course==null) { editor.openList(player); return; }
        Menu menu=new Menu(3,"初期装備："+course.name());
        for(int i=0;i<Course.COURSE_SLOTS;i++) {
            int slot=i; ItemRef ref=course.loadout().get(i);
            List<String> lore=List.of((i+1)+"番スロット","クリックで変更",i==0 ? "1番は必須（Qでスタート／セーブ地点へ戻る）" : "空でもよい");
            ItemStack icon=ref==null ? Icons.icon(Material.GLASS_PANE,(i+1)+"番：空",lore) : preview(ref,lore);
            menu.button(11+i,icon,e -> pick(player,(slot+1)+"番の初期装備",PickMode.LOADOUT,(picked,all) -> {
                if(editor.save(player,id,c -> c.edit().loadout(slot,picked).build())) openLoadout(player,id);
            },() -> openLoadout(player,id)));
        }
        menu.label(16,Icons.icon(Material.BOOK,"5〜9番",List.of("5：本番／練習の切り替え","6：飛行（練習）","7：セーブ（練習）","8・9：ゴール後のリトライ・終了")));
        menu.button(18,Icons.icon(Material.ARROW,"戻る",List.of()),e -> editor.openEdit(player,id));
        menu.open(player);
    }
}
