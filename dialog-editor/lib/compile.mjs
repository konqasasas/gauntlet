// 画面 → Dialog JSON + フォント定義 + タイル画像（切り出し範囲）へのコンパイル。
// ピクセル列（RGBA）を受け取るだけで、Canvasには依存しない。
//
// 方式（core shaderなし）:
//  1. 画面の静的レイヤー全体を textureScale 倍で1枚に描く（呼び出し側）。
//  2. 240px以下のタイルへ分割し、タイルごとに1文字のbitmap glyphとして専用フォントへ登録。
//     タイルの上端は9px行に揃え、ascent=7でその行に置く。
//  3. 各行は space フォントのadvanceでペンを動かし、タイルを置いた後に負のadvanceで戻る。
//  4. クリック領域は画像と独立に、空白glyph（幅=advance, 高さ=9）の列へ click_event / hover_event を付ける。
//     クライアント（26.3 の ActiveTextCollector）は、カーソルの下にあるクリック付きの文字のうち最後のものを選ぶ。
//     ただし行の中で実際に描かれる文字の範囲（bounds）の外は見ない（空白は範囲に入らない）。そのため各行の
//     左端と右端に、ほぼ透明な1pxの点（EDGE）を置いて範囲を行の幅いっぱいにし、文字にはクリックを付けない。
//  5. 各行の合計advanceを本文幅（width+1）にぴったり揃え、中央寄せと折り返しの影響をなくす。
//     plain_message は FocusableTextWidget で左右4pxずつ余白を取るため、JSON の width は本文幅 + 8。
//  6. 状態の付いた要素（states.mjs）は背景に入れず、状態ごとの重ね絵として各行に「描いて元の位置に戻る」
//     形で出し、insertion に目印を付ける。プラグインは合わない状態の部品を取り除いて送る。
//  7. 画面の移動（action: screen）は custom <namespace>:open {"screen": id} にする。show_dialog だと
//     クライアントが登録済みの画面を開くだけでサーバーに届かず、プレイヤーごとの状態を出せないため。

import { LINE, clickRows } from './model.mjs';
import { gameTextItems, shadowColorOf } from './gametext.mjs';
import { stateMarker, stateKeys } from './states.mjs';

export const COMPILER_VERSION = 1;
export const MAX_TILE_TEX = 240; // フォントアトラス 256x256 に収まる範囲
export const SPACE_BITS = 11;     // ±1..1024
const SPACE_POS = 0xE000;
const SPACE_NEG = 0xE010;
const SPACE_HALF = 0xE00F;     // +0.5（Unifont の太字は 0.5 単位）
const SPACE_NEG_HALF = 0xE01F; // -0.5
const GLYPH_BASE = 0xE000;
const EDGE = 0xE020;          // 行の範囲を広げる点（space フォントの bitmap。透明度1/255で描かれない）
export const EDGE_ADVANCE = 2; // 幅1px の bitmap の advance（幅 + 1）
export const BODY_PADDING = 8; // plain_message の内側余白（左右4pxずつ）。JSON の width = 本文幅 + これ

export function spaceFontJson(ns = 'dcg') {
  const advances = {};
  for (let k = 0; k < SPACE_BITS; k++) {
    advances[String.fromCodePoint(SPACE_POS + k)] = 1 << k;
    advances[String.fromCodePoint(SPACE_NEG + k)] = -(1 << k);
  }
  advances[String.fromCodePoint(SPACE_HALF)] = 0.5;
  advances[String.fromCodePoint(SPACE_NEG_HALF)] = -0.5;
  return { providers: [
    { type: 'space', advances },
    { type: 'bitmap', file: `${ns}:font/edge.png`, height: 9, ascent: 7, chars: [String.fromCodePoint(EDGE)] },
  ] };
}

// EDGE の画像（1×9px、ほぼ透明。文字のシェーダーは不透明度0.1未満を描かない）
export function edgePixels() {
  const data = new Uint8ClampedArray(1 * 9 * 4);
  for (let i = 0; i < 9; i++) data.set([255, 255, 255, 1], i * 4);
  return { data, width: 1, height: 9 };
}

// ゲーム内フォント。minecraft:default と同じ中身を、「Unicodeフォントを強制」の影響を受けない形で参照する
export function textFontJson() {
  return { providers: ['space', 'default', 'unifont'].map((n) => ({ type: 'reference', id: `minecraft:include/${n}` })) };
}

// 任意の整数advanceを空白文字列に変換
export function spaceString(n) {
  if (!Number.isInteger(n * 2)) throw new Error(`advanceは0.5単位: ${n}`);
  let s = '';
  const neg = n < 0;
  let m = Math.abs(n);
  if (!Number.isInteger(m)) { s += String.fromCodePoint(neg ? SPACE_NEG_HALF : SPACE_HALF); m -= 0.5; }
  for (let k = SPACE_BITS - 1; k >= 0; k--) {
    while (m >= 1 << k) { s += String.fromCodePoint((neg ? SPACE_NEG : SPACE_POS) + k); m -= 1 << k; }
  }
  return s;
}

export function tileGeometry(scale) {
  const tw = Math.floor(MAX_TILE_TEX / scale);
  const th = Math.floor(MAX_TILE_TEX / scale / LINE) * LINE;
  return { tw, th };
}

// Minecraft の BitmapProvider と同じ advance 計算
//   advance = (int)(0.5 + actualWidth * (float)(height / cellHeight)) + 1
//   actualWidth は右端から見て alpha != 0 の画素がある最後の列 + 1
export function glyphAdvance(actualWidthTex, heightGui, cellHeightTex) {
  const scale = Math.fround(heightGui / cellHeightTex);
  return Math.trunc(0.5 + Math.fround(Math.fround(actualWidthTex) * scale)) + 1;
}

function actualWidth(rgba, imgW, x0, y0, w, h) {
  for (let x = w - 1; x >= 0; x--) {
    for (let y = 0; y < h; y++) {
      if (rgba[((y0 + y) * imgW + (x0 + x)) * 4 + 3] !== 0) return x + 1;
    }
  }
  return 0;
}

export function fontId(project, screen) { return `${project.namespace}:screen/${screen.id}`; }
export function spaceFontId(project) { return `${project.namespace}:space`; }
export function textFontId(project) { return `${project.namespace}:text`; }
export function dialogId(project, screenId) { return `${project.namespace}:${screenId}`; }

function parsePayload(text) {
  if (text == null || text === '') return undefined;
  try { return withoutNulls(JSON.parse(text)); } catch { return String(text); }
}

// NBT には null がなく、payload に null があるとデータパックの読み込みが失敗する（サーバーが起動しない）。
// null のキー・要素は出力しない（受け取る側では「キーなし」になる）
function withoutNulls(v) {
  if (Array.isArray(v)) return v.filter((x) => x !== null).map(withoutNulls);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).filter(([, x]) => x !== null).map(([k, x]) => [k, withoutNulls(x)]));
  return v;
}
function hasNull(text) {
  if (text == null || text === '') return false;
  let v; try { v = JSON.parse(text); } catch { return false; }
  const walk = (x) => x === null || (typeof x === 'object' && Object.values(x).some(walk));
  return walk(v);
}

// 本文のクリックイベント（テキストコンポーネント形式, 1.21.5+）
export function clickEventFor(project, screen, el) {
  const a = el.action || { type: 'none' };
  switch (a.type) {
    case 'screen': return { action: 'custom', id: `${project.namespace}:open`, payload: { screen: a.value } };
    case 'close': return { action: 'custom', id: `${project.namespace}:close`, payload: { screen: screen.id, element: el.id } };
    case 'custom': {
      const ev = { action: 'custom', id: a.value };
      const pl = parsePayload(a.payload);
      if (pl !== undefined) ev.payload = pl;
      return ev;
    }
    case 'command': return { action: 'run_command', command: a.value.startsWith('/') ? a.value.slice(1) : a.value };
    case 'suggest': return { action: 'suggest_command', command: a.value };
    case 'url': return { action: 'open_url', url: a.value };
    case 'copy': return { action: 'copy_to_clipboard', value: a.value };
    default: return null;
  }
}

function hoverEventFor(el) {
  if (!el.tooltip) return null;
  return { action: 'show_text', value: { text: el.tooltip } };
}

function footerAction(project, b) {
  const a = b.action;
  switch (a.type) {
    case 'screen': return { type: 'minecraft:custom', id: `${project.namespace}:open`, payload: { screen: a.value } };
    case 'custom': {
      const r = { type: 'minecraft:custom', id: a.value };
      const pl = parsePayload(a.payload);
      if (pl !== undefined) r.payload = pl;
      return r;
    }
    case 'submit': return { type: 'minecraft:dynamic/custom', id: a.value };
    case 'command': return { type: 'minecraft:run_command', command: a.value.startsWith('/') ? a.value.slice(1) : a.value };
    case 'url': return { type: 'minecraft:open_url', url: a.value };
    case 'copy': return { type: 'minecraft:copy_to_clipboard', value: a.value };
    default: return null; // close: actionなしのボタンは閉じるだけ
  }
}

function footerButton(project, b) {
  const r = { label: { text: b.label }, width: b.width };
  if (b.tooltip) r.tooltip = { text: b.tooltip };
  const act = footerAction(project, b);
  if (act) r.action = act;
  return r;
}

function inputJson(inp) {
  const label = { text: inp.label ?? inp.key };
  switch (inp.type) {
    case 'text': {
      const r = { type: 'minecraft:text', key: inp.key, label, width: inp.width, initial: inp.initial ?? '', max_length: inp.maxLength ?? 32 };
      if (inp.multiline) r.multiline = { max_lines: inp.maxLines ?? 4 };
      return r;
    }
    case 'boolean':
      return { type: 'minecraft:boolean', key: inp.key, label, initial: !!inp.initial, on_true: 'true', on_false: 'false' };
    case 'single_option':
      return {
        type: 'minecraft:single_option', key: inp.key, label, width: inp.width,
        options: inp.options.map((o) => ({ id: o.id, display: { text: o.display ?? o.id }, ...(o.id === inp.initial ? { initial: true } : {}) })),
      };
    case 'number_range':
      return { type: 'minecraft:number_range', key: inp.key, label, width: inp.width, start: inp.start, end: inp.end, step: inp.step, initial: inp.initial };
    default: throw new Error(`未知の入力: ${inp.type}`);
  }
}

// 行ごとの最前面ホットスポット区間を求める
function rowSegments(screen, row, bodyWidth) {
  const hs = screen.elements
    .map((e, z) => ({ e, z }))
    .filter(({ e }) => e.visible && (e.type === 'button' || e.type === 'hotspot'))
    .filter(({ e }) => clickRows(e.y, e.h, screen.height).includes(row));
  if (!hs.length) return [];
  const xs = new Set([0, bodyWidth]);
  for (const { e } of hs) { xs.add(clamp(e.x, 0, bodyWidth)); xs.add(clamp(e.x + e.w, 0, bodyWidth)); }
  const bounds = [...xs].sort((a, b) => a - b);
  const segs = [];
  for (let i = 0; i + 1 < bounds.length; i++) {
    const a = bounds[i], b = bounds[i + 1];
    if (b <= a) continue;
    let top = null;
    for (const h of hs) if (h.e.x <= a && h.e.x + h.e.w >= b && (!top || h.z > top.z)) top = h;
    if (!top) continue;
    const last = segs[segs.length - 1];
    if (last && last.el === top.e && last.x1 === a) last.x1 = b;
    else segs.push({ x0: a, x1: b, el: top.e });
  }
  return segs;
}

function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }

/**
 * @param project プロジェクト
 * @param screen 画面（状態の付いた要素も含む全体）
 * @param raster { data: Uint8ClampedArray (RGBA), width, height }  — width = screen.width*scale。状態の付いた要素を除いて描いたもの
 * @param overlays [{ state, raster }] 状態ごとに、その状態の要素だけを透明の上に描いたもの
 * @returns { dialog, font, tiles:[{file, src, sx, sy, sw, sh}], clicks:[...], stats }
 *   dialog は全状態入り（プラグイン用の雛形）。表示する状態だけ残すには states.mjs の selectStates
 */
export function compileScreen(project, screen, raster, atlas = null, overlays = []) {
  const s = project.textureScale;
  const W = screen.width, H = screen.height;
  for (const r of [raster, ...overlays.map((o) => o.raster)]) {
    if (r.width !== W * s || r.height !== H * s) throw new Error(`raster のサイズが不正 ${r.width}x${r.height}`);
  }
  const bodyWidth = W + 1; // 右端タイルの advance(+1) が溢れないように1px余分に取る
  const { tw, th } = tileGeometry(s);
  const ns = project.namespace;
  const font = { providers: [] };
  const tiles = [];
  let cp = GLYPH_BASE;
  // region（GUI px, 上下は9の倍数）をタイルに分ける。上下の区切りは背景と同じ th ごと
  // （重ね絵が背景の次のタイル行にかかると、後から描かれる背景に隠れるため）
  const addTiles = (src, prefix, region, group) => {
    for (let band = Math.floor(region.y0 / th) * th; band < region.y1; band += th) {
      const y0 = Math.max(band, region.y0), y1 = Math.min(band + th, region.y1, H);
      if (y1 <= y0) continue;
      const h = y1 - y0;
      for (let tx = region.x0; tx < region.x1; tx += tw) {
        const w = Math.min(tw, region.x1 - tx);
        const aw = actualWidth(src.data, src.width, tx * s, y0 * s, w * s, h * s);
        if (aw === 0) continue; // 完全に透明なタイルは出力しない
        const file = `screen/${screen.id}/${prefix}r${y0 / LINE}c${tx}.png`;
        const ch = String.fromCodePoint(cp++);
        if (cp > 0xF8FF) throw new Error('タイル数が私用領域の上限を超えました');
        font.providers.push({ type: 'bitmap', file: `${ns}:font/${file}`, height: h, ascent: 7, chars: [ch] });
        tiles.push({ file, src, sx: tx * s, sy: y0 * s, sw: w * s, sh: h * s, x: tx, y: y0, w, h, ch, advance: glyphAdvance(aw, h, h * s), group });
      }
    }
  };
  addTiles(raster, '', { x0: 0, x1: W, y0: 0, y1: H }, -1);
  overlays.forEach((o, i) => {
    const b = opaqueBounds(o.raster);
    if (!b) return;
    const y0 = Math.floor(Math.floor(b.y0 / s) / LINE) * LINE;
    const y1 = Math.min(H, Math.ceil(Math.ceil(b.y1 / s) / LINE) * LINE);
    addTiles(o.raster, `s${i}_`, { x0: Math.floor(b.x0 / s), x1: Math.min(W, Math.ceil(b.x1 / s)), y0, y1 }, i);
  });

  const errors = [], warnings = [];
  const nullNote = 'payload の null はNBTにできないため、そのキーを出力しません';
  for (const e of screen.elements) if (e.visible && hasNull(e.action?.payload)) warnings.push(`${e.id}: ${nullNote}`);
  screen.footer.buttons.forEach((b, i) => { if (hasNull(b.action?.payload)) warnings.push(`フッターのボタン${i + 1}: ${nullNote}`); });
  if (!atlas && screen.elements.some((e) => e.visible && ((e.type === 'text' && e.render === 'game') || (e.type === 'button' && e.labelRender === 'game' && e.label)))) {
    errors.push('ゲーム内フォントの資源が読み込めていません（node tools/fetch-vanilla.mjs）');
  }
  // 行ごとの文字。group -1 が背景（状態なし）、0.. が overlays の順
  const rowsText = new Map();
  const collect = (items, group) => {
    for (const it of items) {
      if (it.layout.missing.size) errors.push(`${it.el.id}: ゲーム内フォントにない文字 ${[...it.layout.missing].join(' ')}`);
      if (it.layout.clipped) warnings.push(`${it.el.id}: 画面の左右からはみ出した文字は出力しません`);
      if (it.layout.overflow) warnings.push(`${it.el.id}: 文字が要素の高さ（${Math.floor(it.el.h / LINE)}行）に収まりません。はみ出した行は出力しません`);
      if ((it.color || '').length === 9 && !/ff$/i.test(it.color)) warnings.push(`${it.el.id}: ゲーム内フォントの文字色は不透明度を使えません（不透明で出力）`);
      for (const line of it.layout.lines) {
        const k = `${group}/${line.row}`;
        if (!rowsText.has(k)) rowsText.set(k, []);
        rowsText.get(k).push({ it, line });
      }
    }
  };
  const baseItems = atlas ? gameTextItems(screen, atlas, (e) => !e.state) : [];
  collect(baseItems, -1);
  let textCount = baseItems.length;
  overlays.forEach((o, i) => {
    const items = atlas ? gameTextItems(screen, atlas, (e) => e.state === o.state) : [];
    collect(items, i);
    textCount += items.length;
  });
  const textOf = (group, r) => rowsText.get(`${group}/${r}`) || [];
  const tilesOf = (group, r) => tiles.filter((t) => t.group === group && t.y === r * LINE).sort((a, b) => a.x - b.x);

  const textFont = textFontId(project);
  const spaceFont = spaceFontId(project);
  const screenFont = fontId(project, screen);
  const extra = [];
  const push = (text, style) => {
    if (!text) return;
    const last = extra[extra.length - 1];
    if (last && sameStyle(last, style)) { last.text += text; return; }
    extra.push({ text, ...style });
  };
  const spaceStyle = { font: spaceFont };
  const glyphStyle = { font: screenFont, color: 'white', shadow_color: 0 };
  const textStyle = (it) => {
    const st = { font: textFont, color: (it.color || '#ffffff').slice(0, 7).toLowerCase() };
    if (it.el.bold) st.bold = true;
    st.shadow_color = it.shadow ? argbInt(shadowColorOf(it.el)) : 0;
    return st;
  };
  const clicks = [];

  const edgeStyle = { font: spaceFont, color: 'white', shadow_color: 0 };
  for (let r = 0; r < H / LINE; r++) {
    let pen = 0;
    const moveTo = (x, tag = null) => { push(spaceString(x - pen), tag ? { ...spaceStyle, ...tag } : spaceStyle); pen = x; };
    // 行の範囲（クリックを受け付ける範囲）を左端から始める
    push(String.fromCodePoint(EDGE), edgeStyle);
    pen += EDGE_ADVANCE;
    // group のタイルと文字を描く（文字にはクリックを付けない。付けると区間の外まで判定が広がる）
    const draw = (group, tag = null) => {
      for (const t of tilesOf(group, r)) {
        moveTo(t.x, tag);
        push(t.ch, tag ? { ...glyphStyle, ...tag } : glyphStyle);
        pen += t.advance;
      }
      for (const { it, line } of textOf(group, r)) {
        const st = textStyle(it);
        for (const c of line.chars) {
          moveTo(c.x, tag);
          push(c.ch, tag ? { ...st, ...tag } : st);
          pen += c.adv;
        }
      }
    };
    // クリック区間（左から順、重ならない）
    const segs = rowSegments(screen, r, W);
    for (const seg of segs) {
      moveTo(seg.x0);
      const style = { font: spaceFont };
      const ce = clickEventFor(project, screen, seg.el);
      const he = hoverEventFor(seg.el);
      if (ce) style.click_event = ce;
      if (he) style.hover_event = he;
      push(spaceString(seg.x1 - seg.x0), style);
      pen = seg.x1;
      clicks.push({ row: r, x0: seg.x0, x1: seg.x1, element: seg.el.id });
    }
    // 背景のタイル → 状態の重ね絵（描いて元の位置に戻る） → 背景の文字（最前面）
    for (const t of tilesOf(-1, r)) {
      moveTo(t.x);
      push(t.ch, glyphStyle);
      pen += t.advance;
    }
    overlays.forEach((o, i) => {
      if (!tilesOf(i, r).length && !textOf(i, r).length) return;
      const tag = { insertion: stateMarker(ns, o.state) };
      const start = pen;
      draw(i, tag);
      moveTo(start, tag);
    });
    for (const { it, line } of textOf(-1, r)) {
      const st = textStyle(it);
      for (const c of line.chars) {
        moveTo(c.x);
        push(c.ch, st);
        pen += c.adv;
      }
    }
    // 右端の点（右端 = 本文幅 - 1 の1px）。行の合計は本文幅ちょうど
    moveTo(bodyWidth - EDGE_ADVANCE);
    push(String.fromCodePoint(EDGE), edgeStyle);
    pen += EDGE_ADVANCE;
    if (r < H / LINE - 1) push('\n', {});
  }

  const f = screen.footer;
  const dialog = {
    type: f.type === 'notice' ? 'minecraft:notice' : f.type === 'none' ? 'minecraft:dialog_list' : 'minecraft:multi_action',
    title: { text: screen.showTitle ? screen.title : '' },
    external_title: { text: screen.title },
    can_close_with_escape: screen.canCloseWithEscape,
    pause: screen.pause,
    after_action: screen.afterAction,
    body: [{ type: 'minecraft:plain_message', width: bodyWidth + BODY_PADDING, contents: { text: '', extra } }],
  };
  if (screen.inputs.length) dialog.inputs = screen.inputs.map(inputJson);
  if (f.type === 'notice') dialog.action = footerButton(project, f.buttons[0]);
  else if (f.type === 'none') {
    // 一覧のボタンも閉じるボタン（exit_action）もない dialog_list。クライアントはフッターを5pxにする
    dialog.dialogs = [];
  } else {
    dialog.actions = f.buttons.map((b) => footerButton(project, b));
    dialog.columns = f.columns;
  }

  return {
    dialog, font, tiles, clicks, errors, warnings,
    stats: { tiles: tiles.length, overlays: overlays.length, components: extra.length, lines: H / LINE, bodyWidth, texts: textCount, jsonBytes: JSON.stringify(dialog).length },
  };
}

// 不透明な画素の範囲（テクスチャ px、x1/y1 は含まない）。全部透明なら null
function opaqueBounds(r) {
  let x0 = Infinity, y0 = Infinity, x1 = -1, y1 = -1;
  for (let y = 0; y < r.height; y++) {
    for (let x = 0; x < r.width; x++) {
      if (!r.data[(y * r.width + x) * 4 + 3]) continue;
      if (x < x0) x0 = x;
      if (x > x1) x1 = x;
      if (y < y0) y0 = y;
      if (y > y1) y1 = y;
    }
  }
  return x1 < 0 ? null : { x0, y0, x1: x1 + 1, y1: y1 + 1 };
}

function argbInt(hex) {
  const h = hex.slice(1);
  const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) : 255;
  return ((a << 24) | parseInt(h.slice(0, 6), 16)) | 0;
}

function sameStyle(a, b) {
  const ka = Object.keys(a).filter((k) => k !== 'text');
  const kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  return kb.every((k) => JSON.stringify(a[k]) === JSON.stringify(b[k]));
}

// プラグイン向けの説明データ（どのクリックIDが何か）
export function manifestFor(project, compiled) {
  return {
    format: 'dialog-custom-gui/manifest',
    compilerVersion: COMPILER_VERSION,
    namespace: project.namespace,
    closeActionId: `${project.namespace}:close`,
    openActionId: `${project.namespace}:open`,
    templates: 'templates/<画面>.json（全状態入り。insertion の目印で状態を選ぶ）',
    screens: project.screens.map((s) => ({
      id: s.id,
      dialog: dialogId(project, s.id),
      title: s.title,
      width: s.width,
      height: s.height,
      actions: s.elements.filter((e) => (e.type === 'button' || e.type === 'hotspot') && e.action.type !== 'none')
        .map((e) => ({ element: e.id, ...e.action })),
      footer: s.footer.buttons.map((b, i) => ({ index: i, label: b.label, ...b.action })),
      inputs: s.inputs.map((i) => i.key),
      // 状態（名前ごとに条件に出てくる値）と既定値。雛形 templates/<画面>.json の insertion「<namespace>:state:条件」と対応
      states: { keys: stateKeys(s), defaults: s.stateDefaults || {} },
      stats: compiled[s.id]?.stats,
    })),
  };
}
