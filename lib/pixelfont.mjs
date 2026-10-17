// 登録したドットフォント（fonts.json の pixel: true）を、ドット単位のビットマップへ変換する（Node専用）。
// ブラウザーとNodeが同じビットマップから描くため、フォントの読み込み可否やアンチエイリアスの差が出ない。
import fs from 'node:fs';
import { createCanvas } from '@napi-rs/canvas';

// TrueType の cmap（format 4）から収録文字を取り出す。壊れた区間は無視する
export function ttfChars(buf) {
  const n = buf.readUInt16BE(4);
  const tables = {};
  for (let i = 0; i < n; i++) tables[buf.toString('latin1', 12 + 16 * i, 16 + 16 * i)] = buf.readUInt32BE(20 + 16 * i);
  const numGlyphs = buf.readUInt16BE(tables.maxp + 4);
  const off = tables.cmap;
  const out = new Set();
  for (let t = 0; t < buf.readUInt16BE(off + 2); t++) {
    const st = off + buf.readUInt32BE(off + 8 + 8 * t);
    if (buf.readUInt16BE(st) !== 4) continue;
    const seg = buf.readUInt16BE(st + 6) / 2;
    const ends = st + 14, starts = ends + seg * 2 + 2, deltas = starts + seg * 2, ranges = deltas + seg * 2;
    for (let k = 0; k < seg; k++) {
      const a = buf.readUInt16BE(starts + 2 * k), b = buf.readUInt16BE(ends + 2 * k);
      const delta = buf.readInt16BE(deltas + 2 * k), ro = buf.readUInt16BE(ranges + 2 * k);
      for (let c = a; c <= b && c !== 0xffff; c++) {
        let g;
        if (!ro) g = (c + delta) & 0xffff;
        else {
          const p = ranges + 2 * k + ro + 2 * (c - a);
          g = p + 2 <= buf.length ? buf.readUInt16BE(p) : 0;
          if (g) g = (g + delta) & 0xffff;
        }
        if (g > 0 && g < numGlyphs && c >= 0x20 && !(c >= 0x7f && c < 0xa0)) out.add(c);
      }
    }
  }
  return out;
}

/**
 * @returns { family, dotSize, ascent, glyphs: { [ch]: [advance, ox, oy, w, h, bits] } }
 *   単位はドット。ox/oy はペン位置・ベースラインからのずれ。bits は '0'/'1' の文字列（行優先）。
 */
export function buildPixelFont(def, file) {
  const chars = [...ttfChars(fs.readFileSync(file))].map((c) => String.fromCodePoint(c));
  const size = def.dotSize; // この px 指定で 1ドット = 1px
  const W = size * 4, H = size * 4, base = size * 2, pen = size;
  const c = createCanvas(W, H);
  const ctx = c.getContext('2d');
  ctx.font = `${size}px "${def.family}"`;
  ctx.textBaseline = 'alphabetic';
  const glyphs = {};
  let ascent = 0;
  for (const ch of chars) {
    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = '#fff';
    ctx.fillText(ch, pen, base);
    const d = ctx.getImageData(0, 0, W, H).data;
    let x0 = W, y0 = H, x1 = -1, y1 = -1;
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (d[(y * W + x) * 4 + 3] >= 128) { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
    const advance = Math.round(ctx.measureText(ch).width);
    if (x1 < 0) { glyphs[ch] = [advance, 0, 0, 0, 0, '']; continue; }
    let bits = '';
    for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) bits += d[(y * W + x) * 4 + 3] >= 128 ? '1' : '0';
    glyphs[ch] = [advance, x0 - pen, y0 - base, x1 - x0 + 1, y1 - y0 + 1, bits];
    ascent = Math.max(ascent, base - y0);
  }
  return { family: def.family, dotSize: size, ascent, glyphs };
}
