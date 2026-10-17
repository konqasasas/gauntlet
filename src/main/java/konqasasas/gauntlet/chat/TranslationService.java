package konqasasas.gauntlet.chat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a Translator on worker threads with a small cache. After the engine
 * reports its quota as used up, it is not called again until the next UTC day.
 */
public final class TranslationService {
    private static final int CACHE_SIZE=1024;
    private final Translator translator;
    private final Logger logger;
    private final ExecutorService workers;
    private final Map<String,Translator.Result> cache=Collections.synchronizedMap(new LinkedHashMap<>(256,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Translator.Result> eldest) { return size()>CACHE_SIZE; }
    });
    private final AtomicLong charactersToday=new AtomicLong();
    private volatile LocalDate day=LocalDate.now(ZoneOffset.UTC);
    private volatile LocalDate quotaFinishedOn;

    public TranslationService(Translator translator,Logger logger) {
        this.translator=translator; this.logger=logger;
        workers=translator==null ? null : Executors.newFixedThreadPool(2,r -> { Thread t=new Thread(r,"Gauntlet-Translate"); t.setDaemon(true); return t; });
    }
    public boolean enabled() { return translator!=null; }
    public String engine() { return translator==null ? "none" : translator.name(); }
    public long charactersToday() { rollDay(); return charactersToday.get(); }
    public boolean quotaFinished() { rollDay(); return quotaFinishedOn!=null; }
    /** Completes with the translation; a result whose source equals target means "same language". */
    public CompletableFuture<Translator.Result> translate(String text,String target) {
        if(translator==null) return CompletableFuture.failedFuture(new IllegalStateException("Translation disabled"));
        String key=target+"\u0000"+text;
        Translator.Result cached=cache.get(key);
        if(cached!=null) return CompletableFuture.completedFuture(cached);
        if(quotaFinished()) return CompletableFuture.failedFuture(new Translator.QuotaException("quota finished today"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                charactersToday.addAndGet(text.codePointCount(0,text.length()));
                Translator.Result result=translator.translate(text,target);
                cache.put(key,result);
                return result;
            } catch(Translator.QuotaException e) {
                if(quotaFinishedOn==null) logger.warning("Translation quota reached ("+translator.name()+"): "+e.getMessage()+"; paused until the next UTC day");
                quotaFinishedOn=LocalDate.now(ZoneOffset.UTC);
                throw new CompletionException(e);
            } catch(Exception e) {
                logger.log(Level.WARNING,"Translation failed ("+translator.name()+"): "+e.getMessage());
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        },workers);
    }
    private void rollDay() {
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        if(!today.equals(day)) { day=today; charactersToday.set(0); quotaFinishedOn=null; }
    }
    public void shutdown() { if(workers!=null) workers.shutdownNow(); }
}
