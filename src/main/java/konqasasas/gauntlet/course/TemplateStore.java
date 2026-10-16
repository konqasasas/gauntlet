package konqasasas.gauntlet.course;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import konqasasas.gauntlet.io.AtomicFiles;

/** Item templates shared by all courses (templates.json). */
public final class TemplateStore {
    /** item is Paper's ItemStack.serializeAsBytes, Base64-encoded. */
    public record Template(String id,String name,String item,boolean unbreakable) {
        public Template {
            if(id==null || !id.matches("t[0-9a-z]{1,16}") || name==null || name.isBlank() || name.length()>Course.MAX_NAME_LENGTH || item==null || item.isBlank())
                throw new IllegalArgumentException("Invalid template");
        }
        public Template withName(String value) { return new Template(id,value,item,unbreakable); }
        public Template withUnbreakable(boolean value) { return new Template(id,name,item,value); }
    }
    private record SavedFile(int format,List<Template> templates) {}
    private final Path file;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String,Template> templates=new LinkedHashMap<>();

    public TemplateStore(Path file) throws IOException {
        this.file=file;
        if(!Files.exists(file)) return;
        try {
            SavedFile saved=gson.fromJson(Files.readString(file),SavedFile.class);
            if(saved==null || saved.format()!=1 || saved.templates()==null) throw new IllegalArgumentException("Unsupported format");
            for(Template t:saved.templates()) if(t==null || templates.putIfAbsent(t.id(),t)!=null) throw new IllegalArgumentException("Invalid/duplicate template");
        } catch(RuntimeException e) { throw new IOException("Template file is invalid; preserved: "+file,e); }
    }
    public List<Template> all() { return List.copyOf(templates.values()); }
    public Template get(String id) { return templates.get(id); }
    public Template add(String name,String item,boolean unbreakable) throws IOException {
        long n=System.currentTimeMillis(); String id;
        do id="t"+Long.toString(n++,36); while(templates.containsKey(id));
        Template template=new Template(id,name,item,unbreakable); put(template); return template;
    }
    public void put(Template template) throws IOException {
        var next=new LinkedHashMap<>(templates); next.put(template.id(),template); write(next);
    }
    public void remove(String id) throws IOException {
        var next=new LinkedHashMap<>(templates); next.remove(id); write(next);
    }
    private void write(Map<String,Template> next) throws IOException {
        AtomicFiles.write(file,gson.toJson(new SavedFile(1,List.copyOf(next.values()))).getBytes(StandardCharsets.UTF_8));
        templates.clear(); templates.putAll(next);
    }
}
