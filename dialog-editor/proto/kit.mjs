// 試作メニューのスクリプト（build-parts.mjs・build-screens.mjs）で共通に使うもの。
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const i = args.indexOf('--url');
export const BASE = (i >= 0 ? args[i + 1] : process.env.DCG_URL || 'http://127.0.0.1:4320').replace(/\/$/, '');
export const ART = path.join(import.meta.dirname, 'art');
// --live: 本番のメニューとして作る（画面名に proto_ を付けず、題の「試作: 」も外す。プラグインの /menu は home を開く）
export const LIVE = args.includes('--live');
export const S = (name) => (LIVE ? name : `proto_${name}`);

export async function api(method, url, body, headers = {}) {
  const res = await fetch(BASE + url, { method, headers, body });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url}: ${res.status} ${text}`);
  return JSON.parse(text);
}
export async function ops(operations) {
  const { revision } = await api('GET', '/api/project');
  return api('POST', '/api/ops', JSON.stringify({ revision, operations }), { 'Content-Type': 'application/json' });
}

// proto/art の絵を素材として登録し（同じ絵なら同じ ID）、名前と9分割の位置を付ける。{ 絵の名前: 素材ID } を返す
export async function uploadArt() {
  const art = JSON.parse(fs.readFileSync(path.join(ART, 'art.json'), 'utf8'));
  const A = {}, assetOps = [];
  for (const a of art) {
    const up = await api('POST', '/api/assets', fs.readFileSync(path.join(ART, `${a.name}.png`)), { 'Content-Type': 'image/png', 'X-Filename': `${a.name}.png` });
    A[a.name] = up.asset.id;
    assetOps.push({ op: 'set_asset', asset: up.asset.id, values: { name: `proto/${a.name}`, slice: a.slice || { l: 0, t: 0, r: 0, b: 0 } } });
  }
  await ops(assetOps);
  return A;
}

// ---- 要素を作る小さな関数 ----
export const C = { h: 'left', v: 'top' };
export const fixed = C;
export const img = (id, asset, x, y, w, h, extra = {}) => ({ id, type: 'image', x, y, w, h, asset, constraints: C, ...extra });
export const nine = (id, asset, x, y, w, h, extra = {}) => img(id, asset, x, y, w, h, { fit: 'nine', nineFill: 'stretch', constraints: { h: 'stretch', v: 'top' }, ...extra });
export const txt = (id, text, x, y, w, color, extra = {}) => ({ id, type: 'text', x, y, w, h: 9, text, render: 'game', color, align: 'left', constraints: C, ...extra });
export const hot = (id, x, y, w, h, extra = {}) => ({ id, type: 'hotspot', x, y, w, h, binds: { action: 'action', tooltip: 'tooltip' }, constraints: { h: 'stretch', v: 'top' }, ...extra });
export const rect = (id, x, y, w, h, fill, extra = {}) => ({ id, type: 'rect', x, y, w, h, fill, border: '#00000000', borderWidth: 0, constraints: C, ...extra });
export const inst = (id, component, x, y, w, h, variant, props = {}, extra = {}) => ({ id, type: 'instance', x, y, w, h, component, variant, props, constraints: C, ...extra });
export const act = (value, payload) => ({ type: 'custom', value, payload: JSON.stringify(payload) });
// 画面の移動はプラグインと同じく custom dcg:open で出す（show_dialog だとプレイヤーごとの状態を出せないため）
export const open = (screen) => act('dcg:open', { screen });
export const PROPS = (label) => [
  ...(label ? [{ name: 'label', type: 'text', default: label }] : []),
  { name: 'action', type: 'action', default: { type: 'none', value: '', payload: '' } },
  { name: 'tooltip', type: 'text', default: '' },
];

// 見た目の要素に状態の条件を足す（& でつなぐ）。
// クリック範囲は状態で変えられない（本文の空白の文字の列として固定で書き出す）ので、クリック範囲が変わるものは別の画面にする
export function when(cond, elements) {
  return elements.map((e) => {
    if (e.type === 'hotspot' || e.type === 'button') throw new Error(`${e.id}: クリック範囲には状態を付けられません（別の画面にする）`);
    return { ...e, state: e.state ? `${e.state}&${cond}` : cond };
  });
}

// ---- 台帳の画面の共通部分 ----
export const W = 452, H = 216;
export const TABS = [['ホーム', 'home', 'Home'], ['Passus', 'passus', 'Passus'], ['展示場', 'gallery', 'Gallery'], ['名簿', 'ledger', 'Ledger'], ['プロフィール', 'profile', 'Profile']];
export function ledgerBase(A, active, { unread = 3 } = {}) {
  return [
    img('frame', A.frame_ledger, 0, 0, W, H, { fit: 'nine', nineFill: 'tile' }),
    nine('paper', A.panel_paper, 4, 18, 444, 194, { nineFill: 'tile' }),
    ...TABS.map(([label, screen, en], k) =>
      inst(`tab_${k}`, 'ui_tab', 4 + k * 73, 0, 72, 20, `${k === active ? 'on' : 'off'}${isLatin(label) ? '_latin' : ''}`,
        { label: isLatin(label) ? label.toUpperCase() : label, label_en: en.toUpperCase(), tooltip: k === active ? '表示中' : `${label}を開く`, action: k === active ? { type: 'none', value: '', payload: '' } : open(S(screen)) })),
    inst('ic_bell', 'ui_icon', 394, 0, 16, 18, unread ? 'bell_unread' : 'bell', { tooltip: unread ? `お知らせ（未読 ${unread} 件）` : 'お知らせ', action: open(S('notices')) }),
    inst('ic_settings', 'ui_icon', 412, 0, 16, 18, 'settings', { tooltip: '設定', action: open(S('settings')) }),
    inst('ic_close', 'ui_icon', 430, 0, 16, 18, 'close', { tooltip: '閉じる（Esc）', action: act('dcg:close', {}) }),
  ];
}
// 見出し: 日本語の入る見出しは美咲ゴシック、英字だけの見出しは 5x5 Pixel の大文字で焼き込む
// （美咲ゴシックの英字は半角 4 ドットで、小文字の s が ſ のように見えるため）。焼き込みの文字は言語で変えられないので、label が { ja, en } なら
// 日本語版（lang≠en）と英語版（lang=en）を重ねて状態で出し分ける。note はゲーム内フォントの補足（見出しの右）
export const isLatin = (str) => [...str].every((ch) => ch.codePointAt(0) < 0x80);
const misakiWidth = (str) => [...str].reduce((w, ch) => w + (ch.codePointAt(0) < 0x80 ? 4 : 8), 0); // 半角 4・全角 8 ドット
const pixelWidth = (str) => [...str].reduce((w, ch) => w + (ch === ' ' ? 4 : 6), 0);           // 5x5 Pixel: 字 5＋間 1、空白 4
const headingText = (t) => (isLatin(t) ? { text: t.toUpperCase(), textStyle: 'heading_latin', width: pixelWidth(t) } : { text: t, textStyle: 'heading_ink', width: misakiWidth(t) });
// 焼き込みの見出し用の文字（ラベル）の書式。英字だけなら 5x5 Pixel の大文字、日本語が入るなら美咲ゴシック。色は自由
// 字の高さが違う（美咲 7 ドット・5x5 は 5 ドットで、5x5 は要素の上端より 1px 上から描かれる）ので、5x5 は 2px 下げて上下の中心をそろえる（LATIN_DY）
export const LATIN_DY = 2;
export const bakedFont = (latin) => latin
  ? { render: 'baked', fontFamily: '5x5 Pixel', fontSize: 8, pixel: true, wordSpacing: 3, shadow: false, lineHeight: 1 }
  : { render: 'baked', fontFamily: 'Misaki Gothic', fontSize: 8, pixel: true, wordSpacing: 0, shadow: false, lineHeight: 1 };
// 見出しの書体のラベル（日本語版と英語版を lang で出し分ける）
export function label(id, label, x, y, w, color, extra = {}) {
  const versions = typeof label === 'string' ? [['', label]] : [['lang!=en', label.ja], ['lang=en', label.en]];
  return versions.map(([state, t], k) => {
    const latin = isLatin(t);
    return { id: k ? `${id}_en` : id, type: 'text', x, y: y + (latin ? LATIN_DY : 0), w, h: 9, text: latin ? t.toUpperCase() : t, ...bakedFont(latin), color, align: 'left', constraints: C, ...extra,
      state: [extra.state, state].filter(Boolean).join('&') };
  });
}
export function heading(id, label, x, y, w, note) {
  const versions = typeof label === 'string' ? [['', label]] : [['lang!=en', label.ja], ['lang=en', label.en]];
  const lw = Math.max(...versions.map(([, t]) => headingText(t).width));
  return [
    ...versions.map(([state, t], k) => { const { text, textStyle } = headingText(t);
      return { id: k ? `${id}_en` : id, type: 'text', x, y: y + (isLatin(t) ? LATIN_DY : 0), w: Math.min(w, lw + 2), h: 9, text, textStyle, constraints: C, state }; }),
    ...(note ? [txt(`${id}_note`, note, x + lw + 8, y, w - lw - 8, '@ink.sub')] : []),
    rect(`${id}_rule`, x, y + 9, w, 1, '@ink.sub'),
  ];
}
export const screenBase = (id, title, elements, stateDefaults = {}) => ({
  id, title: LIVE ? title.replace(/^試作: /, '') : title, showTitle: false, width: W, height: H, background: '#00000000',
  canCloseWithEscape: true, pause: false, afterAction: 'none', footer: { type: 'none', columns: 1, buttons: [], exitAction: false },
  // lang は表示に使う言語（ja / en）。UI言語が「自動」のときはクライアントの言語で決めて、プラグインが渡す
  elements, groups: [], stateDefaults: { lang: 'ja', ...stateDefaults }, inputs: [],
});
