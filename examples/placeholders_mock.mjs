// 中身が未定の画面の仮画面（準備中）を作る操作（ops）を出力する。
//   node examples/placeholders_mock.mjs [--replace] > ops.json && node cli.mjs batch ops.json
// 画面:
//   ranking           ランキング
//   profile           プロフィール（左タブ: プロフィール / プロフィール設定）
//   profile_settings  プロフィール設定（プロフィール画面のタブ）
//   help              遊び方
// 外枠・見出し・右上の✕は他の画面と共通。左下の「◀ メニュー」でトップメニューへ戻る。

import { createKit, themeOps, FRAME } from './ui_kit.mjs';
import { loadMeasure, componentOps } from './ui_kit.mjs';

const replace = process.argv.includes('--replace');
const { W, H, TOP } = FRAME;
const SCREENS = ['ranking', 'profile', 'profile_settings', 'help'];

const ops = [];
if (replace) for (const id of SCREENS) ops.push({ op: 'remove_screen', screen: id, ifExists: true });
ops.push(...themeOps());
const measure = await loadMeasure();
ops.push(...componentOps(measure)); // 共通の部品（ボタン・✕・見出しの帯など）と見出しの文字スタイル
let target = '';
const add = (element) => ops.push({ op: 'add_element', screen: target, element });
const row = (r) => TOP + r * 9;
const kit = createKit({ add, row, measure });
const { text, rect } = kit;
const heading = (id, x, y, t, w) => add({ type: 'text', id, x, y, w, h: 10, text: t, render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1, textStyle: 'heading' });
const screenAction = (value) => ({ type: 'screen', value, payload: '' });

function screen(id, title, head) {
  ops.push({ op: 'add_screen', screen: { id, title, showTitle: false, width: W, height: H, background: '@screen.bg', afterAction: 'none', footer: { type: 'none', columns: 1, exitAction: false, buttons: [] } } });
  target = id;
  heading('title_head', 2, 1, head, 260);
  kit.closeX(W - 13);
}

// 準備中の表示（x から幅 w の領域の中央）
function todo(x, w, note) {
  text('todo', x, row(9), w, '準備中', { align: 'center', bold: true, color: '@text.sub' });
  text('todo_note', x, row(11), w, note, { align: 'center', color: '@text.faint' });
}
const backToMenu = () => kit.pill('back_btn', 4, 20, 80, 'メニュー', false, screenAction('top_menu'), 'トップメニューに戻る', { icon: 'left' });

// ---- ランキング ----
screen('ranking', 'ランキング', 'RANKING');
rect('panel_bg', 0, TOP, W, H - TOP, '@panel.bg', '@panel.border', 1);
todo(0, W, 'コースごとの記録やランクアップの進行度（内容は後で決めます）');
backToMenu();

// ---- プロフィール（タブ: プロフィール / プロフィール設定） ----
const PANEL_W = 100, CX = PANEL_W + 8;
const PROFILE_TABS = [['profile', 'プロフィール'], ['profile_settings', 'プロフィール設定']];
for (const [id, label, note] of [
  ['profile', 'プロフィール', 'ランク・記録・実績など（内容は後で決めます）'],
  ['profile_settings', 'プロフィール設定', '公開範囲・表示内容など（内容は後で決めます）'],
]) {
  screen(id, label, 'PROFILE');
  rect('tabs_bg', 0, TOP, PANEL_W, H - TOP, '@panel.bg', '@panel.border', 1);
  PROFILE_TABS.forEach(([sid, tab], i) => kit.pill(`tab_${i}`, 4, 1 + i * 2, PANEL_W - 8, tab, sid === id, screenAction(sid), sid === id ? '表示中' : tab));
  rect('panel_bg', CX, TOP, W - CX, H - TOP, '@panel.bg', '@panel.border', 1);
  rect('sec_bar', CX + 1, row(0), W - CX - 2, 9, '@section.bg');
  text('sec', CX + 6, row(0), 200, label, { bold: true, color: '@section.text' });
  todo(CX, W - CX, note);
  backToMenu();
}

// ---- 遊び方 ----
screen('help', '遊び方', 'HOW TO PLAY');
rect('panel_bg', 0, TOP, W, H - TOP, '@panel.bg', '@panel.border', 1);
todo(0, W, '操作のコツ・リセット方法・ルールなど（内容は後で決めます）');
backToMenu();

process.stdout.write(JSON.stringify(ops, null, 1));
