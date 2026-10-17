// 設定画面（タブ付き）の見本を作る操作（ops）を出力する。
//   node examples/settings_mock.mjs [--replace] > ops.json && node cli.mjs batch ops.json
// 画面:
//   settings               一般（言語・サウンド）
//   settings_friend        フレンド（準備中）
//   settings_chat          チャット（準備中）
//   settings_translate     翻訳先の選択（一般の「翻訳先」から開く）
//   settings_reset_confirm 初期設定に戻す前の確認
// 画面の移動はプラグイン経由（dcg:open）。設定の変更は custom をプラグインへ送り、画面を送り直す（即時反映）。
// 選択中の枠・ON/OFF・音量・翻訳先の文字は「状態」（lib/states.mjs）として全部の値を描いておき、
// プラグインがプレイヤーの値に合うものだけを残して送る。状態の値はプラグインの個人設定と同じ
// （ui_lang: auto/ja/en、translate_lang: ui/言語コード、translate_auto: on/off、sound.*: 0〜100）。
// 見た目のルールはコース選択と同じ: 本文の英語見出し、フッターなし（dialog_list）、右上の✕。
// ゲーム内フォントの文字は9px行に置かれるので、1行の部品は「行の2px上から高さ13px」の背景にする。

import { createKit, THEMES, themeOps, FRAME } from './ui_kit.mjs';
import { loadMeasure } from './ui_kit.mjs';

const replace = process.argv.includes('--replace');
const TOP = FRAME.TOP;
const TAB_W = 100;
const CX = TAB_W + 8;                    // 内容の左端
const W = FRAME.W, H = FRAME.H;          // 全画面共通の外枠
const CW = W - CX;                       // 内容の幅
const LABEL_X = CX + 10, CTRL_X = CX + 120, CTRL_R = W - 8;
// プロフィール設定はプロフィール画面の中に置く（設定のタブには入れない）
const TABS = [['settings', '一般'], ['settings_friend', 'フレンド'], ['settings_chat', 'チャット']];
const SCREENS = ['settings', 'settings_profile', 'settings_friend', 'settings_chat', 'settings_translate', 'settings_reset_confirm'];
// 翻訳先（ui = UI言語に合わせる）
const LANGS = [
  ['ui', 'UI言語に合わせる'], ['ja', '日本語 (ja)'], ['en', 'English (en)'], ['ko', '한국어 (ko)'],
  ['zh-CN', '简体中文 (zh-CN)'], ['zh-TW', '繁體中文 (zh-TW)'], ['es', 'Español (es)'], ['pt', 'Português (pt)'],
  ['fr', 'Français (fr)'], ['de', 'Deutsch (de)'], ['ru', 'Русский (ru)'], ['it', 'Italiano (it)'],
  ['th', 'ไทย (th)'], ['vi', 'Tiếng Việt (vi)'], ['id', 'Bahasa Indonesia (id)'],
];
const VOLUMES = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100];

const ops = [];
if (replace) for (const id of SCREENS) ops.push({ op: 'remove_screen', screen: id, ifExists: true });
ops.push(...themeOps()); // テーマの色（既にある色は上書きしない）
let target = '';
// 状態: variant の中で追加した見た目の要素に state を付け、id に印を足す。
// クリック領域（hotspot）は状態で変えられないので、clicks=true の variant（または variant の外）でだけ追加する。
let cur = { state: null, suffix: '', clicks: true };
const suffixes = new Map();
const add = (element) => {
  if (element.type === 'hotspot') { if (cur.clicks) ops.push({ op: 'add_element', screen: target, element }); return; }
  if (cur.state) element = { ...element, id: `${element.id}${cur.suffix}`, state: cur.state };
  ops.push({ op: 'add_element', screen: target, element });
};
const variant = (state, clicks, fn) => {
  const prev = cur;
  const k = `${target}/${state}`;
  if (!suffixes.has(k)) suffixes.set(k, `__s${suffixes.size}`);
  cur = { state, suffix: suffixes.get(k), clicks };
  fn();
  cur = prev;
};
const text = (id, x, y, w, t, over = {}) => add({ type: 'text', id, x, y, w, h: 9, text: t, render: 'game', color: '@text.main', ...over });
const heading = (id, x, y, t, w) => add({ type: 'text', id, x, y, w, h: 10, text: t, render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1 });
const custom = (value, payload) => ({ type: 'custom', value, payload: JSON.stringify(payload) });
const screenAction = (value) => ({ type: 'screen', value, payload: '' });
const row = (r) => TOP + r * 9;

const section = (id, r, label, x = CX, w = CW) => {
  add({ type: 'rect', id: `${id}_bar`, x: x + 1, y: row(r), w: w - 2, h: 9, fill: '@section.bg', border: '#00000000', borderWidth: 0 });
  text(id, x + 6, row(r), w - 12, label, { bold: true, color: '@section.text' });
};
const closeButton = (x) => closeX(x - 1);

function screen(id, title, w, h, stateDefaults = {}) {
  ops.push({
    op: 'add_screen',
    screen: { id, title, showTitle: false, width: w, height: h, background: '@screen.bg', afterAction: 'none', footer: { type: 'none', columns: 1, exitAction: false, buttons: [] }, stateDefaults },
  });
  target = id;
}

// タブ付きの設定画面の枠（見出し・✕・左のタブ・内容の背景）
function settingsFrame(id, tabLabel, stateDefaults = {}) {
  screen(id, `設定 - ${tabLabel}`, W, H, stateDefaults);
  heading('title_head', 2, 1, 'SETTINGS', 200);
  closeButton(W - 12);
  add({ type: 'rect', id: 'tabs_bg', x: 0, y: TOP, w: TAB_W, h: H - TOP, fill: '@panel.bg', border: '@panel.border', borderWidth: 1 });
  TABS.forEach(([sid, label], i) => {
    const on = sid === id;
    pill(`tab_${i}`, 4, 1 + i * 2, TAB_W - 8, label, on, screenAction(sid), on ? '表示中' : `${label}の設定`);
  });
  // 他のトップレベルの画面とそろえて、左下に「メニュー」
  pill('menu_btn', 4, 20, TAB_W - 8, 'メニュー', false, screenAction('top_menu'), 'トップメニューに戻る', { icon: 'left' });
  add({ type: 'rect', id: 'panel_bg', x: CX, y: TOP, w: CW, h: H - TOP, fill: '@panel.bg', border: '@panel.border', borderWidth: 1 });
}

const label = (id, r, t, tip) => {
  text(id, LABEL_X, row(r), CTRL_X - LABEL_X - 4, t);
  if (tip) add({ type: 'hotspot', id: `${id}_tip`, x: LABEL_X - 4, y: row(r), w: CTRL_X - LABEL_X, h: 9, tooltip: tip, action: { type: 'none', value: '', payload: '' } });
};
// ---- 部品（立体的な枠。examples/ui_kit.mjs と共通） ----
const { rect, bevel, hint, pill, closeX, iconIn } = createKit({ add, row, measure: await loadMeasure() });

// 選択肢を横に並べる（境目の枠を共有し、選択中は青）。選択中・それ以外の両方を状態として描く
const segmented = (key, r, options, tip) => {
  const total = CTRL_R - CTRL_X, w = Math.floor((total + options.length - 1) / options.length);
  options.forEach(([value, t], i) => {
    const id = `${key.replace('.', '_')}_${value}`, x = CTRL_X + i * (w - 1), ww = i === options.length - 1 ? total - i * (w - 1) : w;
    const action = custom('dcg:settings_set', { key, value });
    variant(`${key}=${value}`, false, () => pill(id, x, r, ww, t, true, action, tip));
    variant(`${key}!=${value}`, true, () => pill(id, x, r, ww, t, false, action, tip));
  });
};
// ON/OFF スイッチ（右寄せ。ON は青い溝につまみが右、OFF は灰色の溝につまみが左）
const toggle = (key, r, tip) => {
  const id = key.replace('.', '_');
  const w = 30, x = CTRL_R - w, y = row(r) - 2;
  for (const on of [true, false]) {
    variant(`${key}=${on ? 'on' : 'off'}`, false, () => {
      rect(`${id}_track`, x, y + 2, w, 9, on ? '@control.on' : '@control.track', '@control.outline', 1);
      rect(`${id}_track_sh`, x + 1, y + 3, w - 2, 1, on ? '@control.on-shadow' : '@control.track-shadow');
      bevel(`${id}_knob`, on ? x + w - 11 : x, y, 11, 13, 'light');
      text(`${id}_state`, CTRL_X, row(r), x - CTRL_X - 6, on ? 'ON' : 'OFF', { align: 'right', color: on ? '@control.on-text' : '@text.sub' });
    });
  }
  hint(`${id}_toggle`, CTRL_X, r, CTRL_R - CTRL_X, custom('dcg:settings_toggle', { key }), `${tip}（クリックで切り替え）`);
};
// スライダー: 70% ◀ [====|----] ▶（溝のクリックでその値、◀▶で10%ずつ）。値・塗り・つまみは状態
const volume = (key, r, tip) => {
  const id = key.replace('.', '_');
  const TRACK = 100, KNOB = 7;
  const next = CTRL_R - 13, tx = next - 1 - TRACK, prev = tx - 1 - 13;
  pill(`${id}_down`, prev, r, 13, '', false, custom('dcg:settings_step', { key, delta: -10 }), `${tip}（10%下げる）`, { icon: 'left' });
  rect(`${id}_track`, tx, row(r) + 1, TRACK, 7, '@control.track', '@control.outline', 1);
  rect(`${id}_track_sh`, tx + 1, row(r) + 2, TRACK - 2, 1, '@control.track-shadow');
  for (const percent of VOLUMES) {
    variant(`${key}=${percent}`, false, () => {
      text(`${id}_value`, CTRL_X, row(r), prev - CTRL_X - 4, `${percent}%`, { align: 'right', color: percent ? '@text.main' : '@text.sub' });
      const kx = tx + Math.round((percent / 100) * (TRACK - KNOB));
      if (kx > tx + 1) rect(`${id}_fill`, tx + 1, row(r) + 2, kx - tx - 1, 5, '@control.on');
      bevel(`${id}_knob`, kx, row(r) - 2, KNOB, 13, 'light');
    });
  }
  for (let i = 1; i <= 10; i++) add({ type: 'hotspot', id: `${id}_seg${i}_hs`, x: tx + (i - 1) * 10, y: row(r), w: 10, h: 9, tooltip: `${i * 10}% にする`, action: custom('dcg:settings_set', { key, value: i * 10 }) });
  pill(`${id}_up`, next, r, 13, '', false, custom('dcg:settings_step', { key, delta: 10 }), `${tip}（10%上げる）`, { icon: 'right' });
};
// 選択欄（▼付き。クリックで選択画面へ）。表示する値は状態ごとに描く
const dropdown = (id, key, r, choices, action, tip) => {
  const x = CTRL_X, w = CTRL_R - CTRL_X;
  bevel(id, x, row(r) - 2, w, 13, 'light');
  for (const [value, t] of choices) variant(`${key}=${value}`, false, () => text(`${id}_t`, x + 5, row(r), w - 24, t, { color: THEMES.light.text }));
  rect(`${id}_div`, x + w - 15, row(r) - 1, 1, 11, '@button.shadow');
  iconIn(`${id}_arrow`, 'down', { x: x + w - 14, y: row(r) - 2, w: 13, h: 13 }, THEMES.light.text);
  hint(id, x, r, w, action, tip);
};

// ---- 一般 ----
settingsFrame('settings', '一般', { ui_lang: 'auto', translate_lang: 'ui', translate_auto: 'off', 'sound.system': '100', 'sound.course': '100' });
section('sec_lang', 0, '言語');
label('l_ui_lang', 2, 'UI言語', 'メニューや案内の言語。自動ではクライアントの言語設定に従います（日本語・英語以外なら英語）');
segmented('ui_lang', 2, [['auto', '自動'], ['ja', '日本語'], ['en', 'English']], 'UI言語を変更');
label('l_translate_lang', 4, '翻訳先', 'チャットの翻訳先の言語。「UI言語に合わせる」ならUI言語と同じになります');
dropdown('translate_lang', 'translate_lang', 4, LANGS, screenAction('settings_translate'), 'クリックで翻訳先を選ぶ');
label('l_translate_auto', 6, '自動翻訳', 'ほかのプレイヤーの発言を翻訳先の言語に自動で翻訳します');
toggle('translate_auto', 6, '自動翻訳');
section('sec_sound', 8, 'サウンド');
label('l_sound_system', 10, 'システム音', 'CPの設定・クリア・失敗などの合図の音。クライアントの「プレイヤー」の音量も重ねて効きます');
volume('sound.system', 10, 'システム音の音量');
label('l_sound_course', 12, 'コースの演出音', 'コースの仕掛け（トリガー）で鳴る音。クライアントの「プレイヤー」の音量も重ねて効きます');
volume('sound.course', 12, 'コースの演出音の音量');
pill('reset_btn', W - 110, 20, 102, '初期設定に戻す', false, screenAction('settings_reset_confirm'), '確認画面を開きます');

// ---- 未定のタブ（準備中） ----
for (const [id, tab, note] of [
  ['settings_friend', 'フレンド', 'フレンド申請の受け付けなど（フレンド機能を作るときに決めます）'],
  ['settings_chat', 'チャット', 'チャットチャンネルなど（チャットを設計するときに決めます）'],
]) {
  settingsFrame(id, tab);
  section(`sec_${id}`, 0, tab);
  text('todo', CX, row(9), CW, '準備中', { align: 'center', bold: true, color: '@text.sub' });
  text('todo_note', CX, row(11), CW, note, { align: 'center', color: '@text.faint' });
}

// ---- 翻訳先の選択 ----
{
  const PW = W, PH = H;
  screen('settings_translate', '翻訳先の選択', PW, PH, { translate_lang: 'ui' });
  heading('title_head', 2, 1, 'TRANSLATE TO', 250);
  closeButton(PW - 12);
  add({ type: 'rect', id: 'panel_bg', x: 0, y: TOP, w: PW, h: PH - TOP, fill: '@panel.bg', border: '@panel.border', borderWidth: 1 });
  const colW = (PW - 12 - 4) / 2;
  LANGS.forEach(([code, name], i) => {
    const r = 1 + Math.floor(i / 2) * 2, x = 6 + (i % 2) * (colW + 4);
    const id = `lang_${code.replace('-', '_').toLowerCase()}`;
    const action = custom('dcg:settings_set', { key: 'translate_lang', value: code });
    // ベトナム語などはアクセント記号が上に出るので、背の高いボタンにする
    variant(`translate_lang=${code}`, false, () => pill(id, x, r, colW, name, true, action, `翻訳先を ${name} にする`, { tall: true }));
    variant(`translate_lang!=${code}`, true, () => pill(id, x, r, colW, name, false, action, `翻訳先を ${name} にする`, { tall: true }));
  });
  pill('back_btn', 6, 20, 80, '戻る', false, screenAction('settings'), '設定に戻る', { icon: 'left' });
}

// ---- 初期設定に戻す前の確認 ----
{
  const PW = W, PH = H;
  screen('settings_reset_confirm', '初期設定に戻す', PW, PH);
  heading('title_head', 2, 1, 'RESET', 200);
  closeButton(PW - 12);
  add({ type: 'rect', id: 'panel_bg', x: 0, y: TOP, w: PW, h: PH - TOP, fill: '@panel.bg', border: '@panel.border', borderWidth: 1 });
  text('msg', 0, row(7), PW, 'すべての設定を初期値に戻しますか？', { align: 'center' });
  text('msg2', 0, row(8), PW, '（一般のタブの項目が対象です）', { align: 'center', color: '@text.sub' });
  pill('ok_btn', PW / 2 - 110, 11, 104, '初期設定に戻す', false, custom('dcg:settings_reset', {}), '設定を初期値に戻して、設定画面に戻ります', { theme: 'danger' });
  pill('cancel_btn', PW / 2 + 6, 11, 104, 'キャンセル', false, screenAction('settings'), '何もせずに設定画面に戻ります');
}

process.stdout.write(JSON.stringify(ops, null, 1));
