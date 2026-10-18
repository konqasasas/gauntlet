// ゲーム内フォント（minecraft:default 相当）の字幅とグリフを、手元のMinecraft資源から再現する。
// 26.3 client.jar の規則:
//  - space:  advance は定義値。
//  - bitmap: advance = (int)(0.5 + 実幅 * (height / セル高さ)) + 1。太字+1。描画上端 = 7 - ascent。
//  - unihex: 実幅 = 点灯している最左列〜最右列（size_overrides があれば left/right を上書き）。
//            advance = 実幅/2 + 1（整数除算）。太字+0.5。16px を 1/2 で描き、上端は行の上端。
// 参照順は include/space → include/default → include/unifont（先に見つかったものを使う）。
// unifont の jp パッチは、クライアントの言語が日本語のときだけ使われる（filter: jp）。
import fs from 'node:fs';
import path from 'node:path';
import { loadImage, createCanvas } from '@napi-rs/canvas';
import { readZip } from './zipread.mjs';

const SCALE_TOP = 7;

function readJson(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }

function bitmapGlyphs(def, imgData, out) {
  const rows = def.chars.map((r) => Array.from(r));
  const cols = rows[0].length;
  const cellW = imgData.width / cols, cellH = imgData.height / rows.length;
  const height = def.height ?? 8;
  const scale = Math.fround(height / cellH);
  rows.forEach((row, ry) => row.forEach((ch, cx) => {
    const cp = ch.codePointAt(0);
    if (cp === 0 || out.has(cp)) return;
    const alpha = new Uint8Array(cellW * cellH);
    let aw = 0;
    for (let y = 0; y < cellH; y++) for (let x = 0; x < cellW; x++) {
      const i = ((ry * cellH + y) * imgData.width + cx * cellW + x) * 4;
      // getLuminanceOrAlpha: RGBA画像ではアルファ
      const a = imgData.data[i + 3];
      alpha[y * cellW + x] = a;
      if (a && x + 1 > aw) aw = x + 1;
    }
    out.set(cp, {
      kind: 'bitmap', advance: Math.trunc(0.5 + Math.fround(Math.fround(aw) * scale)) + 1, boldOffset: 1, shadowOffset: 1,
      w: cellW, h: cellH, scale, top: SCALE_TOP - def.ascent, alpha,
    });
  }));
}

function parseHex(text, overrides, out, onlyNew = true) {
  for (const line of text.split('\n')) {
    const c = line.indexOf(':');
    if (c < 0) continue;
    const cp = parseInt(line.slice(0, c), 16);
    if (onlyNew && out.has(cp)) continue;
    const hex = line.slice(c + 1).trim();
    const bitWidth = hex.length / 4; // 16行
    if (![8, 16, 24, 32].includes(bitWidth)) continue;
    const per = bitWidth / 4;
    let mask = 0;
    const lines = new Array(16);
    for (let r = 0; r < 16; r++) {
      const v = parseInt(hex.slice(r * per, (r + 1) * per), 16);
      lines[r] = v;
      mask |= (v << (32 - bitWidth)) >>> 0;
    }
    let left, right;
    if (mask === 0) { left = 0; right = bitWidth; } else { left = Math.clz32(mask); right = 31 - ctz32(mask); }
    const ov = overrides.find((o) => cp >= o.from && cp <= o.to);
    if (ov) { left = ov.left; right = ov.right; }
    const width = right - left + 1;
    out.set(cp, { kind: 'unihex', advance: Math.trunc(width / 2) + 1, boldOffset: 0.5, shadowOffset: 0.5, bitWidth, left, right, lines, scale: 0.5, top: 0 });
  }
}

function ctz32(v) { v >>>= 0; if (!v) return 32; let n = 0; while (!(v & 1)) { v >>>= 1; n++; } return n; }

// unihex のグリフ画素（left〜right 列, 16行）
function unihexAlpha(g) {
  if (g.alpha) return g;
  const w = Math.max(0, g.right - g.left + 1);
  const alpha = new Uint8Array(w * 16);
  for (let r = 0; r < 16; r++) {
    const v = g.lines[r];
    for (let x = 0; x < w; x++) {
      const col = g.left + x;
      if (col < g.bitWidth && (v >> (g.bitWidth - 1 - col)) & 1) alpha[r * w + x] = 255;
    }
  }
  g.alpha = alpha; g.w = w; g.h = 16;
  return g;
}

export class VanillaFont {
  constructor() { this.base = new Map(); this.jp = new Map(); this.langDependent = new Set(); this.info = {}; }

  static async load(dir) {
    const f = new VanillaFont();
    const jarDir = path.join(dir, 'jar');
    const assetsDir = path.join(dir, 'assets');
    if (!fs.existsSync(path.join(jarDir, 'font', 'include', 'default.json'))) throw new Error(`ゲーム内フォントの資源がありません: ${dir}（node tools/fetch-vanilla.mjs を実行してください）`);
    f.info = fs.existsSync(path.join(dir, 'source.json')) ? readJson(path.join(dir, 'source.json')) : {};
    const common = new Map();
    for (const [ch, adv] of Object.entries(readJson(path.join(jarDir, 'font', 'include', 'space.json')).providers[0].advances)) {
      common.set(ch.codePointAt(0), { kind: 'space', advance: adv, boldOffset: 1, shadowOffset: 1 });
    }
    for (const p of readJson(path.join(jarDir, 'font', 'include', 'default.json')).providers) {
      if (p.type !== 'bitmap') continue;
      const file = path.join(jarDir, 'textures', ...p.file.replace(/^minecraft:/, '').split('/'));
      const img = await loadImage(fs.readFileSync(file));
      const c = createCanvas(img.width, img.height);
      c.getContext('2d').drawImage(img, 0, 0);
      bitmapGlyphs(p, c.getContext('2d').getImageData(0, 0, img.width, img.height), common);
    }
    const uni = readJson(path.join(assetsDir, 'font', 'include', 'unifont.json'));
    const baseUni = new Map(), jpUni = new Map();
    for (const p of uni.providers) {
      if (p.type !== 'unihex') continue;
      const zip = readZip(fs.readFileSync(path.join(assetsDir, 'font', path.basename(p.hex_file.replace(/^minecraft:font\//, '')))));
      const overrides = (p.size_overrides || []).map((o) => ({ from: o.from.codePointAt(0), to: o.to.codePointAt(0), left: o.left, right: o.right }));
      const target = p.filter?.jp ? jpUni : baseUni;
      for (const [name, e] of zip) if (name.endsWith('.hex')) parseHex(e.read().toString('latin1'), overrides, target);
    }
    for (const m of [f.base, f.jp]) for (const [k, v] of common) m.set(k, v);
    for (const [k, v] of baseUni) { if (!common.has(k)) f.base.set(k, v); }
    for (const [k, v] of jpUni) { if (!common.has(k)) f.jp.set(k, v); }
    for (const [k, v] of baseUni) { if (!common.has(k) && !f.jp.has(k)) f.jp.set(k, v); }
    for (const [k, v] of jpUni) {
      if (common.has(k)) continue;
      const b = baseUni.get(k);
      if (!b || b.advance !== v.advance) f.langDependent.add(k);
    }
    return f;
  }

  glyph(cp, lang = 'base') {
    const g = (lang === 'jp' ? this.jp : this.base).get(cp);
    if (!g) return null;
    if (g.kind === 'unihex') unihexAlpha(g);
    return g;
  }

  has(cp) { return this.base.has(cp); }

  advance(cp, bold) {
    const g = this.base.get(cp);
    if (!g) return null;
    return g.advance + (bold ? g.boldOffset : 0);
  }

  // ブラウザーへ渡す形式
  exportGlyph(cp, lang) {
    const g = this.glyph(cp, lang);
    if (!g) return null;
    const r = { k: g.kind, a: g.advance, b: g.boldOffset, so: g.shadowOffset };
    if (g.alpha) Object.assign(r, { w: g.w, h: g.h, s: g.scale, t: g.top, m: Buffer.from(g.alpha).toString('base64') });
    if (this.langDependent.has(cp)) r.ld = 1;
    return r;
  }
}

let cached = null;
export async function getVanillaFont(dir) {
  if (cached && cached.dir === dir) return cached.promise;
  cached = { dir, promise: VanillaFont.load(dir).catch((e) => { cached = null; throw e; }) };
  return cached.promise;
}
