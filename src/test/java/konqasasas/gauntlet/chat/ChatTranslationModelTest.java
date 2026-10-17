package konqasasas.gauntlet.chat;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatTranslationModelTest {
    @TempDir Path dir;

    @Test void languagesFromClientLocaleAndInput() {
        assertEquals("ja",Languages.fromLocale(Locale.JAPAN));
        assertEquals("en",Languages.fromLocale(Locale.US));
        assertEquals("zh-TW",Languages.fromLocale(Locale.TAIWAN));
        assertEquals("zh-CN",Languages.fromLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals("en",Languages.fromLocale(Locale.of("xx")),"Unsupported falls back to English");
        assertEquals("ja",Languages.normalize("JA")); assertEquals("zh-TW",Languages.normalize("zh_tw")); assertEquals("zh-CN",Languages.normalize("zh"));
        assertNull(Languages.normalize("klingon"));
        assertTrue(Languages.sameLanguage("en-GB","en")); assertFalse(Languages.sameLanguage("","en"));
        assertEquals("zt",LibreTranslateTranslator.code("zh-TW"));
    }
    @Test void obviousCasesSkipTheApi() {
        assertTrue(TextGuess.skip("こんにちは","ja","en"),"Kana to Japanese");
        assertTrue(TextGuess.skip("gg wp! すごいw","ja","ja"),"Kana present");
        assertTrue(TextGuess.skip("w","ja","ja"),"Too short");
        assertTrue(TextGuess.skip("123 !!!","en","ja"),"No letters");
        assertTrue(TextGuess.skip("hello there","en","en"),"English client, English reader");
        assertTrue(TextGuess.skip("안녕하세요","ko","ja"));
        assertFalse(TextGuess.skip("hello there","ja","en"));
        assertFalse(TextGuess.skip("hello there","en","ja"),"A Japanese client writing English still goes to the API");
        assertFalse(TextGuess.skip("こんにちは","en","ja"));
    }
    @Test void chunksStayUnderTheByteLimitAndKeepAllText() {
        String japanese="これはテストです。".repeat(40);
        List<String> parts=TextGuess.chunks(japanese,MyMemoryTranslator.MAX_BYTES);
        assertTrue(parts.size()>1);
        for(String p:parts) assertTrue(p.getBytes(StandardCharsets.UTF_8).length<=MyMemoryTranslator.MAX_BYTES);
        assertEquals(japanese,String.join("",parts));
        assertTrue(parts.getFirst().endsWith("。"),"Splits at sentence ends");
        assertEquals(List.of("short"),TextGuess.chunks("short",480));
        String emoji="😀".repeat(200);
        assertEquals(emoji,String.join("",TextGuess.chunks(emoji,480)),"Never splits a surrogate pair");
    }
    @Test void htmlEntitiesAreDecoded() {
        assertEquals("it's <ok> & \"fine\" é",TextGuess.unescapeHtml("it&#39;s &lt;ok&gt; &amp; &quot;fine&quot; &#xE9;"));
        assertEquals("A & B; 5&6",TextGuess.unescapeHtml("A & B; 5&6"));
    }
    @Test void myMemoryResponses() throws Exception {
        var ok=MyMemoryTranslator.parse(200,"{\"responseData\":{\"translatedText\":\"みなさん&#39;こんにちは\",\"match\":0.85,\"detectedLanguage\":\"en\"},\"quotaFinished\":false,\"responseStatus\":200}","hello","ja");
        assertEquals(new Translator.Result("みなさん'こんにちは","en"),ok);
        var same=MyMemoryTranslator.parse(200,"{\"responseData\":{\"translatedText\":\"PLEASE SELECT TWO DISTINCT LANGUAGES\"},\"quotaFinished\":null,\"responseDetails\":\"PLEASE SELECT TWO DISTINCT LANGUAGES\",\"responseStatus\":\"403\"}","hello there","en");
        assertEquals(new Translator.Result("hello there","en"),same);
        assertThrows(Translator.QuotaException.class,() -> MyMemoryTranslator.parse(200,"{\"responseData\":{\"translatedText\":\"x\"},\"quotaFinished\":true,\"responseStatus\":200}","x","ja"));
        assertThrows(Translator.QuotaException.class,() -> MyMemoryTranslator.parse(429,"{\"responseStatus\":429,\"responseDetails\":\"too many\"}","x","ja"));
        assertThrows(IOException.class,() -> MyMemoryTranslator.parse(500,"<html>down</html>","x","ja"));
    }
    @Test void libreTranslateResponses() throws Exception {
        assertEquals(new Translator.Result("こんにちは","en"),LibreTranslateTranslator.parse(200,"{\"translatedText\":\"こんにちは\",\"detectedLanguage\":{\"confidence\":90,\"language\":\"en\"}}","hello","ja"));
        assertEquals(new Translator.Result("hello","en"),LibreTranslateTranslator.parse(200,"{\"translatedText\":\"hello\",\"detectedLanguage\":{\"language\":\"en\"}}","hello","en"));
        assertThrows(Translator.QuotaException.class,() -> LibreTranslateTranslator.parse(429,"{\"error\":\"Slowdown\"}","x","ja"));
        assertThrows(IOException.class,() -> LibreTranslateTranslator.parse(400,"{\"error\":\"bad\"}","x","ja"));
    }
    @Test void serviceCachesAndStopsAfterQuota() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        Translator fake=new Translator() {
            @Override public String name() { return "fake"; }
            @Override public Result translate(String text,String target) throws IOException {
                calls.incrementAndGet();
                if(text.equals("quota")) throw new QuotaException("done");
                return new Result(text.toUpperCase(),"en");
            }
        };
        var service=new TranslationService(fake,Logger.getAnonymousLogger());
        try {
            assertEquals("HELLO",service.translate("hello","ja").get(5,TimeUnit.SECONDS).text());
            assertEquals("HELLO",service.translate("hello","ja").get(5,TimeUnit.SECONDS).text());
            assertEquals(1,calls.get(),"Second call served from cache");
            service.translate("hello","ko").get(5,TimeUnit.SECONDS);
            assertEquals(2,calls.get(),"Cache is per target language");
            assertEquals(10,service.charactersToday(),"Cache hits are not counted");
            var failed=assertThrows(ExecutionException.class,() -> service.translate("quota","ja").get(5,TimeUnit.SECONDS));
            assertInstanceOf(Translator.QuotaException.class,failed.getCause());
            assertTrue(service.quotaFinished());
            assertThrows(ExecutionException.class,() -> service.translate("new text","ja").get(5,TimeUnit.SECONDS));
            assertEquals(3,calls.get(),"No calls after the quota is used up");
            assertEquals("HELLO",service.translate("hello","ja").get(5,TimeUnit.SECONDS).text(),"Cache still answers");
        } finally { service.shutdown(); }
        assertFalse(new TranslationService(null,Logger.getAnonymousLogger()).enabled());
    }
}
