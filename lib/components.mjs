// 部品（コンポーネント）。Figma のコンポーネントと同じ考え方。
//
// 部品のマスターは project.components にあり、画面と同じく要素とグループを持つ:
//   { id, name, width, height, elements, groups, stateDefaults,
//     props: [{ name, type: 'text'|'action', default }],   // 使用箇所ごとに変えられる値（差し込み口）
//     variants: ['normal', 'selected', ...] }               // 見た目の種類（バリアント）
// 部品の中の要素:
//   binds:       { text: 'label', action: 'act', tooltip: 'tip' } … 要素の項目に差し込み口の値を入れる
//   constraints: { h: left|right|stretch|center|scale, v: top|bottom|stretch|center|scale }
//                … 使用箇所で大きさを変えたときの動き方
//   state:       'variant=selected' … そのバリアントのときだけ表示（ほかの状態と & でつなげる）
//
// 使用箇所は画面の要素（type: 'instance'）:
//   { component, x, y, w, h, variant, variantBy: { key, map: { 値: バリアント }, else: バリアント } | null,
//     props: { 名前: 値 }, state }
// variantBy を使うと、バリアントを画面の状態に結び付けられる（例: ui_lang が auto なら selected、ほかは normal）。
// 各バリアントの要素は、対応する状態の条件を付けて書き出す（≠ の見た目も作れる）。
//
// 書き出し・描画の前に expandElements で平らな要素に展開する（resolveScreen から呼ぶ）。
// ここは依存を states.mjs だけにする（ブラウザーと Node の両方で使う）。

import { parseConditions, combineStates, formatCondition } from './states.mjs';

export const COMPONENT_PREFIX = 'component:';
export const H_CONSTRAINTS = ['left', 'right', 'stretch', 'center', 'scale'];
export const V_CONSTRAINTS = ['top', 'bottom', 'stretch', 'center', 'scale'];
export const PROP_TYPES = ['text', 'action'];
// 差し込み口を結び付けられる要素の項目と、その種類
export const BIND_TARGETS = { text: 'text', label: 'text', tooltip: 'text', action: 'action' };
const MAX_DEPTH = 4;

export const isComponentRef = (id) => typeof id === 'string' && id.startsWith(COMPONENT_PREFIX);
export function findComponent(p, id) { return (p?.components || []).find((c) => c.id === id) || null; }

export function defaultComponent(id, over = {}) {
  return structuredClone({ id, name: id, width: 72, height: 18, elements: [], groups: [], stateDefaults: {}, props: [], variants: ['normal'], ...over });
}

// 部品の中の要素の、グループの状態・非表示も含めた条件と表示
function chainOf(c, gid) {
  const out = [];
  const seen = new Set();
  for (let g = (c.groups || []).find((x) => x.id === gid); g && !seen.has(g.id); g = (c.groups || []).find((x) => x.id === g.parent)) { seen.add(g.id); out.push(g); }
  return out;
}

// 使用箇所の大きさに合わせた、要素の位置・大きさ（部品の座標で）
export function constrain(ce, c, w, h) {
  const k = ce.constraints || {};
  const dx = w - c.width, dy = h - c.height;
  const axis = (pos, size, d, total, scale, mode) => {
    switch (mode) {
      case 'right': case 'bottom': return [pos + d, size];
      case 'stretch': return [pos, Math.max(1, size + d)];
      case 'center': return [pos + Math.round(d / 2), size];
      case 'scale': { const a = Math.round(pos * scale), b = Math.round((pos + size) * scale); return [a, Math.max(1, b - a)]; }
      default: return [pos, size];
    }
  };
  const [x, ew] = axis(ce.x, ce.w, dx, w, w / c.width, k.h);
  const [y, eh] = axis(ce.y, ce.h, dy, h, h / c.height, k.v);
  return { x, y, w: ew, h: eh };
}

// バリアントの条件（variant=x / variant!=x）を、使用箇所のバリアントの決め方に合わせて書き換える。
// 戻り値: { show: false }（このバリアントでは出さない）か { show: true, state: 書き出す条件 }
export function variantCondition(c, inst, cond) {
  const cs = parseConditions(cond) || [];
  const vcs = cs.filter((x) => x.key === 'variant');
  const rest = cs.filter((x) => x.key !== 'variant').map(formatCondition).join('&');
  if (!vcs.length) return { show: true, state: rest };
  const variants = c.variants?.length ? c.variants : ['normal'];
  const allowed = variants.filter((v) => vcs.every((x) => (x.negate ? v !== x.value : v === x.value)));
  const vb = inst.variantBy;
  if (!vb?.key) {
    const v = inst.variant || variants[0];
    return allowed.includes(v) ? { show: true, state: rest } : { show: false };
  }
  // 画面の状態の値 → バリアント（map にない値は else）
  const map = vb.map || {};
  const elseV = vb.else || variants[0];
  const keysTo = (pred) => Object.entries(map).filter(([, v]) => pred(v)).map(([u]) => u);
  let state;
  if (allowed.includes(elseV)) {
    // else を含む: 許されないバリアントに対応する値を除く
    state = keysTo((v) => !allowed.includes(v)).map((u) => `${vb.key}!=${u}`).join('&');
  } else {
    const us = keysTo((v) => allowed.includes(v));
    if (!us.length) return { show: false };
    if (us.length > 1) return { show: false, error: `バリアント ${allowed.join('/')} に ${vb.key} の値が複数（${us.join('/')}）割り当てられています。1つのバリアントには1つの値だけ割り当ててください（ほかは else に）` };
    state = `${vb.key}=${us[0]}`;
  }
  return { show: true, state: combineStates(state, rest) };
}

// 使用箇所を平らな要素に展開する（入れ子の使用箇所も）。errors に問題を足す
export function expandInstance(p, inst, errors = [], depth = 0) {
  const c = findComponent(p, inst.component);
  if (!c) { errors.push(`${inst.id}: 部品 ${inst.component} がありません`); return []; }
  if (depth > MAX_DEPTH) { errors.push(`${inst.id}: 部品の入れ子が深すぎます（循環していませんか）`); return []; }
  const out = [];
  const propDefault = Object.fromEntries((c.props || []).map((pr) => [pr.name, pr.default]));
  const hidden = new Set((c.groups || []).filter((g) => chainOf(c, g.id).some((x) => !x.visible)).map((g) => g.id));
  for (const ce of c.elements) {
    const chain = ce.group ? chainOf(c, ce.group) : [];
    const cond = combineStates(...chain.reverse().map((g) => g.state), ce.state);
    const v = variantCondition(c, inst, cond);
    if (v.error) errors.push(`${inst.id}: ${v.error}`);
    if (!v.show) continue;
    const e = structuredClone(ce);
    e.id = `${inst.id}__${ce.id}`;
    Object.assign(e, constrain(ce, c, inst.w, inst.h));
    e.x += inst.x; e.y += inst.y;
    for (const [target, prop] of Object.entries(ce.binds || {})) {
      const value = inst.props && prop in inst.props ? inst.props[prop] : propDefault[prop];
      if (value !== undefined && value !== null && target in e) e[target] = structuredClone(value);
    }
    e.group = inst.group || '';
    e.visible = inst.visible !== false && ce.visible !== false && !(ce.group && hidden.has(ce.group));
    e.locked = false;
    const st = combineStates(v.state, inst.state);
    if (e.type === 'instance') {
      // 入れ子: 外側の条件を内側に引き継いで展開する
      e.state = st;
      out.push(...expandInstance(p, e, errors, depth + 1));
      continue;
    }
    if ('state' in e) e.state = st;
    else if (st) errors.push(`${inst.id}: 状態（またはバリアントの結び付け）で出し分ける部品の中に、${e.type === 'hotspot' ? 'クリック領域' : 'ボタン'} ${ce.id} があります。クリック範囲は状態で変えられません`);
    out.push(e);
  }
  return out;
}

// 画面の要素の並びから、使用箇所を展開した並びを作る
export function expandElements(p, elements, errors = []) {
  if (!elements.some((e) => e.type === 'instance')) return elements;
  const out = [];
  for (const e of elements) {
    if (e.type === 'instance') out.push(...expandInstance(p, e, errors));
    else out.push(e);
  }
  return out;
}

// 部品が使われている場所
export function componentUsers(p, id) {
  const out = [];
  for (const s of [...(p.screens || []), ...(p.components || [])]) {
    for (const e of s.elements || []) if (e.type === 'instance' && e.component === id) out.push({ screen: p.screens.includes(s) ? s.id : `${COMPONENT_PREFIX}${s.id}`, id: e.id });
  }
  return out;
}
