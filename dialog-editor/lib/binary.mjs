// PNG（RGBA 8bit）とZIPの最小実装。Canvasの乗算済みアルファを経由せず、画素値をそのまま書き出すため自前で持つ。
import zlib from 'node:zlib';
import crypto from 'node:crypto';

const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(td) >>> 0);
  return Buffer.concat([len, td, crc]);
}

export function encodePNG(rgba, width, height) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  return Buffer.concat([PNG_SIG, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

// encodePNG が書いた形式（RGBA 8bit・非インターレース）だけを読む。検証用。
export function decodeOwnPNG(buf) {
  if (!buf.subarray(0, 8).equals(PNG_SIG)) throw new Error('PNGではありません');
  let off = 8, width = 0, height = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off); const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      if (data[8] !== 8 || data[9] !== 6 || data[12] !== 0) throw new Error('RGBA8以外のPNGです');
    } else if (type === 'IDAT') idat.push(data);
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * 4;
  const out = new Uint8ClampedArray(width * height * 4);
  const prev = new Uint8Array(stride);
  for (let y = 0; y < height; y++) {
    const f = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const cur = new Uint8Array(stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= 4 ? cur[i - 4] : 0, b = prev[i], c = i >= 4 ? prev[i - 4] : 0;
      let v = line[i];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      cur[i] = v & 255;
    }
    out.set(cur, y * stride); prev.set(cur);
  }
  return { data: out, width, height };
}

// ZIP（deflate）。日時は固定値にして、同じ内容なら同じSHA-1になるようにする。
export function makeZip(files) {
  // files: [{ path, data: Buffer|string }]（パス順に並べ替える）
  const sorted = [...files].sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));
  const locals = [], centrals = [];
  let offset = 0;
  const DOS_TIME = 0, DOS_DATE = (2026 - 1980) << 9 | 1 << 5 | 1;
  for (const f of sorted) {
    const data = Buffer.isBuffer(f.data) ? f.data : Buffer.from(f.data, 'utf8');
    const name = Buffer.from(f.path, 'utf8');
    const comp = zlib.deflateRawSync(data, { level: 9 });
    const useStore = comp.length >= data.length;
    const body = useStore ? data : comp;
    const crc = zlib.crc32(data) >>> 0;
    const lh = Buffer.alloc(30);
    lh.writeUInt32LE(0x04034b50, 0); lh.writeUInt16LE(20, 4); lh.writeUInt16LE(0x0800, 6);
    lh.writeUInt16LE(useStore ? 0 : 8, 8); lh.writeUInt16LE(DOS_TIME, 10); lh.writeUInt16LE(DOS_DATE, 12);
    lh.writeUInt32LE(crc, 14); lh.writeUInt32LE(body.length, 18); lh.writeUInt32LE(data.length, 22);
    lh.writeUInt16LE(name.length, 26); lh.writeUInt16LE(0, 28);
    locals.push(lh, name, body);
    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0); ch.writeUInt16LE(20, 4); ch.writeUInt16LE(20, 6); ch.writeUInt16LE(0x0800, 8);
    ch.writeUInt16LE(useStore ? 0 : 8, 10); ch.writeUInt16LE(DOS_TIME, 12); ch.writeUInt16LE(DOS_DATE, 14);
    ch.writeUInt32LE(crc, 16); ch.writeUInt32LE(body.length, 20); ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(name.length, 28); ch.writeUInt32LE(offset, 42);
    centrals.push(ch, name);
    offset += 30 + name.length + body.length;
  }
  const cd = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(sorted.length, 8); end.writeUInt16LE(sorted.length, 10);
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, cd, end]);
}

export function sha1(buf) { return crypto.createHash('sha1').update(buf).digest('hex'); }
