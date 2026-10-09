import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.net.URLClassLoader;

/** Local development helper only; not included in the plugin JAR. */
public class LocalServerAgent {
    public static void agentmain(String action,Instrumentation instrumentation) throws Exception {
        String[] request=action.split("\\|",2);
        final String operation=request[0];
        Class<?> bukkit=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses()) if(type.getName().equals("org.bukkit.Bukkit")) { bukkit=type; break; }
        if(bukkit==null) throw new IllegalStateException("Paper is not loaded");
        final Class<?> api=bukkit; ClassLoader loader=api.getClassLoader();
        var manager=api.getMethod("getPluginManager").invoke(null);
        var pm=Class.forName("org.bukkit.plugin.PluginManager",false,loader);
        Object plugin=pm.getMethod("getPlugin",String.class).invoke(manager,"ParkourGauntlet");
        if(plugin==null) throw new IllegalStateException("ParkourGauntlet is not loaded");
        Runnable task=() -> {
            try {
                if(operation.equals("check")) {
                    try(var checks=new URLClassLoader(new java.net.URL[]{new java.io.File(request[1]).toURI().toURL()},loader)) {
                        checks.loadClass("ServerChecks").getMethod("run",Object.class).invoke(null,plugin);
                    }
                } else if(operation.equals("stop")) {
                    var sender=Class.forName("org.bukkit.command.CommandSender",false,loader);
                    api.getMethod("dispatchCommand",sender,String.class).invoke(null,api.getMethod("getConsoleSender").invoke(null),"stop");
                } else throw new IllegalArgumentException("check or stop only");
            } catch(Throwable e) { e.printStackTrace(); }
        };
        var scheduler=api.getMethod("getScheduler").invoke(null);
        Class.forName("org.bukkit.scheduler.BukkitScheduler",false,loader).getMethod("runTask",Class.forName("org.bukkit.plugin.Plugin",false,loader),Runnable.class).invoke(scheduler,plugin,task);
    }
    public static void main(String[] args) throws Exception {
        VirtualMachine vm=VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1],args[2]+"|"+args[1]); } finally { vm.detach(); }
        System.out.println("Scheduled "+args[2]+" on verified ParkourGauntlet server.");
    }
}
