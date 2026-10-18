// プロジェクトJSONのスキーマ・既定値・検証・編集操作。
// ブラウザー（editor）とNode（server/CLI/compile）の両方から読み込む。依存は states.mjs だけ（同じく依存なし）。
import { parseConditions, combineStates, renameStateKeyIn, STATE_KEY_RE, STATE_VALUE_RE } from './states.mjs';
import { expandElements, expandInstance, findComponent, isComponentRef, COMPONENT_PREFIX, H_CONSTRAINTS, V_CONSTRAINTS, PROP_TYPES, BIND_TARGETS, componentUsers } from './components.mjs';

export const SCHEMA_VERSION = 6;
export const LINE = 9;              // Minecraftの1行の高さ（GUI px）
export const MAX_BODY_WIDTH = 1015; // plain_messageの上限1024から、右端の+1 advanceと左右の余白8を引いた値
export const MIN_BODY_WIDTH = 16;
export const MAX_BODY_HEIGHT = 9 * 300;

export const ELEMENT_TYPES = ['rect', 'image', 'text', 'button', 'hotspot', 'instance'];
export const ACTION_TYPES = ['none', 'screen', 'close', 'custom', 'command', 'suggest', 'url', 'copy'];
// none: フッターのボタンなし（dialog_list を exit_action なし・中身なしで出力。フッター領域は5pxになる）
export const FOOTER_TYPES = ['notice', 'multi_action', 'none'];
export const FOOTER_ACTION_TYPES = ['close', 'screen', 'custom', 'submit', 'command', 'url', 'copy'];
export const INPUT_TYPES = ['text', 'boolean', 'single_option', 'number_range'];
export const AFTER_ACTIONS = ['close', 'none', 'wait_for_response'];
export const FITS = ['stretch', 'contain', 'cover', 'tile', 'nine'];
// 9分割（fit: nine）の辺と真ん中の埋め方
export const NINE_FILLS = ['stretch', 'tile'];
export const ALIGNS = ['left', 'center', 'right'];
export const TEXT_RENDERS = ['game', 'baked']; // game: ゲーム内フォント（8px固定・9px行） / baked: 任意フォントを画像に焼き込み
export const SKIN_SLOTS = ['button', 'button_highlighted', 'button_disabled', 'scroller', 'scroller_background'];

const ID_RE = /^[a-z0-9_]{1,48}$/;
const NS_RE = /^[a-z0-9_.-]{1,32}$/;
const COLOR_RE = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
export const TOKEN_KEY_RE = /^[a-z0-9_.-]{1,48}$/;
// 色を持つプロパティ。値は '#RRGGBB(AA)' か、テーマの色の名前 '@name'
export const COLOR_PROPS = ['background', 'fill', 'border', 'color', 'shadowColor', 'labelColor'];
// 文字スタイル（project.textStyles）が決める項目。文字の要素は textStyle を指定すると、これらの値がスタイルの値になる
export const TEXT_STYLE_KEYS = ['render', 'fontFamily', 'fontSize', 'pixel', 'wordSpacing', 'bold', 'color', 'shadow', 'shadowColor', 'align', 'valign', 'lineHeight'];
// ボタンのラベルでは名前が違う項目
const BUTTON_STYLE_KEY = { render: 'labelRender', color: 'labelColor' };
const BUTTON_STYLE_KEYS = ['render', 'fontFamily', 'fontSize', 'pixel', 'wordSpacing', 'bold', 'color', 'shadow'];

export function isTokenRef(v) { return typeof v === 'string' && v.startsWith('@'); }

// 要素を持つもの（画面と部品のマスター）
export const containers = (p) => [...(p?.screens || []), ...(p?.components || [])];

// 色の値として正しいか（テーマの色の名前なら、その名前があるか）
export function isColorValue(p, v) {
  if (COLOR_RE.test(v || '')) return true;
  return isTokenRef(v) && TOKEN_KEY_RE.test(v.slice(1)) && !!p?.theme?.tokens?.[v.slice(1)];
}

// 描画・書き出し用に解決した画面のコピー:
//  - 文字スタイルの値を文字の要素に入れる
//  - 非表示のグループの中の要素を非表示にする
//  - テーマの色の名前を実際の色に置き換える
export function resolveScreen(p, screen) {
  const tokens = p?.theme?.tokens || {};
  const r = (v) => (isTokenRef(v) ? tokens[v.slice(1)] ?? '#ff00ffff' : v);
  const s = structuredClone(screen);
  s.background = r(s.background);
  // 部品の使用箇所を平らな要素に展開する（書き出し・描画はこの結果を使う）
  s.elements = expandElements(p, s.elements);
  const hidden = hiddenGroups(s);
  for (const e of s.elements) {
    applyTextStyle(p, e);
    if (e.group && hidden.has(e.group)) e.visible = false;
    // グループの状態は、中の要素の条件として & でつなぐ（外側のグループから順に）
    if (e.group) {
      const gs = groupChain(s, e.group).reverse().map((g) => g.state).filter(Boolean);
      if (gs.length) e.state = combineStates(...gs, e.state);
    }
    for (const k of COLOR_PROPS) if (k in e) e[k] = r(e[k]);
    if (e.fit === 'nine') e.nine = nineOf(p, e);
  }
  return s;
}

// 画像の1画素を何 GUI px で描くか（素材ごと。既定はテクスチャ倍率の逆数＝「素材の実寸」と同じ）
export function assetPixelScale(p, a) { return a?.pixelScale || 1 / (p?.textureScale || 1); }

// 9分割の描き方: 分割位置（画像の画素）・画素の大きさ・辺と真ん中の埋め方
export function nineOf(p, e) {
  const a = p?.assets?.[e.type === 'button' ? e.image : e.asset];
  const sl = e.slice || a?.slice || { l: 0, t: 0, r: 0, b: 0 };
  return { l: sl.l, t: sl.t, r: sl.r, b: sl.b, ps: assetPixelScale(p, a), fill: e.nineFill || 'stretch' };
}

// 文字スタイルの値を要素に入れる（その場で書き換える）。スタイルがなければ何もしない
export function applyTextStyle(p, e) {
  const style = e.textStyle ? p?.textStyles?.[e.textStyle] : null;
  if (!style) return e;
  if (e.type === 'text') for (const k of TEXT_STYLE_KEYS) { if (k in style) e[k] = style[k]; }
  if (e.type === 'button') for (const k of BUTTON_STYLE_KEYS) { if (k in style) e[BUTTON_STYLE_KEY[k] || k] = style[k]; }
  return e;
}

// ---- グループ ---------------------------------------------------------------
// グループは画面の groups にあり、要素は group で所属を示す（入れ子は parent）。
// 要素の並び（重なり順）はグループと関係なく平ら。グループは選択・一覧・表示/ロックのための情報。

export function findGroup(s, id) { return (s.groups || []).find((g) => g.id === id) || null; }

// グループの祖先（近い順。自身を含む）
export function groupChain(s, id) {
  const out = [];
  const seen = new Set();
  for (let g = findGroup(s, id); g && !seen.has(g.id); g = findGroup(s, g.parent)) { seen.add(g.id); out.push(g); }
  return out;
}

// 非表示（自身か祖先が visible=false）のグループの id
export function hiddenGroups(s) {
  const out = new Set();
  for (const g of s.groups || []) if (groupChain(s, g.id).some((x) => !x.visible)) out.add(g.id);
  return out;
}

// ロック中（自身か祖先が locked）のグループの id
export function lockedGroups(s) {
  const out = new Set();
  for (const g of s.groups || []) if (groupChain(s, g.id).some((x) => x.locked)) out.add(g.id);
  return out;
}

// グループの中の要素（入れ子の中も含む）
export function groupMembers(s, id) {
  return s.elements.filter((e) => e.group && groupChain(s, e.group).some((g) => g.id === id));
}

// 要素・グループのどちらも含まないグループを消す（入れ子は外側へ順に）
function pruneGroups(s) {
  if (!s.groups?.length) return;
  for (;;) {
    const used = new Set([...s.elements.map((e) => e.group), ...s.groups.map((g) => g.parent)]);
    const before = s.groups.length;
    s.groups = s.groups.filter((g) => used.has(g.id));
    if (s.groups.length === before) return;
  }
}

// テーマの色を使っている場所
export function themeUsers(p, key) {
  const ref = `@${key}`;
  const out = [];
  for (const s of containers(p)) {
    if (s.background === ref) out.push(`${s.id}.background`);
    for (const e of s.elements) for (const k of COLOR_PROPS) if (e[k] === ref) out.push(`${s.id}/${e.id}.${k}`);
  }
  for (const [id, st] of Object.entries(p.textStyles || {})) for (const k of ['color', 'shadowColor']) if (st[k] === ref) out.push(`textStyles.${id}.${k}`);
  return out;
}

// 文字スタイルを使っている要素
export function textStyleUsers(p, key) {
  const out = [];
  for (const s of containers(p)) for (const e of s.elements) if (e.textStyle === key) out.push(`${s.id}/${e.id}`);
  return out;
}

// ---- 既定値 -------------------------------------------------------------

export function defaultAction() { return { type: 'none', value: '', payload: '' }; }

export function defaultElement(type, over = {}) {
  // binds / constraints は部品のマスターの中でだけ使う（components.mjs）
  const base = { id: '', type, name: '', x: 0, y: 0, w: 64, h: 18, visible: true, locked: false, group: '', binds: {}, constraints: { h: 'left', v: 'top' } };
  const byType = {
    rect: { fill: '#3a3f4bff', border: '#00000000', borderWidth: 0, state: '' },
    image: { asset: null, fit: 'stretch', smooth: false, opacity: 1, slice: null, nineFill: 'stretch', state: '' },
    text: { text: 'テキスト', render: 'game', fontFamily: 'Yu Gothic UI', fontSize: 9, pixel: false, wordSpacing: 0, bold: false, color: '#ffffffff', align: 'left', valign: 'top', lineHeight: 1.2, shadow: false, shadowColor: '#3f3f3fff', state: '', textStyle: '', w: 96, h: 18 },
    button: {
      fill: '#5a6270ff', border: '#1e2128ff', borderWidth: 1, image: null, fit: 'stretch', slice: null, nineFill: 'stretch',
      label: 'ボタン', labelRender: 'game', fontFamily: 'Yu Gothic UI', fontSize: 9, pixel: false, wordSpacing: 0, bold: false, labelColor: '#ffffffff', shadow: true, textStyle: '',
      action: defaultAction(), tooltip: '', w: 72, h: 18,
    },
    hotspot: { action: defaultAction(), tooltip: '', w: 36, h: 18 },
    instance: { component: '', variant: '', variantBy: null, props: {}, state: '' },
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
    groups: [],        // [{ id, name, parent, visible, locked }]
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
    textStyles: {},
    components: [],
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
  else for (const [id, a] of Object.entries(p.assets)) {
    if (a.slice != null && !validSlice(a.slice, a)) err(`assets.${id}`, `分割位置が不正です（0以上の整数で、左+右 ≤ 幅 ${a.width}、上+下 ≤ 高さ ${a.height}）`);
    if (a.pixelScale != null && !(a.pixelScale >= 0.125 && a.pixelScale <= 8)) err(`assets.${id}`, 'pixelScale は 0.125〜8（GUI px / 画素）');
  }
  for (const slot of SKIN_SLOTS) {
    const a = p.skin?.[slot];
    if (a != null && !p.assets?.[a]) err(`skin.${slot}`, `素材 ${a} が存在しません`);
  }
  if (!p.theme || typeof p.theme.tokens !== 'object' || Array.isArray(p.theme.tokens)) err('theme', 'theme.tokens はオブジェクト');
  else for (const [k, v] of Object.entries(p.theme.tokens)) {
    if (!TOKEN_KEY_RE.test(k)) err(`theme.${k}`, '色の名前は英小文字・数字・_ . - の1〜48文字');
    if (!COLOR_RE.test(v || '')) err(`theme.${k}`, '色は #RRGGBB または #RRGGBBAA（テーマの中で別の名前は参照できません）');
  }
  if (p.textStyles != null && (typeof p.textStyles !== 'object' || Array.isArray(p.textStyles))) err('textStyles', 'オブジェクトが必要です');
  else for (const [k, v] of Object.entries(p.textStyles || {})) validateTextStyle(p, k, v, err);
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
    else for (const [k, v] of Object.entries(s.stateDefaults || {})) if (!STATE_KEY_RE.test(k) || !STATE_VALUE_RE.test(v)) err(w, `状態 ${k}=${v} の名前か値が不正です（英数字と _ . -）`);
    validateFooter(s, w, err);
    validateInputs(s, w, err);
    validateBody(p, s, w, err, screenIds);
  }
  // 部品のマスター
  if (p.components != null && !Array.isArray(p.components)) err('components', '配列が必要です');
  const compIds = new Set();
  for (const c of p.components || []) {
    const w = `component ${c?.id}`;
    if (!ID_RE.test(c?.id || '')) { err(w, 'idは英小文字・数字・_ の1〜48文字'); continue; }
    if (compIds.has(c.id)) err(w, 'idが重複しています');
    compIds.add(c.id);
    if (typeof c.name !== 'string') err(w, 'name は文字列');
    if (!int(c.width) || !int(c.height) || c.width < 1 || c.height < 1 || c.width > MAX_BODY_WIDTH || c.height > MAX_BODY_HEIGHT) err(w, `大きさは 1〜${MAX_BODY_WIDTH} × 1〜${MAX_BODY_HEIGHT}`);
    if (!Array.isArray(c.variants) || !c.variants.length || c.variants.some((v) => !STATE_VALUE_RE.test(v)) || new Set(c.variants).size !== c.variants.length) err(w, 'variants は重複のない名前（英数字と _ . -）の配列で、1つ以上');
    const names = new Set();
    for (const pr of c.props || []) {
      if (!/^[a-z0-9_]{1,32}$/.test(pr?.name || '') || names.has(pr.name)) err(w, `差し込み口の名前 ${pr?.name} が不正か重複しています`);
      names.add(pr?.name);
      if (!PROP_TYPES.includes(pr?.type)) err(w, `差し込み口 ${pr?.name} の type は ${PROP_TYPES.join('/')}`);
      else if (pr.type === 'action' && pr.default != null) validateAction(pr.default, `${w} / ${pr.name}`, err, screenIds, p);
    }
    for (const e of c.elements || []) for (const x of parseConditions(e.state) || []) if (x.key === 'variant' && !c.variants?.includes(x.value)) err(`${w} / ${e.id}`, `バリアント ${x.value} は部品にありません`);
    validateBody(p, c, w, err, screenIds);
  }
  // 画面遷移先は全画面のidが揃ってから確認する
  const allIds = new Set(p.screens.map((s) => s?.id));
  for (const s of containers(p)) {
    for (const e of s?.elements || []) {
      if (e?.action?.type === 'screen' && !allIds.has(e.action.value)) err(`screen ${s.id} / ${e.id}`, `遷移先の画面 ${e.action.value} がありません`);
    }
    for (const [i, b] of (s?.footer?.buttons || []).entries()) {
      if (b?.action?.type === 'screen' && !allIds.has(b.action.value)) err(`screen ${s.id} / footer[${i}]`, `遷移先の画面 ${b.action.value} がありません`);
    }
    for (const e of s?.elements || []) if (e.type === 'instance') for (const [k, v] of Object.entries(e.props || {})) {
      if (v?.type === 'screen' && !allIds.has(v.value)) err(`screen ${s.id} / ${e.id}`, `差し込み口 ${k} の遷移先の画面 ${v.value} がありません`);
    }
  }
  return errors;
}

// 画面・部品のマスターの本文（グループと要素）の検証
function validateBody(p, s, w, err, screenIds) {
  if (!Array.isArray(s.elements)) { err(w, 'elements は配列'); return; }
  const groupIds = new Set();
  if (s.groups != null && !Array.isArray(s.groups)) err(w, 'groups は配列');
  for (const g of s.groups || []) {
    const wg = `${w} / group ${g?.id}`;
    if (!ID_RE.test(g?.id || '')) { err(wg, 'idは英小文字・数字・_'); continue; }
    if (groupIds.has(g.id)) err(wg, 'idが重複しています');
    groupIds.add(g.id);
    if (typeof g.name !== 'string') err(wg, 'name は文字列');
    if (typeof g.visible !== 'boolean' || typeof g.locked !== 'boolean') err(wg, 'visible / locked は true か false');
    if (g.state && !parseConditions(g.state)) err(wg, 'state は 名前=値 または 名前!=値（& でつなげる）');
  }
  for (const g of s.groups || []) {
    if (g.state && groupMembers(s, g.id).some((e) => e.type === 'button' || e.type === 'hotspot')) err(`${w} / group ${g.id}`, '状態を付けたグループには、ボタン・クリック領域を入れられません（クリック領域は状態で変えられません）');
    if (g.parent && !groupIds.has(g.parent)) err(`${w} / group ${g.id}`, `親のグループ ${g.parent} がありません`);
    if (g.parent && groupChain(s, g.id).length <= groupChain(s, g.parent).length) err(`${w} / group ${g.id}`, 'グループの入れ子が循環しています');
  }
  const ids = new Set();
  for (const e of s.elements) {
    const we = `${w} / ${e?.id}`;
    if (!ID_RE.test(e?.id || '')) { err(we, 'idは英小文字・数字・_'); continue; }
    if (ids.has(e.id)) err(we, 'idが重複しています');
    ids.add(e.id);
    if (!ELEMENT_TYPES.includes(e.type)) { err(we, `type は ${ELEMENT_TYPES.join('/')}`); continue; }
    for (const k of ['x', 'y', 'w', 'h']) if (!int(e[k])) err(we, `${k} は整数`);
    if (e.w < 1 || e.h < 1) err(we, 'w/h は1以上');
    if (e.group && !groupIds.has(e.group)) err(we, `グループ ${e.group} がありません`);
    if (e.textStyle && !p.textStyles?.[e.textStyle]) err(we, `文字スタイル ${e.textStyle} がありません`);
    if (e.type === 'rect' || e.type === 'button') {
      if (!isColorValue(p, e.fill)) err(we, `fill の色が不正です（${e.fill}）`);
      if (!isColorValue(p, e.border)) err(we, `border の色が不正です（${e.border}）`);
      if (!int(e.borderWidth) || e.borderWidth < 0 || e.borderWidth > 32) err(we, 'borderWidth は0〜32');
    }
    if (e.type === 'image' && e.asset != null && !p.assets?.[e.asset]) err(we, `素材 ${e.asset} が存在しません`);
    if (e.type === 'button' && e.image != null && !p.assets?.[e.image]) err(we, `素材 ${e.image} が存在しません`);
    if ((e.type === 'image' || e.type === 'button') && !FITS.includes(e.fit)) err(we, `fit は ${FITS.join('/')}`);
    if ((e.type === 'image' || e.type === 'button') && e.slice != null && !validSlice(e.slice, p.assets?.[e.type === 'button' ? e.image : e.asset])) err(we, '分割位置（slice）が不正です（0以上の整数で、画像の大きさの内側）');
    if ((e.type === 'image' || e.type === 'button') && e.nineFill != null && !NINE_FILLS.includes(e.nineFill)) err(we, `nineFill は ${NINE_FILLS.join('/')}`);
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
      // 文字スタイルを使っているときは、スタイルの描画方式で判定する
      const eff = e.type === 'text' ? applyTextStyle(p, { ...e }).render : r;
      if (e.type === 'text' && eff === 'game' && (e.y % LINE || e.h < LINE)) err(we, 'ゲーム内フォントの文字は y を9の倍数、h を9以上にしてください（9px行に置かれます）');
    }
    if (e.type === 'button' || e.type === 'hotspot') validateAction(e.action, we, err, screenIds, p);
    if (e.state != null && e.state !== '') {
      if (!parseConditions(e.state)) err(we, 'state は 名前=値 または 名前!=値（英数字と _ . -）。& でつなぐとすべてを満たすときだけ');
      if (!['rect', 'text', 'image', 'instance'].includes(e.type)) err(we, 'state を付けられるのは rect / text / image / 部品の使用箇所 だけです（クリック領域は状態で変えられません）');
    }
    if (e.constraints && (!H_CONSTRAINTS.includes(e.constraints.h) || !V_CONSTRAINTS.includes(e.constraints.v))) err(we, `constraints は h: ${H_CONSTRAINTS.join('/')}、v: ${V_CONSTRAINTS.join('/')}`);
    for (const [target, prop] of Object.entries(e.binds || {})) {
      if (!(target in BIND_TARGETS) || !(target in e)) { err(we, `binds の ${target} は結び付けられません`); continue; }
      const pr = (s.props || []).find((x) => x.name === prop);
      if (!pr) err(we, `差し込み口 ${prop} がありません（部品のマスターでだけ使えます）`);
      else if (pr.type !== BIND_TARGETS[target]) err(we, `差し込み口 ${prop}（${pr.type}）は ${target} に結び付けられません`);
    }
    if (e.type === 'instance') validateInstance(p, s, e, we, err, screenIds);
  }
}

// 部品の使用箇所の検証（展開した結果も確かめる）
function validateInstance(p, s, e, we, err, screenIds) {
  const c = findComponent(p, e.component);
  if (!c) { err(we, `部品 ${e.component} がありません`); return; }
  const variants = c.variants || [];
  if (e.variant && !variants.includes(e.variant)) err(we, `バリアント ${e.variant} は部品 ${c.id} にありません`);
  const vb = e.variantBy;
  if (vb != null) {
    if (typeof vb !== 'object' || !STATE_KEY_RE.test(vb.key || '')) err(we, 'variantBy.key は状態の名前');
    else {
      for (const [u, v] of Object.entries(vb.map || {})) {
        if (!STATE_VALUE_RE.test(u)) err(we, `variantBy の値 ${u} が不正です`);
        if (!variants.includes(v)) err(we, `variantBy のバリアント ${v} は部品 ${c.id} にありません`);
      }
      if (vb.else && !variants.includes(vb.else)) err(we, `variantBy.else のバリアント ${vb.else} は部品 ${c.id} にありません`);
    }
  }
  for (const [k, v] of Object.entries(e.props || {})) {
    const pr = (c.props || []).find((x) => x.name === k);
    if (!pr) { err(we, `差し込み口 ${k} は部品 ${c.id} にありません`); continue; }
    if (pr.type === 'action') validateAction(v, `${we} / ${k}`, err, screenIds, p);
    else if (typeof v !== 'string') err(we, `差し込み口 ${k} は文字列`);
  }
  // 展開して、部品の中で起きる問題（循環・状態とクリック領域・9px行）を確かめる
  const errors = [];
  const expanded = expandInstance(p, e, errors);
  for (const m of new Set(errors)) err(we, m);
  for (const x of expanded) {
    if (x.type === 'text' && applyTextStyle(p, { ...x }).render === 'game' && x.y % LINE) { err(we, `中の文字 ${x.id.slice(e.id.length + 2)} が9px行からずれます（使用箇所の y・高さや、文字の伸び縮みの指定を見直してください）`); break; }
  }
}

// 分割位置 {l,t,r,b}（画像の画素）。素材の大きさが分かれば内側にあるか
function validSlice(sl, a) {
  if (!sl || typeof sl !== 'object') return false;
  if (!['l', 't', 'r', 'b'].every((k) => Number.isInteger(sl[k]) && sl[k] >= 0)) return false;
  return !a || (sl.l + sl.r <= a.width && sl.t + sl.b <= a.height);
}

function validateTextStyle(p, k, v, err) {
  const w = `textStyles.${k}`;
  if (!TOKEN_KEY_RE.test(k)) err(w, '名前は英小文字・数字・_ . - の1〜48文字');
  if (!v || typeof v !== 'object' || Array.isArray(v)) { err(w, 'オブジェクトが必要です'); return; }
  for (const key of Object.keys(v)) if (!TEXT_STYLE_KEYS.includes(key) && key !== 'name') err(w, `未知の項目 ${key}`);
  if ('name' in v && typeof v.name !== 'string') err(w, 'name は文字列');
  if ('render' in v && !TEXT_RENDERS.includes(v.render)) err(w, `render は ${TEXT_RENDERS.join('/')}`);
  if ('fontSize' in v && !(v.fontSize >= 4 && v.fontSize <= 128)) err(w, 'fontSize は4〜128');
  for (const c of ['color', 'shadowColor']) if (c in v && !isColorValue(p, v[c])) err(w, `${c} の色が不正です（${v[c]}）`);
  if ('align' in v && !ALIGNS.includes(v.align)) err(w, `align は ${ALIGNS.join('/')}`);
  for (const b of ['pixel', 'bold', 'shadow']) if (b in v && typeof v[b] !== 'boolean') err(w, `${b} は true か false`);
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
  if (p.schemaVersion === 3) {
    // グループ・文字スタイルを足す（見た目は変わらない）
    p.textStyles ??= {};
    for (const s of p.screens || []) {
      s.groups ??= [];
      s.stateDefaults ??= {};
      for (const e of s.elements || []) {
        e.group ??= '';
        if (e.type === 'text' || e.type === 'button') e.textStyle ??= '';
      }
    }
    p.schemaVersion = 4;
  }
  if (p.schemaVersion === 4) {
    // 9分割（素材の分割位置・画素の大きさ、要素の分割位置の上書き・埋め方）を足す。見た目は変わらない
    for (const a of Object.values(p.assets || {})) { a.slice ??= { l: 0, t: 0, r: 0, b: 0 }; a.pixelScale ??= null; }
    for (const s of p.screens || []) for (const e of s.elements || []) {
      if (e.type === 'image' || e.type === 'button') { e.slice ??= null; e.nineFill ??= 'stretch'; }
    }
    p.schemaVersion = 5;
  }
  if (p.schemaVersion === 5) {
    // 部品（コンポーネント）を足す。要素に差し込み口の結び付けと伸び縮みの指定を足す（見た目は変わらない）
    p.components ??= [];
    for (const s of containers(p)) for (const e of s.elements || []) { e.binds ??= {}; e.constraints ??= { h: 'left', v: 'top' }; }
    p.schemaVersion = 6;
  }
  return p;
}

// ---- 参照・パス -----------------------------------------------------------

export function findScreen(p, id) {
  if (isComponentRef(id)) {
    const c = findComponent(p, id.slice(COMPONENT_PREFIX.length));
    if (!c) throw new OpError(`部品 ${id.slice(COMPONENT_PREFIX.length)} がありません`);
    return c;
  }
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
  set_asset: '{op:"set_asset", asset, values:{name?, slice?:{l,t,r,b}, pixelScale?}} — 素材の名前・9分割の位置・1画素の大きさ（GUI px。null で既定）',
  replace_asset: '{op:"replace_asset", asset, with} — 素材を別の素材に差し替える（使っている要素・スキンを付け替え、分割位置も引き継ぐ）',
  set_theme_token: '{op:"set_theme_token", key, value, ifMissing?} — テーマの色を追加・変更（ifMissing:true なら既にあるとき変更しない）',
  rename_theme_token: '{op:"rename_theme_token", key, to} — 色の名前を変え、使っている要素も付け替える',
  remove_theme_token: '{op:"remove_theme_token", key} — 未使用の色だけ削除',
  set_theme: '{op:"set_theme", theme:{name?, tokens}, merge?} — テーマをまとめて置き換え（merge:true なら上書き追加）',
  patch_elements: '{op:"patch_elements", screen, patches:[{element, values:{...}}]} — 複数の要素をまとめて変更（整列・複数移動など）',
  add_elements: '{op:"add_elements", screen, elements:[{...}], groups?:[{id,name,parent,...}], index?} — 要素とグループをまとめて追加（貼り付け・複製）。id は空いているものを指定',
  remove_elements: '{op:"remove_elements", screen, elements:[id...]} — まとめて削除（空になったグループも消える）',
  move_elements: '{op:"move_elements", screen, elements:[id...], index} — まとめて重なり順を移動（今の順を保ったまま index の位置へ）',
  group_elements: '{op:"group_elements", screen, elements:[id...], groups?:[id...], group:{id, name?}} — 要素（とグループ）をグループにまとめる',
  ungroup: '{op:"ungroup", screen, group} — グループを解除（中身は親のグループへ）',
  set_group: '{op:"set_group", screen, group, values:{name?, visible?, locked?, parent?, state?}}',
  rename_state_key: '{op:"rename_state_key", screen, key, to} — 状態の名前を変え、画面の要素・グループ・既定値の条件も付け替える',
  set_state_default: '{op:"set_state_default", screen, key, value} — 状態の既定値を設定（value:null で削除）',
  set_text_style: '{op:"set_text_style", key, style:{...}, merge?, ifMissing?} — 文字スタイルを追加・変更',
  add_component: '{op:"add_component", component:{id, name?, width?, height?, elements?, groups?, props?, variants?}, replace?} — 部品のマスターを追加（replace:true なら同じ id を置き換える）。中の要素は screen:"component:<id>" の操作で編集する',
  set_component: '{op:"set_component", component, values:{name?, width?, height?, props?, variants?, stateDefaults?}}',
  remove_component: '{op:"remove_component", component} — 使われていない部品だけ削除',
  create_component: '{op:"create_component", screen, elements:[id...], component:{id, name?}, instanceId?} — 選んだ要素を部品にして、その場所に使用箇所を置く',
  detach_instance: '{op:"detach_instance", screen, element, group?} — 使用箇所を部品から切り離し、展開した要素（とグループ）に置き換える',
  rename_text_style: '{op:"rename_text_style", key, to} — 名前を変え、使っている要素も付け替える',
  remove_text_style: '{op:"remove_text_style", key, detach?} — 削除（使用中は detach:true で値を要素に移してから外す）',
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
      // 要素は1つずつ足しながら正規化する（id の重複は、すでに足した分とだけ比べる）
      const srcElements = s.elements || [];
      s.elements = [];
      for (const e of srcElements) s.elements.push(normalizeElement(s, e));
      s.groups = (s.groups || []).map(normalizeGroup);
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
      for (const s of containers(p)) {
        if (s.background === `@${op.key}`) s.background = `@${op.to}`;
        for (const e of s.elements) for (const k of COLOR_PROPS) if (e[k] === `@${op.key}`) e[k] = `@${op.to}`;
      }
      for (const st of Object.values(p.textStyles || {})) for (const k of ['color', 'shadowColor']) if (st[k] === `@${op.key}`) st[k] = `@${op.to}`;
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
      p.assets[op.asset.id] = { slice: { l: 0, t: 0, r: 0, b: 0 }, pixelScale: null, ...structuredClone(op.asset) };
      return;
    }
    case 'set_asset': {
      const a = p.assets[op.asset];
      if (!a) throw new OpError(`素材 ${op.asset} がありません`);
      for (const [k, v] of Object.entries(op.values || {})) {
        if (!['name', 'slice', 'pixelScale'].includes(k)) throw new OpError(`素材の ${k} は変更できません`);
        a[k] = structuredClone(v);
      }
      return;
    }
    case 'replace_asset': {
      const from = p.assets[op.asset], to = p.assets[op.with];
      if (!from || !to) throw new OpError('差し替える素材がありません');
      // 分割位置・画素の大きさは、新しい素材に入っていなければ引き継ぐ（大きさが違えば内側に収まる場合だけ）
      if (!to.slice || ['l', 't', 'r', 'b'].every((k) => !to.slice[k])) { if (validSlice(from.slice, to)) to.slice = structuredClone(from.slice); }
      to.pixelScale ??= from.pixelScale ?? null;
      for (const s of containers(p)) for (const e of s.elements) {
        if (e.asset === op.asset) e.asset = op.with;
        if (e.image === op.asset) e.image = op.with;
      }
      for (const slot of SKIN_SLOTS) if (p.skin?.[slot] === op.asset) p.skin[slot] = op.with;
      return;
    }
    case 'patch_elements': {
      const s = findScreen(p, op.screen);
      if (!Array.isArray(op.patches) || !op.patches.length) throw new OpError('patches は1件以上の配列');
      for (const pt of op.patches) {
        const e = findElement(s, pt.element);
        for (const [k, v] of Object.entries(pt.values || {})) {
          if (k === 'id' || k === 'type') throw new OpError(`${k} は patch で変更できません`);
          setPath(e, k, v);
        }
      }
      return;
    }
    case 'add_elements': {
      const s = findScreen(p, op.screen);
      s.groups ??= [];
      for (const g of op.groups || []) {
        if (!ID_RE.test(g?.id || '')) throw new OpError(`グループの id ${g?.id} が不正です`);
        if (findGroup(s, g.id)) throw new OpError(`グループ ${g.id} は既にあります`);
        s.groups.push(normalizeGroup(g));
      }
      const added = [];
      for (const src of op.elements || []) added.push(normalizeElement({ elements: [...s.elements, ...added] }, src));
      const at = op.index == null ? s.elements.length : Math.max(0, Math.min(s.elements.length, op.index));
      s.elements.splice(at, 0, ...added);
      return;
    }
    case 'remove_elements': {
      const s = findScreen(p, op.screen);
      const ids = new Set(op.elements || []);
      for (const id of ids) findElement(s, id);
      s.elements = s.elements.filter((e) => !ids.has(e.id));
      return;
    }
    case 'move_elements': {
      const s = findScreen(p, op.screen);
      const ids = new Set(op.elements || []);
      for (const id of ids) findElement(s, id);
      const moving = s.elements.filter((e) => ids.has(e.id));
      const rest = s.elements.filter((e) => !ids.has(e.id));
      const to = Math.max(0, Math.min(rest.length, op.index | 0));
      rest.splice(to, 0, ...moving);
      s.elements = rest;
      return;
    }
    case 'group_elements': {
      const s = findScreen(p, op.screen);
      s.groups ??= [];
      const g = op.group || {};
      if (!ID_RE.test(g.id || '')) throw new OpError('グループの id は英小文字・数字・_');
      if (findGroup(s, g.id)) throw new OpError(`グループ ${g.id} は既にあります`);
      const els = (op.elements || []).map((id) => findElement(s, id));
      const subs = (op.groups || []).map((id) => { const x = findGroup(s, id); if (!x) throw new OpError(`グループ ${id} がありません`); return x; });
      if (!els.length && !subs.length) throw new OpError('まとめる要素がありません');
      // 新しいグループは、まとめるものの共通の親に入る（ばらばらなら一番外）
      const parents = new Set([...els.map((e) => e.group), ...subs.map((x) => x.parent)]);
      const parent = parents.size === 1 ? [...parents][0] : '';
      s.groups.push(normalizeGroup({ id: g.id, name: g.name ?? g.id, parent }));
      for (const e of els) e.group = g.id;
      for (const x of subs) x.parent = g.id;
      // 中身を重なり順で連続させる（一番前面にあったものの位置へ集める）
      const members = new Set(groupMembers(s, g.id).map((e) => e.id));
      const top = Math.max(...s.elements.map((e, i) => (members.has(e.id) ? i : -1)));
      const moving = s.elements.filter((e) => members.has(e.id));
      const before = s.elements.slice(0, top + 1).filter((e) => !members.has(e.id));
      const after = s.elements.slice(top + 1);
      s.elements = [...before, ...moving, ...after];
      return;
    }
    case 'ungroup': {
      const s = findScreen(p, op.screen);
      const g = findGroup(s, op.group);
      if (!g) throw new OpError(`グループ ${op.group} がありません`);
      for (const e of s.elements) if (e.group === g.id) e.group = g.parent || '';
      for (const x of s.groups) if (x.parent === g.id) x.parent = g.parent || '';
      s.groups = s.groups.filter((x) => x.id !== g.id);
      return;
    }
    case 'set_group': {
      const s = findScreen(p, op.screen);
      const g = findGroup(s, op.group);
      if (!g) throw new OpError(`グループ ${op.group} がありません`);
      for (const [k, v] of Object.entries(op.values || {})) {
        if (!['name', 'visible', 'locked', 'parent', 'state'].includes(k)) throw new OpError(`グループの ${k} は変更できません`);
        g[k] = v;
      }
      return;
    }
    case 'rename_state_key': {
      const s = findScreen(p, op.screen);
      if (!STATE_KEY_RE.test(op.to || '')) throw new OpError('状態の名前は英小文字・数字・_ . の1〜32文字');
      for (const x of [...s.elements, ...(s.groups || [])]) if (x.state) x.state = renameStateKeyIn(x.state, op.key, op.to);
      const d = s.stateDefaults || {};
      if (op.key in d) {
        if (op.to in d && op.to !== op.key) throw new OpError(`状態 ${op.to} の既定値がすでにあります`);
        const v = d[op.key];
        delete d[op.key];
        d[op.to] = v;
      }
      return;
    }
    case 'set_state_default': {
      const s = findScreen(p, op.screen);
      s.stateDefaults ??= {};
      if (!STATE_KEY_RE.test(op.key || '')) throw new OpError('状態の名前は英小文字・数字・_ . の1〜32文字');
      if (op.value == null) delete s.stateDefaults[op.key];
      else s.stateDefaults[op.key] = String(op.value);
      return;
    }
    case 'add_component': {
      const src = op.component || {};
      if (!ID_RE.test(src.id || '')) throw new OpError('部品の id は英小文字・数字・_');
      p.components ??= [];
      const existing = p.components.findIndex((x) => x.id === src.id);
      if (existing >= 0 && !op.replace) throw new OpError(`部品 ${src.id} は既にあります`);
      const c = { id: src.id, name: src.name ?? src.id, width: src.width ?? 72, height: src.height ?? 18, elements: [], groups: (src.groups || []).map(normalizeGroup), stateDefaults: structuredClone(src.stateDefaults || {}), props: structuredClone(src.props || []), variants: structuredClone(src.variants || ['normal']) };
      for (const e of src.elements || []) c.elements.push(normalizeElement(c, e));
      // replace: 同じ id の部品を置き換える（使用箇所はそのまま新しい定義になる）
      if (existing >= 0) p.components[existing] = c; else p.components.push(c);
      return;
    }
    case 'set_component': {
      const c = findComponent(p, op.component);
      if (!c) throw new OpError(`部品 ${op.component} がありません`);
      for (const [k, v] of Object.entries(op.values || {})) {
        if (!['name', 'width', 'height', 'props', 'variants', 'stateDefaults'].includes(k)) throw new OpError(`部品の ${k} は変更できません`);
        c[k] = structuredClone(v);
      }
      return;
    }
    case 'remove_component': {
      const i = (p.components || []).findIndex((c) => c.id === op.component);
      if (i < 0) throw new OpError(`部品 ${op.component} がありません`);
      const users = componentUsers(p, op.component);
      if (users.length) throw new OpError(`部品 ${op.component} は使用中です: ${users.slice(0, 5).map((u) => `${u.screen}/${u.id}`).join(', ')}`);
      p.components.splice(i, 1);
      return;
    }
    case 'create_component': {
      const s = findScreen(p, op.screen);
      const src = op.component || {};
      if (!ID_RE.test(src.id || '')) throw new OpError('部品の id は英小文字・数字・_');
      p.components ??= [];
      if (findComponent(p, src.id)) throw new OpError(`部品 ${src.id} は既にあります`);
      const ids = new Set(op.elements || []);
      const els = s.elements.filter((e) => ids.has(e.id));
      if (!els.length) throw new OpError('部品にする要素がありません');
      // 部品の上端は9px行にそろえる（中の文字・クリック領域が行からずれないように）
      const x0 = Math.min(...els.map((e) => e.x)), y0 = Math.floor(Math.min(...els.map((e) => e.y)) / LINE) * LINE;
      const x1 = Math.max(...els.map((e) => e.x + e.w)), y1 = Math.max(...els.map((e) => e.y + e.h));
      // 中のグループ（中身がすべて選ばれているもの）も部品へ移す
      const moveGroups = (s.groups || []).filter((g) => { const m = groupMembers(s, g.id); return m.length && m.every((e) => ids.has(e.id)); });
      const moved = new Set(moveGroups.map((g) => g.id));
      const parents = new Set(els.map((e) => (e.group && !moved.has(e.group) ? e.group : '')).concat(moveGroups.filter((g) => !moved.has(g.parent)).map((g) => g.parent || '')));
      const c = {
        id: src.id, name: src.name ?? src.id, width: x1 - x0, height: y1 - y0, stateDefaults: {}, props: [], variants: ['normal'],
        groups: moveGroups.map((g) => ({ ...g, parent: moved.has(g.parent) ? g.parent : '' })),
        elements: els.map((e) => ({ ...structuredClone(e), x: e.x - x0, y: e.y - y0, group: moved.has(e.group) ? e.group : '' })),
      };
      p.components.push(c);
      const top = Math.max(...s.elements.map((e, i) => (ids.has(e.id) ? i : -1)));
      const instId = op.instanceId || uniqueId(s.elements.filter((e) => !ids.has(e.id)).map((e) => e.id), src.id);
      const inst = normalizeElement({ elements: s.elements.filter((e) => !ids.has(e.id)) }, { type: 'instance', id: instId, name: c.name, component: c.id, x: x0, y: y0, w: c.width, h: c.height, group: parents.size === 1 ? [...parents][0] : '' });
      const before = s.elements.slice(0, top + 1).filter((e) => !ids.has(e.id));
      const after = s.elements.slice(top + 1);
      s.elements = [...before, inst, ...after];
      s.groups = (s.groups || []).filter((g) => !moved.has(g.id));
      return;
    }
    case 'detach_instance': {
      const s = findScreen(p, op.screen);
      const i = s.elements.findIndex((e) => e.id === op.element);
      if (i < 0 || s.elements[i].type !== 'instance') throw new OpError(`部品の使用箇所 ${op.element} がありません`);
      const inst = s.elements[i];
      const c = findComponent(p, inst.component);
      const errors = [];
      const flat = expandInstance(p, inst, errors);
      if (errors.length) throw new OpError(errors[0]);
      // 展開した要素をグループにまとめて置き換える（id は空いているものに付け直す）
      s.groups ??= [];
      const gid = op.group || uniqueId(s.groups.map((g) => g.id), inst.id);
      s.groups.push(normalizeGroup({ id: gid, name: inst.name || c?.name || gid, parent: inst.group || '' }));
      const used = s.elements.filter((e) => e !== inst).map((e) => e.id);
      const out = flat.map((e) => {
        const id = uniqueId(used, e.id.replace(/__/g, '_').slice(0, 40));
        used.push(id);
        return normalizeElement({ elements: [] }, { ...e, id, group: gid, binds: {}, constraints: { h: 'left', v: 'top' } });
      });
      s.elements.splice(i, 1, ...out);
      return;
    }
    case 'set_text_style': {
      if (!TOKEN_KEY_RE.test(op.key || '')) throw new OpError('文字スタイルの名前は英小文字・数字・_ . - の1〜48文字');
      p.textStyles ??= {};
      if (op.ifMissing && op.key in p.textStyles) return;
      const style = op.style || {};
      p.textStyles[op.key] = structuredClone(op.merge ? { ...(p.textStyles[op.key] || {}), ...style } : style);
      return;
    }
    case 'rename_text_style': {
      const t = p.textStyles || {};
      if (!(op.key in t)) throw new OpError(`文字スタイル ${op.key} がありません`);
      if (!TOKEN_KEY_RE.test(op.to || '') || op.to in t) throw new OpError(`新しい名前 ${op.to} は使えません`);
      t[op.to] = t[op.key];
      delete t[op.key];
      for (const s of containers(p)) for (const e of s.elements) if (e.textStyle === op.key) e.textStyle = op.to;
      return;
    }
    case 'remove_text_style': {
      const t = p.textStyles || {};
      if (!(op.key in t)) throw new OpError(`文字スタイル ${op.key} がありません`);
      const users = textStyleUsers(p, op.key);
      if (users.length && !op.detach) throw new OpError(`文字スタイル ${op.key} は使用中です: ${users.slice(0, 5).join(', ')}${users.length > 5 ? ` ほか${users.length - 5}件` : ''}`);
      // detach: 見た目を変えないよう、スタイルの値を要素に移してから外す
      for (const s of containers(p)) for (const e of s.elements) if (e.textStyle === op.key) { applyTextStyle(p, e); e.textStyle = ''; }
      delete t[op.key];
      return;
    }
    default:
      throw new OpError(`未知の op: ${op.op}`);
  }
}

function normalizeGroup(g) {
  return { id: g.id, name: typeof g.name === 'string' ? g.name : g.id, parent: g.parent || '', visible: g.visible !== false, locked: !!g.locked, state: g.state || '' };
}

function renameScreen(p, from, to) {
  if (!ID_RE.test(to || '')) throw new OpError('画面idは英小文字・数字・_');
  if (p.screens.some((s) => s.id === to)) throw new OpError(`画面 ${to} は既にあります`);
  findScreen(p, from).id = to;
  for (const s of containers(p)) {
    for (const e of s.elements) if (e.action?.type === 'screen' && e.action.value === from) e.action.value = to;
    for (const e of s.elements) if (e.type === 'instance') for (const [k, v] of Object.entries(e.props || {})) if (v?.type === 'screen' && v.value === from) e.props[k] = { ...v, value: to };
    for (const pr of s.props || []) if (pr.default?.type === 'screen' && pr.default.value === from) pr.default = { ...pr.default, value: to };
    for (const b of s.footer?.buttons || []) if (b.action?.type === 'screen' && b.action.value === from) b.action.value = to;
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
  for (const s of containers(p)) for (const e of s.elements) if (e.asset === assetId || e.image === assetId) out.push(`${s.id}/${e.id}`);
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
  for (const s of containers(next)) pruneGroups(s);
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
