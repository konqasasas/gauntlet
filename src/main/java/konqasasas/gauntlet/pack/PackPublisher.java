package konqasasas.gauntlet.pack;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.time.*;
import java.util.*;

/**
 * Copies resource pack files into a web folder under their SHA-1 ("<sha1>.zip"), so the URL changes
 * whenever the content does and clients never use a stale cached pack. A file is hashed again only
 * when its size or modification time changes. No Bukkit; ResourcePacks sends the result to players.
 */
public final class PackPublisher {
    /** One pack as players receive it. The id stays the same for a source, so a new version replaces the old one. */
    public record Published(String source,String sha1,URI uri,UUID id) {}
    private record Seen(long size,FileTime modified,String sha1) {}
    private final Path root,dir;
    private final URI base;
    private final Map<String,Seen> seen=new HashMap<>();

    /** root: the server folder the sources are relative to; dir: the published folder; base: its URL (ends with "/"). */
    public PackPublisher(Path root,Path dir,URI base) {
        this.root=root; this.dir=dir;
        String b=base.toString();
        this.base=URI.create(b.endsWith("/") ? b : b+"/");
    }

    /** The sources that exist, in order, each published into dir. Missing sources are skipped. */
    public synchronized List<Published> refresh(List<String> sources) throws IOException {
        List<Published> out=new ArrayList<>();
        for(String source:sources) {
            Path file=root.resolve(source).normalize();
            if(!Files.isRegularFile(file)) { seen.remove(source); continue; }
            long size=Files.size(file);
            FileTime modified=Files.getLastModifiedTime(file);
            Seen last=seen.get(source);
            String sha1=last!=null && last.size()==size && last.modified().equals(modified) ? last.sha1() : sha1(file);
            seen.put(source,new Seen(size,modified,sha1));
            Path target=dir.resolve(sha1+".zip");
            if(!Files.isRegularFile(target)) {
                Path tmp=Files.createTempFile(dir,".upload-",".zip");
                try {
                    Files.copy(file,tmp,StandardCopyOption.REPLACE_EXISTING);
                    if(!sha1(tmp).equals(sha1)) throw new IOException(source+" changed while it was being published");
                    Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(tmp); }
            }
            out.add(new Published(source,sha1,base.resolve(sha1+".zip"),UUID.nameUUIDFromBytes(("gauntlet-pack:"+source).getBytes(StandardCharsets.UTF_8))));
        }
        return out;
    }

    /** Deletes published packs nobody uses any more, after a grace period for players still downloading them. */
    public synchronized int prune(Collection<Published> keep,Duration grace,Instant now) throws IOException {
        Set<String> names=new HashSet<>();
        for(Published p:keep) names.add(p.sha1()+".zip");
        int removed=0;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(dir,"*.zip")) {
            for(Path f:files) {
                if(names.contains(f.getFileName().toString())) continue;
                if(Files.getLastModifiedTime(f).toInstant().isAfter(now.minus(grace))) continue;
                Files.deleteIfExists(f); removed++;
            }
        }
        return removed;
    }

    static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-1");
            try(InputStream in=Files.newInputStream(file)) {
                byte[] buf=new byte[64*1024];
                for(int n;(n=in.read(buf))>0;) digest.update(buf,0,n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
