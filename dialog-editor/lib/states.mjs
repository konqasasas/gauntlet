// 状態（プレイヤーごとに変わる見た目）。
//
// 要素に state を付けると、その要素は背景に焼き込まず、状態ごとの重ね絵として書き出す。
//   state: "ui_lang=auto"            … 値が auto のときだけ描く
//   state: "ui_lang!=auto"           … 値が auto 以外のときだけ描く
//   state: "tab=sound&muted=on"      … & でつなぐと、すべてを満たすときだけ描く
// グループにも state を付けられ、中の要素は自分とグループの条件をすべて満たすときだけ描く
// （model.mjs の resolveScreen が & でつないで要素の state にする）。
// 画面の stateDefaults は、プレビュー・データパック・検証に使う既定の値。
//
// 重ね絵は本文の各行で「描いて、元の位置に戻る」形で出し、文字部品の insertion に
// 目印（<namespace>:state:<条件>）を付ける。プラグインは条件に合わない部品を取り除くだけでよく、
// 取り除いても行の幅は変わらない（折り返し・中央寄せ・クリック判定に影響しない）。
// insertion は Dialog の中では何もしない標準の項目なので、そのままでも正しい JSON のまま。

export const STATE_RE = /^([a-z0-9_.]{1,32})(!?=)([A-Za-z0-9_.-]{1,32})$/;
export const STATE_KEY_RE = /^[a-z0-9_.]{1,32}$/;
export const STATE_VALUE_RE = /^[A-Za-z0-9_.-]{1,32}$/;

// 1つの条件
export function parseState(s) {
  const m = STATE_RE.exec(s || '');
  return m ? { key: m[1], negate: m[2] === '!=', value: m[3] } : null;
}

// & でつないだ条件の一覧。1つでも読めなければ null
export function parseConditions(s) {
  if (!s) return [];
  const out = String(s).split('&').map(parseState);
  return out.every(Boolean) ? out : null;
}

export const formatCondition = (c) => `${c.key}${c.negate ? '!=' : '='}${c.value}`;

export function stateMatches(s, values) {
  const cs = parseConditions(s);
  if (!cs || !cs.length) return false;
  return cs.every((c) => (c.negate ? values?.[c.key] !== c.value : values?.[c.key] === c.value));
}

// 条件をつなぐ（空は除き、同じ条件は1つに）
export function combineStates(...list) {
  const parts = [];
  for (const s of list) for (const p of String(s || '').split('&')) if (p && !parts.includes(p)) parts.push(p);
  return parts.join('&');
}

// 条件の中の状態の名前を付け替える
export function renameStateKeyIn(s, from, to) {
  const cs = parseConditions(s);
  if (!cs || !cs.length) return s;
  return cs.map((c) => formatCondition(c.key === from ? { ...c, key: to } : c)).join('&');
}

// 要素がこの値で見えるか（state なしは常に見える）
export function elementShown(e, values) { return !e.state || stateMatches(e.state, values); }

export const stateMarker = (ns, s) => `${ns}:state:${s}`;

// 全状態入りの Dialog JSON から、values に合う重ね絵だけを残す（insertion の目印は外す）。
// プラグインの Java 側と同じ規則。
export function selectStates(dialog, ns, values) {
  const prefix = `${ns}:state:`;
  const out = structuredClone(dialog);
  for (const body of out.body || []) {
    if (body.type !== 'minecraft:plain_message' || !Array.isArray(body.contents?.extra)) continue;
    body.contents.extra = body.contents.extra.filter((c) => {
      if (typeof c.insertion !== 'string' || !c.insertion.startsWith(prefix)) return true;
      const keep = stateMatches(c.insertion.slice(prefix.length), values);
      delete c.insertion;
      return keep;
    });
  }
  return out;
}

// 画面で使っている状態: { key: [値...] }（要素・グループの条件と既定値に出てくる値）
export function stateKeys(screen) {
  const keys = {};
  const add = (k, v) => { (keys[k] ||= []).includes(v) || keys[k].push(v); };
  for (const x of [...screen.elements, ...(screen.groups || [])]) for (const c of parseConditions(x.state) || []) add(c.key, c.value);
  for (const [k, v] of Object.entries(screen.stateDefaults || {})) add(k, v);
  return keys;
}
