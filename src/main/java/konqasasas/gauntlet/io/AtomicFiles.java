package konqasasas.gauntlet.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Durable replace: a crash leaves either the old or the new file, never a partial one. */
public final class AtomicFiles {
    public static void write(Path file,byte[] content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary=file.resolveSibling(file.getFileName()+".tmp");
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(content); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        try { Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING); }
    }
    private AtomicFiles() {}
}
