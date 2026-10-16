package konqasasas.gauntlet.course;

import java.util.*;
import konqasasas.gauntlet.ui.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Registering iron pressure plates as triggers and editing their actions. */
final class TriggerMenus {
    private record SoundPreset(String key,String label,Material icon) {}
    private static final List<SoundPreset> SOUNDS=List.of(
            new SoundPreset("minecraft:entity.experience_orb.pickup","経験値",Material.EXPERIENCE_BOTTLE),
            new SoundPreset("minecraft:entity.player.levelup","レベルアップ",Material.ENCHANTING_TABLE),
            new SoundPreset("minecraft:block.note_block.pling","ピアノ（プリン）",Material.NOTE_BLOCK),
            new SoundPreset("minecraft:block.note_block.bell","ベル",Material.BELL),
            new SoundPreset("minecraft:block.note_block.chime","チャイム",Material.PACKED_ICE),
            new SoundPreset("minecraft:block.amethyst_block.chime","アメジスト",Material.AMETHYST_BLOCK),
            new SoundPreset("minecraft:entity.item.pickup","アイテムを拾う",Material.STICK),
            new SoundPreset("minecraft:item.armor.equip_elytra","エリトラ装備",Material.ELYTRA),
            new SoundPreset("minecraft:entity.wind_charge.wind_burst","ウィンドバースト",Material.WIND_CHARGE),
            new SoundPreset("minecraft:block.beacon.activate","ビーコン起動",Material.BEACON),
            new SoundPreset("minecraft:entity.firework_rocket.launch","ロケット発射",Material.FIREWORK_ROCKET),
            new SoundPreset("minecraft:entity.ender_dragon.growl","ドラゴンの咆哮",Material.DRAGON_HEAD),
            new SoundPreset("minecraft:entity.wither.spawn","ウィザー出現",Material.WITHER_SKELETON_SKULL),
            new SoundPreset("minecraft:block.anvil.land","金床",Material.ANVIL),
            new SoundPreset("minecraft:ui.toast.challenge_complete","進捗達成",Material.GOLD_INGOT),
            new SoundPreset("minecraft:block.bell.use","鐘",Material.LIGHTNING_ROD));
    private final CourseEditor editor;
    private final ItemMenus items;
    TriggerMenus(CourseEditor editor,ItemMenus items) { this.editor=editor; this.items=items; }

    /** Registers the iron pressure plate under the player if needed, then opens it. */
    void openAtFeet(Player player,String id) {
        Course course=editor.store.get(id); if(course==null) return;
        if(!player.getWorld().getUID().equals(course.world())) { Icons.tell(player,"コースと同じワールドで操作してください（"+CourseEditor.worldName(course)+"）。"); return; }
        Block feet=player.getLocation().getBlock();
        if(feet.getType()!=Material.HEAVY_WEIGHTED_PRESSURE_PLATE) { Icons.tell(player,"鉄の感圧板の上に立って押してください。"); return; }
        BlockPos pos=new BlockPos(feet.getX(),feet.getY(),feet.getZ());
        if(course.region()==null || !course.region().contains(pos.x(),pos.y(),pos.z())) { Icons.tell(player,"トリガーはコース範囲の中にしてください。"); return; }
        if(course.trigger(pos)==null) {
            if(!editor.save(player,id,c -> c.edit().trigger(new Trigger(pos,List.of())).build())) return;
            Icons.tell(player,"トリガーを登録しました："+pos.describe());
        }
        openTrigger(player,id,pos);
    }
    void openList(Player player,String id) {
        Course course=editor.store.get(id); if(course==null) { editor.openList(player); return; }
        Menu menu=new Menu(6,"トリガー一覧："+course.name());
        List<Trigger> triggers=course.triggers();
        for(int i=0;i<Math.min(triggers.size(),45);i++) {
            Trigger t=triggers.get(i);
            List<String> lore=new ArrayList<>(List.of("位置："+t.pos().describe(),"アクション："+t.actions().size()+"個"));
            t.actions().stream().limit(6).forEach(a -> lore.add("・"+describe(a)));
            lore.add("クリックで編集");
            menu.button(i,Icons.icon(Material.HEAVY_WEIGHTED_PRESSURE_PLATE,"トリガー "+(i+1),lore),e -> openTrigger(player,id,t.pos()));
        }
        menu.button(45,Icons.icon(Material.ARROW,"戻る",List.of()),e -> editor.openEdit(player,id));
        menu.open(player);
    }
    private void openTrigger(Player player,String id,BlockPos pos) {
        Course course=editor.store.get(id); if(course==null) { editor.openList(player); return; }
        Trigger trigger=course.trigger(pos);
        if(trigger==null) { Icons.tell(player,"このトリガーは削除されています。"); openList(player,id); return; }
        Menu menu=new Menu(4,"トリガー "+pos.describe());
        for(int i=0;i<trigger.actions().size();i++) {
            int index=i; TriggerAction action=trigger.actions().get(i);
            menu.button(i,actionIcon(action,List.of((i+1)+"番目に実行","クリック：編集・削除")),e -> openAction(player,id,pos,index));
        }
        if(trigger.actions().size()<Trigger.MAX_ACTIONS)
            menu.button(27,Icons.icon(Material.LIME_DYE,"アクションを追加",List.of("アイテム付与／アイテム削除／mob／サウンド")),e -> openAddAction(player,id,pos));
        menu.button(28,Icons.icon(Material.ARROW,"戻る",List.of()),e -> editor.openEdit(player,id));
        menu.button(31,Icons.icon(Material.ENDER_PEARL,"トリガーへ移動",List.of()),e -> editor.teleport(player,course,pos.x()+.5,pos.y(),pos.z()+.5,player.getYaw(),player.getPitch()));
        menu.button(35,Icons.icon(Material.BARRIER,"トリガーを削除",List.of("Shift＋クリックで削除","感圧板ブロック自体は残ります")),e -> {
            if(!e.isShiftClick()) { Icons.tell(player,"Shift＋クリックで削除します。"); return; }
            if(editor.save(player,id,c -> c.edit().removeTrigger(pos).build())) { Icons.tell(player,"トリガーを削除しました。"); openList(player,id); }
        });
        menu.open(player);
    }
    private void addAction(Player player,String id,BlockPos pos,TriggerAction action) {
        if(editor.save(player,id,c -> {
            Trigger t=c.trigger(pos);
            if(t==null || t.actions().size()>=Trigger.MAX_ACTIONS) throw new IllegalStateException("トリガーが削除されたか、アクションが上限です");
            return c.edit().trigger(t.withAction(action)).build();
        })) openTrigger(player,id,pos);
    }
    private void replaceAction(Player player,String id,BlockPos pos,int index,TriggerAction action) {
        if(editor.save(player,id,c -> c.edit().trigger(c.trigger(pos).withAction(index,action)).build())) openAction(player,id,pos,index);
    }
    private void openAddAction(Player player,String id,BlockPos pos) {
        Menu menu=new Menu(3,"アクションを追加");
        Runnable back=() -> openAddAction(player,id,pos);
        menu.button(10,Icons.icon(Material.CHEST_MINECART,"アイテム付与",List.of("持っていなければ渡す","同じアイテムでもレベルが違えば渡す")),
                e -> items.pick(player,"渡すアイテム",ItemMenus.PickMode.GIVE,(ref,all) -> pickPlacement(player,id,pos,ref,back),back));
        menu.button(12,Icons.icon(Material.HOPPER_MINECART,"アイテム削除",List.of("指定アイテムを消す","メイスは全レベルか指定レベル")),
                e -> items.pick(player,"消すアイテム",ItemMenus.PickMode.REMOVE,(ref,all) -> addAction(player,id,pos,TriggerAction.remove(ref,all)),back));
        menu.button(14,Icons.icon(Material.ZOMBIE_HEAD,"mobスポーン",List.of("summonの内容をチャットで入力","例：minecraft:strider {Age:0,Health:1}","座標を含めればその位置、なければ今の位置")),
                e -> askMob(player,id,pos,-1));
        menu.button(16,Icons.icon(Material.NOTE_BLOCK,"サウンド",List.of("一覧から選ぶか、IDを入力")),e -> pickSound(player,id,pos,-1));
        menu.button(18,Icons.icon(Material.ARROW,"戻る",List.of()),e -> openTrigger(player,id,pos));
        menu.open(player);
    }
    private void pickPlacement(Player player,String id,BlockPos pos,ItemRef ref,Runnable back) {
        Menu menu=new Menu(3,"渡す場所："+editor.items.name(ref));
        TriggerAction.Placement[] all=TriggerAction.Placement.values();
        for(int i=0;i<all.length;i++) {
            TriggerAction.Placement p=all[i];
            String detail=switch(p) {
                case SLOT1 -> "1番のアイテムを消して入れる";
                case EMPTY -> "空きがなければ渡さない";
                default -> p.label+"に入れる（元のアイテムは消える）";
            };
            menu.button(11+i,Icons.icon(p==TriggerAction.Placement.EMPTY ? Material.GLASS_PANE : Material.ITEM_FRAME,p.label,List.of(detail)),
                    e -> addAction(player,id,pos,TriggerAction.give(ref,p)));
        }
        menu.button(18,Icons.icon(Material.ARROW,"戻る",List.of()),e -> back.run());
        menu.open(player);
    }
    private void askMob(Player player,String id,BlockPos pos,int replace) {
        editor.prompts.ask(player,"mobの内容を入力してください（例：minecraft:strider -84 -56 -37 {Age:0,Silent:1b}）",text -> {
            MobSpec spec=MobSpec.parse(text);
            if(spec==null) { Icons.tell(player,"形式が正しくありません。「種類 [x y z] [{NBT}]」の形で、Tagsは使えません。"); return; }
            Location at=player.getLocation();
            Spot spot=spec.position()==null ? new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch())
                    : new Spot(spec.position()[0],spec.position()[1],spec.position()[2],0,0);
            TriggerAction action=TriggerAction.mob(spec.stored(),spot);
            if(replace<0) addAction(player,id,pos,action); else replaceAction(player,id,pos,replace,action);
            Icons.tell(player,"mobの位置："+spot.describe());
        });
    }
    private void pickSound(Player player,String id,BlockPos pos,int replace) {
        Menu menu=new Menu(3,"サウンドを選ぶ");
        for(int i=0;i<SOUNDS.size();i++) {
            SoundPreset s=SOUNDS.get(i);
            menu.button(i,Icons.icon(s.icon(),s.label(),List.of(s.key(),"左クリック：試聴","右クリック：決定")),e -> {
                if(e.isRightClick()) addOrReplaceSound(player,id,pos,replace,s.key());
                else player.playSound(player.getLocation(),s.key(),SoundCategory.PLAYERS,1f,1f);
            });
        }
        menu.button(22,Icons.icon(Material.WRITABLE_BOOK,"IDを入力",List.of("例：minecraft:block.note_block.harp")),e ->
                editor.prompts.ask(player,"サウンドIDを入力してください",text -> {
                    String key=text.toLowerCase(Locale.ROOT);
                    if(!key.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+")) { Icons.tell(player,"サウンドIDの形式が正しくありません。"); return; }
                    addOrReplaceSound(player,id,pos,replace,key.contains(":") ? key : "minecraft:"+key);
                }));
        menu.button(18,Icons.icon(Material.ARROW,"戻る",List.of()),e -> { if(replace<0) openAddAction(player,id,pos); else openAction(player,id,pos,replace); });
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
        Menu menu=new Menu(3,"アクション "+(index+1));
        menu.label(4,actionIcon(action,List.of()));
        switch(action.type()) {
            case SOUND -> {
                menu.button(10,Icons.icon(Material.NOTE_BLOCK,"音を変更",List.of(action.sound())),e -> pickSound(player,id,pos,index));
                menu.button(11,Icons.icon(Material.JUKEBOX,String.format("音量 %.1f",action.volume()),List.of("左：+0.1　右：-0.1")),
                        e -> replaceAction(player,id,pos,index,action.withSound(round(action.volume()+(e.isRightClick() ? -.1f : .1f)),action.pitch())));
                menu.button(12,Icons.icon(Material.AMETHYST_SHARD,String.format("音程 %.2f",action.pitch()),List.of("左：+0.05　右：-0.05")),
                        e -> replaceAction(player,id,pos,index,action.withSound(action.volume(),round(action.pitch()+(e.isRightClick() ? -.05f : .05f)))));
                menu.button(13,Icons.icon(Material.BELL,"試聴",List.of()),
                        e -> player.playSound(player.getLocation(),action.sound(),SoundCategory.PLAYERS,action.volume(),action.pitch()));
            }
            case MOB -> {
                menu.button(10,Icons.icon(Material.WRITABLE_BOOK,"内容を入力し直す",List.of(action.mob())),e -> askMob(player,id,pos,index));
                menu.button(11,Icons.icon(Material.COMPASS,"位置を今の場所にする",List.of("現在："+action.mobAt().describe())),e -> {
                    Location at=player.getLocation();
                    replaceAction(player,id,pos,index,TriggerAction.mob(action.mob(),new Spot(at.getX(),at.getY(),at.getZ(),at.getYaw(),at.getPitch())));
                });
                menu.button(12,Icons.icon(Material.ENDER_PEARL,"mobの位置へ移動",List.of()),
                        e -> editor.teleport(player,course,action.mobAt().x(),action.mobAt().y(),action.mobAt().z(),action.mobAt().yaw(),action.mobAt().pitch()));
            }
            case GIVE -> menu.button(10,Icons.icon(Material.ITEM_FRAME,"渡す場所を変更",List.of("現在："+action.placement().label)),
                    e -> pickPlacement(player,id,pos,action.item(),() -> openAction(player,id,pos,index)));
            case REMOVE -> {}
        }
        if(index>0) menu.button(15,Icons.icon(Material.SPECTRAL_ARROW,"前へ移動",List.of("実行順を1つ早める")),e -> move(player,id,pos,index,index-1));
        menu.button(16,Icons.icon(Material.BARRIER,"このアクションを削除",List.of()),e -> {
            if(editor.save(player,id,c -> c.edit().trigger(c.trigger(pos).withoutAction(index)).build())) openTrigger(player,id,pos);
        });
        menu.button(18,Icons.icon(Material.ARROW,"戻る",List.of()),e -> openTrigger(player,id,pos));
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
    String describe(TriggerAction a) {
        return switch(a.type()) {
            case GIVE -> "付与："+editor.items.name(a.item())+"（"+a.placement().label+"）";
            case REMOVE -> "削除："+(a.allLevels() && !a.item().isScaffold() && editor.items.isMace(a.item().template())
                    ? editor.items.name(new ItemRef(a.item().template(),0))+"（全レベル）" : editor.items.name(a.item()));
            case MOB -> "mob："+a.mob().split(" ",2)[0];
            case SOUND -> String.format("サウンド：%s（%.1f / %.2f）",a.sound(),a.volume(),a.pitch());
        };
    }
    private ItemStack actionIcon(TriggerAction a,List<String> extra) {
        List<String> lore=new ArrayList<>();
        Material icon=switch(a.type()) {
            case GIVE -> Material.CHEST_MINECART;
            case REMOVE -> Material.HOPPER_MINECART;
            case MOB -> { lore.add(a.mob().length()>60 ? a.mob().substring(0,60)+"…" : a.mob()); lore.add("位置："+a.mobAt().describe()); yield Material.ZOMBIE_HEAD; }
            case SOUND -> Material.NOTE_BLOCK;
        };
        lore.addAll(extra);
        if(a.item()!=null && !editor.items.exists(a.item())) lore.add("⚠ テンプレートが削除されています");
        return Icons.icon(icon,describe(a),lore);
    }
}
