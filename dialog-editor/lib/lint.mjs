// 警告の形と、その場で分かる点検。
//
// 警告は { severity, kind, element, message, box } の形で持つ。
//   severity: 'error'（書き出せない） | 'warn'（注意）
//   element:  画面の要素の id（部品の使用箇所の中なら、展開後の id「使用箇所__中の id」）。画面全体のものは null
//   box:      画面上の範囲 { x, y, w, h }（GUI px）。印を出す場所。なければ null
// CLI・書き出しの報告は issueText() で今までと同じ「id: 内容」の文にする。
//
// lintScreen() は resolveScreen の結果だけで分かる点検（9px行・クリック範囲・はみ出し・状態の既定値など）。
// ブラウザーでは描くたびに（ドラッグ中も）呼び、書き出しの検証（export.mjs の buildScreen）でも同じものを使う。
import { LINE, clickRows, resolveScreen } from './model.mjs';
import { stateKeys } from './states.mjs';

export const issue = (severity, kind, element, message, box = null, extra = {}) => ({ severity, kind, element: element ?? null, message, box, ...extra });
export const issueText = (i) => (i.element ? `${i.element}: ${i.message}` : i.message);
const boxOf = (e) => (e ? { x: e.x, y: e.y, w: e.w, h: e.h } : null);
const clickable = (e) => e.type === 'button' || e.type === 'hotspot';

/**
 * 画面の要素の id（展開後の id でもよい）を、画面に直接置いた要素の id にする（キャンバスで選ぶ単位）。
 * 部品の使用箇所の中の要素は、使用箇所の id になる。見つからなければ null。
 */
export function ownerId(rawScreen, id) {
  if (!id) return null;
  const els = rawScreen.elements || [];
  if (els.some((e) => e.id === id)) return id;
  const hit = els.filter((e) => id.startsWith(`${e.id}__`)).sort((a, b) => b.id.length - a.id.length)[0];
  return hit ? hit.id : null;
}

/**
 * 書き出しの処理が出す文（「id: 内容」「行N: 内容」）を警告の形にする。
 * id が画面の要素なら element と box を付け、行の話なら box をその行にする。
 */
export function issueFromText(severity, kind, resolved, text) {
  const s = String(text);
  const m = /^([A-Za-z0-9_.-]+): ([\s\S]*)$/.exec(s);
  if (m) {
    const el = resolved.elements.find((e) => e.id === m[1]);
    if (el) return issue(severity, kind, el.id, m[2], boxOf(el));
  }
  const r = /^(?:全状態入り: )?行(\d+)[:：]/.exec(s);
  if (r) return issue(severity, kind, null, s, { x: 0, y: Number(r[1]) * LINE, w: resolved.width, h: LINE });
  return issue(severity, kind, null, s, null);
}

/**
 * その場で分かる点検。rawScreen は画面（または部品のマスター）のデータ、resolved を渡せば resolveScreen を省く。
 * opts.namespace: 「閉じる」の注意の文に使う。opts.component: 部品のマスター（動作・状態の既定値は見ない）
 */
export function lintScreen(project, rawScreen, opts = {}) {
  const screen = opts.resolved || resolveScreen(project, rawScreen);
  const ns = opts.namespace || project?.namespace || 'dcg';
  const out = [];
  const isMaster = !!opts.component;
  for (const e of screen.elements) {
    if (!e.visible) continue;
    const box = boxOf(e);
    if (e.x < 0 || e.y < 0 || e.x + e.w > screen.width || e.y + e.h > screen.height) out.push(issue('warn', 'outside', e.id, '画面の外にはみ出しています（はみ出し部分は描かれません）', box));
    if (e.type === 'text' && e.render === 'game' && (e.y % LINE || e.h < LINE)) {
      out.push(issue('error', 'row', e.id, e.y % LINE ? `ゲーム内フォントの文字が9px行からずれています（y ${e.y}。9の倍数にしてください）` : 'ゲーム内フォントの文字は高さを9以上にしてください（9px行に置かれます）', box));
    }
    if (clickable(e)) {
      // クリック領域は9px行単位。見た目との差が上下どちらかで2pxを超えるときだけ知らせる
      const rows = clickRows(e.y, e.h, screen.height);
      if (!rows.length) out.push(issue('warn', 'click', e.id, '高さが足りず、クリック領域ができません（9px行の半分以上に重なる必要があります）', box));
      else if (Math.abs(rows[0] * LINE - e.y) > 2 || Math.abs((rows[rows.length - 1] + 1) * LINE - (e.y + e.h)) > 2) {
        out.push(issue('warn', 'click', e.id, `クリック領域（y ${rows[0] * LINE}〜${(rows[rows.length - 1] + 1) * LINE}）が見た目とずれています。9px行に合わせてください`, box));
      }
      // 部品のマスターでは、動作は使用箇所で決めるので見ない
      if (!isMaster) {
        // after_action=close の画面では、本文のどのクリックでもクライアントが閉じるのでプラグインは不要
        if (e.action.type === 'close' && screen.afterAction !== 'close') out.push(issue('warn', 'plugin', e.id, `本文内の「閉じる」はプラグイン（custom ${ns}:close）が必要です（クリック後の動作が「閉じる」以外のため）`, box));
        if (e.action.type === 'none' && !e.tooltip) out.push(issue('warn', 'action', e.id, 'アクションもツールチップもありません', box));
      }
    }
    if (e.type === 'button' && e.labelRender === 'game' && e.label) {
      const row = Math.max(0, Math.round((e.y + e.h / 2 - LINE / 2) / LINE));
      const d = Math.abs(row * LINE + LINE / 2 - (e.y + e.h / 2));
      if (d > 2) out.push(issue('warn', 'row', e.id, `ラベルは9px行に置かれるため、上下の中央から ${d.toFixed(1)}px ずれます`, box));
    }
  }
  if (!isMaster) {
    const defaults = screen.stateDefaults || {};
    for (const key of Object.keys(stateKeys(screen))) {
      if (key in defaults) continue;
      const users = screen.elements.filter((e) => e.visible && e.state && e.state.split('&').some((c) => c.startsWith(`${key}=`) || c.startsWith(`${key}!=`)));
      const b = users.length ? users.reduce((a, e) => ({ x: Math.min(a.x, e.x), y: Math.min(a.y, e.y), r: Math.max(a.r, e.x + e.w), b: Math.max(a.b, e.y + e.h) }), { x: Infinity, y: Infinity, r: -Infinity, b: -Infinity }) : null;
      out.push(issue('error', 'state-default', users[0]?.id ?? null, `状態 ${key} の既定値がありません（画面の stateDefaults）`, b ? { x: b.x, y: b.y, w: b.r - b.x, h: b.b - b.y } : null, { stateKey: key }));
    }
    if (screen.footer?.type === 'none' && !screen.canCloseWithEscape && !screen.elements.some((e) => e.visible && clickable(e) && e.action.type === 'close')) {
      out.push(issue('error', 'no-close', null, 'フッターなしで Esc でも閉じられず、本文に「閉じる」もないため、画面を閉じる手段がありません'));
    }
  }
  // live: エディターが描くたびに求め直せる指摘（サーバーの結果のうちこれが付いたものは、手元の結果で置き換える）
  return out.map((i) => ({ ...i, live: true }));
}

// 状態の値の組み合わせ: 既定値、各状態の各値を1つずつ（ほかは既定値）、「ほかの値」（_other）
export function stateCombos(resolved) {
  const keys = stateKeys(resolved);
  const base = { ...(resolved.stateDefaults || {}) };
  const out = [base];
  for (const [k, vs] of Object.entries(keys)) for (const v of [...vs, '_other']) if (base[k] !== v) out.push({ ...base, [k]: v });
  return out;
}

export const describeValues = (values, base) => Object.entries(values).filter(([k, v]) => base[k] !== v).map(([k, v]) => `${k}=${v === '_other' ? '（ほかの値）' : v}`).join(', ');
