// コース選択画面（と検索ダイアログ）の静的な見本を作る操作（ops）を出力する。
//   node examples/course_select_mock.mjs [--replace] > ops.json && node cli.mjs batch ops.json
//   --replace: 既存の course_select / course_search を削除してから作り直す
// 本文の上に英語の見出し（5x5 Pixel）、左パネルの見出しはゲーム内フォントの太字＋背景色の帯。
// サムネイルは仮の色付き矩形。選択中のタブ・ページ番号・クリア済みの印は固定の見本（状態切り替えは未実装）。
// 寸法: 1080p・GUIスケール4（480×270）で、スクロールなしで収まる大きさ（フッターなしなら本文の高さは約224pxまで）。
// ゲーム内フォントの文字は9px行に置かれるので、1行のボタンは「行の1px上から13px」の背景にすると上下中央に見える。
// 検索欄は本文の中に置けないため、左パネルの「検索」から別の小さなダイアログ（course_search）を開く。

import { createKit, themeOps, FRAME } from './ui_kit.mjs';
import { loadMeasure } from './ui_kit.mjs';

const replace = process.argv.includes('--replace');
const TOP = FRAME.TOP;     // 本文上部の見出しの高さ（2行）
const W = FRAME.W, H = FRAME.H; // 全画面共通の外枠
const PANEL_W = 100;
const GRID_X = 108, CARD_W = 64, CARD_H = 54, THUMB_H = 45, GAP_X = 6, ROW_STEP = 63, COLS = 5;
const GRID_W = COLS * CARD_W + (COLS - 1) * GAP_X;
const PAGER_Y = TOP + 189;

const courses = [
  ['はじめの一歩', 1, '#6aa84fff', true], ['ブロックの橋', 1, '#8e7cc3ff', true], ['水流ジャンプ', 1, '#3d85c6ff', true],
  ['落下注意', 2, '#e69138ff', true], ['氷の回廊', 2, '#9fc5e8ff', false], ['スライム工場', 2, '#93c47dff', true],
  ['風の通り道', 2, '#d9ead3ff', false], ['黒曜石の塔', 3, '#4c3a6bff', false], ['溶岩渡り', 3, '#cc4125ff', false],
  ['迷いの森', 3, '#38761dff', false], ['エリトラ谷', 3, '#76a5afff', false], ['鉄の階段', 4, '#999999ff', false],
  ['夜明けの崖', 4, '#f6b26bff', false], ['雪原ダッシュ', 4, '#cfe2f3ff', false], ['天空回廊', 5, '#674ea7ff', false],
];

const FAVORITES = new Set([1, 4, 8]); // 見本: お気に入り登録済みのカード（0始まり）

const ops = [];
if (replace) ops.push({ op: 'remove_screen', screen: 'course_select', ifExists: true }, { op: 'remove_screen', screen: 'course_search', ifExists: true });
ops.push(...themeOps()); // テーマの色（既にある色は上書きしない）
let target = 'course_select';
const add = (element) => ops.push({ op: 'add_element', screen: target, element });
const text = (id, x, y, w, t, over = {}) => add({ type: 'text', id, x, y, w, h: 9, text: t, render: 'game', color: '@text.main', ...over });
const heading = (id, x, y, t, w = 92) => add({ type: 'text', id, x, y, w, h: 10, text: t, render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1 });
const hot = (id, x, y, w, h, action, tooltip) => add({ type: 'hotspot', id, x, y, w, h, tooltip, action });
const custom = (value, payload) => ({ type: 'custom', value, payload: JSON.stringify(payload) });

ops.push({
  op: 'add_screen',
  screen: {
    id: 'course_select', title: 'コース選択', showTitle: false, width: W, height: H, background: '@screen.bg', afterAction: 'none',
    // フッターなし（dialog_list・exit_action なし）。閉じるのは右上の×か Esc
    footer: { type: 'none', columns: 1, exitAction: false, buttons: [] },
  },
});

const row = (r) => TOP + r * 9;
const kit = createKit({ add, row, measure: await loadMeasure() }); // 立体ボタン（設定画面と共通）

// ---- 見出し（本文の上）と閉じるボタン（右上） ----
heading('title_head', 2, 1, 'COURSE SELECT', 300);
kit.closeX(W - 13);

// ---- 左パネル（見出しは太字＋背景色の帯） ----
const section = (id, r, label) => {
  add({ type: 'rect', id: `${id}_bar`, x: 1, y: row(r), w: PANEL_W - 2, h: 9, fill: '@section.bg', border: '#00000000', borderWidth: 0 });
  text(id, 6, row(r), 88, label, { bold: true, color: '@section.text' });
};
add({ type: 'rect', id: 'panel_bg', x: 0, y: TOP, w: PANEL_W, h: H - TOP, fill: '@panel.bg', border: '@panel.border', borderWidth: 1 });
section('sort_head', 0, '並べ替え');
kit.stack(4, 2, PANEL_W - 8, [['sort_diff', '難易度順', true], ['sort_new', '新着順', false], ['sort_name', '名前順', false]]
  .map(([id, label, on]) => ({ id, label, on, action: custom('dcg:course_sort', { by: id.slice(5) }), tooltip: `${label}に並べ替え` })));
section('filter_head', 6, '絞り込み');
kit.stack(4, 8, PANEL_W - 8, [['f_all', 'すべて', true], ['f_fav', 'お気に入り', false, 'heart'], ['f_open', '未クリア', false], ['f_done', 'クリア済み', false], ['f_easy', '★1〜2', false], ['f_hard', '★3〜5', false]]
  .map(([id, label, on, icon]) => ({ id, label, on, icon, iconColor: icon ? '@fav.on.bg' : undefined, action: custom('dcg:course_filter', { filter: id.slice(2) }), tooltip: `${label}だけ表示` })));
section('search_head', 15, '検索');
kit.pill('search_btn', 4, 17, PANEL_W - 8, 'コース名で検索…', false, { type: 'screen', value: 'course_search', payload: '' }, '検索ダイアログを開きます', { left: true });
text('search_now', 8, row(19), 70, '条件: なし', { color: '@text.sub' });
kit.pill('search_clear', PANEL_W - 17, 19, 13, '', false, custom('dcg:course_search_clear', {}), '検索条件を消す', { icon: 'close' });
text('summary', 6, row(21), 88, 'クリア 5 / 30', { color: '@text.accent' });

// ---- グリッド（1ページ目の15枚） ----
courses.forEach(([name, stars, color, cleared], i) => {
  const x = GRID_X + (i % COLS) * (CARD_W + GAP_X);
  const y = TOP + Math.floor(i / COLS) * ROW_STEP;
  const id = `c${String(i + 1).padStart(2, '0')}`;
  add({ type: 'rect', id: `${id}_frame`, x, y, w: CARD_W, h: CARD_H, fill: '@card.bg', border: '@card.border', borderWidth: 1 });
  add({ type: 'rect', id: `${id}_thumb`, x: x + 1, y: y + 1, w: CARD_W - 2, h: THUMB_H - 1, fill: color, border: '#00000000', borderWidth: 0 });
  if (cleared) {
    add({ type: 'rect', id: `${id}_badge`, x: x + CARD_W - 14, y: y + 2, w: 12, h: 9, fill: '@badge.clear.bg', border: '@badge.clear.border', borderWidth: 1 });
    kit.iconIn(`${id}_check`, 'check', { x: x + CARD_W - 14, y: y + 2, w: 12, h: 9 }, '@text.main');
  }
  text(`${id}_title`, x, y + THUMB_H, CARD_W, name, { align: 'center' });
  hot(`${id}_hs`, x, y, CARD_W, CARD_H, custom('dcg:course_start', { course: id }),
    `${name}\n難易度 ${'★'.repeat(stars)}${'☆'.repeat(5 - stars)}\n${cleared ? 'ベスト 0:28.41' : '未クリア'}\nクリックで開始`);
  // お気に入り（左上）。カードのクリック領域より前面に置き、こちらのクリックを優先する
  const fav = FAVORITES.has(i);
  add({ type: 'rect', id: `${id}_fav_bg`, x: x + 2, y: y + 2, w: 11, h: 10, fill: fav ? '@fav.on.bg' : '@fav.off.bg', border: fav ? '@fav.on.border' : '@fav.off.border', borderWidth: 1 });
  kit.iconIn(`${id}_fav`, fav ? 'heart' : 'heartOutline', { x: x + 2, y: y + 2, w: 11, h: 10 }, fav ? '@fav.on.icon' : '@fav.off.icon');
  hot(`${id}_fav_hs`, x, y, 14, 9, custom('dcg:course_favorite', { course: id, on: !fav }), fav ? 'お気に入りから外す' : 'お気に入りに登録');
});

// ---- ページ送り（カードの下、中央） ----
const cx = GRID_X + GRID_W / 2;
const pr = (PAGER_Y - TOP) / 9;
kit.pill('pager_prev', cx - 45, pr, 15, '', false, custom('dcg:course_page', { delta: -1 }), '前のページ（なし）', { compact: true, icon: 'left' });
text('pager_label', cx - 28, PAGER_Y, 56, '1 / 2', { align: 'center' });
kit.pill('pager_next', cx + 30, pr, 15, '', false, custom('dcg:course_page', { delta: 1 }), '次のページ', { compact: true, icon: 'right' });

// ---- 検索ダイアログ ----
ops.push({
  op: 'add_screen',
  screen: {
    id: 'course_search', title: 'コース検索', width: 200, height: 18, background: '#00000000', afterAction: 'none',
    footer: {
      type: 'multi_action', columns: 2, exitAction: false,
      buttons: [
        { label: '検索', width: 98, tooltip: '入力した文字でコースを絞り込みます', action: { type: 'submit', value: 'dcg:course_search', payload: '' } },
        { label: '戻る', width: 98, tooltip: '', action: { type: 'screen', value: 'course_select', payload: '' } },
      ],
    },
    inputs: [{ type: 'text', key: 'query', label: 'コース名', width: 200, initial: '', maxLength: 32, multiline: false }],
  },
});
target = 'course_search';
text('hint', 0, 0, 200, 'コース名の一部を入力してください', { align: 'center', color: '@text.body' });
text('hint2', 0, 9, 200, '（空欄で検索すると条件を解除）', { align: 'center', color: '@text.sub' });

process.stdout.write(JSON.stringify(ops, null, 1));
