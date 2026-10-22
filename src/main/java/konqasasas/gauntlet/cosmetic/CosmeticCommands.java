package konqasasas.gauntlet.cosmetic;

import java.io.IOException;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import konqasasas.gauntlet.i18n.Msg;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /cosmetic for builders (testing): catalog, list, grant, revoke, equip, purchases,
 * reload. Granting as a purchase writes a purchase record too (provider "manual").
 */
public final class CosmeticCommands implements CommandExecutor,TabCompleter {
    private static final List<String> SUBS=List.of("catalog","list","grant","revoke","equip","purchases","reload");
    private final JavaPlugin plugin;
    private final Cosmetics cosmetics;
    private final Path override;
    public CosmeticCommands(JavaPlugin plugin,Cosmetics cosmetics,Path override) { this.plugin=plugin; this.cosmetics=cosmetics; this.override=override; }
    public void start() {
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("cosmetic"));
        command.setExecutor(this); command.setTabCompleter(this);
    }

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("gauntlet.admin")) { Msg.send(sender,"editor.admin-only"); return true; }
        if(args.length==0) { Msg.send(sender,"editor.cosmetic.usage"); return true; }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("catalog")) { catalog(sender); return true; }
        if(sub.equals("reload")) { reload(sender); return true; }
        if(args.length<2) { Msg.send(sender,"editor.cosmetic.usage"); return true; }
        OfflinePlayer target=Bukkit.getOfflinePlayerIfCached(args[1]);
        if(target==null) { Msg.send(sender,"editor.rank.unknown",Msg.arg("player",args[1])); return true; }
        UUID id=target.getUniqueId(); String name=target.getName()==null ? id.toString() : target.getName();
        switch(sub) {
            case "list" -> list(sender,id,name);
            case "purchases" -> purchases(sender,id,name);
            case "grant" -> grant(sender,id,name,args);
            case "revoke" -> {
                if(args.length!=3) { Msg.send(sender,"editor.cosmetic.usage"); return true; }
                Msg.send(sender,cosmetics.revoke(id,args[2]) ? "editor.cosmetic.revoked" : "editor.cosmetic.not-owned",Msg.arg("player",name),Msg.arg("item",args[2]));
            }
            case "equip" -> {
                if(args.length!=3) { Msg.send(sender,"editor.cosmetic.usage"); return true; }
                Msg.send(sender,cosmetics.equip(id,args[2]) ? "editor.cosmetic.equipped" : "editor.cosmetic.not-owned",Msg.arg("player",name),Msg.arg("item",args[2]));
            }
            default -> Msg.send(sender,"editor.cosmetic.usage");
        }
        return true;
    }
    /** grant <player> <item|bundle> [reward|start|purchase]: purchase records an order (a bundle gives each of its items). */
    private void grant(CommandSender sender,UUID id,String name,String[] args) {
        if(args.length<3 || args.length>4) { Msg.send(sender,"editor.cosmetic.usage"); return; }
        String what=args[2]; String how=args.length==4 ? args[3].toUpperCase(Locale.ROOT) : "REWARD";
        Cosmetics.Source source;
        try { source=Cosmetics.Source.valueOf(how); } catch(IllegalArgumentException e) { Msg.send(sender,"editor.cosmetic.usage"); return; }
        if(source==Cosmetics.Source.PURCHASE) {
            Catalog.Item item=cosmetics.catalog().get(what);
            if(cosmetics.catalog().bundle(what)==null && (item==null || item.obtain()!=Catalog.Obtain.PURCHASE)) {
                Msg.send(sender,item==null ? "editor.cosmetic.unknown-item" : "editor.cosmetic.not-for-sale",Msg.arg("item",what)); return;
            }
            Cosmetics.Purchase p=cosmetics.purchase(id,id,what,"manual",null);
            Msg.send(sender,"editor.cosmetic.purchased",Msg.arg("player",name),Msg.arg("items",String.join(", ",p.items())),Msg.arg("order",p.id()));
            return;
        }
        Cosmetics.Grant result=cosmetics.grant(id,what,source);
        String key=switch(result) {
            case GRANTED -> "editor.cosmetic.granted";
            case UNKNOWN -> "editor.cosmetic.unknown-item";
            case EVERYONE_HAS_IT -> "editor.cosmetic.everyone";
            case ALREADY_OWNED -> "editor.cosmetic.already";
            case NOT_FOR_SALE -> "editor.cosmetic.not-for-sale";
        };
        Msg.send(sender,key,Msg.arg("player",name),Msg.arg("item",what),Msg.arg("source",source.name()));
    }
    private void reload(CommandSender sender) {
        try { cosmetics.catalog(Catalog.load(plugin.getClass().getClassLoader(),override)); Msg.send(sender,"editor.cosmetic.reloaded",Msg.arg("count",cosmetics.catalog().all().size())); }
        catch(IOException e) { Msg.send(sender,"editor.cosmetic.reload-failed",Msg.arg("error",String.valueOf(e.getMessage()))); plugin.getLogger().log(Level.SEVERE,"Cannot reload cosmetics",e); }
    }
    private void catalog(CommandSender sender) {
        Catalog c=cosmetics.catalog();
        Msg.send(sender,"editor.cosmetic.catalog",Msg.arg("slots",c.slots().size()),Msg.arg("count",c.all().size()),Msg.arg("bundles",c.bundles().size()));
        for(Catalog.Item i:c.all())
            sender.sendMessage(Msg.text(sender,"editor.cosmetic.catalog-line",Msg.arg("item",i.id()),Msg.arg("slot",i.slot()),Msg.arg("theme",i.theme()),
                    Msg.arg("obtain",Msg.text(sender,i.standard() ? "editor.cosmetic.obtain.standard" : "editor.cosmetic.obtain."+i.obtain().name().toLowerCase(Locale.ROOT)))));
        for(Catalog.Bundle b:c.bundles())
            sender.sendMessage(Msg.text(sender,"editor.cosmetic.bundle-line",Msg.arg("bundle",b.id()),Msg.arg("items",String.join(", ",b.items()))));
    }
    private void list(CommandSender sender,UUID id,String name) {
        Msg.send(sender,"editor.cosmetic.list",Msg.arg("player",name));
        for(String slot:cosmetics.catalog().slots())
            sender.sendMessage(Msg.text(sender,"editor.cosmetic.slot-line",Msg.arg("slot",slot),Msg.arg("item",cosmetics.equipped(id,slot).id())));
        SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for(Cosmetics.Owned o:cosmetics.owned(id))
            sender.sendMessage(Msg.text(sender,"editor.cosmetic.owned-line",Msg.arg("item",o.item()),Msg.arg("source",o.source().name()),Msg.arg("at",date.format(new Date(o.acquiredAt())))));
    }
    private void purchases(CommandSender sender,UUID id,String name) {
        // Read on the database thread; answer on the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin,() -> {
            List<Cosmetics.Purchase> all; String error=null;
            try { all=cosmetics.purchases(id); } catch(IOException e) { all=List.of(); error=e.getMessage(); }
            List<Cosmetics.Purchase> found=all; String failed=error;
            Bukkit.getScheduler().runTask(plugin,() -> {
                if(failed!=null) { plugin.getLogger().warning("Cannot read purchases: "+failed); Msg.send(sender,"editor.cosmetic.read-failed"); return; }
                Msg.send(sender,"editor.cosmetic.purchases",Msg.arg("player",name),Msg.arg("count",found.size()));
                SimpleDateFormat date=new SimpleDateFormat("yyyy-MM-dd HH:mm");
                for(Cosmetics.Purchase p:found)
                    sender.sendMessage(Msg.text(sender,"editor.cosmetic.purchase-line",Msg.arg("at",date.format(new Date(p.at()))),
                            Msg.arg("items",(p.bundle()==null ? "" : p.bundle()+": ")+String.join(", ",p.items())),
                            Msg.arg("provider",String.valueOf(p.provider())),Msg.arg("order",p.providerOrder()==null ? "-" : p.providerOrder())));
            });
        });
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return filter(SUBS,args[0]);
        if(args.length==2 && !args[0].equalsIgnoreCase("catalog") && !args[0].equalsIgnoreCase("reload"))
            return filter(Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList(),args[1]);
        if(args.length==3) {
            List<String> ids=new ArrayList<>(cosmetics.catalog().all().stream().filter(i -> !i.everyone()).map(Catalog.Item::id).toList());
            if(args[0].equalsIgnoreCase("grant")) cosmetics.catalog().bundles().forEach(b -> ids.add(b.id()));
            return filter(ids,args[2]);
        }
        if(args.length==4 && args[0].equalsIgnoreCase("grant")) return filter(List.of("reward","start","purchase"),args[3]);
        return List.of();
    }
    private static List<String> filter(List<String> all,String prefix) {
        return all.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
