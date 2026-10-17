package konqasasas.gauntlet.chat;

import java.io.IOException;

/** A translation engine. Calls block; TranslationService runs them off the main thread. */
public interface Translator {
    /** source is the detected language code (may be empty when unknown). */
    record Result(String text,String source) {}
    /** The engine refused because its free quota ran out. */
    final class QuotaException extends IOException {
        public QuotaException(String message) { super(message); }
    }
    String name();
    Result translate(String text,String target) throws IOException,InterruptedException;
}
