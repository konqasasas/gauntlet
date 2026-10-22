package konqasasas.gauntlet.cosmetic;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import konqasasas.gauntlet.data.Database;

/**
 * What each player owns, what they use in each slot, and every purchase. Read
 * once at startup, written in the background (gauntlet.db: owned, equipped,
 * purchases, purchase_items).
 *
 * START items are everyone's without a row. A slot with nothing chosen (or whose
 * choice is no longer owned or in the catalog) uses its standard item. REWARD
 * items are never sold. A purchase keeps the buyer and the recipient apart (the
 * same player for now; gifts may come later) and stays as history even when an
 * item is taken back. Buying a bundle gives each of its items.
 */
public final class Cosmetics {
    /** How a row came to be. */
    public enum Source { START,REWARD,PURCHASE }
    public record Owned(String item,Source source,long acquiredAt) {}
    /** bundle: the bundle bought, or null for single items. */
    public record Purchase(String id,UUID buyer,UUID recipient,String bundle,List<String> items,long at,String provider,String providerOrder) {}
    private final Database db;
    private volatile Catalog catalog;
    private final Map<UUID,Map<String,Owned>> owned=new ConcurrentHashMap<>();
    private final Map<UUID,Map<String,String>> equipped=new ConcurrentHashMap<>();

    public Cosmetics(Database db,Catalog catalog) { this.db=db; this.catalog=catalog; }
    public Catalog catalog() { return catalog; }
    public void catalog(Catalog value) { catalog=value; }
    public void load() throws IOException {
        db.call(c -> {
            try(Statement s=c.createStatement()) {
                try(ResultSet r=s.executeQuery("SELECT player,item,source,acquired_at FROM owned")) {
                    while(r.next()) owned.computeIfAbsent(UUID.fromString(r.getString(1)),k -> new ConcurrentHashMap<>())
                            .put(r.getString(2),new Owned(r.getString(2),Source.valueOf(r.getString(3)),r.getLong(4)));
                }
                try(ResultSet r=s.executeQuery("SELECT player,slot,item FROM equipped")) {
                    while(r.next()) equipped.computeIfAbsent(UUID.fromString(r.getString(1)),k -> new ConcurrentHashMap<>()).put(r.getString(2),r.getString(3));
                }
            }
            return null;
        });
    }

    // ---- Owning ----
    public boolean owns(UUID player,String item) {
        Catalog.Item i=catalog.get(item);
        return i!=null && (i.everyone() || owned.getOrDefault(player,Map.of()).containsKey(item));
    }
    /** Rows of what the player was given (START items are not listed). */
    public List<Owned> owned(UUID player) {
        return owned.getOrDefault(player,Map.of()).values().stream().sorted(Comparator.comparingLong(Owned::acquiredAt)).toList();
    }
    public enum Grant { GRANTED,UNKNOWN,EVERYONE_HAS_IT,ALREADY_OWNED,NOT_FOR_SALE }
    /** Gives the item. A purchase is refused for items not sold (START and REWARD). */
    public Grant grant(UUID player,String item,Source source) {
        Catalog.Item i=catalog.get(item);
        if(i==null) return Grant.UNKNOWN;
        if(i.everyone()) return Grant.EVERYONE_HAS_IT;
        if(source==Source.PURCHASE && i.obtain()!=Catalog.Obtain.PURCHASE) return Grant.NOT_FOR_SALE;
        long now=System.currentTimeMillis();
        if(owned.computeIfAbsent(player,k -> new ConcurrentHashMap<>()).putIfAbsent(item,new Owned(item,source,now))!=null) return Grant.ALREADY_OWNED;
        db.write("owned "+player+" "+item,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO owned(player,item,source,acquired_at) VALUES(?,?,?,?)")) {
                s.setString(1,player.toString()); s.setString(2,item); s.setString(3,source.name()); s.setLong(4,now); return s.executeUpdate();
            }
        });
        return Grant.GRANTED;
    }
    /** Takes the item back (its slot falls back to the standard); false when it was not owned. */
    public boolean revoke(UUID player,String item) {
        Map<String,Owned> mine=owned.get(player);
        if(mine==null || mine.remove(item)==null) return false;
        db.write("revoke "+player+" "+item,c -> {
            try(PreparedStatement s=c.prepareStatement("DELETE FROM owned WHERE player=? AND item=?")) {
                s.setString(1,player.toString()); s.setString(2,item); return s.executeUpdate();
            }
        });
        Map<String,String> slots=equipped.get(player);
        if(slots!=null) for(var e:Map.copyOf(slots).entrySet()) if(e.getValue().equals(item)) unequip(player,e.getKey());
        return true;
    }

    // ---- Using ----
    /** The item used in the slot: the choice when still owned and listed, else the slot's standard (null for an unknown slot). */
    public Catalog.Item equipped(UUID player,String slot) {
        String id=equipped.getOrDefault(player,Map.of()).get(slot);
        Catalog.Item item=id==null ? null : catalog.get(id);
        return item!=null && item.slot().equals(slot) && owns(player,id) ? item : catalog.standard(slot);
    }
    /** Uses the item in its slot; false when unknown or not owned. */
    public boolean equip(UUID player,String item) {
        Catalog.Item i=catalog.get(item);
        if(i==null || !owns(player,item)) return false;
        equipped.computeIfAbsent(player,k -> new ConcurrentHashMap<>()).put(i.slot(),item);
        db.write("equip "+player+" "+item,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT OR REPLACE INTO equipped(player,slot,item) VALUES(?,?,?)")) {
                s.setString(1,player.toString()); s.setString(2,i.slot()); s.setString(3,item); return s.executeUpdate();
            }
        });
        return true;
    }
    public void unequip(UUID player,String slot) {
        Map<String,String> slots=equipped.get(player);
        if(slots==null || slots.remove(slot)==null) return;
        db.write("unequip "+player+" "+slot,c -> {
            try(PreparedStatement s=c.prepareStatement("DELETE FROM equipped WHERE player=? AND slot=?")) {
                s.setString(1,player.toString()); s.setString(2,slot); return s.executeUpdate();
            }
        });
    }

    // ---- Purchases ----
    /**
     * Records an order and gives its items to the recipient: one item for sale, or a
     * bundle (each of its items; items already owned are simply kept). provider /
     * providerOrder: the payment service and its order id when known.
     */
    public Purchase purchase(UUID buyer,UUID recipient,String itemOrBundle,String provider,String providerOrder) {
        Catalog.Bundle bundle=catalog.bundle(itemOrBundle);
        List<String> items;
        if(bundle!=null) items=bundle.items();
        else {
            Catalog.Item i=catalog.get(itemOrBundle);
            if(i==null || i.obtain()!=Catalog.Obtain.PURCHASE) throw new IllegalArgumentException("Not for sale: "+itemOrBundle);
            items=List.of(itemOrBundle);
        }
        Purchase p=new Purchase(UUID.randomUUID().toString(),buyer,recipient,bundle==null ? null : bundle.id(),items,System.currentTimeMillis(),provider,providerOrder);
        db.write("purchase "+p.id(),c -> Database.transaction(c,x -> {
            try(PreparedStatement s=x.prepareStatement("INSERT INTO purchases(id,buyer,recipient,bundle,created_at,provider,provider_order) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1,p.id()); s.setString(2,buyer.toString()); s.setString(3,recipient.toString()); s.setString(4,p.bundle());
                s.setLong(5,p.at()); s.setString(6,provider); s.setString(7,providerOrder); s.executeUpdate();
            }
            try(PreparedStatement s=x.prepareStatement("INSERT INTO purchase_items(purchase,item) VALUES(?,?)")) {
                for(String item:p.items()) { s.setString(1,p.id()); s.setString(2,item); s.executeUpdate(); }
            }
            return null;
        }));
        for(String item:items) grant(recipient,item,Source.PURCHASE);
        return p;
    }
    /** Every order the player bought or received, newest first (read from the database). */
    public List<Purchase> purchases(UUID player) throws IOException {
        return db.call(c -> {
            record Row(String id,UUID buyer,UUID recipient,String bundle,long at,String provider,String order,String item) {}
            List<Row> rows=new ArrayList<>();
            try(PreparedStatement s=c.prepareStatement("SELECT p.id,p.buyer,p.recipient,p.bundle,p.created_at,p.provider,p.provider_order,i.item FROM purchases p "
                    +"LEFT JOIN purchase_items i ON i.purchase=p.id WHERE p.buyer=? OR p.recipient=? ORDER BY p.created_at DESC,p.id")) {
                s.setString(1,player.toString()); s.setString(2,player.toString());
                try(ResultSet r=s.executeQuery()) {
                    while(r.next()) rows.add(new Row(r.getString(1),UUID.fromString(r.getString(2)),UUID.fromString(r.getString(3)),r.getString(4),
                            r.getLong(5),r.getString(6),r.getString(7),r.getString(8)));
                }
            }
            Map<String,Purchase> out=new LinkedHashMap<>();
            for(Row r:rows) {
                Purchase p=out.computeIfAbsent(r.id(),k -> new Purchase(k,r.buyer(),r.recipient(),r.bundle(),new ArrayList<>(),r.at(),r.provider(),r.order()));
                if(r.item()!=null) p.items().add(r.item());
            }
            return List.copyOf(out.values());
        });
    }
}
