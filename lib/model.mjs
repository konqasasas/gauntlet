// プロジェクトJSONのスキーマ・既定値・検証・編集操作。
// ブラウザー（editor）とNode（server/CLI/compile）の両方から読み込む。依存は states.mjs だけ（同じく依存なし）。
import { parseState } from './states.mjs';

export const SCHEMA_VERSION = 3;
export const LINE = 9;              // Minecraftの1行の高さ（GUI px）
export const MAX_BODY_WIDTH = 1015; // plain_messageの上限1024から、右端の+1 advanceと左右の余白8を引いた値
export const MIN_BODY_WIDTH = 16;
export const MAX_BODY_HEIGHT = 9 * 300;

export const ELEMENT_TYPES = ['rect', 'image', 'text', 'button', 'hotspot'];
export const ACTION_TYPES = ['none', 'screen', 'close', 'custom', 'command', 'suggest', 'url', 'copy'];
// none: フッターのボタンなし（dialog_list を exit_action なし・中身なしで出力。フッター領域は5pxになる）
export const FOOTER_TYPES = ['notice', 'multi_action', 'none'];
export const FOOTER_ACTION_TYPES = ['close', 'screen', 'custom', 'submit', 'command', 'url', 'copy'];
export const INPUT_TYPES = ['text', 'boolean', 'single_option', 'number_range'];
export const AFTER_ACTIONS = ['close', 'none', 'wait_for_response'];
export const FITS = ['stretch', 'contain', 'cover', 'tile'];
export const ALIGNS = ['left', 'center', 'right'];
export const TEXT_RENDERS = ['game', 'baked']; // game: ゲーム内フォント（8px固定・9px行） / baked: 任意フォントを画像に焼き込み
export const SKIN_SLOTS = ['button', 'button_highlighted', 'button_disabled', 'scroller', 'scroller_background'];

const ID_RE = /^[a-z0-9_]{1,48}$/;
const NS_RE = /^[a-z0-9_.-]{1,32}$/;
const COLOR_RE = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
export const TOKEN_KEY_RE = /^[a-z0-9_.-]{1,48}$/;
// 色を持つプロパティ。値は '#RRGGBB(AA)' か、テーマの色の名前 '@name'
export const COLOR_PROPS = ['background', 'fill', 'border', 'color', 'shadowColor', 'labelColor'];

export function isTokenRef(v) { return typeof v === 'string' && v.startsWith('@'); }

// 色の値として正しいか（テーマの色の名前なら、その名前があるか）
export function isColorValue(p, v) {
  if (COLOR_RE.test(v || '')) return true;
  return isTokenRef(v) && TOKEN_KEY_RE.test(v.slice(1)) && !!p?.theme?.tokens?.[v.slice(1)];
}

// テーマの色の名前を実際の色に置き換えた画面のコピー（描画・書き出し用）
export function resolveScreen(p, screen) {
  const tokens = p?.theme?.tokens || {};
  const r = (v) => (isTokenRef(v) ? tokens[v.slice(1)] ?? '#ff00ffff' : v);
  const s = structuredClone(screen);
  s.background = r(s.background);
  for (const e of s.elements) for (const k of COLOR_PROPS) if (k in e) e[k] = r(e[k]);
  return s;
}

// テーマの色を使っている場所
export function themeUsers(p, key) {
  const ref = `@${key}`;
  const out = [];
  for (const s of p.screens) {
    if (s.background === ref) out.push(`${s.id}.background`);
    for (const e of s.elements) for (const k of COLOR_PROPS) if (e[k] === ref) out.push(`${s.id}/${e.id}.${k}`);
  }
  return out;
}

// ---- 既定値 -------------------------------------------------------------

export function defaultAction() { return { type: 'none', value: '', payload: '' }; }

export function defaultElement(type, over = {}) {
  const base = { id: '', type, name: '', x: 0, y: 0, w: 64, h: 18, visible: true, locked: false };
  const byType = {
    rect: { fill: '#3a3f4bff', border: '#00000000', borderWidth: 0, state: '' },
    image: { asset: null, fit: 'stretch', smooth: false, opacity: 1, state: '' },
    text: { text: 'テキスト', render: 'game', fontFamily: 'Yu Gothic UI', fontSize: 9, pixel: false, wordSpacing: 0, bold: false, color: '#ffffffff', align: 'left', valign: 'top', lineHeight: 1.2, shadow: false, shadowColor: '#3f3f3fff', state: '', w: 96, h: 18 },
    button: {
      fill: '#5a6270ff', border: '#1e2128ff', borderWidth: 1, image: null, fit: 'stretch',
      label: 'ボタン', labelRender: 'game', fontFamily: 'Yu Gothic UI', fontSize: 9, pixel: false, wordSpacing: 0, bold: false, labelColor: '#ffffffff', shadow: true,
      action: defaultAction(), tooltip: '', w: 72, h: 18,
    },
    hotspot: { action: defaultAction(), tooltip: '', w: 36, h: 18 },
  };
  return structuredClone({ ...base, ...byType[type], ...over });
}

export function defaultFooter() {
  return { type: 'notice', columns: 2, buttons: [defaultFooterButton()], exitAction: false };
}

export function defaultFooterButton(over = {}) {
  return { label: '閉じる', width: 150, tooltip: '', action: { type: 'close', value: '', payload: '' }, ...over };
}

export function defaultScreen(id, over = {}) {
  return structuredClone({
    id,
    title: id,
    showTitle: true,
    width: 288,
    height: 162,
    background: '#00000000',
    canCloseWithEscape: true,
    pause: false,
    afterAction: 'close',
    footer: defaultFooter(),
    inputs: [],
    elements: [],
    stateDefaults: {}, // 状態の既定値（states.mjs）
    ...over,
  });
}

export function defaultProject() {
  return {
    schemaVersion: SCHEMA_VERSION,
    namespace: 'dcg',
    name: 'Dialog Custom GUI',
    description: 'Dialog Custom GUI resource pack',
    textureScale: 2,
    assets: {},
    skin: Object.fromEntries(SKIN_SLOTS.map((s) => [s, null])),
    theme: { name: 'default', tokens: {} },
    screens: [],
  };
}

// ---- 検証 ---------------------------------------------------------------

export function validateProject(p) {
  const errors = [];
  const err = (where, msg) => errors.push(`${where}: ${msg}`);
  if (!p || typeof p !== 'object') return ['project: オブジェクトではありません'];
  if (p.schemaVersion !== SCHEMA_VERSION) err('schemaVersion', `${SCHEMA_VERSION}である必要があります`);
  if (!NS_RE.test(p.namespace || '')) err('namespace', '英小文字・数字・_ . - の1〜32文字');
  if (![1, 2, 3, 4].includes(p.textureScale)) err('textureScale', '1〜4の整数');
  if (typeof p.assets !== 'object' || !p.assets) err('assets', 'オブジェクトが必要です');
  for (const slot of SKIN_SLOTS) {
    const a = p.skin?.[slot];
    if (a != null && !p.assets?.[a]) err(`skin.${slot}`, `素材 ${a} が存在しません`);
  }
  if (!p.theme || typeof p.theme.tokens !== 'object' || Array.isArray(p.theme.tokens)) err('theme', 'theme.tokens はオブジェクト');
  else for (const [k, v] of Object.entries(p.theme.tokens)) {
    if (!TOKEN_KEY_RE.test(k)) err(`theme.${k}`, '色の名前は英小文字・数字・_ . - の1〜48文字');
    if (!COLOR_RE.test(v || '')) err(`theme.${k}`, '色は #RRGGBB または #RRGGBBAA（テーマの中で別の名前は参照できません）');
  }
  if (!Array.isArray(p.screens)) { err('screens', '配列が必要です'); return errors; }
  const screenIds = new Set();
  for (const s of p.screens) {
    const w = `screen ${s?.id}`;
    if (!ID_RE.test(s?.id || '')) { err(w, 'idは英小文字・数字・_ の1〜48文字'); continue; }
    if (screenIds.has(s.id)) err(w, 'idが重複しています');
    screenIds.add(s.id);
    if (!int(s.width) || s.width < MIN_BODY_WIDTH || s.width > MAX_BODY_WIDTH) err(w, `width は${MIN_BODY_WIDTH}〜${MAX_BODY_WIDTH}の整数`);
    if (!int(s.height) || s.height < LINE || s.height > MAX_BODY_HEIGHT || s.height % LINE) err(w, `height は9の倍数（9〜${MAX_BODY_HEIGHT}）`);
    if (!isColorValue(p, s.background)) err(w, `background の色が不正です（${s.background}）`);
    if (!AFTER_ACTIONS.includes(s.afterAction)) err(w, `afterAction は ${AFTER_ACTIONS.join('/')}`);
    if (typeof s.title !== 'string') err(w, 'title は文字列');
    if (s.stateDefaults != null && (typeof s.stateDefaults !== 'object' || Array.isArray(s.stateDefaults) || Object.values(s.stateDefaults).some((v) => typeof v !== 'string'))) err(w, 'stateDefaults は { 名前: 文字列 } のオブジェクト');
    validateFooter(s, w, err);
    validateInputs(s, w, err);
    if (!Array.isArray(s.elements)) { err(w, 'elements は配列'); continue; }
    const ids = new Set();
    for (const e of s.elements) {
      const we = `${w} / ${e?.id}`;
      if (!ID_RE.test(e?.id || '')) { err(we, 'idは英小文字・数字・_'); continue; }
      if (ids.has(e.id)) err(we, 'idが重複しています');
      ids.add(e.id);
      if (!ELEMENT_TYPES.includes(e.type)) { err(we, `type は ${ELEMENT_TYPES.join('/')}`); continue; }
      for (const k of ['x', 'y', 'w', 'h']) if (!int(e[k])) err(we, `${k} は整数`);
      if (e.w < 1 || e.h < 1) err(we, 'w/h は1以上');
      if (e.type === 'rect' || e.type === 'button') {
        if (!isColorValue(p, e.fill)) err(we, `fill の色が不正です（${e.fill}）`);
        if (!isColorValue(p, e.border)) err(we, `border の色が不正です（${e.border}）`);
        if (!int(e.borderWidth) || e.borderWidth < 0 || e.borderWidth > 32) err(we, 'borderWidth は0〜32');
      }
      if (e.type === 'image' && e.asset != null && !p.assets?.[e.asset]) err(we, `素材 ${e.asset} が存在しません`);
      if (e.type === 'button' && e.image != null && !p.assets?.[e.image]) err(we, `素材 ${e.image} が存在しません`);
      if ((e.type === 'image' || e.type === 'button') && !FITS.includes(e.fit)) err(we, `fit は ${FITS.join('/')}`);
      if (e.type === 'text') {
        if (typeof e.text !== 'string') err(we, 'text は文字列');
        if (!isColorValue(p, e.color)) err(we, `color の色が不正です（${e.color}）`);
        if (!ALIGNS.includes(e.align)) err(we, `align は ${ALIGNS.join('/')}`);
      }
      if (e.type === 'text' || e.type === 'button') {
        if (!(e.fontSize >= 4 && e.fontSize <= 128)) err(we, 'fontSize は4〜128');
        if (!isColorValue(p, e.shadowColor ?? '#3f3f3fff')) err(we, `shadowColor の色が不正です（${e.shadowColor}）`);
        if (e.type === 'button' && !isColorValue(p, e.labelColor)) err(we, `labelColor の色が不正です（${e.labelColor}）`);
        if (typeof e.wordSpacing !== 'number' || e.wordSpacing < -16 || e.wordSpacing > 64) err(we, 'wordSpacing は-16〜64');
        const r = e.type === 'text' ? e.render : e.labelRender;
        if (!TEXT_RENDERS.includes(r)) err(we, `${e.type === 'text' ? 'render' : 'labelRender'} は ${TEXT_RENDERS.join('/')}`);
        if (e.type === 'text' && r === 'game' && (e.y % LINE || e.h < LINE)) err(we, 'ゲーム内フォントの文字は y を9の倍数、h を9以上にしてください（9px行に置かれます）');
      }
      if (e.type === 'button' || e.type === 'hotspot') validateAction(e.action, we, err, screenIds, p);
      if (e.state != null && e.state !== '') {
        if (!parseState(e.state)) err(we, 'state は 名前=値 または 名前!=値（英数字と _ . -）');
        if (!['rect', 'text', 'image'].includes(e.type)) err(we, 'state を付けられるのは rect / text / image だけです（クリック領域は状態で変えられません）');
      }
    }
  }
  // 画面遷移先は全画面のidが揃ってから確認する
  const allIds = new Set(p.screens.map((s) => s?.id));
  for (const s of p.screens) {
    for (const e of s?.elements || []) {
      if (e?.action?.type === 'screen' && !allIds.has(e.action.value)) err(`screen ${s.id} / ${e.id}`, `遷移先の画面 ${e.action.value} がありません`);
    }
    for (const [i, b] of (s?.footer?.buttons || []).entries()) {
      if (b?.action?.type === 'screen' && !allIds.has(b.action.value)) err(`screen ${s.id} / footer[${i}]`, `遷移先の画面 ${b.action.value} がありません`);
    }
  }
  return errors;
}

function validateAction(a, w, err) {
  if (!a || !ACTION_TYPES.includes(a.type)) { err(w, `action.type は ${ACTION_TYPES.join('/')}`); return; }
  if (typeof a.value !== 'string') err(w, 'action.value は文字列');
  if (a.type === 'custom' && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(a.value)) err(w, 'custom の value は namespace:path 形式のID');
  if (a.type === 'url' && !/^https?:\/\//.test(a.value)) err(w, 'url は http(s):// で始まる必要があります');
  if ((a.type === 'command' || a.type === 'suggest') && !a.value) err(w, 'コマンドが空です');
}

function validateFooter(s, w, err) {
  const f = s.footer;
  if (!f || !FOOTER_TYPES.includes(f.type)) { err(w, `footer.type は ${FOOTER_TYPES.join('/')}`); return; }
  if (f.type === 'none') {
    if (!Array.isArray(f.buttons)) err(w, 'footer.buttons は配列');
    else if (f.buttons.length) err(w, 'footer.type=none のときボタンは置けません');
    if (s.inputs?.length) err(w, 'footer.type=none では入力値を送るボタンがないため、入力欄は使えません');
    return;
  }
  if (!Array.isArray(f.buttons) || f.buttons.length < 1) { err(w, 'footer.buttons は1つ以上必要です（Minecraftの仕様）'); return; }
  if (f.type === 'notice' && f.buttons.length !== 1) err(w, 'notice のボタンは1つです');
  if (!int(f.columns) || f.columns < 1 || f.columns > 8) err(w, 'footer.columns は1〜8');
  for (const [i, b] of f.buttons.entries()) {
    const wb = `${w} / footer[${i}]`;
    if (typeof b.label !== 'string') err(wb, 'label は文字列');
    if (!int(b.width) || b.width < 1 || b.width > 1024) err(wb, 'width は1〜1024');
    if (!b.action || !FOOTER_ACTION_TYPES.includes(b.action.type)) err(wb, `action.type は ${FOOTER_ACTION_TYPES.join('/')}`);
    else if ((b.action.type === 'custom' || b.action.type === 'submit') && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(b.action.value)) err(wb, 'value は namespace:path 形式のID');
  }
}

function validateInputs(s, w, err) {
  if (!Array.isArray(s.inputs)) { err(w, 'inputs は配列'); return; }
  const keys = new Set();
  for (const [i, inp] of s.inputs.entries()) {
    const wi = `${w} / inputs[${i}]`;
    if (!INPUT_TYPES.includes(inp?.type)) { err(wi, `type は ${INPUT_TYPES.join('/')}`); continue; }
    if (!/^[a-zA-Z0-9_]{1,32}$/.test(inp.key || '')) err(wi, 'key は英数字・_');
    if (keys.has(inp.key)) err(wi, 'key が重複しています');
    keys.add(inp.key);
    if (inp.type === 'single_option' && (!Array.isArray(inp.options) || !inp.options.length)) err(wi, 'options が必要です');
    if (inp.type === 'number_range' && !(inp.start < inp.end)) err(wi, 'start < end が必要です');
  }
}

function int(v) { return Number.isInteger(v); }

export function defaultInput(type, key) {
  const base = { type, key, label: key, width: 200 };
  if (type === 'text') return { ...base, initial: '', maxLength: 32, multiline: false };
  if (type === 'boolean') return { ...base, initial: false };
  if (type === 'single_option') return { ...base, options: [{ id: 'a', display: 'A' }, { id: 'b', display: 'B' }], initial: 'a' };
  return { ...base, start: 0, end: 10, step: 1, initial: 0, format: '%s: %s' };
}

// ---- 旧形式からの移行 -------------------------------------------------------

export function migrateProject(p) {
  if (!p || typeof p !== 'object') return p;
  if (p.schemaVersion === 1) {
    for (const s of p.screens || []) for (const e of s.elements || []) {
      // v1 の文字はすべて焼き込みだった。見た目を変えないよう baked にする
      if (e.type === 'text') { e.render ??= 'baked'; e.pixel ??= false; e.wordSpacing ??= 0; }
      if (e.type === 'button') { e.labelRender ??= 'baked'; e.pixel ??= false; e.wordSpacing ??= 0; }
    }
    p.schemaVersion = 2;
  }
  if (p.schemaVersion === 2) {
    p.theme ??= { name: 'default', tokens: {} };
    p.schemaVersion = 3;
  }
  return p;
}

// ---- 参照・パス -----------------------------------------------------------

export function findScreen(p, id) {
  const s = p.screens.find((x) => x.id === id);
  if (!s) throw new OpError(`画面 ${id} がありません`);
  return s;
}

export function findElement(s, id) {
  const e = s.elements.find((x) => x.id === id);
  if (!e) throw new OpError(`要素 ${id} が画面 ${s.id} にありません`);
  return e;
}

export class OpError extends Error {}

const FORBIDDEN = new Set(['__proto__', 'prototype', 'constructor']);

export function setPath(obj, path, value) {
  const keys = String(path).split('.');
  if (!keys.length || keys.some((k) => !k || FORBIDDEN.has(k))) throw new OpError(`不正なパス ${path}`);
  let o = obj;
  for (const k of keys.slice(0, -1)) {
    if (o[k] == null || typeof o[k] !== 'object') throw new OpError(`パス ${path} の途中 ${k} がありません`);
    o = o[k];
  }
  const last = keys[keys.length - 1];
  if (Array.isArray(o) && !/^\d+$/.test(last)) throw new OpError(`配列の添字は数値です: ${path}`);
  if (!Array.isArray(o) && !(last in o)) throw new OpError(`未知のプロパティ ${path}`);
  o[last] = value;
}

export function getPath(obj, path) {
  return String(path).split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);
}

export function uniqueId(existing, base) {
  const set = new Set(existing);
  const clean = (base || 'el').toLowerCase().replace(/[^a-z0-9_]/g, '_').slice(0, 40) || 'el';
  if (!set.has(clean)) return clean;
  for (let i = 2; ; i++) if (!set.has(`${clean}_${i}`)) return `${clean}_${i}`;
}

// ---- 編集操作 -------------------------------------------------------------
// すべての編集はこの操作の配列で表す。UI・API・CLIで共通。
// 適用はコピーに対して行い、検証が通ったときだけ採用する（呼び出し側の責務）。

export const OPS = {
  set: '{op:"set", screen?, element?, path, value} — project/画面/要素のプロパティを変更。footer.buttons.0.label のような入れ子も可',
  add_screen: '{op:"add_screen", screen:{id,...}, index?} — 画面を追加（未指定の項目は既定値）',
  remove_screen: '{op:"remove_screen", screen, ifExists?} — ifExists:true なら無くてもエラーにしない',
  duplicate_screen: '{op:"duplicate_screen", screen, newId}',
  add_element: '{op:"add_element", screen, element:{type,...}, index?} — idを省略すると自動採番',
  remove_element: '{op:"remove_element", screen, element}',
  duplicate_element: '{op:"duplicate_element", screen, element, newId?, dx?, dy?}',
  move_element: '{op:"move_element", screen, element, index} — 重なり順（0が最背面）',
  patch_element: '{op:"patch_element", screen, element, values:{x:..,y:..}} — 複数プロパティを一度に変更',
  add_input: '{op:"add_input", screen, input:{type,key,...}}',
  remove_input: '{op:"remove_input", screen, index}',
  add_footer_button: '{op:"add_footer_button", screen, button?}',
  remove_footer_button: '{op:"remove_footer_button", screen, index}',
  remove_asset: '{op:"remove_asset", asset} — 未使用の素材のみ削除',
  set_theme_token: '{op:"set_theme_token", key, value, ifMissing?} — テーマの色を追加・変更（ifMissing:true なら既にあるとき変更しない）',
  rename_theme_token: '{op:"rename_theme_token", key, to} — 色の名前を変え、使っている要素も付け替える',
  remove_theme_token: '{op:"remove_theme_token", key} — 未使用の色だけ削除',
  set_theme: '{op:"set_theme", theme:{name?, tokens}, merge?} — テーマをまとめて置き換え（merge:true なら上書き追加）',
};

export function applyOp(p, op) {
  if (!op || typeof op.op !== 'string') throw new OpError('op がありません');
  switch (op.op) {
    case 'set': {
      if (op.path === 'schemaVersion' || op.path === 'assets' || String(op.path).startsWith('assets.')) throw new OpError(`${op.path} は直接変更できません`);
      let target = p;
      if (op.screen != null) target = findScreen(p, op.screen);
      if (op.element != null) target = findElement(target, op.element);
      if (op.element != null && op.path === 'id') {
        const s = findScreen(p, op.screen);
        if (s.elements.some((e) => e.id === op.value)) throw new OpError(`id ${op.value} は使用済みです`);
      }
      if (op.screen != null && op.element == null && op.path === 'id') return renameScreen(p, op.screen, op.value);
      setPath(target, op.path, op.value);
      return;
    }
    case 'patch_element': {
      const e = findElement(findScreen(p, op.screen), op.element);
      for (const [k, v] of Object.entries(op.values || {})) {
        if (k === 'id' || k === 'type') throw new OpError(`${k} は patch で変更できません`);
        setPath(e, k, v);
      }
      return;
    }
    case 'add_screen': {
      const src = op.screen || {};
      const id = src.id || uniqueId(p.screens.map((s) => s.id), 'screen');
      const s = defaultScreen(id, { title: src.title ?? id });
      for (const [k, v] of Object.entries(src)) if (k in s) s[k] = structuredClone(v);
      s.elements = (s.elements || []).map((e) => normalizeElement(s, e));
      insertAt(p.screens, s, op.index);
      return;
    }
    case 'remove_screen': {
      const i = p.screens.findIndex((s) => s.id === op.screen);
      if (i < 0) { if (op.ifExists) return; throw new OpError(`画面 ${op.screen} がありません`); }
      p.screens.splice(i, 1);
      return;
    }
    case 'duplicate_screen': {
      const s = structuredClone(findScreen(p, op.screen));
      s.id = op.newId || uniqueId(p.screens.map((x) => x.id), `${op.screen}_copy`);
      s.title = s.title + ' (copy)';
      insertAt(p.screens, s, p.screens.findIndex((x) => x.id === op.screen) + 1);
      return;
    }
    case 'add_element': {
      const s = findScreen(p, op.screen);
      insertAt(s.elements, normalizeElement(s, op.element || {}), op.index);
      return;
    }
    case 'remove_element': {
      const s = findScreen(p, op.screen);
      const i = s.elements.findIndex((e) => e.id === op.element);
      if (i < 0) throw new OpError(`要素 ${op.element} がありません`);
      s.elements.splice(i, 1);
      return;
    }
    case 'duplicate_element': {
      const s = findScreen(p, op.screen);
      const i = s.elements.findIndex((e) => e.id === op.element);
      if (i < 0) throw new OpError(`要素 ${op.element} がありません`);
      const e = structuredClone(s.elements[i]);
      e.id = op.newId || uniqueId(s.elements.map((x) => x.id), e.id);
      e.x += op.dx ?? LINE; e.y += op.dy ?? LINE;
      s.elements.splice(i + 1, 0, e);
      return;
    }
    case 'move_element': {
      const s = findScreen(p, op.screen);
      const i = s.elements.findIndex((e) => e.id === op.element);
      if (i < 0) throw new OpError(`要素 ${op.element} がありません`);
      const [e] = s.elements.splice(i, 1);
      const to = Math.max(0, Math.min(s.elements.length, op.index | 0));
      s.elements.splice(to, 0, e);
      return;
    }
    case 'add_input': {
      const s = findScreen(p, op.screen);
      const src = op.input || {};
      const type = src.type || 'text';
      const key = src.key || uniqueId(s.inputs.map((x) => x.key), type);
      s.inputs.push({ ...defaultInput(type, key), ...structuredClone(src), type, key });
      return;
    }
    case 'remove_input': {
      const s = findScreen(p, op.screen);
      if (!s.inputs[op.index]) throw new OpError(`inputs[${op.index}] がありません`);
      s.inputs.splice(op.index, 1);
      return;
    }
    case 'add_footer_button': {
      const s = findScreen(p, op.screen);
      s.footer.buttons.push(defaultFooterButton(op.button || { label: 'ボタン' }));
      if (s.footer.buttons.length > 1 || s.footer.type === 'none') s.footer.type = s.footer.buttons.length > 1 ? 'multi_action' : 'notice';
      return;
    }
    case 'remove_footer_button': {
      const s = findScreen(p, op.screen);
      if (!s.footer.buttons[op.index]) throw new OpError(`footer.buttons[${op.index}] がありません`);
      s.footer.buttons.splice(op.index, 1);
      return;
    }
    case 'set_theme_token': {
      if (!TOKEN_KEY_RE.test(op.key || '')) throw new OpError('色の名前は英小文字・数字・_ . - の1〜48文字');
      p.theme ??= { name: 'default', tokens: {} };
      if (op.ifMissing && op.key in p.theme.tokens) return;
      p.theme.tokens[op.key] = op.value;
      return;
    }
    case 'rename_theme_token': {
      const t = p.theme?.tokens || {};
      if (!(op.key in t)) throw new OpError(`テーマの色 ${op.key} がありません`);
      if (!TOKEN_KEY_RE.test(op.to || '') || op.to in t) throw new OpError(`新しい名前 ${op.to} は使えません`);
      t[op.to] = t[op.key];
      delete t[op.key];
      for (const s of p.screens) {
        if (s.background === `@${op.key}`) s.background = `@${op.to}`;
        for (const e of s.elements) for (const k of COLOR_PROPS) if (e[k] === `@${op.key}`) e[k] = `@${op.to}`;
      }
      return;
    }
    case 'remove_theme_token': {
      if (!(op.key in (p.theme?.tokens || {}))) throw new OpError(`テーマの色 ${op.key} がありません`);
      const users = themeUsers(p, op.key);
      if (users.length) throw new OpError(`テーマの色 ${op.key} は使用中です: ${users.slice(0, 5).join(', ')}${users.length > 5 ? ` ほか${users.length - 5}件` : ''}`);
      delete p.theme.tokens[op.key];
      return;
    }
    case 'set_theme': {
      const src = op.theme || {};
      if (typeof src.tokens !== 'object' || !src.tokens) throw new OpError('theme.tokens が必要です');
      p.theme = { name: src.name ?? p.theme?.name ?? 'default', tokens: op.merge ? { ...(p.theme?.tokens || {}), ...src.tokens } : { ...src.tokens } };
      return;
    }
    case 'remove_asset': {
      if (!p.assets[op.asset]) throw new OpError(`素材 ${op.asset} がありません`);
      const users = assetUsers(p, op.asset);
      if (users.length) throw new OpError(`素材 ${op.asset} は使用中です: ${users.join(', ')}`);
      delete p.assets[op.asset];
      return;
    }
    case 'add_asset': {
      // サーバーのアップロード処理だけが使う
      if (!op.asset?.id || !op.asset.file) throw new OpError('asset が不正です');
      p.assets[op.asset.id] = structuredClone(op.asset);
      return;
    }
    default:
      throw new OpError(`未知の op: ${op.op}`);
  }
}

function renameScreen(p, from, to) {
  if (!ID_RE.test(to || '')) throw new OpError('画面idは英小文字・数字・_');
  if (p.screens.some((s) => s.id === to)) throw new OpError(`画面 ${to} は既にあります`);
  findScreen(p, from).id = to;
  for (const s of p.screens) {
    for (const e of s.elements) if (e.action?.type === 'screen' && e.action.value === from) e.action.value = to;
    for (const b of s.footer.buttons) if (b.action?.type === 'screen' && b.action.value === from) b.action.value = to;
  }
}

export function normalizeElement(s, src) {
  const type = src.type || 'rect';
  if (!ELEMENT_TYPES.includes(type)) throw new OpError(`未知の要素type: ${type}`);
  const e = defaultElement(type);
  for (const [k, v] of Object.entries(src)) {
    if (k === 'action' && v && typeof v === 'object') e.action = { ...defaultAction(), ...v };
    else if (k in e) e[k] = structuredClone(v);
    else throw new OpError(`要素type ${type} に未知のプロパティ ${k}`);
  }
  e.id = src.id || uniqueId(s.elements.map((x) => x.id), type);
  if (s.elements.some((x) => x.id === e.id)) throw new OpError(`要素id ${e.id} は使用済みです`);
  return e;
}

export function assetUsers(p, assetId) {
  const out = [];
  for (const s of p.screens) for (const e of s.elements) if (e.asset === assetId || e.image === assetId) out.push(`${s.id}/${e.id}`);
  for (const slot of SKIN_SLOTS) if (p.skin?.[slot] === assetId) out.push(`skin.${slot}`);
  return out;
}

function insertAt(arr, item, index) {
  if (index == null || index < 0 || index > arr.length) arr.push(item);
  else arr.splice(index, 0, item);
}

// 操作の配列をまとめて適用。失敗したら例外（元のオブジェクトは変更しない）。
export function applyOps(project, ops) {
  if (!Array.isArray(ops) || !ops.length) throw new OpError('operations は1件以上の配列');
  const next = structuredClone(project);
  ops.forEach((op, i) => {
    try { applyOp(next, op); } catch (e) { throw new OpError(`operations[${i}] (${op?.op}): ${e.message}`); }
  });
  const errors = validateProject(next);
  if (errors.length) throw new OpError(`検証エラー:\n- ${errors.join('\n- ')}`);
  return next;
}

// 9px行への量子化（クリック領域は行単位でしか作れない）
export function clickRows(y, h, height) {
  const rows = [];
  const n = height / LINE;
  for (let r = 0; r < n; r++) {
    const top = r * LINE, bottom = top + LINE;
    const overlap = Math.min(bottom, y + h) - Math.max(top, y);
    if (overlap * 2 >= LINE) rows.push(r);
  }
  return rows;
}
