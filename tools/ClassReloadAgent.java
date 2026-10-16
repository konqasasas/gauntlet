import com.sun.tools.attach.VirtualMachine;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Local development helper only; not included in the plugin JAR.
 * main (JDK side): diffs build/classes against the last applied state and attaches to the server.
 * agentmain (server side): redefines loaded classes and defines new ones in the plugin class loader.
 */
public class ClassReloadAgent {
    public static void agentmain(String args,Instrumentation instrumentation) throws Throwable {
        String[] files=args.split("\\|",2);
        Path result=Path.of(files[1]);
        try {
            Files.writeString(result,apply(Path.of(files[0]),instrumentation),StandardCharsets.UTF_8);
        } catch(Throwable e) {
            StringWriter trace=new StringWriter(); e.printStackTrace(new PrintWriter(trace));
            Files.writeString(result,"ERROR\n"+trace,StandardCharsets.UTF_8);
            throw e;
        }
    }

    private static String apply(Path listFile,Instrumentation instrumentation) throws Throwable {
        Class<?> bukkit=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses()) if(type.getName().equals("org.bukkit.Bukkit")) { bukkit=type; break; }
        if(bukkit==null) throw new IllegalStateException("Paper is not loaded");
        Object manager=bukkit.getMethod("getPluginManager").invoke(null);
        Object plugin=Class.forName("org.bukkit.plugin.PluginManager",false,bukkit.getClassLoader()).getMethod("getPlugin",String.class).invoke(manager,"ParkourGauntlet");
        if(plugin==null) throw new IllegalStateException("ParkourGauntlet is not loaded");
        ClassLoader loader=plugin.getClass().getClassLoader();
        Map<String,Class<?>> loaded=new HashMap<>();
        for(Class<?> type:instrumentation.getAllLoadedClasses()) if(type.getClassLoader()==loader && !type.isHidden()) loaded.put(type.getName(),type);

        List<ClassDefinition> redefine=new ArrayList<>();
        Map<String,byte[]> define=new LinkedHashMap<>();
        for(String line:Files.readAllLines(listFile,StandardCharsets.UTF_8)) {
            if(line.isBlank()) continue;
            String[] entry=line.split("\t",2);
            byte[] bytes=Files.readAllBytes(Path.of(entry[1]));
            Class<?> type=loaded.get(entry[0]);
            if(type!=null) redefine.add(new ClassDefinition(type,bytes)); else define.put(entry[0],bytes);
        }
        if(!redefine.isEmpty()) instrumentation.redefineClasses(redefine.toArray(ClassDefinition[]::new));

        // Changed or new classes that are not loaded yet are defined now, so the stale copy in the deployed JAR is never used.
        // Supertypes resolve during definition, so retry until no further progress.
        instrumentation.redefineModule(Object.class.getModule(),Set.of(),Map.of(),Map.of("java.lang",Set.of(ClassReloadAgent.class.getModule())),Set.of(),Map.of());
        Method defineClass=ClassLoader.class.getDeclaredMethod("defineClass",String.class,byte[].class,int.class,int.class);
        defineClass.setAccessible(true);
        int defined=0;
        while(!define.isEmpty()) {
            Throwable last=null; int before=define.size();
            for(var it=define.entrySet().iterator();it.hasNext();) {
                var entry=it.next();
                try {
                    defineClass.invoke(loader,entry.getKey(),entry.getValue(),0,entry.getValue().length);
                    defined++; it.remove();
                } catch(java.lang.reflect.InvocationTargetException e) { last=e.getCause(); }
            }
            if(define.size()==before) throw new IllegalStateException("Could not define "+define.keySet(),last);
        }
        String summary="redefined "+redefine.size()+", defined "+defined;
        System.out.println("[Hotswap] ParkourGauntlet: "+summary);
        return "OK "+summary+"\n";
    }

    /** args: pid agentJar classesDir deployedJar stateDir */
    public static void main(String[] args) throws Exception {
        Path classes=Path.of(args[2]), deployed=Path.of(args[3]), state=Path.of(args[4]);
        Path applied=state.resolve("applied");
        String stamp=Files.getLastModifiedTime(deployed).toMillis()+":"+Files.size(deployed);
        Path stampFile=state.resolve("deployed-jar.stamp");
        if(!Files.exists(stampFile) || !Files.readString(stampFile).equals(stamp)) {
            deleteTree(applied);
            Files.createDirectories(state);
            Files.writeString(stampFile,stamp);
        }

        List<Path> changed=new ArrayList<>();
        try(ZipFile jar=new ZipFile(deployed.toFile()); Stream<Path> walk=Files.walk(classes)) {
            for(Path file:(Iterable<Path>)walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String relative=classes.relativize(file).toString().replace('\\','/');
                byte[] current=Files.readAllBytes(file);
                Path previous=applied.resolve(relative);
                byte[] baseline=null;
                if(Files.exists(previous)) baseline=Files.readAllBytes(previous);
                else { var entry=jar.getEntry(relative); if(entry!=null) try(var in=jar.getInputStream(entry)) { baseline=in.readAllBytes(); } }
                if(!Arrays.equals(current,baseline)) changed.add(file);
            }
        }
        if(changed.isEmpty()) { System.out.println("No class changes to hotswap."); return; }

        StringBuilder list=new StringBuilder();
        for(Path file:changed) {
            String relative=classes.relativize(file).toString().replace('\\','/');
            list.append(relative.substring(0,relative.length()-6).replace('/','.')).append('\t').append(file.toAbsolutePath()).append('\n');
        }
        Path listFile=state.resolve("request.txt"), resultFile=state.resolve("result.txt");
        Files.writeString(listFile,list,StandardCharsets.UTF_8);
        Files.deleteIfExists(resultFile);
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1],listFile.toAbsolutePath()+"|"+resultFile.toAbsolutePath()); }
        catch(Exception e) { /* details are in the result file */ }
        finally { vm.detach(); }

        String result=Files.exists(resultFile) ? Files.readString(resultFile,StandardCharsets.UTF_8) : "ERROR\nAgent produced no result.";
        if(!result.startsWith("OK")) { System.err.println("Hotswap failed:\n"+result); System.exit(1); }
        for(Path file:changed) {
            Path target=applied.resolve(classes.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("  "+classes.relativize(file));
        }
        System.out.println("Hotswap "+result.trim());
    }

    private static void deleteTree(Path root) throws Exception {
        if(!Files.exists(root)) return;
        try(Stream<Path> walk=Files.walk(root)) { for(Path p:walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p); }
    }
}
