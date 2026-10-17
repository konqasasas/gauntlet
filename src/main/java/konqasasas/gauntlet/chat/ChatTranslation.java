package konqasasas.gauntlet.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import konqasasas.gauntlet.data.*;
import konqasasas.gauntlet.i18n.Msg;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Translates free chat per viewer. Auto on: the line is shown translated with a
 * "[訳]" prefix and the original on hover. Auto off: a "[翻訳]" button is added
 * when the speaker's client language differs from the viewer's language.
 * Settings are the personal settings translate_auto / translate_lang.
 */
public final class ChatTranslation implements Listener,CommandExecutor,TabCompleter {
    private static final String ADMIN="gauntlet.admin";
    private final JavaPlugin plugin;
    private final PlayerData prefs;
    private final TranslationService service;
    private final Duration timeout;

    public ChatTranslation(JavaPlugin plugin,PlayerData prefs) {
        this.plugin=plugin; this.prefs=prefs;
        ConfigurationSection config=plugin.getConfig().getConfigurationSection("translation");
        timeout=Duration.ofMillis(Math.clamp(config==null ? 2500 : config.getLong("timeout-ms",2500),200,10000));
        service=new TranslationService(createTranslator(config),plugin.getLogger());
    }
    private Translator createTranslator(ConfigurationSection config) {
        String engine=config==null ? "mymemory" : config.getString("engine","mymemory").toLowerCase(Locale.ROOT);
        HttpClient http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
        return switch(engine) {
            case "mymemory" -> {
                String email=config==null ? "" : config.getString("mymemory.email","");
                if(email.isBlank()) plugin.getLogger().warning("translation.mymemory.email is empty: MyMemory allows 5,000 characters/day (50,000 with an email)");
                yield new MyMemoryTranslator(http,email,timeout);
            }
            case "libretranslate" -> new LibreTranslateTranslator(http,config.getString("libretranslate.url","http://localhost:5000"),config.getString("libretranslate.api-key",""),timeout);
            case "none","off","" -> null;
            default -> { plugin.getLogger().severe("Unknown translation.engine '"+engine+"'; chat translation disabled"); yield null; }
        };
    }
    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
        PluginCommand command=Objects.requireNonNull(plugin.getCommand("translate"));
        command.setExecutor(this); command.setTabCompleter(this);
        plugin.getLogger().info("Chat translation: "+service.engine());
    }
    public void stop() { service.shutdown(); }

    // ---- Chat ----
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void chat(AsyncChatEvent event) {
        if(!service.enabled()) return;
        Player sender=event.getPlayer();
        Component original=event.message();
        String text=PlainTextComponentSerializer.plainText().serialize(original).strip();
        if(text.isEmpty()) return;
        String senderLanguage=Languages.fromLocale(sender.locale());
        // Translate once per target language among viewers with auto translation on.
        Map<String,CompletableFuture<Translator.Result>> pending=new HashMap<>();
        for(Audience viewer:event.viewers()) {
            if(!(viewer instanceof Player p) || p.equals(sender) || !prefs.get(p,Settings.TRANSLATE_AUTO)) continue;
            String target=prefs.translateTarget(p);
            if(!TextGuess.skip(text,target,senderLanguage)) pending.computeIfAbsent(target,t -> service.translate(text,t));
        }
        Map<String,Translator.Result> done=new HashMap<>();
        if(!pending.isEmpty()) {
            // Player chat arrives off the main thread, so waiting here does not stall the server.
            // A synchronous chat event (plugin-sent) never waits; it only takes what is cached.
            long deadline=System.nanoTime()+(event.isAsynchronous() ? timeout.toNanos() : 0);
            for(var entry:pending.entrySet()) {
                try { done.put(entry.getKey(),entry.getValue().get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch(ExecutionException|TimeoutException|CancellationException ignored) {}
            }
        }
        Component senderName=sender.displayName();
        ChatRenderer previous=event.renderer();
        event.renderer((source,displayName,message,viewer) -> {
            if(!(viewer instanceof Player p) || p.equals(sender)) return previous.render(source,displayName,message,viewer);
            String target=prefs.translateTarget(p);
            Translator.Result result=prefs.get(p,Settings.TRANSLATE_AUTO) ? done.get(target) : null;
            Component shown=message;
            if(result!=null && isTranslation(result,text,target)) shown=translated(p,result,message);
            // No result: auto is off, or the engine was too slow / out of quota. A result that is
            // not a translation means the engine already found the same language: no button then.
            else if(result==null && !Languages.sameLanguage(senderLanguage,target) && !TextGuess.skip(text,target,senderLanguage))
                shown=message.append(button(p,senderName,text,message));
            return previous.render(source,displayName,shown,viewer);
        });
    }
    private static boolean isTranslation(Translator.Result result,String original,String target) {
        return !Languages.sameLanguage(result.source(),target) && !result.text().strip().equalsIgnoreCase(original.strip()) && !result.text().isBlank();
    }
    private static Component translated(Player viewer,Translator.Result result,Component original) {
        Component hover=Msg.text(viewer,"chat.original",Msg.arg("language",Languages.label(result.source()))).append(Component.newline()).append(original);
        return Component.text().append(Msg.text(viewer,"chat.translated-mark")).append(Component.text(result.text()))
                .hoverEvent(HoverEvent.showText(hover)).build();
    }
    private Component button(Player viewer,Component senderName,String text,Component original) {
        return Msg.text(viewer,"chat.button")
                .hoverEvent(HoverEvent.showText(Msg.text(viewer,"chat.button-hover")))
                .clickEvent(ClickEvent.callback(audience -> translateFor(audience,senderName,text,original),
                        options -> options.uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30))));
    }
    private void translateFor(Audience audience,Component senderName,String text,Component original) {
        if(!(audience instanceof Player player)) return;
        String target=prefs.translateTarget(player);
        service.translate(text,target).whenComplete((result,error) -> {
            if(error!=null) {
                Throwable cause=error instanceof CompletionException ? error.getCause() : error;
                Msg.send(player,cause instanceof Translator.QuotaException ? "chat.quota" : "chat.failed");
            } else if(!isTranslation(result,text,target)) {
                Msg.send(player,"chat.same-language",Msg.arg("language",Languages.label(target)));
            } else {
                player.sendMessage(Component.text().append(Component.text("<")).append(senderName).append(Component.text("> ")).append(translated(player,result,original)).build());
            }
        });
    }

    // ---- /translate (also in /settings; the settings screen replaces both) ----
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof Player player)) { Msg.send(sender,"settings.console"); return true; }
        UUID id=player.getUniqueId();
        if(args.length==1 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            boolean on=args[0].equalsIgnoreCase("on");
            prefs.set(id,Settings.TRANSLATE_AUTO,on);
            if(on) Msg.send(player,"chat.auto-on",Msg.arg("language",Languages.label(prefs.translateTarget(player))));
            else Msg.send(player,"chat.auto-off");
        } else if(args.length==2 && args[0].equalsIgnoreCase("lang")) {
            if(args[1].equalsIgnoreCase("auto") || args[1].equalsIgnoreCase("ui")) {
                prefs.set(id,Settings.TRANSLATE_LANG,"ui");
                Msg.send(player,"chat.lang-ui",Msg.arg("language",Languages.label(prefs.translateTarget(player))));
            } else {
                String code=Languages.normalize(args[1]);
                if(code==null) { Msg.send(player,"chat.lang-unsupported",Msg.arg("codes",String.join(", ",Languages.codes()))); return true; }
                prefs.set(id,Settings.TRANSLATE_LANG,code);
                Msg.send(player,"chat.lang-set",Msg.arg("language",Languages.label(code)),Msg.arg("code",code));
            }
        } else status(player);
        return true;
    }
    private void status(Player player) {
        boolean chosen=!"ui".equals(prefs.get(player,Settings.TRANSLATE_LANG));
        Msg.send(player,"chat.status",Msg.arg("auto",Msg.text(player,prefs.get(player,Settings.TRANSLATE_AUTO) ? "setting.value.on" : "setting.value.off")),
                Msg.arg("language",Languages.label(prefs.translateTarget(player))),
                Msg.arg("source",Msg.text(player,chosen ? "chat.source-chosen" : "chat.source-ui")),Msg.arg("engine",service.engine()));
        Msg.send(player,"chat.usage",Msg.arg("codes",String.join("|",Languages.codes())));
        if(player.hasPermission(ADMIN)) Msg.send(player,"chat.admin-usage",Msg.arg("count",service.charactersToday()),
                Msg.arg("stopped",service.quotaFinished() ? Msg.text(player,"chat.quota-stopped") : Component.empty()));
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return filter(List.of("on","off","lang"),args[0]);
        if(args.length==2 && args[0].equalsIgnoreCase("lang")) {
            List<String> options=new ArrayList<>(Languages.codes()); options.add("ui"); return filter(options,args[1]);
        }
        return List.of();
    }
    private static List<String> filter(List<String> options,String prefix) {
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
