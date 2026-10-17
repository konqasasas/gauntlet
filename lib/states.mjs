// 状態（プレイヤーごとに変わる見た目）。
//
// 要素に state を付けると、その要素は背景に焼き込まず、状態ごとの重ね絵として書き出す。
//   state: "ui_lang=auto"   … 値が auto のときだけ描く
//   state: "ui_lang!=auto"  … 値が auto 以外のときだけ描く
// 画面の stateDefaults は、プレビュー・データパック・検証に使う既定の値。
//
// 重ね絵は本文の各行で「描いて、元の位置に戻る」形で出し、文字部品の insertion に
// 目印（<namespace>:state:<条件>）を付ける。プラグインは条件に合わない部品を取り除くだけでよく、
// 取り除いても行の幅は変わらない（折り返し・中央寄せ・クリック判定に影響しない）。
// insertion は Dialog の中では何もしない標準の項目なので、そのままでも正しい JSON のまま。

export const STATE_RE = /^([a-z0-9_.]{1,32})(!?=)([A-Za-z0-9_.-]{1,32})$/;

export function parseState(s) {
  const m = STATE_RE.exec(s || '');
  return m ? { key: m[1], negate: m[2] === '!=', value: m[3] } : null;
}

export function stateMatches(s, values) {
  const p = parseState(s);
  if (!p) return false;
  const v = values?.[p.key];
  return p.negate ? v !== p.value : v === p.value;
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

// 画面で使っている状態: { key: [値...] }（条件に出てくる値）
export function stateKeys(screen) {
  const keys = {};
  for (const e of screen.elements) {
    const p = parseState(e.state);
    if (!p) continue;
    (keys[p.key] ||= []).includes(p.value) || keys[p.key].push(p.value);
  }
  return keys;
}
