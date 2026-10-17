// コンパイル結果を、Minecraftクライアントの規則で解釈し直して検証する。
//  - StringSplitter: 幅の累積が maxWidth を超えた時点で改行、'\n' で強制改行（maxWidth = width - 左右の余白8）
//  - クリック・ホバー（26.3 の ActiveTextCollector）: 行の中で実際に描かれる文字（bitmap・ゲーム内フォント）の
//    範囲の外は当たらない（空白は範囲に入らない）。範囲の中では、その位置にあるクリック付きの文字
//    （幅 = advance）のうち最後のもの。負のadvanceの文字は当たらない
//  - bitmap glyph: advance = BitmapProvider の計算、描画は行上端 + (7 - ascent)
// 画像の再構成とクリック領域の再構成を返す。実機描画の代わりではない。

import { LINE } from './model.mjs';
import { BODY_PADDING } from './compile.mjs';

function buildFontTable(fonts) {
  // fonts: { [fontId]: {providers}, ...} → Map<fontId, Map<char, {advance, bitmap?}>>
  const table = new Map();
  for (const [id, def] of Object.entries(fonts)) {
    // { lookup(ch) → {advance, boldOffset} | null } 形式のフォント（ゲーム内フォント）
    if (typeof def.lookup === 'function') { table.set(id, { get: (ch) => def.lookup(ch), text: true }); continue; }
    const m = new Map();
    for (const p of def.providers) {
      if (p.type === 'space') for (const [ch, adv] of Object.entries(p.advances)) m.set(ch, { advance: adv });
      if (p.type === 'bitmap') for (const ch of p.chars) m.set(ch, { advance: p.advance, bitmap: p });
    }
    table.set(id, m);
  }
  return table;
}

/**
 * @param dialog コンパイル済み Dialog JSON
 * @param fonts { fontId: fontJson } — bitmap provider には advance を補ったもの（compileの tiles から）
 * @returns { lines:[{width, maxPen, glyphs:[{x,y,provider}], clicks:[{x0,x1,click,hover}]}], errors:[] }
 */
export function simulate(dialog, fonts) {
  const errors = [];
  const table = buildFontTable(fonts);
  const body = dialog.body?.[0];
  if (!body || body.type !== 'minecraft:plain_message') return { lines: [], errors: ['本文がplain_messageではありません'] };
  if (body.width < 1 || body.width > 1024) errors.push(`plain_message.width ${body.width} は1〜1024の範囲外`);
  const maxWidth = body.width - BODY_PADDING;
  const lines = [];
  let cur = newLine();
  let started = false; // hadNonZeroWidthChar
  const runs = body.contents.extra || [];
  for (const run of runs) {
    for (const ch of Array.from(run.text)) {
      if (ch === '\n') { lines.push(cur); cur = newLine(); started = false; continue; }
      const font = run.font || 'minecraft:default';
      const g = table.get(font)?.get(ch);
      if (!g) { errors.push(`行${lines.length}: フォント ${font} に U+${ch.codePointAt(0).toString(16)} がありません`); continue; }
      const x = cur.width;
      const advance = g.advance + (run.bold ? (g.boldOffset ?? 1) : 0);
      cur.width += advance;
      if (started && cur.width > maxWidth) errors.push(`行${lines.length}: 幅 ${cur.width} が ${maxWidth} を超え、クライアントで折り返されます`);
      if (advance !== 0) started = true;
      cur.maxPen = Math.max(cur.maxPen, cur.width);
      if (g.bitmap) cur.glyphs.push({ x, y: lines.length * LINE + 7 - g.bitmap.ascent, provider: g.bitmap });
      if (table.get(font)?.text && ch !== ' ') cur.texts.push({ x, ch, cp: ch.codePointAt(0), color: run.color, bold: !!run.bold, shadow: run.shadow_color });
      // 描かれる文字（絵の幅 = advance - 1）は行の範囲を広げる
      if ((g.bitmap || (table.get(font)?.text && ch !== ' ')) && advance > 1) {
        cur.visL = Math.min(cur.visL, x);
        cur.visR = Math.max(cur.visR, x + advance - 1);
      }
      if ((run.click_event || run.hover_event) && advance > 0) cur.runs.push({ x0: x, x1: x + advance, click: run.click_event || null, hover: run.hover_event || null });
    }
  }
  lines.push(cur);
  lines.forEach((l, i) => {
    if (l.width !== maxWidth) errors.push(`行${i}: 合計幅 ${l.width} が本文幅 ${maxWidth} と一致しません（中央寄せでずれます）`);
    l.clicks = clickRegions(l.runs, l.maxPen, l.visL, l.visR);
    delete l.runs;
  });
  return { lines, errors, maxWidth };
}

function newLine() { return { width: 0, maxPen: 0, glyphs: [], clicks: [], texts: [], runs: [], visL: Infinity, visR: -Infinity }; }

// 0.5px 刻み（advance の最小単位）で、位置ごとに当たる文字を求めて区間にまとめる
function clickRegions(runs, maxPen, visL, visR) {
  const out = [];
  for (let x = 0; x < maxPen; x += 0.5) {
    if (x < visL || x >= visR) continue;
    let hit = null;
    for (const r of runs) if (r.x0 <= x && x < r.x1) hit = r;
    if (!hit) continue;
    const key = clickKey(hit);
    const last = out[out.length - 1];
    if (last && last.x1 === x && last.key === key) last.x1 = x + 0.5;
    else out.push({ x0: x, x1: x + 0.5, click: hit.click, hover: hit.hover, key });
  }
  for (const c of out) delete c.key;
  return out;
}

function clickKey(r) { return JSON.stringify([r.click ?? null, r.hover ?? null]); }

// 再構成した画像と元のラスターを比べる（RGBA配列）。tilesPixels: Map<file, {data,width,height}>
export function reconstruct(sim, size, scale, tilePixels) {
  const W = size.width * scale, H = size.height * scale;
  const out = new Uint8ClampedArray(W * H * 4);
  for (const line of sim.lines) {
    for (const g of line.glyphs) {
      const img = tilePixels.get(g.provider.file);
      if (!img) continue;
      const k = (g.provider.height * scale) / img.height; // 通常1
      for (let y = 0; y < img.height; y++) {
        const dy = Math.round(g.y * scale + y * k);
        if (dy < 0 || dy >= H) continue;
        for (let x = 0; x < img.width; x++) {
          const dx = Math.round(g.x * scale + x * k);
          if (dx < 0 || dx >= W) continue;
          const si = (y * img.width + x) * 4, di = (dy * W + dx) * 4;
          const a = img.data[si + 3];
          if (!a) continue;
          // 後から描いたタイルを上に重ねる（状態の重ね絵は背景の上に重なる）
          if (a === 255 || !out[di + 3]) { out[di] = img.data[si]; out[di + 1] = img.data[si + 1]; out[di + 2] = img.data[si + 2]; out[di + 3] = a; continue; }
          const da = out[di + 3] / 255, sa = a / 255, oa = sa + da * (1 - sa);
          for (let c = 0; c < 3; c++) out[di + c] = Math.round((img.data[si + c] * sa + out[di + c] * da * (1 - sa)) / oa);
          out[di + 3] = Math.round(oa * 255);
        }
      }
    }
  }
  return { data: out, width: W, height: H };
}

export function diffPixels(a, b) {
  if (a.width !== b.width || a.height !== b.height) return { different: Infinity };
  let different = 0;
  for (let i = 0; i < a.data.length; i += 4) {
    if (a.data[i + 3] === 0 && b.data[i + 3] === 0) continue;
    if (a.data[i] !== b.data[i] || a.data[i + 1] !== b.data[i + 1] || a.data[i + 2] !== b.data[i + 2] || a.data[i + 3] !== b.data[i + 3]) different++;
  }
  return { different };
}
