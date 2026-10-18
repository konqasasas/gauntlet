// トップメニュー（とお知らせ）の静的な見本を作る操作（ops）を出力する。
//   node examples/top_menu_mock.mjs [--replace] > ops.json && node cli.mjs batch ops.json
// 画面:
//   top_menu  トップメニュー（ランクアップ・コース選択・ランキング・プロフィール・設定・遊び方・エモート・ロビーへ戻る）
//             外枠は全画面共通（FRAME）。重要度で大きさを変える: ランクアップ（大）、コース選択・ランキング（中）、
//             プロフィール・エモート（小）、設定・遊び方・ロビーへ戻るは下の小さなボタン
//   news      お知らせ一覧（右上のお知らせボタンから開く）
// 各画面へは show_dialog で移動（ranking / profile / help は仮画面、emote はエモート）。ランクアップ開始・ロビーへ戻るは custom でプラグインへ送る。
// プレイヤー情報・未読数・お知らせの中身は固定の見本（差し込み文字は未実装）。
// 色はテーマの色の名前で参照する（docs/theme.md）。

import { createKit, THEMES, themeOps, FRAME } from './ui_kit.mjs';
import { loadMeasure, componentOps } from './ui_kit.mjs';

const replace = process.argv.includes('--replace');
const TOP = FRAME.TOP;
const W = FRAME.W, H = FRAME.H;               // 全画面共通の外枠

const ops = [];
if (replace) for (const id of ['top_menu', 'news']) ops.push({ op: 'remove_screen', screen: id, ifExists: true });
ops.push(...themeOps());
const measure = await loadMeasure();
ops.push(...componentOps(measure)); // 共通の部品（ボタン・✕・見出しの帯など）と見出しの文字スタイル
let target = '';
const add = (element) => ops.push({ op: 'add_element', screen: target, element });
const row = (r) => TOP + r * 9;
const kit = createKit({ add, row, measure });
const { text, bevel, rect } = kit;
const heading = (id, x, y, t, w) => add({ type: 'text', id, x, y, w, h: 10, text: t, render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1, textStyle: 'heading' });
const custom = (value, payload) => ({ type: 'custom', value, payload: JSON.stringify(payload) });
const screenAction = (value) => ({ type: 'screen', value, payload: '' });
const hot = (id, x, y, w, h, action, tooltip) => add({ type: 'hotspot', id, x, y, w, h, tooltip, action });

function screen(id, title, w, h) {
  ops.push({ op: 'add_screen', screen: { id, title, showTitle: false, width: w, height: h, background: '@screen.bg', afterAction: 'none', footer: { type: 'none', columns: 1, exitAction: false, buttons: [] } } });
  target = id;
}

// 右上: （設定）・お知らせ（未読の印付き）・✕
function topRight(w, unread, withSettings = false) {
  const bx = w - 13 - 2 - 15;
  if (withSettings) {
    const sx = bx - 2 - 15;
    kit.iconButton('settings_btn', 'gear', sx, 0, 15, 11, 'light', screenAction('settings'), '設定（言語・表示・サウンドなど）');
  }
  kit.iconButton('news_btn', 'bell', bx, 0, 15, 11, 'light', screenAction('news'), unread ? `お知らせ（未読 ${unread} 件）` : 'お知らせ');
  if (unread) rect('news_badge', bx + 10, 0, 5, 5, '@button.danger.face', '@control.outline', 1);
  kit.closeX(w - 13);
}

// メニューのカード（アイコン欄＋名前1行）。大きさは重要度で変える
function card(id, x, y, w, h, label, icon, action, tooltip) {
  rect(`${id}_frame`, x, y, w, h, '@card.bg', '@card.border', 1);
  rect(`${id}_icon_bg`, x + 2, y + 2, w - 4, h - 12, '@panel.bg');
  text(`${id}_icon`, x, y + Math.floor((h - 9) / 18) * 9, w, icon, { align: 'center', bold: true, color: '@text.sub' });
  text(`${id}_t`, x, y + h - 9, w, label, { align: 'center', bold: true });
  hot(`${id}_hs`, x, y, w, h, action, tooltip);
}

// ---- トップメニュー ----
// 重要度: 1. ランクアップ（左の大きなカード） 2. コース選択・ランキング（右上の中サイズ）
//         3. プロフィール・エモート（右の小サイズ） 4. 設定・遊び方・ロビーへ戻る（下の小さなボタン）
screen('top_menu', 'メニュー', W, H);
heading('title_head', 2, 1, 'GAUNTLET', 200);
topRight(W, 3);

const BIG_W = 220, GAP = 6;
const RX = BIG_W + 8, RW = W - RX, HALF = Math.floor((RW - GAP) / 2);
const BODY_H = 171; // 下の帯（2行）を除いた高さ

// 1. ランクアップ（本編）: 進み具合もこのカードの中に
{
  const x = 0, y = TOP, w = BIG_W, h = BODY_H;
  rect('rankup_frame', x, y, w, h, '@card.bg', '@button.on.face', 2);
  rect('rankup_art', x + 2, y + 2, w - 4, 79, '@button.on.shadow');
  text('rankup_icon', x, y + 36, w, '▲', { align: 'center', bold: true, color: '@button.on.text' });
  add({ type: 'text', id: 'rankup_head', x: x + 8, y: y + 86, w: w - 16, h: 10, text: 'RANK UP', render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1, textStyle: 'heading' });
  text('rankup_rank', x + 8, row(11), 100, 'RANK 3', { bold: true, color: '@text.accent' });
  text('rankup_next', x + 8, row(11), w - 16, '次のステージ  3-4', { align: 'right' });
  rect('rankup_bar', x + 8, row(12) + 1, w - 16, 7, '@control.track', '@control.outline', 1);
  rect('rankup_bar_fill', x + 9, row(12) + 2, Math.round((w - 18) * 3 / 11), 5, '@control.on');
  text('rankup_bar_t', x + 8, row(13), w - 16, '3 / 11 クリア', { align: 'right', color: '@text.sub' });
  hot('rankup_hs', x, y, w, h, custom('dcg:rankup_start', {}), 'ランクアップ（本編）\nRANK 3 ・ 3-4 から挑戦');
  kit.pill('rankup_go', x + 8, 16, w - 16, '続きから挑戦', true, custom('dcg:rankup_start', {}), '3-4 から始めます', { icon: 'right', iconRight: true });
}

// 2. コース選択・ランキング（中サイズ）
card('to_courses', RX, TOP, HALF, 99, 'コース選択', '▦', screenAction('course_select'), 'コース選択\nランクアップ以外のコースを選んで遊ぶ');
card('to_ranking', RX + HALF + GAP, TOP, RW - HALF - GAP, 99, 'ランキング', '♛', screenAction('ranking'), 'ランキング\nコースごとの記録を見る');
// 3. プロフィール・エモート（小サイズ）
card('to_profile', RX, TOP + 108, HALF, 63, 'プロフィール', '☺', screenAction('profile'), 'プロフィール\n自分のランク・記録・実績');
card('to_emote', RX + HALF + GAP, TOP + 108, RW - HALF - GAP, 63, 'エモート', '♪', screenAction('emote'), 'エモート\n喜ぶ・手を振る・突き上げる など');

// 4. 下の帯: プレイヤー名・クリア数と、設定・遊び方・ロビーへ戻る
text('info_name', 2, row(20), 80, 'Steve', { bold: true });
text('info_clear', 60, row(20), 120, 'コース 12 / 30', { color: '@text.sub' });
kit.pill('to_settings', W - 64 - 64 - 90 - 8, 20, 64, '設定', false, screenAction('settings'), '言語・表示・サウンドなど');
kit.pill('to_help', W - 64 - 90 - 4, 20, 64, '遊び方', false, screenAction('help'), '操作のコツやルール');
kit.pill('to_lobby', W - 90, 20, 90, 'ロビーへ戻る', false, custom('dcg:lobby', {}), 'ロビーのスポーン地点へ移動します');

// ---- お知らせ ----
{
  const NW = W, NH = H;
  screen('news', 'お知らせ', NW, NH);
  heading('title_head', 2, 1, 'NEWS', 200);
  topRight(NW, 0);
  rect('panel_bg', 0, TOP, NW, NH - TOP, '@panel.bg', '@panel.border', 1);
  const items = [
    ['10/17', '新コース「天空回廊」を追加しました', true],
    ['10/15', 'ランク4のボスステージを公開しました', true],
    ['10/12', '設定に「コースの演出音」の音量を追加しました', true],
    ['10/01', 'クローズドαテストを開始しました', false],
  ];
  items.forEach(([date, title, unread], i) => {
    const r = 1 + i * 3;
    rect(`n${i}_bg`, 4, row(r) - 2, NW - 8, 22, '@card.bg', '@card.border', 1);
    if (unread) rect(`n${i}_dot`, 8, row(r) + 2, 5, 5, '@button.danger.face');
    text(`n${i}_date`, 16, row(r), 40, date, { color: '@text.sub' });
    text(`n${i}_title`, 52, row(r), NW - 60, title, { bold: unread });
    text(`n${i}_more`, 52, row(r + 1), NW - 60, '詳しくはクリック', { color: '@text.faint' });
    hot(`n${i}_hs`, 4, row(r), NW - 8, 18, custom('dcg:news_open', { id: i }), `${title}${unread ? '（未読）' : ''}`);
  });
  kit.pill('back_btn', 4, 20, 80, 'メニュー', false, screenAction('top_menu'), 'トップメニューに戻る', { icon: 'left' });
}

process.stdout.write(JSON.stringify(ops, null, 1));
