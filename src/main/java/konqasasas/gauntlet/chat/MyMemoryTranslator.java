package konqasasas.gauntlet.chat;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * MyMemory (api.mymemory.translated.net). No key; 5,000 characters a day, or
 * 50,000 with a contact email. One request carries at most 500 bytes of text.
 */
public final class MyMemoryTranslator implements Translator {
    static final int MAX_BYTES=480;
    private final HttpClient http;
    private final String email;
    private final Duration timeout;
    public MyMemoryTranslator(HttpClient http,String email,Duration timeout) { this.http=http; this.email=email==null ? "" : email.strip(); this.timeout=timeout; }
    @Override public String name() { return "MyMemory"; }
    @Override public Result translate(String text,String target) throws IOException,InterruptedException {
        StringBuilder out=new StringBuilder(); String source="";
        for(String chunk:TextGuess.chunks(text,MAX_BYTES)) {
            String url="https://api.mymemory.translated.net/get?q="+encode(chunk)+"&langpair="+encode("autodetect|"+target)
                    +(email.isEmpty() ? "" : "&de="+encode(email));
            HttpResponse<String> response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Result part=parse(response.statusCode(),response.body(),chunk,target);
            out.append(part.text()); if(source.isEmpty()) source=part.source();
        }
        return new Result(out.toString(),source);
    }
    /** Parses one /get response. Same-language input comes back unchanged with source = target. */
    static Result parse(int status,String body,String original,String target) throws IOException {
        JsonObject json;
        try { json=JsonParser.parseString(body).getAsJsonObject(); }
        catch(RuntimeException e) { throw new IOException("MyMemory returned non-JSON (HTTP "+status+")"); }
        if(json.has("quotaFinished") && !json.get("quotaFinished").isJsonNull() && json.get("quotaFinished").getAsBoolean())
            throw new QuotaException("MyMemory daily quota finished");
        int code=json.has("responseStatus") ? parseStatus(json.get("responseStatus")) : status;
        JsonObject data=json.has("responseData") && json.get("responseData").isJsonObject() ? json.getAsJsonObject("responseData") : null;
        String translated=data!=null && data.has("translatedText") && !data.get("translatedText").isJsonNull() ? data.get("translatedText").getAsString() : null;
        String detected=data!=null && data.has("detectedLanguage") && !data.get("detectedLanguage").isJsonNull() ? data.get("detectedLanguage").getAsString() : "";
        if(code==429) throw new QuotaException("MyMemory rate limited");
        String details=json.has("responseDetails") && !json.get("responseDetails").isJsonNull() ? json.get("responseDetails").getAsString() : "";
        // Asking for the language the text is already in is reported as an error, not a translation.
        if((translated!=null && translated.contains("SELECT TWO DISTINCT LANGUAGES")) || details.contains("DISTINCT LANGUAGES") || Languages.sameLanguage(detected,target))
            return new Result(original,target);
        if(code!=200 || translated==null) throw new IOException("MyMemory error "+code+": "+details);
        return new Result(TextGuess.unescapeHtml(translated),detected);
    }
    private static int parseStatus(JsonElement e) {
        try { return e.getAsInt(); } catch(RuntimeException ex) { return -1; }
    }
    private static String encode(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
}
