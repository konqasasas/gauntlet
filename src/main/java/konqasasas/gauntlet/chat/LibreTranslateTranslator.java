package konqasasas.gauntlet.chat;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** A LibreTranslate server (self-hosted later). POST {url}/translate with source "auto". */
public final class LibreTranslateTranslator implements Translator {
    private final HttpClient http;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    public LibreTranslateTranslator(HttpClient http,String url,String apiKey,Duration timeout) {
        this.http=http; this.timeout=timeout; this.apiKey=apiKey==null ? "" : apiKey.strip();
        endpoint=URI.create(url.replaceAll("/+$","")+"/translate");
    }
    @Override public String name() { return "LibreTranslate"; }
    @Override public Result translate(String text,String target) throws IOException,InterruptedException {
        JsonObject body=new JsonObject();
        body.addProperty("q",text); body.addProperty("source","auto"); body.addProperty("target",code(target)); body.addProperty("format","text");
        if(!apiKey.isEmpty()) body.addProperty("api_key",apiKey);
        HttpResponse<String> response=http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parse(response.statusCode(),response.body(),text,target);
    }
    /** LibreTranslate names Chinese "zh" (simplified) and "zt" (traditional). */
    static String code(String target) {
        return switch(target) { case "zh-CN" -> "zh"; case "zh-TW" -> "zt"; default -> target; };
    }
    static Result parse(int status,String body,String original,String target) throws IOException {
        JsonObject json;
        try { json=JsonParser.parseString(body).getAsJsonObject(); }
        catch(RuntimeException e) { throw new IOException("LibreTranslate returned non-JSON (HTTP "+status+")"); }
        if(status==429) throw new QuotaException("LibreTranslate rate limited");
        if(status!=200 || !json.has("translatedText")) throw new IOException("LibreTranslate error "+status+": "+(json.has("error") ? json.get("error").getAsString() : body));
        String detected="";
        if(json.has("detectedLanguage") && json.get("detectedLanguage").isJsonObject()) {
            JsonObject d=json.getAsJsonObject("detectedLanguage");
            if(d.has("language")) detected=d.get("language").getAsString();
        }
        if(Languages.sameLanguage(detected.equals("zt") ? "zh" : detected,target)) return new Result(original,target);
        return new Result(json.get("translatedText").getAsString(),detected.equals("zt") ? "zh-TW" : detected);
    }
}
