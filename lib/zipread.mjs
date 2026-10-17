// ZIP（JAR）の読み取り。中央ディレクトリから一覧を作り、必要なエントリだけ展開する。
import zlib from 'node:zlib';

export function readZip(buf) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65557); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('ZIPではありません');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('ZIPの中央ディレクトリが壊れています');
    const method = buf.readUInt16LE(off + 10);
    const csize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28), extraLen = buf.readUInt16LE(off + 30), commentLen = buf.readUInt16LE(off + 32);
    const local = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    entries.set(name, {
      name,
      read() {
        const ln = buf.readUInt16LE(local + 26), le = buf.readUInt16LE(local + 28);
        const data = buf.subarray(local + 30 + ln + le, local + 30 + ln + le + csize);
        if (method === 0) return Buffer.from(data);
        if (method === 8) return zlib.inflateRawSync(data);
        throw new Error(`未対応の圧縮方式 ${method}: ${name}`);
      },
    });
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}
