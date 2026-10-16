package konqasasas.gauntlet.course;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import konqasasas.gauntlet.io.AtomicFiles;

/**
 * One JSON file per course, readable and diffable. A broken file is skipped and
 * never overwritten: its id stays reserved until someone repairs or removes it.
 */
public final class CourseStore {
    private record CourseFile(int format,Course course) {}
    private static final Pattern ID=Pattern.compile("[a-z0-9_-]{1,32}");
    private final Path directory;
    private final Gson gson=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String,Course> courses=new TreeMap<>();
    private final List<String> invalidFiles=new ArrayList<>();

    public CourseStore(Path directory) throws IOException {
        this.directory=directory;
        if(!Files.isDirectory(directory)) return;
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String id=file.getFileName().toString().replaceFirst("\\.json$","");
                try {
                    CourseFile saved=gson.fromJson(Files.readString(file),CourseFile.class);
                    if(saved==null || saved.format()!=1 || saved.course()==null || !saved.course().id().equals(id))
                        throw new IllegalArgumentException("Unsupported format or id mismatch");
                    courses.put(id,saved.course());
                } catch(IOException|RuntimeException e) { invalidFiles.add(file.getFileName()+": "+e.getMessage()); }
            }
        }
    }
    public static boolean validId(String id) { return id!=null && ID.matcher(id).matches(); }
    public List<String> invalidFiles() { return List.copyOf(invalidFiles); }
    public List<Course> all() { return List.copyOf(courses.values()); }
    public Course get(String id) { return courses.get(id); }
    public void create(Course course) throws IOException {
        if(courses.containsKey(course.id()) || Files.exists(file(course.id()))) throw new FileAlreadyExistsException(course.id());
        write(course);
    }
    public void save(Course course) throws IOException {
        if(!courses.containsKey(course.id())) throw new NoSuchFileException(course.id());
        write(course);
    }
    /** Keeps a copy under deleted/ instead of erasing the definition. */
    public void delete(String id) throws IOException {
        if(!courses.containsKey(id)) throw new NoSuchFileException(id);
        Path target=directory.resolve("deleted").resolve(id+"-"+System.currentTimeMillis()+".json");
        Files.createDirectories(target.getParent());
        Files.move(file(id),target);
        courses.remove(id);
    }
    private void write(Course course) throws IOException {
        AtomicFiles.write(file(course.id()),gson.toJson(new CourseFile(1,course)).getBytes(StandardCharsets.UTF_8));
        courses.put(course.id(),course);
    }
    private Path file(String id) { return directory.resolve(id+".json"); }
}
