package konqasasas.gauntlet.replay;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * One recorded run, first person: a frame per server tick (where the player was,
 * where they looked, sneaking, sprinting, a swing, the item in hand), the blocks
 * that changed in the course (placed blocks, trigger water, falling blocks landing:
 * the block data a cell had at the end of that tick) and the other things that
 * moved in it (mobs, falling blocks, boats...), each tick where they were.
 *
 * Stored gzip-compressed (Replay.write / read); strings (items, block data, entity
 * types) are kept once in a table.
 */
public record Replay(UUID run,UUID player,String course,int rev,long ticks,List<Frame> frames,List<BlockChange> blocks,List<List<Thing>> things) {
    private static final int FORMAT=1;
    /** item: material key of the item in hand, or "" for none. */
    public record Frame(double x,double y,double z,float yaw,float pitch,boolean sneaking,boolean sprinting,boolean swing,String item) {}
    /** At tick (frame index), the cell got data (block data string). */
    public record BlockChange(int tick,int x,int y,int z,String data) {}
    /** Something else in the course at one tick: id is stable across ticks; data: block data for falling blocks, else "". */
    public record Thing(int id,String type,double x,double y,double z,float yaw,String data) {}

    public void write(OutputStream raw) throws IOException {
        Strings strings=new Strings();
        for(Frame f:frames) strings.id(f.item());
        for(BlockChange b:blocks) strings.id(b.data());
        for(List<Thing> tick:things) for(Thing t:tick) { strings.id(t.type()); strings.id(t.data()); }
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw)))) {
            out.writeInt(FORMAT);
            out.writeLong(run.getMostSignificantBits()); out.writeLong(run.getLeastSignificantBits());
            out.writeLong(player.getMostSignificantBits()); out.writeLong(player.getLeastSignificantBits());
            out.writeUTF(course); out.writeInt(rev); out.writeLong(ticks);
            out.writeInt(strings.list.size());
            for(String s:strings.list) out.writeUTF(s);
            out.writeInt(frames.size());
            for(Frame f:frames) {
                out.writeDouble(f.x()); out.writeDouble(f.y()); out.writeDouble(f.z()); out.writeFloat(f.yaw()); out.writeFloat(f.pitch());
                out.writeByte((f.sneaking() ? 1 : 0)|(f.sprinting() ? 2 : 0)|(f.swing() ? 4 : 0));
                out.writeInt(strings.id(f.item()));
            }
            out.writeInt(blocks.size());
            for(BlockChange b:blocks) { out.writeInt(b.tick()); out.writeInt(b.x()); out.writeInt(b.y()); out.writeInt(b.z()); out.writeInt(strings.id(b.data())); }
            out.writeInt(things.size());
            for(List<Thing> tick:things) {
                out.writeInt(tick.size());
                for(Thing t:tick) {
                    out.writeInt(t.id()); out.writeInt(strings.id(t.type()));
                    out.writeDouble(t.x()); out.writeDouble(t.y()); out.writeDouble(t.z()); out.writeFloat(t.yaw()); out.writeInt(strings.id(t.data()));
                }
            }
        }
    }
    public static Replay read(InputStream raw) throws IOException {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)))) {
            if(in.readInt()!=FORMAT) throw new IOException("Unknown replay format");
            UUID run=new UUID(in.readLong(),in.readLong()),player=new UUID(in.readLong(),in.readLong());
            String course=in.readUTF(); int rev=in.readInt(); long ticks=in.readLong();
            List<String> strings=new ArrayList<>();
            for(int i=in.readInt();i>0;i--) strings.add(in.readUTF());
            List<Frame> frames=new ArrayList<>();
            for(int i=in.readInt();i>0;i--) {
                double x=in.readDouble(),y=in.readDouble(),z=in.readDouble(); float yaw=in.readFloat(),pitch=in.readFloat(); int flags=in.readByte();
                frames.add(new Frame(x,y,z,yaw,pitch,(flags&1)!=0,(flags&2)!=0,(flags&4)!=0,strings.get(in.readInt())));
            }
            List<BlockChange> blocks=new ArrayList<>();
            for(int i=in.readInt();i>0;i--) blocks.add(new BlockChange(in.readInt(),in.readInt(),in.readInt(),in.readInt(),strings.get(in.readInt())));
            List<List<Thing>> things=new ArrayList<>();
            for(int i=in.readInt();i>0;i--) {
                List<Thing> tick=new ArrayList<>();
                for(int j=in.readInt();j>0;j--)
                    tick.add(new Thing(in.readInt(),strings.get(in.readInt()),in.readDouble(),in.readDouble(),in.readDouble(),in.readFloat(),strings.get(in.readInt())));
                things.add(tick);
            }
            return new Replay(run,player,course,rev,ticks,frames,blocks,things);
        }
    }
    private static final class Strings {
        final List<String> list=new ArrayList<>(); final Map<String,Integer> ids=new HashMap<>();
        int id(String s) { return ids.computeIfAbsent(s==null ? "" : s,k -> { list.add(k); return list.size()-1; }); }
    }
}
