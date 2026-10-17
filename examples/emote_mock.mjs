// エモート画面の静的な見本を作る操作（ops）を出力する。
//   node examples/emote_mock.mjs [--replace] > ops.json && node cli.mjs batch ops.json
// ポーズもエモートとして扱い、タグ（動き / ポーズ）で絞り込む。タグごとに画面を分け、左の一覧で切り替える（show_dialog、プラグイン不要）:
//   emote         すべて
//   emote_motion  動き（social_emotes）
//   emote_pose    ポーズ（photo_poses）
// 右はコース選択と同じ 64×54 のカード（5列×3段、15個/ページ）。未所持はロック表示。
// クリックでプラグインへ custom dcg:emote_play（BetterModel のモデル名とアニメーション名）を送る。
// 外枠・見出し・右上の✕は他の画面と共通。色はテーマの色の名前で参照する。

import { createKit, themeOps, FRAME } from './ui_kit.mjs';
import { loadMeasure } from './ui_kit.mjs';

const replace = process.argv.includes('--replace');
const { W, H, TOP } = FRAME;
const PANEL_W = 100;
const GRID_X = 108, CARD_W = 64, CARD_H = 54, ICON_H = 45, GAP_X = 6, ROW_STEP = 63, COLS = 5;
const GRID_W = COLS * CARD_W + (COLS - 1) * GAP_X;
const PAGER_Y = TOP + 189;

// エモート（2026-10-18 決定）。プラグインは /bettermodel play <model> <id> に当たる処理を行う。アイコンは仮
const TAGS = { motion: { label: '動き', model: 'social_emotes' }, pose: { label: 'ポーズ', model: 'photo_poses' } };
// 表示名はすべて動詞の形にそろえる（カードの幅に収まる7文字まで）
const EMOTES = [
  ['喜ぶ', 'motion', 'cheer'], ['怒る', 'motion', 'stomp'], ['落ち込む', 'motion', 'slump'], ['拍手する', 'motion', 'clap'], ['手を振る', 'motion', 'wave'],
  ['突き上げる', 'pose', 'fist_pump'], ['カメラに向ける', 'pose', 'point_block'], ['掲げる', 'pose', 'raise_block'],
];
const VIEWS = [['emote', 'すべて', null], ['emote_motion', '動き', 'motion'], ['emote_pose', 'ポーズ', 'pose']];
const OLD_SCREENS = ['emote_dance', 'emote_pair']; // 以前の見本で作った画面（--replace で消す）

const ops = [];
if (replace) for (const id of [...VIEWS.map(([id]) => id), ...OLD_SCREENS]) ops.push({ op: 'remove_screen', screen: id, ifExists: true });
ops.push(...themeOps());
let target = '';
const add = (element) => ops.push({ op: 'add_element', screen: target, element });
const row = (r) => TOP + r * 9;
const kit = createKit({ add, row, measure: await loadMeasure() });
const { text, rect } = kit;
const heading = (id, x, y, t, w) => add({ type: 'text', id, x, y, w, h: 10, text: t, render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1 });
const custom = (value, payload) => ({ type: 'custom', value, payload: JSON.stringify(payload) });
const screenAction = (value) => ({ type: 'screen', value, payload: '' });
const hot = (id, x, y, w, h, action, tooltip) => add({ type: 'hotspot', id, x, y, w, h, tooltip, action });

// 枠・見出し・✕・左のタグ一覧（コース選択の絞り込みと同じ形）
function frame(id, title) {
  ops.push({ op: 'add_screen', screen: { id, title, showTitle: false, width: W, height: H, background: '@screen.bg', afterAction: 'none', footer: { type: 'none', columns: 1, exitAction: false, buttons: [] } } });
  target = id;
  heading('title_head', 2, 1, 'EMOTE', 200);
  kit.closeX(W - 13);
  rect('panel_bg', 0, TOP, PANEL_W, H - TOP, '@panel.bg', '@panel.border', 1);
  rect('tag_head_bar', 1, row(0), PANEL_W - 2, 9, '@section.bg');
  text('tag_head', 6, row(0), 88, 'タグ', { bold: true, color: '@section.text' });
  kit.stack(4, 2, PANEL_W - 8, VIEWS.map(([sid, label]) => ({ id: `tag_${sid}`, label, on: sid === id, action: screenAction(sid), tooltip: sid === id ? '表示中' : `${label}を表示` })));
  kit.pill('back_btn', 4, 20, PANEL_W - 8, 'メニュー', false, screenAction('top_menu'), 'トップメニューに戻る', { icon: 'left' });
}

// カードのグリッド（未所持は暗くしてロック）。items: [名前, タグ, アニメーション名, 所持]
function grid(prefix, items) {
  items.forEach(([name, tag, anim, owned = true], i) => {
    const x = GRID_X + (i % COLS) * (CARD_W + GAP_X), y = TOP + Math.floor(i / COLS) * ROW_STEP;
    const id = `${prefix}_${String(i + 1).padStart(2, '0')}`;
    rect(`${id}_frame`, x, y, CARD_W, CARD_H, '@card.bg', '@card.border', 1);
    rect(`${id}_icon_bg`, x + 1, y + 1, CARD_W - 2, ICON_H - 1, owned ? '@panel.bg' : '@screen.bg');
    if (owned) text(`${id}_icon`, x, y + 18, CARD_W, '?', { align: 'center', bold: true, color: '@text.main' });
    else kit.iconIn(`${id}_lock`, 'lock', { x, y: y + 1, w: CARD_W, h: ICON_H - 1 }, '@text.faint');
    text(`${id}_t`, x, y + ICON_H, CARD_W, name, { align: 'center', color: owned ? '@text.main' : '@text.faint' });
    hot(`${id}_hs`, x, y, CARD_W, CARD_H,
      owned ? custom('dcg:emote_play', { model: TAGS[tag].model, id: anim }) : { type: 'none', value: '', payload: '' },
      owned ? `${name}（${TAGS[tag].label}）\nクリックで再生` : `${name}（未所持）`);
  });
  // ページ送り（カードの下、中央）
  const cx = GRID_X + GRID_W / 2, pr = (PAGER_Y - TOP) / 9;
  kit.pill(`${prefix}_prev`, cx - 45, pr, 15, '', false, custom('dcg:emote_page', { view: target, delta: -1 }), '前のページ（なし）', { compact: true, icon: 'left' });
  text(`${prefix}_page`, cx - 28, PAGER_Y, 56, '1 / 1', { align: 'center' });
  kit.pill(`${prefix}_next`, cx + 30, pr, 15, '', false, custom('dcg:emote_page', { view: target, delta: 1 }), '次のページ（なし）', { compact: true, icon: 'right' });
}

for (const [id, label, tag] of VIEWS) {
  frame(id, tag ? `エモート - ${label}` : 'エモート');
  grid('em', EMOTES.filter((e) => !tag || e[1] === tag));
}

process.stdout.write(JSON.stringify(ops, null, 1));
