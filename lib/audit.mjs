// 画面の見た目の点検。ゲーム内フォントの文字の「実際に描かれる画素（インク）」の位置を求め、
// 囲んでいる部品（矩形・ボタン）の中心とのずれ、はみ出し、文字どうしの重なりを調べる。
// 送り幅（advance）ではなくインクで測るので、右端の1pxの隙間や、行の上寄りに描かれる分も反映される。
import { LINE, resolveScreen } from './model.mjs';
import { gameTextItems, glyphInk } from './gametext.mjs';

export { glyphInk };

// 1行の文字のインクの外接矩形（画面のGUI px）
export function lineInk(atlas, line, bold) {
  let l = Infinity, r = -Infinity, t = Infinity, b = -Infinity;
  for (const c of line.chars) {
    const k = glyphInk(atlas, c.cp);
    if (!k) continue;
    const g = atlas.get(c.cp);
    l = Math.min(l, c.x + k.l); r = Math.max(r, c.x + k.r + (bold ? g.b : 0));
    t = Math.min(t, line.row * LINE + k.t); b = Math.max(b, line.row * LINE + k.b);
  }
  return l === Infinity ? null : { l, r, t, b };
}

const contains = (o, i) => o.x <= i.l && o.y <= i.t && o.x + o.w >= i.r && o.y + o.h >= i.b;
const overlap = (a, b) => a.l < b.r && b.l < a.r && a.t < b.b && b.t < a.b;
const opaque = (c) => typeof c === 'string' && !/00$/i.test(c.length === 9 ? c.slice(7) : 'ff');

/**
 * @returns [{ screen, element, kind, message, dx?, dy? }]
 *   kind: 'center-x' | 'center-y' | 'clip' | 'outside' | 'overlap'
 */
export function auditScreen(project, rawScreen, atlas, opts = {}) {
  const tolX = opts.tolX ?? 0.5, tolY = opts.tolY ?? 0.5;
  const screen = resolveScreen(project, rawScreen);
  const issues = [];
  const add = (element, kind, message, extra = {}) => issues.push({ screen: screen.id, element, kind, message, ...extra });
  // 文字を囲む候補: 見える塗りか枠のある矩形・ボタン
  const boxes = screen.elements.filter((e) => e.visible && (e.type === 'rect' || e.type === 'button') && e.w >= 6 && e.h >= 6 && (opaque(e.fill) || (e.borderWidth > 0 && opaque(e.border))));
  const inks = [];
  for (const it of gameTextItems(screen, atlas)) {
    const el = it.el;
    for (const line of it.layout.lines) {
      const ink = lineInk(atlas, line, el.bold);
      if (!ink) continue;
      inks.push({ id: el.id, ink, z: it.z });
      if (ink.l < 0 || ink.t < 0 || ink.r > screen.width || ink.b > screen.height) add(el.id, 'outside', `文字が本文の外にはみ出しています（${fmt(ink)}）`);
      // ボタンのラベルはボタン自身、テキストは自分より背面で一番小さい「部品」（高さ30px以下）を枠とみなす
      let box = null;
      if (el.type === 'button') box = el;
      else {
        const cands = boxes.filter((b) => screen.elements.indexOf(b) < it.z && b.h <= 30 && b.x <= ink.l + 0.01 && b.x + b.w >= ink.r - 0.01 && b.y < ink.b && b.y + b.h > ink.t);
        box = cands.sort((a, b) => a.w * a.h - b.w * b.h)[0] || null;
      }
      if (!box) continue;
      const bw = box.borderWidth || 0;
      const inner = { x: box.x + bw, y: box.y + bw, w: box.w - bw * 2, h: box.h - bw * 2 };
      if (!contains(inner, ink)) add(el.id, 'clip', `文字が枠（${box.id}）からはみ出しています（文字 ${fmt(ink)} / 枠の内側 ${inner.x},${inner.y} ${inner.w}×${inner.h}）`);
      const dy = (ink.t + ink.b) / 2 - (inner.y + inner.h / 2);
      // 記号1文字は0.5px、文字列は下に伸びる字や括弧があるので1pxまで許容
      const tol = line.chars.filter((c) => c.ch !== ' ').length <= 1 ? tolY : Math.max(tolY, 1);
      if (box.h <= 15 && Math.abs(dy) > tol) add(el.id, 'center-y', `文字が枠（${box.id}）の上下中央から ${dy > 0 ? '下' : '上'}へ ${Math.abs(dy).toFixed(1)}px ずれています`, { dy, box: box.id });
      // 同じ部品にアイコン（id が「部品名_i_」で始まる矩形）があれば、文字とアイコンのまとまりで測る
      const base = box.id.replace(/_bg$/, '');
      const icons = screen.elements.filter((e) => e.visible && e.type === 'rect' && e.id.startsWith(`${base}_i_`));
      if (icons.length) {
        const gl = Math.min(ink.l, ...icons.map((e) => e.x)), gr = Math.max(ink.r, ...icons.map((e) => e.x + e.w));
        const dx = (gl + gr) / 2 - (inner.x + inner.w / 2);
        if (Math.abs(dx) > tolX) add(el.id, 'center-x', `文字とアイコンが枠（${box.id}）の左右中央から ${dx > 0 ? '右' : '左'}へ ${Math.abs(dx).toFixed(1)}px ずれています`, { dx, box: box.id });
      } else if ((el.type === 'button' || el.align === 'center')) {
        const dx = (ink.l + ink.r) / 2 - (inner.x + inner.w / 2);
        if (Math.abs(dx) > tolX) add(el.id, 'center-x', `文字が枠（${box.id}）の左右中央から ${dx > 0 ? '右' : '左'}へ ${Math.abs(dx).toFixed(1)}px ずれています`, { dx, box: box.id });
      }
    }
  }
  // 文字どうしの重なり
  for (let i = 0; i < inks.length; i++) for (let j = i + 1; j < inks.length; j++) {
    if (inks[i].id !== inks[j].id && overlap(inks[i].ink, inks[j].ink)) add(inks[i].id, 'overlap', `文字が ${inks[j].id} の文字と重なっています`);
  }
  return issues;
}

function fmt(k) { return `x ${k.l}〜${k.r}, y ${k.t}〜${k.b}`; }
