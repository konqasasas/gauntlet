package konqasasas.gauntlet.data;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import konqasasas.gauntlet.chat.Languages;
import konqasasas.gauntlet.i18n.Lang;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Profiles, settings and small counters of online players. Loaded before login
 * (off the main thread), kept in memory while online, written in the background.
 * Values of players who are not loaded read as defaults.
 */
public final class PlayerData implements Listener {
    private static final long FLUSH_TICKS=20*60;
    private static final class Profile {
        final UUID id; volatile String name; final long firstSeen; volatile long lastSeen,playtimeMs;
        /** When the current online session began (0 when offline). */
        volatile long sessionStart;
        final Map<String,String> settings=new ConcurrentHashMap<>();
        final Map<String,Integer> counters=new ConcurrentHashMap<>();
        Profile(UUID id,String name,long firstSeen,long lastSeen,long playtimeMs) {
            this.id=id; this.name=name; this.firstSeen=firstSeen; this.lastSeen=lastSeen; this.playtimeMs=playtimeMs;
        }
    }
    private final JavaPlugin plugin;
    private final Database db;
    private final Map<UUID,Profile> loaded=new ConcurrentHashMap<>();

    public PlayerData(JavaPlugin plugin,Database db) { this.plugin=plugin; this.db=db; }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        // After a reload, players are already online.
        for(Player player:Bukkit.getOnlinePlayers()) {
            try { joined(player,load(player.getUniqueId(),player.getName())); }
            catch(IOException e) { plugin.getLogger().log(Level.SEVERE,"Cannot load player data of "+player.getName(),e); }
        }
        Bukkit.getScheduler().runTaskTimer(plugin,this::flushOnline,FLUSH_TICKS,FLUSH_TICKS);
    }
    /** Saves the playtime of everyone online (queued before the database closes). */
    public void stop() { for(Player player:Bukkit.getOnlinePlayers()) leave(player.getUniqueId()); loaded.clear(); }

    // ---- Lifecycle ----
    @EventHandler(priority=EventPriority.MONITOR)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if(event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try { load(event.getUniqueId(),event.getName()); }
        catch(IOException e) { plugin.getLogger().log(Level.SEVERE,"Cannot load player data of "+event.getName()+"; defaults are used",e); }
    }
    @EventHandler(priority=EventPriority.LOWEST)
    public void join(PlayerJoinEvent event) {
        Player player=event.getPlayer();
        Profile profile=loaded.get(player.getUniqueId());
        if(profile==null) {
            // Pre-login failed or was skipped: keep the player playable with an empty profile.
            long now=System.currentTimeMillis();
            profile=new Profile(player.getUniqueId(),player.getName(),now,now,0);
            loaded.put(profile.id,profile);
        }
        joined(player,profile);
    }
    private void joined(Player player,Profile profile) {
        long now=System.currentTimeMillis();
        profile.name=player.getName(); profile.lastSeen=now; profile.sessionStart=now;
        saveProfile(profile);
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        UUID id=event.getPlayer().getUniqueId();
        leave(id);
        // Other quit listeners may still read settings in this tick.
        Bukkit.getScheduler().runTask(plugin,() -> { if(Bukkit.getPlayer(id)==null) loaded.remove(id); });
    }
    private void leave(UUID id) {
        Profile profile=loaded.get(id); if(profile==null) return;
        accumulate(profile);
        profile.sessionStart=0;
        saveProfile(profile);
    }
    private void flushOnline() {
        for(Player player:Bukkit.getOnlinePlayers()) {
            Profile profile=loaded.get(player.getUniqueId());
            if(profile!=null && profile.sessionStart>0) { accumulate(profile); profile.sessionStart=System.currentTimeMillis(); saveProfile(profile); }
        }
    }
    private static void accumulate(Profile profile) {
        long now=System.currentTimeMillis();
        if(profile.sessionStart>0) profile.playtimeMs+=Math.max(0,now-profile.sessionStart);
        profile.lastSeen=now;
    }

    // ---- Loading and saving ----
    private Profile load(UUID id,String name) throws IOException {
        Profile profile=db.call(c -> {
            Profile p=null;
            try(PreparedStatement s=c.prepareStatement("SELECT name,first_seen,last_seen,playtime_ms FROM players WHERE uuid=?")) {
                s.setString(1,id.toString());
                try(ResultSet r=s.executeQuery()) { if(r.next()) p=new Profile(id,r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4)); }
            }
            if(p==null) { long now=System.currentTimeMillis(); p=new Profile(id,name,now,now,0); }
            try(PreparedStatement s=c.prepareStatement("SELECT key,value FROM player_settings WHERE uuid=?")) {
                s.setString(1,id.toString());
                try(ResultSet r=s.executeQuery()) { while(r.next()) p.settings.put(r.getString(1),r.getString(2)); }
            }
            try(PreparedStatement s=c.prepareStatement("SELECT key,value FROM player_counters WHERE uuid=?")) {
                s.setString(1,id.toString());
                try(ResultSet r=s.executeQuery()) { while(r.next()) p.counters.put(r.getString(1),r.getInt(2)); }
            }
            return p;
        });
        loaded.put(id,profile);
        return profile;
    }
    private void saveProfile(Profile p) {
        String id=p.id.toString(),name=p.name; long first=p.firstSeen,last=p.lastSeen,playtime=p.playtimeMs;
        db.write("profile "+name,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT INTO players(uuid,name,first_seen,last_seen,playtime_ms) VALUES(?,?,?,?,?) "
                    +"ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen,playtime_ms=excluded.playtime_ms")) {
                s.setString(1,id); s.setString(2,name); s.setLong(3,first); s.setLong(4,last); s.setLong(5,playtime);
                return s.executeUpdate();
            }
        });
    }

    // ---- Settings ----
    public <T> T get(UUID player,Setting<T> setting) {
        Profile profile=loaded.get(player);
        T value=profile==null ? null : setting.parse(profile.settings.get(setting.key()));
        return value==null ? setting.defaultValue() : value;
    }
    public <T> T get(Player player,Setting<T> setting) { return get(player.getUniqueId(),setting); }
    /** Changes a setting of a loaded (online) player. The default value removes the stored row. */
    public <T> boolean set(UUID player,Setting<T> setting,T value) {
        Profile profile=loaded.get(player);
        if(profile==null || !setting.choices().contains(value)) return false;
        boolean reset=value.equals(setting.defaultValue());
        String key=setting.key(),text=setting.format(value),id=player.toString();
        if(reset) profile.settings.remove(key); else profile.settings.put(key,text);
        db.write("setting "+key,c -> {
            if(reset) try(PreparedStatement s=c.prepareStatement("DELETE FROM player_settings WHERE uuid=? AND key=?")) {
                s.setString(1,id); s.setString(2,key); return s.executeUpdate();
            }
            try(PreparedStatement s=c.prepareStatement("INSERT INTO player_settings(uuid,key,value) VALUES(?,?,?) ON CONFLICT(uuid,key) DO UPDATE SET value=excluded.value")) {
                s.setString(1,id); s.setString(2,key); s.setString(3,text); return s.executeUpdate();
            }
        });
        return true;
    }
    /** Interface language: the chosen one, else the client's (Japanese or English). */
    public String uiLanguage(Player player) {
        String chosen=get(player,Settings.UI_LANG);
        return Lang.supported(chosen) ? chosen : Lang.fromLocale(player.locale());
    }
    /** Chat translation target: the chosen one, else the interface language. */
    public String translateTarget(Player player) {
        String chosen=Languages.normalize(get(player,Settings.TRANSLATE_LANG));
        return chosen!=null ? chosen : uiLanguage(player);
    }

    // ---- Profile values and counters ----
    /** Stored playtime plus the current session. */
    public long playtimeMs(Player player) {
        Profile profile=loaded.get(player.getUniqueId());
        if(profile==null) return 0;
        long session=profile.sessionStart>0 ? Math.max(0,System.currentTimeMillis()-profile.sessionStart) : 0;
        return profile.playtimeMs+session;
    }
    public int counter(UUID player,String key) {
        Profile profile=loaded.get(player); return profile==null ? 0 : profile.counters.getOrDefault(key,0);
    }
    public int increment(UUID player,String key) {
        Profile profile=loaded.get(player); if(profile==null) return 0;
        int value=profile.counters.merge(key,1,Integer::sum);
        String id=player.toString();
        db.write("counter "+key,c -> {
            try(PreparedStatement s=c.prepareStatement("INSERT INTO player_counters(uuid,key,value) VALUES(?,?,?) ON CONFLICT(uuid,key) DO UPDATE SET value=excluded.value")) {
                s.setString(1,id); s.setString(2,key); s.setInt(3,value); return s.executeUpdate();
            }
        });
        return value;
    }
}
