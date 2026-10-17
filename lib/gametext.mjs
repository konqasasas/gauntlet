// ゲーム内フォント（minecraft:default 相当）の文字配置と描画。ブラウザーとNodeで共通。
// 字幅は VanillaFont（Node）またはサーバーから取得したグリフ（ブラウザー）で求める。
import { elementShown } from './states.mjs';
import { LINE } from './model.mjs';

// source.raw(cp) → { a: advance, b: boldOffset, so: shadowOffset, w, h, s: scale, t: top, alpha: Uint8Array } | null
export class GlyphAtlas {
  constructor(source, makeCanvas) {
    this.source = source;
    this.makeCanvas = makeCanvas;
    this.cache = new Map();
    this.tinted = new Map();
  }

  get(cp) {
    if (this.cache.has(cp)) return this.cache.get(cp);
    const g = this.source.raw(cp);
    if (g === undefined) return undefined; // 未取得（ブラウザーで読み込み待ち）
    this.cache.set(cp, g);
    return g;
  }

  advance(cp, bold) {
    const g = this.get(cp);
    if (!g) return g;
    return g.a + (bold ? g.b : 0);
  }

  // 指定色で塗ったグリフのキャンバス（テクスチャ1px = グリフ1px）
  canvas(cp, color) {
    const key = `${cp}|${color}`;
    if (this.tinted.has(key)) return this.tinted.get(key);
    const g = this.get(cp);
    if (!g || !g.alpha || !g.w) { this.tinted.set(key, null); return null; }
    const c = this.makeCanvas(g.w, g.h);
    const ctx = c.getContext('2d');
    const img = ctx.createImageData(g.w, g.h);
    const [r, gg, b, a] = parseColor(color);
    for (let i = 0; i < g.w * g.h; i++) {
      const al = g.alpha[i];
      if (!al) continue;
      img.data[i * 4] = r; img.data[i * 4 + 1] = gg; img.data[i * 4 + 2] = b; img.data[i * 4 + 3] = Math.round(al * a / 255);
    }
    ctx.putImageData(img, 0, 0);
    this.tinted.set(key, c);
    return c;
  }
}

export function parseColor(hex) {
  const h = (hex || '#ffffffff').slice(1);
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), h.length >= 8 ? parseInt(h.slice(6, 8), 16) : 255];
}

const isBreakable = (ch) => ch === ' ';

// グリフのインク（実際に描かれる画素）の範囲。GUI px、x はペン位置から、y は行の上端から
export function glyphInk(atlas, cp) {
  const g = atlas.get(cp);
  if (!g || !g.alpha || !g.w) return null;
  if (g.ink !== undefined) return g.ink;
  let x0 = Infinity, y0 = Infinity, x1 = -1, y1 = -1;
  for (let y = 0; y < g.h; y++) for (let x = 0; x < g.w; x++) {
    if (g.alpha[y * g.w + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  g.ink = x1 < 0 ? null : { l: x0 * g.s, r: (x1 + 1) * g.s, t: g.t + y0 * g.s, b: g.t + (y1 + 1) * g.s };
  return g.ink;
}

// 行の文字のインクの左右（ペン位置0から）。送り幅には右端の1pxの隙間などが含まれるため、揃えにはこちらを使う
function inkSpan(atlas, chars, bold) {
  let a = Infinity, b = -Infinity, pen = 0;
  for (const c of chars) {
    const k = glyphInk(atlas, c.cp);
    if (k) { a = Math.min(a, pen + k.l); b = Math.max(b, pen + k.r + (bold ? (atlas.get(c.cp)?.b ?? 0) : 0)); }
    pen += c.adv;
  }
  return a === Infinity ? null : { a, b };
}

/**
 * テキスト要素（render=game）の配置。
 * @returns { lines:[{row, x, width, chars:[{ch, cp, x, adv}]}], missing:Set<string>, pending:boolean, overflow:boolean }
 *   row は画面の行番号、x は画面のGUI px（整数または .5）
 */
export function layoutGameText(el, atlas, screenHeight, screenWidth = null) {
  const bold = !!el.bold;
  const maxRows = Math.max(1, Math.floor(el.h / LINE));
  const rowStart = Math.round(el.y / LINE);
  const missing = new Set();
  let pending = false;
  const adv = (ch) => {
    const v = atlas.advance(ch.codePointAt(0), bold);
    if (v === undefined) { pending = true; return 0; }
    if (v === null) { missing.add(ch); return 0; }
    return v;
  };
  const raw = [];
  for (const para of String(el.text ?? '').split('\n')) {
    let line = [];
    let width = 0;
    let lastSpace = -1;
    for (const ch of Array.from(para)) {
      const a = adv(ch);
      if (line.length && width + a > el.w && !isBreakable(ch)) {
        if (lastSpace > 0) {
          const rest = line.slice(lastSpace + 1);
          raw.push(line.slice(0, lastSpace));
          line = rest;
        } else {
          raw.push(line);
          line = [];
        }
        width = line.reduce((s, c) => s + c.adv, 0);
        lastSpace = -1;
      }
      if (isBreakable(ch)) lastSpace = line.length;
      line.push({ ch, cp: ch.codePointAt(0), adv: a });
      width += a;
    }
    raw.push(line);
  }
  const lines = [];
  const overflow = raw.length > maxRows;
  let clipped = false;
  raw.slice(0, maxRows).forEach((chars, i) => {
    while (chars.length && chars[chars.length - 1].ch === ' ') chars = chars.slice(0, -1);
    const width = chars.reduce((s, c) => s + c.adv, 0);
    // 中央・右寄せはインクの範囲で揃える（位置は整数に丸める）
    const ink = inkSpan(atlas, chars, bold) || { a: 0, b: width };
    let off = 0;
    if (el.align === 'center') off = Math.round((el.w - (ink.b - ink.a)) / 2 - ink.a);
    else if (el.align === 'right') off = Math.floor(el.w - ink.b);
    let x = el.x + off;
    let placed = chars.map((c) => { const r = { ...c, x }; x += c.adv; return r; });
    // 本文の外（左端より左、右端 width+1 より右）の文字は出力できない（クライアントで折り返される）
    if (screenWidth != null) {
      const n = placed.length;
      placed = placed.filter((c) => c.x >= 0 && c.x + c.adv <= screenWidth + 1);
      if (placed.length !== n) clipped = true;
    }
    const row = rowStart + i;
    if (row >= 0 && (screenHeight == null || row < screenHeight / LINE)) lines.push({ row, x: el.x + off, width, chars: placed });
  });
  return { lines, missing, pending, overflow, clipped };
}

// ボタンのラベル（labelRender=game）: 1行、中央寄せ、中心に最も近い行へ置く
export function layoutButtonLabel(el, atlas, screenHeight, screenWidth = null) {
  const row = Math.max(0, Math.round((el.y + el.h / 2 - LINE / 2) / LINE));
  const pseudo = { text: String(el.label ?? '').split('\n')[0], bold: el.bold, x: el.x, y: row * LINE, w: el.w, h: LINE, align: 'center' };
  return layoutGameText(pseudo, atlas, screenHeight, screenWidth);
}

export function shadowColorOf(el) {
  return el.shadowColor || '#3f3f3fff';
}

// 画面のゲーム内フォント要素を列挙（z順）
// include(e): 対象の要素（省略時は状態の既定値 stateDefaults で見える要素）
export function gameTextItems(screen, atlas, include = (e) => elementShown(e, screen.stateDefaults)) {
  const items = [];
  screen.elements.forEach((e, z) => {
    if (!e.visible || !include(e)) return;
    if (e.type === 'text' && e.render === 'game') items.push({ el: e, z, color: e.color, shadow: e.shadow, layout: layoutGameText(e, atlas, screen.height, screen.width) });
    if (e.type === 'button' && e.labelRender === 'game' && e.label) items.push({ el: e, z, color: e.labelColor, shadow: e.shadow, layout: layoutButtonLabel(e, atlas, screen.height, screen.width) });
  });
  return items;
}

/**
 * 1文字を描く。x,y はGUI px（行の上端）。ctx はGUI pxの座標系。
 * Minecraft と同じく、影 → 本体の順。太字は boldOffset ずらして2回描く。
 */
export function drawGlyph(ctx, atlas, cp, x, lineTop, color, { bold = false, shadow = null } = {}) {
  const g = atlas.get(cp);
  if (!g || !g.alpha || !g.w) return;
  const w = g.w * g.s, h = g.h * g.s, y = lineTop + g.t;
  const passes = bold ? [0, g.b] : [0];
  if (shadow) {
    const sc = atlas.canvas(cp, shadow);
    if (sc) for (const dx of passes) ctx.drawImage(sc, x + dx + g.so, y + g.so, w, h);
  }
  const c = atlas.canvas(cp, color);
  if (c) for (const dx of passes) ctx.drawImage(c, x + dx, y, w, h);
}

export function drawGameTextItems(ctx, items, atlas) {
  ctx.save();
  ctx.imageSmoothingEnabled = false;
  for (const it of items) {
    const shadow = it.shadow ? shadowColorOf(it.el) : null;
    for (const line of it.layout.lines) {
      for (const c of line.chars) drawGlyph(ctx, atlas, c.cp, c.x, line.row * LINE, it.color, { bold: it.el.bold, shadow });
    }
  }
  ctx.restore();
}

// 画面で使っている文字（ブラウザーで先読みする）
export function gameTextCodepoints(screen) {
  const set = new Set();
  for (const e of screen.elements) {
    const t = e.type === 'text' && e.render === 'game' ? e.text : e.type === 'button' && e.labelRender === 'game' ? e.label : null;
    if (t) for (const ch of Array.from(t)) set.add(ch.codePointAt(0));
  }
  return set;
}
