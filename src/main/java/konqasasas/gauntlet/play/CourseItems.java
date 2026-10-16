package konqasasas.gauntlet.play;

import java.util.Base64;
import konqasasas.gauntlet.course.*;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Builds course items from templates and recognizes them again by tag. */
public final class CourseItems {
    private final TemplateStore templates;
    private final Scaffold scaffold;
    private final NamespacedKey key;
    public CourseItems(TemplateStore templates,Scaffold scaffold,NamespacedKey key) { this.templates=templates; this.scaffold=scaffold; this.key=key; }

    public static String encode(ItemStack item) { return Base64.getEncoder().encodeToString(item.serializeAsBytes()); }
    public static ItemStack decode(String item) { return ItemStack.deserializeBytes(Base64.getDecoder().decode(item)); }

    /** A template is "a mace" when its base item is one: then the level picks Wind Burst I-III. */
    public boolean isMace(String template) {
        TemplateStore.Template t=templates.get(template);
        try { return t!=null && decode(t.item()).getType()==Material.MACE; } catch(RuntimeException e) { return false; }
    }
    /** null when the template was deleted or cannot be read. */
    public ItemStack create(ItemRef ref) {
        if(ref.isScaffold()) return scaffold.create();
        TemplateStore.Template t=templates.get(ref.template());
        if(t==null) return null;
        ItemStack item;
        try { item=decode(t.item()); } catch(RuntimeException e) { return null; }
        if(ref.level()>0 && item.getType()==Material.MACE) item.addUnsafeEnchantment(Enchantment.WIND_BURST,ref.level());
        item.editMeta(meta -> {
            if(t.unbreakable()) meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,ref.tag());
        });
        return item;
    }
    public ItemRef ref(ItemStack item) {
        if(item==null || item.getType().isAir()) return null;
        if(scaffold.is(item)) return ItemRef.scaffold();
        return ItemRef.fromTag(item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING));
    }
    public String name(ItemRef ref) {
        if(ref==null) return "空";
        if(ref.isScaffold()) return "設置用ブロック";
        TemplateStore.Template t=templates.get(ref.template());
        String base=t==null ? "削除されたテンプレート("+ref.template()+")" : t.name();
        return ref.level()>0 ? base+" ウィンドバースト"+"ⅠⅡⅢ".charAt(ref.level()-1) : base;
    }
    public boolean exists(ItemRef ref) { return ref.isScaffold() || templates.get(ref.template())!=null; }
}
