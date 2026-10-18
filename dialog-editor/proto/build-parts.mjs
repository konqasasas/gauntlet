// 試作メニューの部品（ボタン・タブ・右上のアイコン）と、見本の画面を手元のエディターに作る。
// 先に node proto/make-art.mjs で絵を作っておく。何度流しても同じ結果になる（部品と見本の画面は置き換える）。
//
//   node proto/build-parts.mjs [--url http://127.0.0.1:4320]
import { uploadArt, ops, C, fixed, img, nine, txt, hot, inst, act, PROPS, W, H, ledgerBase as kitLedger, heading, bakedFont, LATIN_DY } from './kit.mjs';

const A = await uploadArt();

// ボタン: 60×21。絵は y=5〜20（16、下の 1 は影）で、y=9 の行の文字が上下の真ん中に来る。クリックはその行
const BTN = { normal: '@ink', primary: '@ink', danger: '@paper.hi', disabled: '@ink.faint' };
const button = {
  id: 'ui_button', name: '試作: ボタン', width: 60, height: 21, variants: Object.keys(BTN), props: PROPS('ボタン'),
  elements: [
    ...Object.entries(BTN).flatMap(([v, color]) => [
      nine(`${v}_img`, A[`btn_${v}`], 0, 5, 60, 16, { state: `variant=${v}` }),
      txt(`${v}_t`, 'ボタン', 0, 9, 60, color, { align: 'center', binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' }, state: `variant=${v}` }),
    ]),
    hot('hs', 0, 9, 60, 9),
  ],
};

// タブ: 72×20。選んでいるタブは y=4〜19 で紙の面の縁を覆う。ほかは y=4〜17
// 名前は見出しと同じ書体で焼き込む。label は日本語版（lang≠en）、label_en は英語版（lang=en、5x5 Pixel の大文字）。
// 日本語版の名前が英字だけ（Passus）のときは *_latin のバリアントにして 5x5 Pixel で描く
// 焼き込みの名前は文字の行に縛られないので、タブの絵（y=4〜17、中心 10.5）の上下の真ん中に置く（字の中心は y+3.5）
const TAB_TY = 7;
const TAB_V = ['on', 'off', 'on_latin', 'off_latin'];
const tab = {
  id: 'ui_tab', name: '試作: タブ', width: 72, height: 20, variants: TAB_V,
  props: [{ name: 'label', type: 'text', default: 'タブ' }, { name: 'label_en', type: 'text', default: 'TAB' }, ...PROPS(null)],
  elements: [
    ...TAB_V.flatMap((v) => {
      const on = v.startsWith('on'), color = on ? '@ink' : '@ink.sub', stretch = { h: 'stretch', v: 'top' };
      return [
        on ? nine(`${v}_img`, A.tab_on, 0, 4, 72, 16, { state: `variant=${v}` }) : nine(`${v}_img`, A.tab_off, 0, 4, 72, 14, { state: `variant=${v}` }),
        { id: `${v}_t`, type: 'text', x: 0, y: TAB_TY + (v.endsWith('_latin') ? LATIN_DY : 0), w: 72, h: 9, text: 'タブ', ...bakedFont(v.endsWith('_latin')), color, align: 'center', binds: { text: 'label' }, constraints: stretch, state: `variant=${v}&lang!=en` },
        { id: `${v}_te`, type: 'text', x: 0, y: TAB_TY + LATIN_DY, w: 72, h: 9, text: 'TAB', ...bakedFont(true), color, align: 'center', binds: { text: 'label_en' }, constraints: stretch, state: `variant=${v}&lang=en` },
      ];
    }),
    hot('hs', 0, 9, 72, 9),
  ],
};

// 右上のアイコン: 16×18（絵は y=1〜16）。bell_unread は未読のバッジ付き
const icon = {
  id: 'ui_icon', name: '試作: 右上のアイコン', width: 16, height: 18, variants: ['bell', 'bell_unread', 'settings', 'close'], props: PROPS(null),
  elements: [
    img('bell', A.icon_bell, 0, 1, 16, 16, { state: 'variant=bell' }),
    img('bell_u', A.icon_bell, 0, 1, 16, 16, { state: 'variant=bell_unread' }),
    img('badge', A.badge_unread, 11, 0, 6, 6, { state: 'variant=bell_unread' }),
    img('settings', A.icon_settings, 0, 1, 16, 16, { state: 'variant=settings' }),
    img('close', A.icon_close, 0, 1, 16, 16, { state: 'variant=close' }),
    hot('hs', 0, 0, 16, 18, { constraints: C }),
  ],
};

// ---- 見本の画面 ----
const ledgerBase = (active) => kitLedger(A, active);

const parts = {
  id: 'proto_parts', title: '試作: 部品', showTitle: false, width: W, height: H, background: '#00000000',
  canCloseWithEscape: true, pause: false, afterAction: 'none', footer: { type: 'none', columns: 1, buttons: [], exitAction: false },
  elements: [
    ...ledgerBase(0),
    ...heading('h_btn', { ja: 'ボタン', en: 'Buttons' }, 14, 27, 424),
    inst('b_primary', 'ui_button', 14, 45, 100, 21, 'primary', { label: '続きから辿る', tooltip: '主ボタン（1画面に1つ）' }),
    inst('b_normal', 'ui_button', 122, 45, 80, 21, 'normal', { label: '展示場へ', tooltip: '普通のボタン' }),
    inst('b_danger', 'ui_button', 210, 45, 90, 21, 'danger', { label: '初期値に戻す', tooltip: '取り消せない操作' }),
    inst('b_disabled', 'ui_button', 308, 45, 90, 21, 'disabled', { label: '試練を出す', tooltip: 'Anulus III で解放' }),
    ...heading('h_panel', { ja: '面', en: 'Panels' }, 14, 81, 424),
    nine('cloth', A.panel_cloth, 14, 99, 200, 99, { nineFill: 'tile' }),
    txt('cloth_t', '布張りの面（展示場）', 22, 108, 180, '@ink'),
    inst('cloth_b', 'ui_button', 22, 171, 80, 21, 'primary', { label: '挑戦する', tooltip: '布の上の主ボタン' }),
    txt('paper_t', '紙の面（台帳）', 230, 108, 180, '@ink'),
    txt('paper_t2', '本文はインクの色で、影なし', 230, 117, 200, '@ink.sub'),
    txt('paper_t3', '補足は薄いインク', 230, 126, 200, '@ink.faint'),
  ],
  groups: [], stateDefaults: { lang: 'ja' }, inputs: [],
};

const gothic = {
  ...parts, id: 'proto_gothic', title: '試作: 正統な試練の枠',
  elements: [
    ...ledgerBase(1),
    // 空と尖塔の稜線（正統な試練の画面だけ）。紙の面の上半分に置く
    img('sky', A.sky, 6, 20, 440, 54),
    img('spires', A.spires, 6, 56, 440, 18, { fit: 'nine', nineFill: 'tile' }),
    { id: 'sky_rule', type: 'rect', x: 6, y: 74, w: 440, h: 1, fill: '@ink.sub', border: '#00000000', borderWidth: 0, constraints: C },
    txt('era_l', '昼  Anulus I', 12, 27, 120, '@ink'),
    txt('era_r', 'Anulus XI  黄昏', 330, 27, 110, '@paper.hi', { align: 'right' }),
    ...heading('h', 'Anulus III', 14, 81, 424, 'この時代: 3 / 11'),
    inst('b', 'ui_button', 14, 99, 100, 21, 'primary', { label: '挑戦する', tooltip: 'Limen 3-4 に挑戦' }),
    inst('b2', 'ui_button', 122, 99, 100, 21, 'normal', { label: 'この時代の名簿', tooltip: '名簿を開く' }),
  ],
};

// ---- 入力部品 ----

// トグル: 22×9（1行）。押すと切り替え
const toggle = {
  id: 'ui_toggle', name: '試作: トグル', width: 22, height: 9, variants: ['on', 'off'], props: PROPS(null),
  elements: [
    img('on', A.toggle_on, 0, 0, 22, 9, { state: 'variant=on' }),
    img('off', A.toggle_off, 0, 0, 22, 9, { state: 'variant=off' }),
    hot('hs', 0, 0, 22, 9, { constraints: fixed }),
  ],
};

// セグメントの区切り: 60×21（ボタンと同じ縦の作り）。位置（l/m/r）と選択（on/off）の組み合わせ
const SEG = ['l', 'm', 'r'].flatMap((p) => [`${p}_on`, `${p}_off`]);
const segment = {
  id: 'ui_seg', name: '試作: セグメントの区切り', width: 60, height: 21, variants: SEG, props: PROPS('項目'),
  elements: [
    ...SEG.map((v) => nine(`${v}_img`, A[`seg_${v}`], 0, 5, 60, 16, { state: `variant=${v}` })),
    ...SEG.map((v) => txt(`${v}_t`, '項目', 0, 9, 60, v.endsWith('_on') ? '@ink' : '@ink.sub', { align: 'center', binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' }, state: `variant=${v}` })),
    hot('hs', 0, 9, 60, 9),
  ],
};

// チップ: 幅は文字に合わせる × 19。絵は y=7〜18（12）、左の 6 は ✓ / ○ の印
const chipC = {
  id: 'ui_chip', name: '試作: チップ', width: 60, height: 19, variants: ['on', 'off'], props: PROPS('チップ'),
  elements: [
    nine('on_img', A.chip_on, 0, 7, 60, 12, { state: 'variant=on' }),
    nine('off_img', A.chip_off, 0, 7, 60, 12, { state: 'variant=off' }),
    txt('on_t', 'チップ', 7, 9, 50, '@paper.hi', { align: 'center', binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' }, state: 'variant=on' }),
    txt('off_t', 'チップ', 7, 9, 50, '@ink.sub', { align: 'center', binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' }, state: 'variant=off' }),
    hot('hs', 0, 9, 60, 9),
  ],
};

// スライダー: 溝 100×8 と、値ごとの満ちとつまみ（v0〜v100）。目盛り1つが1つのクリック範囲
const STEPS = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100];
const slider = {
  id: 'ui_slider', name: '試作: スライダー', width: 100, height: 9, variants: STEPS.map((v) => `v${v}`),
  props: STEPS.slice(1).map((v) => ({ name: `set${v}`, type: 'action', default: { type: 'none', value: '', payload: '' } })),
  elements: [
    img('track', A.slider_track, 0, 0, 100, 8, { constraints: fixed }),
    ...STEPS.slice(1).map((v) => nine(`fill${v}`, A.slider_fill, 1, 1, v - 2, 6, { constraints: fixed, state: `variant=v${v}` })),
    ...STEPS.map((v) => img(`knob${v}`, A.slider_knob, Math.max(0, Math.min(92, v - 4)), 0, 8, 8, { state: `variant=v${v}` })),
    ...STEPS.slice(1).map((v) => ({ ...hot(`hs${v}`, v - 10, 0, 10, 9, { constraints: fixed }), binds: { action: `set${v}` }, tooltip: `${v}% にする` })),
  ],
};
const stepC = {
  id: 'ui_step', name: '試作: ◀ ▶', width: 12, height: 9, variants: ['left', 'right'], props: PROPS(null),
  elements: [
    img('left', A.step_left, 0, 0, 12, 9, { state: 'variant=left' }),
    img('right', A.step_right, 0, 0, 12, 9, { state: 'variant=right' }),
    hot('hs', 0, 0, 12, 9, { constraints: fixed }),
  ],
};

// ドロップダウンの欄: 幅自由 × 21。今の値と右端の ▼（開いているときは ▲）
const dropdownC = {
  id: 'ui_dropdown', name: '試作: ドロップダウン', width: 120, height: 21, variants: ['closed', 'open', 'disabled'], props: PROPS('値'),
  elements: [
    nine('closed_img', A.dropdown, 0, 5, 120, 16, { state: 'variant=closed' }),
    nine('open_img', A.dropdown_open, 0, 5, 120, 16, { state: 'variant=open' }),
    nine('disabled_img', A.dropdown_disabled, 0, 5, 120, 16, { state: 'variant=disabled' }),
    txt('t', '値', 5, 9, 98, '@ink', { binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' }, state: 'variant!=disabled' }),
    txt('t_off', '値', 5, 9, 98, '@ink.faint', { binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' }, state: 'variant=disabled' }),
    hot('hs', 0, 9, 120, 9),
  ],
};

const segRow = (id, x, y, w, labels, selected, key) => labels.map((label, k) => {
  const pos = k === 0 ? 'l' : k === labels.length - 1 ? 'r' : 'm';
  return inst(`${id}_${k}`, 'ui_seg', x + k * w, y, w, 21, `${pos}_${k === selected ? 'on' : 'off'}`,
    { label, tooltip: k === selected ? '選択中' : `${label}にする`, action: act('dcg:settings_set', { key, value: k }) });
});
const chipW = (label) => label.length * 9 + 16;

// 開いたドロップダウンの一覧。y は欄の文字の行。欄の絵は y+11 まであるので、一覧はその下の行（y+18）から
function ddList(id, x, y, w, items, selected) {
  const top = y + 12, rows = items.length;
  return [
    nine(`${id}_panel`, A.dd_list, x, top, w + 3, rows * 9 + 11, { constraints: fixed }),
    nine(`${id}_sel`, A.dd_sel, x + 1, y + 9 * (selected + 2), w - 2, 9, { constraints: fixed }),
    img(`${id}_check`, A.check, x + w - 12, y + 9 * (selected + 2) + 2, 7, 6),
    ...items.flatMap((label, k) => [
      txt(`${id}_t${k}`, label, x + 6, y + 9 * (k + 2), w - 20, k === selected ? '@ink' : '@ink.sub'),
      { ...hot(`${id}_h${k}`, x, y + 9 * (k + 2), w, 9, { constraints: fixed }), binds: {}, action: act('dcg:settings_set', { key: 'translate_lang', value: k }), tooltip: `${label} にする` },
    ]),
  ];
}

const inputs = {
  ...parts, id: 'proto_inputs', title: '試作: 入力部品',
  elements: [
    ...ledgerBase(-1),
    // 左の列
    ...heading('h_toggle', { ja: 'トグル', en: 'Toggle' }, 14, 27, 204),
    txt('tg1_l', '自動翻訳', 14, 45, 80, '@ink'),
    inst('tg1', 'ui_toggle', 110, 45, 22, 9, 'on', { tooltip: '自動翻訳（クリックで OFF）', action: act('dcg:settings_toggle', { key: 'translate_auto' }) }),
    txt('tg1_v', 'ON', 138, 45, 30, '@verdigris.dark'),
    txt('tg2_l', '演出の字幕', 14, 63, 80, '@ink'),
    inst('tg2', 'ui_toggle', 110, 63, 22, 9, 'off', { tooltip: '演出の字幕（クリックで ON）', action: act('dcg:settings_toggle', { key: 'subtitle' }) }),
    txt('tg2_v', 'OFF', 138, 63, 30, '@ink.faint'),
    ...heading('h_seg', { ja: 'セグメント', en: 'Segment' }, 14, 81, 204),
    ...segRow('seg', 14, 90, 66, ['人気順', '新着順', '名前順'], 0, 'sort'),
    ...heading('h_chip', { ja: 'チップ', en: 'Chips' }, 14, 117, 204, '複数 ON にできる'),
    ...(() => { let x = 14; return [['未クリア', 'on'], ['クリア済み', 'off'], ['お気に入り', 'on']].map(([label, v], k) => {
      const w = chipW(label), e = inst(`chip${k}`, 'ui_chip', x, 126, w, 19, v, { label, tooltip: v === 'on' ? `${label}（クリックで外す）` : `${label}で絞り込む`, action: act('dcg:course_filter', { filter: k }) });
      x += w + 4; return e; }); })(),
    ...(() => { let x = 14; return [['★1〜2', 'off'], ['★3〜5', 'on']].map(([label, v], k) => {
      const w = chipW(label), e = inst(`chipd${k}`, 'ui_chip', x, 144, w, 19, v, { label, tooltip: `${label}で絞り込む`, action: act('dcg:course_filter', { diff: k }) });
      x += w + 4; return e; }); })(),
    // 右の列
    ...heading('h_slider', { ja: 'スライダー', en: 'Slider' }, 232, 27, 206),
    ...[['sound.system', 'システム音', 70, 45], ['sound.course', '演出音', 30, 63]].flatMap(([key, label, v, y]) => { const id = key.replace(".", "_"); return [
      txt(`${id}_l`, label, 232, y, 60, '@ink'),
      inst(`${id}_dec`, 'ui_step', 284, y, 12, 9, 'left', { tooltip: `${label}を 10% 下げる`, action: act('dcg:settings_step', { key, delta: -10 }) }),
      inst(`${id}_s`, 'ui_slider', 299, y, 100, 9, `v${v}`, Object.fromEntries(STEPS.slice(1).map((s) => [`set${s}`, act('dcg:settings_set', { key, value: s })]))),
      inst(`${id}_inc`, 'ui_step', 402, y, 12, 9, 'right', { tooltip: `${label}を 10% 上げる`, action: act('dcg:settings_step', { key, delta: 10 }) }),
      txt(`${id}_v`, `${v}%`, 416, y, 24, '@ink', { align: 'right' }),
    ]; }),
    ...heading('h_dd', { ja: 'ドロップダウン', en: 'Dropdown' }, 232, 81, 206),
    txt('dd1_l', 'UI言語', 232, 99, 56, '@ink'),
    inst('dd1', 'ui_dropdown', 284, 90, 154, 21, 'closed', { label: '自動', tooltip: 'クリックで一覧を開く', action: act('dcg:dropdown', { key: 'ui_lang' }) }),
    txt('dd2_l', '翻訳先', 232, 117, 56, '@ink'),
    inst('dd2', 'ui_dropdown', 284, 108, 154, 21, 'open', { label: '日本語 (ja)', tooltip: 'クリックで一覧を閉じる', action: act('dcg:dropdown', { key: 'translate_lang' }) }),
    ...ddList('dd2l', 284, 117, 154, ['UI言語に合わせる', '日本語 (ja)', 'English (en)', '한국어 (ko)', '简体中文 (zh-CN)', 'Español (es)'], 1),
  ],
};

await ops([
  { op: 'set_text_style', key: 'heading_latin', style: { name: '英字の見出し（紙の上・5x5 Pixel）', render: 'baked', fontFamily: '5x5 Pixel', fontSize: 8, pixel: true, wordSpacing: 3, color: '@ink', shadow: false, lineHeight: 1 } },
  { op: 'set_text_style', key: 'heading_ink', style: { name: '見出し（紙の上・美咲ゴシック）', render: 'baked', fontFamily: 'Misaki Gothic', fontSize: 8, pixel: true, wordSpacing: 0, color: '@ink', shadow: false, lineHeight: 1 } },
  { op: 'add_component', component: button, replace: true },
  { op: 'add_component', component: tab, replace: true },
  { op: 'add_component', component: icon, replace: true },
  ...[toggle, segment, chipC, slider, stepC, dropdownC].map((component) => ({ op: 'add_component', component, replace: true })),
  // 見た目だけの版（クリック範囲なし）。値を状態で出し分けるとき（バリアントの結び付け・状態の条件）に使い、クリック範囲は画面に置く
  // （クリック範囲は状態で変えられないため、クリック範囲を含む部品には状態を付けられない）
  ...[button, toggle, segment, chipC, slider, dropdownC].map((c) => ({ op: 'add_component', replace: true, component: {
    ...c, id: `${c.id}_v`, name: `${c.name}（見た目だけ）`, props: c.props.filter((p) => p.type !== 'action' && p.name !== 'tooltip'),
    elements: c.elements.filter((e) => e.type !== 'hotspot') } })),
  ...['proto_parts', 'proto_gothic', 'proto_inputs'].map((screen) => ({ op: 'remove_screen', screen, ifExists: true })),
  { op: 'add_screen', screen: parts },
  { op: 'add_screen', screen: gothic },
  { op: 'add_screen', screen: inputs },
]);
console.log('作成: 部品 ui_button / ui_tab / ui_icon / ui_toggle / ui_seg / ui_chip / ui_slider / ui_step / ui_dropdown（と見た目だけの *_v）、画面 proto_parts / proto_gothic / proto_inputs');
