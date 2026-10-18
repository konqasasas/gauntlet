// 試作メニューの画面（docs/gui-design.md の 5 章）を手元のエディターに作る。
// 先に make-art.mjs と build-parts.mjs を流しておく。何度流しても同じ結果になる（画面は置き換える）。
//
// クリック範囲は状態で変えられない（本文の空白の文字の列として固定で書き出す）。そのため、
// クリックできる場所が変わるもの（設定のカテゴリ・開いた一覧・確認ダイアログ）は別の画面にし、
// 同じ画面の中では値の見た目（トグル・音量・選んでいる言語など）だけを状態で出し分ける。
//
//   node proto/build-screens.mjs [--url http://127.0.0.1:4320]
import { LIVE, S, uploadArt, ops, fixed, img, nine, txt, hot, rect, inst, act, open, when, ledgerBase, heading, label, screenBase } from './kit.mjs';

const A = await uploadArt();
const STEPS = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100];
const sid = (s) => s.replace(/[^a-z0-9]/gi, '_').toLowerCase();
const click = (id, x, y, w, action, tooltip) => ({ ...hot(id, x, y, w, 9, { constraints: fixed }), binds: {}, action, tooltip });

// ======================= ホーム（ロビー） =======================

// 鎖（11個の輪）。now 番目が今の時代。輪の間は短い棒でつなぐ
function chain(id, x, y, now, step = 21) {
  const out = [];
  for (let k = 0; k < 11; k++) {
    const kind = k < now ? 'done' : k === now ? 'now' : 'lock';
    if (k > 0) out.push(rect(`${id}_bar${k}`, x + k * step - (step - 14), y + 4, step - 14, 1, k <= now ? '@brass.dark' : '@ink.faint'));
    out.push(img(`${id}_r${k}`, A[`ring_${kind}`], x + k * step, y, 14, 9));
  }
  return out;
}

// Limen の札の列（10個＋Culmen）。done 個つないで、次が now
function limenRow(id, x, y, done) {
  const out = [];
  for (let k = 0; k < 10; k++) out.push(img(`${id}_${k}`, A[`limen_${k < done ? 'done' : k === done ? 'now' : 'todo'}`], x + k * 10, y + 1, 6, 6));
  out.push(img(`${id}_c`, A.culmen_todo, x + 101, y, 8, 8));
  return out;
}

const home = screenBase(S('home'), '試作: ホーム', [
  ...ledgerBase(A, 0),
  // 左: 続きから（正統な試練なので空と尖塔を添える）
  nine('cont_card', A.card, 8, 21, 252, 157, { constraints: fixed }),
  img('cont_sky', A.sky, 10, 23, 248, 45),
  img('cont_spires', A.spires, 10, 50, 248, 18, { fit: 'nine', nineFill: 'tile' }),
  rect('cont_sky_rule', 10, 68, 248, 1, '@ink.sub'),
  ...chain('cont_chain', 18, 30, 2),
  ...heading('cont_h', 'Anulus III', 16, 81, 236, '今の時代'),
  txt('cont_next_l', '次の Limen', 16, 99, 80, '@ink.sub'),
  txt('cont_next', '3-4', 100, 99, 60, '@ink'),
  txt('cont_done_l', '繋いだ Limen', 16, 108, 80, '@ink.sub'),
  txt('cont_done', '3 / 10 ・ Culmen まだ', 100, 108, 150, '@ink'),
  ...limenRow('cont_limen', 16, 126, 3),
  inst('cont_go', 'ui_button', 16, 144, 128, 21, 'primary', { label: '続きから辿る', tooltip: 'Limen 3-4 から始めます', action: act('dcg:rankup_start', {}) }),
  inst('cont_map', 'ui_button', 150, 144, 102, 21, 'normal', { label: '鎖を見る', tooltip: 'Passus（ランクアップの進行）を開く', action: open(S('passus')) }),

  // 右: 3枚のカード（見出しの行の 6 上から、見出し・空き・2行）
  nine('rec_card', A.card, 266, 21, 178, 49, { constraints: fixed }),
  ...heading('rec_h', { ja: '最近の記録', en: 'Recent' }, 274, 27, 162),
  txt('rec_1', '3-3   0:41.20', 274, 45, 100, '@ink'), txt('rec_1b', 'ベスト更新', 376, 45, 60, '@verdigris.dark', { align: 'right' }),
  txt('rec_2', '3-2   0:38.02', 274, 54, 100, '@ink'),
  click('rec_hs1', 266, 45, 178, open(S('ledger')), '名簿で記録を見る'),
  click('rec_hs2', 266, 54, 178, open(S('ledger')), '名簿で記録を見る'),

  nine('ntc_card', A.card, 266, 75, 178, 49, { constraints: fixed }),
  ...heading('ntc_h', { ja: '守り手の掲示', en: 'Notice' }, 274, 81, 162),
  img('ntc_badge', A.badge_unread, 430, 82, 6, 6),
  txt('ntc_1', '10/17  新しい試練「天空回廊」', 274, 99, 162, '@ink'),
  txt('ntc_2', 'ほかに未読 2 件', 274, 108, 162, '@ink.faint'),
  click('ntc_hs1', 266, 99, 178, open(S('notices')), 'お知らせを開く（未読 3 件）'),
  click('ntc_hs2', 266, 108, 178, open(S('notices')), 'お知らせを開く（未読 3 件）'),

  nine('gal_card', A.card, 266, 129, 178, 49, { constraints: fixed }),
  ...heading('gal_h', { ja: '展示場から', en: 'Gallery' }, 274, 135, 162),
  txt('gal_1', '天空回廊', 274, 153, 80, '@ink'), txt('gal_1b', '★★★★★', 376, 153, 60, '@brass.dark', { align: 'right' }),
  txt('gal_2', '作 Alex ・ 辿った人 120', 274, 162, 162, '@ink.sub'),
  click('gal_hs1', 266, 153, 178, open(S('gallery')), '展示場でこの試練を見る'),
  click('gal_hs2', 266, 162, 178, open(S('gallery')), '展示場でこの試練を見る'),

  // 下の段
  txt('me_name', 'Steve', 12, 189, 40, '@sky.3'),
  txt('me_rank', 'Anulus III', 50, 189, 80, '@ink.sub'),
  inst('help', 'ui_button', 358, 180, 86, 21, 'normal', { label: '遊び方', tooltip: '操作のコツやルール', action: open(S('help')) }),
]);

// ======================= Passus（ランクアップの進行マップ）=======================
// 上半分: 時代の空と尖塔の稜線の上に、11個の輪（左が Anulus I）。輪を押すとその時代を選ぶ（anulus）
// 下半分: 選んだ時代の 10 の Limen と Culmen の札、選んだ札の記録と「挑戦する」
// クリック範囲はどの時代でも同じ。押したときの意味（どの時代・どの札か）はプラグインが状態から決める。
// 見た目の状態: anulus（選んでいる時代 1〜11）、ring0〜10（done / now / lock）、limen0〜10（done / now / todo / lock、10 は Culmen）、
// limen_sel（選んでいる札 0〜10）、era_lock（選んだ時代がこの先なら on）
const ROMAN = ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI'];
const ringX = (k) => 20 + k * 36;
const cardX = (k) => (k < 10 ? 14 + k * 37 : 386), cardW = (k) => (k < 10 ? 33 : 56);
const CARD_Y = 113, CARD_ROW = 117;

const passusTop = [
  img('px_sky', A.sky, 6, 20, 440, 70),
  img('px_spires', A.spires, 6, 72, 440, 18, { fit: 'nine', nineFill: 'tile' }),
  rect('px_sky_rule', 6, 90, 440, 1, '@ink.sub'),
  ...ROMAN.flatMap((r, k) => {
    const x = ringX(k);
    return [
      ...(k > 0 ? [rect(`px_bar${k}`, ringX(k - 1) + 14, 40, 22, 1, '@brass.dark')] : []),
      ...['done', 'now', 'lock'].map((v) => img(`px_ring${k}_${v}`, A[`ring_${v}`], x, 36, 14, 9, { state: `ring${k}=${v}` })),
      ...label(`px_num${k}`, r, x - 11, 45, 36, '@paper.hi', { align: 'center', shadow: true, shadowColor: '@ink' }),
      img(`px_caret${k}`, A.sel_caret, x + 5, 56, 5, 3, { state: `anulus=${k + 1}` }),
      click(`px_ring${k}_hs1`, x - 11, 36, 36, act('dcg:passus_select', { anulus: k + 1 }), `Anulus ${r} を見る`),
      click(`px_ring${k}_hs2`, x - 11, 45, 36, act('dcg:passus_select', { anulus: k + 1 }), `Anulus ${r} を見る`),
    ];
  }),
  // XI の先: 途切れた鎖と Origo
  rect('px_origo_bar', ringX(10) + 14, 40, 6, 1, '@ink.faint'),
  rect('px_origo_bar2', ringX(10) + 23, 40, 3, 1, '@ink.faint'),
  ...label('px_origo', 'Origo', ringX(10) + 28, 36, 36, '@paper.hi', { shadow: true, shadowColor: '@ink' }),
];

const passusEra = ROMAN.flatMap((r, k) => {
  const n = k + 1, note = n < 3 ? '繋いだ時代' : n === 3 ? '今の時代 ・ 繋いだ Limen 3 / 10' : 'この先の時代';
  return when(`anulus=${n}`, heading(`px_h${n}`, `Anulus ${r}`, 14, 99, 424, note));
});

const passusCards = Array.from({ length: 11 }, (_, k) => {
  const x = cardX(k), w = cardW(k), culmen = k === 10;
  return [
    nine(`px_card${k}`, A.card, x, CARD_Y, w, 16, { constraints: fixed }),
    rect(`px_card${k}_sel`, x - 1, CARD_Y - 1, w + 2, 16, '#00000000', { border: '@brass', borderWidth: 1, state: `limen_sel=${k}` }),
    ...(culmen
      ? [img('px_culmen', A.culmen_todo, x + 4, CARD_ROW, 8, 8), ...label('px_culmen_t', 'Culmen', x + 15, CARD_ROW, 38, '@ink')]
      : [...['done', 'now', 'todo', 'lock'].map((v) => img(`px_node${k}_${v}`, A[`limen_${v}`], x + 5, CARD_ROW + 1, 6, 6, { state: `limen${k}=${v}` })),
        txt(`px_card${k}_t`, String(k + 1), x + 14, CARD_ROW, 16, '@ink')]),
    click(`px_card${k}_hs`, x, CARD_ROW, w, act('dcg:passus_limen', { slot: k }), culmen ? 'Culmen（この時代の要の試練）' : `Limen ${k + 1} を選ぶ`),
  ];
}).flat();

// 選んだ札の記録（この試作では見本の値）。この先の時代を選んでいるときは案内だけ
const passusDetail = [
  ...when('era_lock!=on', [
    ...Array.from({ length: 11 }, (_, k) => txt(`px_d_name${k}`, k === 10 ? 'Culmen' : `Limen ${k + 1}`, 14, 135, 120, '@ink', { state: `limen_sel=${k}` })),
    txt('px_d_best', 'ベスト  ―', 14, 144, 200, '@ink.sub'),
    txt('px_d_top', 'この時代の最速  0:52.10（Alex）', 14, 153, 300, '@ink.sub'),
  ]),
  ...when('era_lock=on', [
    txt('px_d_lock', 'この先の時代です。今の時代の Culmen を越えると進めます', 14, 135, 420, '@ink.sub'),
  ]),
  inst('px_go', 'ui_button_v', 14, 171, 120, 21, 'primary', { label: '挑戦する' }, { variantBy: { key: 'era_lock', map: { on: 'disabled' }, else: 'primary' } }),
  click('px_go_hs', 14, 180, 120, act('dcg:passus_start', {}), '選んだ Limen に挑戦する（この先の時代は選べません）'),
  inst('px_ledger', 'ui_button', 140, 171, 120, 21, 'normal', { label: 'この時代の名簿', tooltip: '名簿でこの時代の記録を見る', action: open(S('ledger')) }),
];

const PASSUS_STATE = {
  anulus: '3', limen_sel: '3', era_lock: 'off',
  ...Object.fromEntries(ROMAN.map((_, k) => [`ring${k}`, k < 2 ? 'done' : k === 2 ? 'now' : 'lock'])),
  ...Object.fromEntries(Array.from({ length: 11 }, (_, k) => [`limen${k}`, k < 3 ? 'done' : k === 3 ? 'now' : 'todo'])),
};
const passus = screenBase(S('passus'), '試作: Passus', [...ledgerBase(A, 1), ...passusTop, ...passusEra, ...passusCards, ...passusDetail], PASSUS_STATE);

// ======================= 展示場（市井の試練）=======================
// 背景は布張り（正統な試練ではないので尖塔は付けない）。左: 検索・並べ替え・絞り込み、中: カード 2列×3段、右: 選んだ試練の詳細
// カードを押しても始まらず、選んだ状態になるだけ（selected）。挑戦は右の「挑戦する」から
// 見た目の状態: sort（popular / new / name）、f_open・f_done・f_fav・f_easy・f_hard（on / off）、selected（0〜5 / none）、fav_sel（on / off）
// カードの中身はこの試作では見本の値。本番ではプラグインが差し込む
const COURSES = [
  ['天空回廊', 5, 'Alex', 120, '1:02.40'], ['氷の回廊', 2, 'Mia', 45, '―'], ['スライム工場', 2, 'Ken', 88, '0:48.15'],
  ['溶岩渡り', 3, 'Rio', 61, '―'], ['風の通り道', 2, 'Yui', 30, '0:39.80'], ['黒曜石の塔', 4, 'Sora', 74, '―'],
];
const stars = (n) => '★'.repeat(n) + '☆'.repeat(5 - n);
const GX = (c) => 118 + c * 96, GY = (r) => 22 + r * 54; // 段の間隔は 6 行。カードの文字（y+32・y+41）が 9px の行に乗る

const galleryLeft = [
  nine('gl_search', A.search, 8, 23, 104, 16, { constraints: fixed }),
  txt('gl_search_t', '試練を検索…', 26, 27, 84, '@ink.faint'),
  click('gl_search_hs', 8, 27, 104, act('dcg:gallery_search', {}), '名前・作者で検索'),
  ...label('gl_sort_l', { ja: '並べ替え', en: 'Sort' }, 8, 45, 104, '@ink'),
  ...[['popular', '人気'], ['new', '新着'], ['name', '名前']].flatMap(([v, name], k) => {
    const pos = k === 0 ? 'l' : k === 2 ? 'r' : 'm', x = 8 + k * 35;
    return [
      inst(`gl_sort_${v}`, 'ui_seg_v', x, 54, k === 2 ? 34 : 35, 21, `${pos}_off`, { label: name }, { variantBy: { key: 'sort', map: { [v]: `${pos}_on` }, else: `${pos}_off` } }),
      click(`gl_sort_${v}_hs`, x, 63, k === 2 ? 34 : 35, act('dcg:gallery_sort', { by: v }), `${name}順に並べる`),
    ];
  }),
  ...label('gl_filter_l', { ja: '絞り込み', en: 'Filter' }, 8, 81, 104, '@ink'),
  ...[['f_open', '未クリア', 8, 99, 50], ['f_done', 'クリア済', 62, 99, 50], ['f_fav', 'お気に入り', 8, 117, 62], ['f_easy', '★1〜2', 8, 135, 50], ['f_hard', '★3〜5', 62, 135, 50]]
    .flatMap(([key, name, x, row, w]) => [
      inst(`gl_${key}`, 'ui_chip_v', x, row - 9, w, 19, 'off', { label: name }, { variantBy: { key, map: { on: 'on' }, else: 'off' } }),
      click(`gl_${key}_hs`, x, row, w, act('dcg:gallery_filter', { key }), `${name}で絞り込む（もう一度押すと外す）`),
    ]),
  txt('gl_count', '30 件中 12 件', 8, 162, 104, '@ink.sub'),
  // 自分の試練を出す（ランクが足りない間は使えない見た目。押すと条件を案内する）
  inst('gl_make', 'ui_button', 8, 180, 104, 21, 'disabled', { label: '試練を出す', tooltip: 'Anulus V から出せます', action: act('dcg:gallery_make', {}) }),
];

const galleryGrid = [
  ...COURSES.flatMap(([name, star, , runs], k) => {
    const x = GX(k % 2), y = GY(Math.floor(k / 2));
    return [
      nine(`gc_card${k}`, A.card, x, y, 92, 51, { constraints: fixed }),
      img(`gc_thumb${k}`, A[`thumb${k}`], x + 4, y + 3, 84, 27),
      rect(`gc_sel${k}`, x - 1, y - 1, 94, 52, '#00000000', { border: '@brass', borderWidth: 2, state: `selected=${k}` }),
      txt(`gc_name${k}`, name, x + 5, y + 32, 82, '@ink'),
      txt(`gc_star${k}`, stars(star), x + 5, y + 41, 44, '@brass.dark'),
      txt(`gc_runs${k}`, `${runs} 人`, x + 50, y + 41, 37, '@ink.sub', { align: 'right' }),
      click(`gc_hs${k}a`, x, y + 32, 92, act('dcg:gallery_select', { slot: k }), `${name}を選ぶ`),
      click(`gc_hs${k}b`, x, y + 41, 92, act('dcg:gallery_select', { slot: k }), `${name}を選ぶ`),
    ];
  }),
  inst('gc_prev', 'ui_step', 180, 189, 12, 9, 'left', { tooltip: '前のページ', action: act('dcg:gallery_page', { delta: -1 }) }),
  txt('gc_page', '1 / 3', 194, 189, 38, '@ink', { align: 'center' }),
  inst('gc_next', 'ui_step', 234, 189, 12, 9, 'right', { tooltip: '次のページ', action: act('dcg:gallery_page', { delta: 1 }) }),
];

// 右: 選んだ試練の詳細（selected の値で出し分け。何も選んでいないときは案内だけ）
const galleryDetail = [
  nine('gd_card', A.card, 312, 22, 132, 168, { constraints: fixed }),
  ...COURSES.flatMap(([name, star, by, runs, best], k) => when(`selected=${k}`, [
    img(`gd_thumb${k}`, A[`thumb${k}`], 316, 26, 124, 41),
    txt(`gd_name${k}`, name, 318, 72, 120, '@ink'),
    txt(`gd_star${k}`, stars(star), 318, 81, 120, '@brass.dark'),
    txt(`gd_by${k}`, `作 ${by}`, 318, 90, 120, '@ink.sub'),
    txt(`gd_runs${k}`, `辿った人 ${runs}`, 318, 99, 120, '@ink.sub'),
    txt(`gd_best${k}`, `ベスト ${best}`, 318, 108, 120, '@ink.sub'),
  ])),
  ...when('selected=none', [txt('gd_none', '試練を選んでください', 318, 90, 120, '@ink.faint', { align: 'center' })]),
  // お気に入り（押すたびに切り替え。入れているときは ♥ と文言）
  inst('gd_fav', 'ui_button_v', 316, 126, 124, 21, 'normal', { label: '' }, { variantBy: { key: 'selected', map: { none: 'disabled' }, else: 'normal' } }),
  img('gd_fav_heart', A.heart, 324, 137, 6, 5, { state: 'fav_sel=on&selected!=none' }),
  txt('gd_fav_on', 'お気に入りに入れた', 334, 135, 104, '@ink', { state: 'fav_sel=on&selected!=none' }),
  txt('gd_fav_off', 'お気に入りに入れる', 316, 135, 124, '@ink', { align: 'center', state: 'fav_sel!=on&selected!=none' }),
  txt('gd_fav_none', 'お気に入りに入れる', 316, 135, 124, '@ink.faint', { align: 'center', state: 'selected=none' }),
  click('gd_fav_hs', 316, 135, 124, act('dcg:gallery_favorite', {}), 'お気に入りを切り替える'),
  inst('gd_go', 'ui_button_v', 316, 153, 124, 21, 'primary', { label: '挑戦する' }, { variantBy: { key: 'selected', map: { none: 'disabled' }, else: 'primary' } }),
  click('gd_go_hs', 316, 162, 124, act('dcg:gallery_start', {}), '選んだ試練に挑戦する'),
];

const gallery = screenBase(S('gallery'), '試作: 展示場', [
  ...ledgerBase(A, 2),
  nine('cloth', A.panel_cloth, 4, 18, 444, 194, { nineFill: 'tile' }),
  ...galleryLeft, ...galleryGrid, ...galleryDetail,
], { sort: 'popular', f_open: 'on', f_done: 'off', f_fav: 'off', f_easy: 'on', f_hard: 'off', selected: '0', fav_sel: 'on' });

// ======================= 設定 =======================
const CATS = [['general', '一般', S('settings'), 'General'], ['sound', 'サウンド', S('settings_sound'), 'Sound'], ['profile', 'プロフィール', S('settings_profile'), 'Profile'],
  ['friend', 'フレンド', S('settings_friend'), 'Friend'], ['chat', 'チャット', S('settings_chat'), 'Chat']];
const UI_LANGS = [['auto', '自動（クライアントの言語）'], ['ja', '日本語'], ['en', 'English']];
const TR_LANGS = [['ui', 'UI言語に合わせる'], ['ja', '日本語 (ja)'], ['en', 'English (en)'], ['ko', '한국어 (ko)'], ['zh-CN', '简体中文 (zh-CN)'], ['zh-TW', '繁體中文 (zh-TW)'],
  ['es', 'Español (es)'], ['pt', 'Português (pt)'], ['fr', 'Français (fr)'], ['de', 'Deutsch (de)'], ['ru', 'Русский (ru)'], ['it', 'Italiano (it)'], ['th', 'ไทย (th)'], ['vi', 'Tiếng Việt (vi)'], ['id', 'Bahasa Indonesia (id)']];
// null は空けておく枠（ベトナム語は上下の記号が行からはみ出すので、上を空けて列の一番下に置く）
const TR_LIST_ORDER = ['ui', 'ja', 'en', 'ko', 'zh-CN', 'zh-TW', 'es', 'th', 'pt', 'fr', 'de', 'ru', 'it', 'id', null, 'vi'];
const DEFAULTS = { ui_lang: 'auto', translate_auto: 'on', translate_lang: 'ja', 'sound.system': '70', 'sound.course': '30' };
const set = (key, value, back = S('settings')) => act('dcg:settings_set', { key, value, back });

// 状態の値ごとの文字（同じ場所に重ね、値に合うものだけ出る）
const valueTexts = (id, key, items, x, y, w, color, extra = {}) =>
  items.map(([value, label]) => txt(`${id}_${sid(value)}`, label, x, y, w, color, { ...extra, state: `${key}=${value}` }));

// 設定の画面の共通部分: 検索欄・カテゴリ（しおり）・区切りの線
function settingsCommon(active) {
  return [
    ...ledgerBase(A, -1),
    nine('search', A.search, 8, 23, 436, 16, { constraints: fixed }),
    txt('search_t', '設定を検索…', 26, 27, 200, '@ink.faint'),
    click('search_hs', 8, 27, 436, act('dcg:settings_search', {}), '検索の欄を開く'),
    ...CATS.flatMap(([cat, name, screen, en], k) => {
      const y = 45 + k * 18, on = cat === active;
      return [
        ...(on ? [nine(`cat_${cat}_sel`, A.dd_sel, 8, y, 80, 9, { constraints: fixed })] : []),
        ...label(`cat_${cat}`, { ja: name, en }, 14, y, 72, on ? '@ink' : '@ink.sub'), // 見出しと同じ書体
        click(`cat_${cat}_hs`, 8, y, 80, on ? { type: 'none', value: '', payload: '' } : open(screen), on ? '表示中' : `${name}の設定`),
      ];
    }),
    rect('cat_rule', 92, 45, 1, 162, '@ink.faint'),
  ];
}

// 開いたドロップダウンの一覧。y は欄の文字の行。一覧は y+18 の行から、cols 列（上から下、左から右の順）
function ddList(id, x, y, w, key, items, cols = 1, action = (value) => set(key, value)) {
  const rows = Math.ceil(items.length / cols), cw = Math.floor(w / cols);
  const out = [nine(`${id}_panel`, A.dd_list, x, y + 12, w + 3, rows * 9 + 11, { constraints: fixed })];
  items.forEach((item, k) => {
    if (!item) return; // 空けておく枠
    const [value, label] = item;
    const cx = x + Math.floor(k / rows) * cw, ry = y + 18 + (k % rows) * 9, vid = sid(value);
    out.push(
      nine(`${id}_sel_${vid}`, A.dd_sel, cx + 1, ry, cw - 2, 9, { constraints: fixed, state: `${key}=${value}` }),
      img(`${id}_chk_${vid}`, A.check, cx + cw - 12, ry + 2, 7, 6, { state: `${key}=${value}` }),
      txt(`${id}_t_${vid}`, label, cx + 6, ry, cw - 20, '@ink'),
      click(`${id}_h_${vid}`, cx, ry, cw, action(value), `${label} にする`),
    );
  });
  return out;
}

// 一般: open は開いている一覧（'ui_lang' / 'translate_lang' / なし）
function general(openList) {
  const uiOpen = openList === 'ui_lang', trOpen = openList === 'translate_lang';
  return [
    ...heading('lang_h', { ja: '言語', en: 'Language' }, 100, 45, 340),
    txt('ui_lang_l', 'UI言語', 104, 63, 88, '@ink'),
    inst('ui_lang_dd', 'ui_dropdown', 196, 54, 180, 21, uiOpen ? 'open' : 'closed',
      { label: '', tooltip: uiOpen ? 'クリックで一覧を閉じる' : 'クリックで一覧を開く', action: open(uiOpen ? S('settings') : S('settings_ui_lang')) }),
    ...valueTexts('ui_lang_v', 'ui_lang', UI_LANGS, 201, 63, 150, '@ink'),
    // 翻訳（UI言語の一覧が開いているときは、一覧の下になるので出さない）
    ...(uiOpen ? [] : [
      ...heading('tr_h', { ja: '翻訳', en: 'Translate' }, 100, 81, 340),
      txt('tr_auto_l', '自動翻訳', 104, 99, 88, '@ink'),
      inst('tr_auto', 'ui_toggle_v', 196, 99, 22, 9, 'on', {}, { variantBy: { key: 'translate_auto', map: { on: 'on' }, else: 'off' } }),
      click('tr_auto_hs', 196, 99, 22, act('dcg:settings_toggle', { key: 'translate_auto', back: S('settings') }), '自動翻訳（クリックで切り替え）'),
      txt('tr_auto_on', 'ON', 224, 99, 30, '@verdigris.dark', { state: 'translate_auto=on' }),
      txt('tr_auto_off', 'OFF', 224, 99, 30, '@ink.faint', { state: 'translate_auto=off' }),
    ]),
    txt('tr_lang_l', '翻訳先', 104, 117, 88, '@ink', { state: 'translate_auto=on' }),
    txt('tr_lang_l_off', '翻訳先', 104, 117, 88, '@ink.faint', { state: 'translate_auto=off' }),
    ...(trOpen
      ? [inst('tr_dd', 'ui_dropdown', 196, 108, 180, 21, 'open', { label: '', tooltip: 'クリックで一覧を閉じる', action: open(S('settings')) })]
      : [inst('tr_dd', 'ui_dropdown_v', 196, 108, 180, 21, 'closed', { label: '' }, { variantBy: { key: 'translate_auto', map: { off: 'disabled' }, else: 'closed' } }),
        click('tr_dd_hs', 196, 117, 180, act('dcg:settings_dropdown', { key: 'translate_lang', screen: S('settings_translate') }), 'クリックで一覧を開く（自動翻訳が ON のとき）')]),
    ...when('translate_auto=on', valueTexts('tr_v', 'translate_lang', TR_LANGS, 201, 117, 150, '@ink')),
    ...when('translate_auto=off', valueTexts('tr_vo', 'translate_lang', TR_LANGS, 201, 117, 150, '@ink.faint')),
    // 翻訳先の一覧が開いているときは、注意書きと「初期値に戻す」は一覧の下になるので出さない
    ...(trOpen ? [] : [
      txt('tr_note', '自動翻訳が ON のとき、チャットをこの言語に訳します', 104, 135, 336, '@ink.faint'),
      inst('reset', 'ui_button', 316, 180, 124, 21, 'normal', { label: 'この欄を初期値に戻す', tooltip: '確認してから戻します', action: open(S('settings_reset')) }),
    ]),
    ...(uiOpen ? ddList('ui_lang_list', 196, 63, 180, 'ui_lang', UI_LANGS) : []),
    // 15言語は 8 行 × 2 列（タイ語とベトナム語は上下の記号がはみ出すので、隣の行と重ならない場所に置く）
    ...(trOpen ? ddList('tr_list', 150, 117, 290, 'translate_lang', TR_LIST_ORDER.map((v) => v && TR_LANGS.find(([k]) => k === v)), 2) : []),
  ];
}

// スライダーの行（見た目は状態で出し分け、クリック範囲は目盛りごとに共通）
function sliderRow(key, label, y) {
  const id = sid(key);
  return [
    txt(`${id}_l`, label, 104, y, 88, '@ink'),
    inst(`${id}_dec`, 'ui_step', 196, y, 12, 9, 'left', { tooltip: `${label}を 10% 下げる`, action: act('dcg:settings_step', { key, delta: -10, back: S('settings_sound') }) }),
    inst(`${id}_s`, 'ui_slider_v', 211, y, 100, 9, 'v100', {}, { variantBy: { key, map: Object.fromEntries(STEPS.map((s) => [String(s), `v${s}`])), else: 'v100' } }),
    ...STEPS.slice(1).map((s) => click(`${id}_h${s}`, 211 + s - 10, y, 10, set(key, s, S('settings_sound')), `${s}% にする`)),
    inst(`${id}_inc`, 'ui_step', 314, y, 12, 9, 'right', { tooltip: `${label}を 10% 上げる`, action: act('dcg:settings_step', { key, delta: 10, back: S('settings_sound') }) }),
    ...valueTexts(`${id}_v`, key, STEPS.map((s) => [String(s), `${s}%`]), 330, y, 28, '@ink', { align: 'right' }),
  ];
}
const sound = () => [
  ...heading('snd_h', { ja: 'サウンド', en: 'Sound' }, 100, 45, 340),
  ...sliderRow('sound.system', 'システム音', 63),
  ...sliderRow('sound.course', 'コースの演出音', 81),
  txt('snd_note', '音量を変えると、その音量で試しの音が鳴ります', 104, 99, 336, '@ink.faint'),
  inst('reset', 'ui_button', 316, 180, 124, 21, 'normal', { label: 'この欄を初期値に戻す', tooltip: '確認してから戻します', action: open(S('settings_sound_reset')) }),
];
const later = (cat, title, note) => [
  ...heading(`${cat}_h`, { ja: CATS.find(([c]) => c === cat)[1], en: title }, 100, 45, 340),
  txt(`${cat}_t`, '準備中', 104, 63, 336, '@ink'),
  txt(`${cat}_n`, note, 104, 72, 336, '@ink.faint'),
];

// 確認ダイアログ: 下の画面は絵だけを残して暗くし（文字は常に手前に描かれるので出さない）、上に小さな紙の面
function confirmScreen(id, title, under, question, back, keys) {
  const shapes = under.filter((e) => e.type === 'image' || e.type === 'rect');
  return screenBase(id, title, [
    ...shapes,
    rect('cf_shade', 0, 0, 452, 216, '#1a160fb4'),
    nine('cf_card', A.card, 116, 66, 220, 72, { constraints: fixed }),
    ...heading('cf_h', { ja: '初期値に戻す', en: 'Reset' }, 126, 72, 200),
    txt('cf_q', question, 126, 90, 200, '@ink', { align: 'center' }),
    txt('cf_n', 'この操作は取り消せません', 126, 99, 200, '@ink.sub', { align: 'center' }),
    inst('cf_ok', 'ui_button', 136, 108, 92, 21, 'danger', { label: '初期値に戻す', tooltip: '戻して設定に戻ります', action: act('dcg:settings_reset', { back, keys: keys.join(',') }) }), // payload の配列はプラグインが読まないので文字列
    inst('cf_no', 'ui_button', 234, 108, 82, 21, 'normal', { label: 'やめる', tooltip: '何もせずに戻ります', action: open(back) }),
  ]);
}

// ======================= 名簿（ランキング）=======================
// 守り手の組織が付けている台帳。表は 10 人ずつのランキング 1 つで、ページ送りと「自分の順位へ」（自分のいるページに跳ぶ）
// 見た目の状態: course（選んでいるコース）、period（week / all）、page（表示しているページ）。自分のいるページでは自分の行に真鍮の帯
// 表の中身はこの試作では見本の値（1ページ目と、自分のいる5ページ目）。本番はプラグインが差し込む
// コースの一覧を開いたところは、クリック範囲が変わるので別の画面（proto_ledger_course）
const LEDGER_COURSES = [['c01', '天空回廊'], ['c02', '氷の回廊'], ['c03', 'スライム工場'], ['c04', '溶岩渡り'], ['c05', '風の通り道'], ['c06', '黒曜石の塔'],
  ['era3', 'Anulus III（時代の合計）'], ['era2', 'Anulus II（時代の合計）']];
// 名前の色は Anulus の段階ごと（ランクの11色が決まるまでの仮。空の色を濃くしたもの）
const rankColor = (n) => (n <= 2 ? '@verdigris.dark' : n <= 4 ? '@brass.dark' : n <= 7 ? '@sky.3' : n <= 10 ? '@sky.4' : '@sky.5');
const LEDGER_PAGES = {
  1: [['Alex', 7, '0:58.10', '10/16'], ['Mia', 6, '0:59.42', '10/17'], ['Sora', 9, '1:00.05', '10/12'], ['Ken', 5, '1:01.77', '10/15'], ['Rio', 4, '1:02.30', '10/17'],
    ['Lena', 6, '1:02.88', '10/14'], ['Taro', 5, '1:03.10', '10/16'], ['Emi', 4, '1:03.45', '10/13'], ['Jun', 8, '1:03.92', '10/18'], ['Aki', 3, '1:04.20', '10/15']],
  5: [['Yui', 3, '1:11.58', '10/14'], ['Haru', 3, '1:11.90', '10/16'], ['Steve', 3, '1:12.40', '10/18', true], ['Nao', 2, '1:12.95', '10/11'], ['Kai', 4, '1:13.20', '10/13'],
    ['Ren', 2, '1:13.44', '10/17'], ['Mei', 3, '1:13.80', '10/12'], ['Gen', 1, '1:14.02', '10/18'], ['Rui', 2, '1:14.35', '10/16'], ['Ayu', 3, '1:14.90', '10/10']],
};
const LEDGER_TOTAL_PAGES = 121;
const LX = { rank: 14, name: 46, anulus: 150, time: 214, date: 276, umbra: 352 };
const TABLE_TOP = 45; // 見出しの行。10 人は 54〜135 の行
const rowY = (k) => TABLE_TOP + 9 * (k + 1);

function ledgerControls(open) {
  return [
    ...label('lg_course_l', { ja: 'コース', en: 'Course' }, 8, 27, 40, '@ink'),
    inst('lg_course_dd', 'ui_dropdown', 52, 18, 168, 21, open ? 'open' : 'closed',
      { label: '', tooltip: open ? 'クリックで一覧を閉じる' : 'クリックでコースを選ぶ', action: open ? act('dcg:open', { screen: S('ledger') }) : act('dcg:open', { screen: S('ledger_course') }) }),
    ...valueTexts('lg_course_v', 'course', LEDGER_COURSES, 57, 27, 140, '@ink'),
    ...label('lg_period_l', { ja: '期間', en: 'Period' }, 236, 27, 30, '@ink'),
    ...[['week', '今週'], ['all', '全期間']].flatMap(([v, name], k) => {
      const pos = k === 0 ? 'l' : 'r', x = 268 + k * 52;
      return [
        inst(`lg_period_${v}`, 'ui_seg_v', x, 18, 52, 21, `${pos}_off`, { label: name }, { variantBy: { key: 'period', map: { [v]: `${pos}_on` }, else: `${pos}_off` } }),
        click(`lg_period_${v}_hs`, x, 27, 52, act('dcg:ledger_period', { period: v }), `${name}の記録`),
      ];
    }),
  ];
}

// 表。shapesOnly のときは文字とクリックを出さない（一覧を重ねた画面で、下に隠れる部分）
function ledgerTable(shapesOnly) {
  const out = [nine('lg_sheet', A.card, 8, TABLE_TOP - 5, 436, 109, { constraints: fixed })];
  // 表の見出しは焼き込み（見出しの書体）にして 3px 上げ、罫との間と 1 行目の字との間に余白を作る（ゲーム内フォントの文字は行に縛られる）
  out.push(rect('lg_head_rule', 12, TABLE_TOP + 6, 428, 1, '@ink.sub'));
  // 文字は 9px の行にしか置けず、行の間はあけられない。帯は 10px（字の上と下に 1px）にして、行の区切りに余白を見せる
  for (let k = 1; k < 10; k += 2) out.push(rect(`lg_band${k}`, 12, rowY(k) - 1, 428, 10, '@paper'));
  for (const [page, rows] of Object.entries(LEDGER_PAGES)) rows.forEach((row, k) => {
    if (row[4]) out.push(nine(`lg_me_band_p${page}`, A.dd_sel, 12, rowY(k) - 1, 428, 10, { constraints: fixed, state: `page=${page}` }));
  });
  if (shapesOnly) return out;
  const head = [['rank', { ja: '順位', en: 'Rank' }, 'right', 24], ['name', { ja: '名前', en: 'Name' }, 'left', 100], ['anulus', 'Anulus', 'left', 60], ['time', { ja: '記録', en: 'Time' }, 'right', 52], ['date', { ja: '日付', en: 'Date' }, 'left', 50]];
  for (const [key, name, align, w] of head) out.push(...label(`lg_h_${key}`, name, LX[key], TABLE_TOP - 3, w, '@ink.sub', { align }));
  for (const [page, rows] of Object.entries(LEDGER_PAGES)) out.push(...when(`page=${page}`, rows.flatMap(([name, anulus, time, date, me], k) => {
    const ty = rowY(k), id = `lg_p${page}_r${k}`;
    return [
      txt(`${id}_rank`, String((page - 1) * 10 + k + 1), LX.rank, ty, 24, me ? '@ink' : '@ink.sub', { align: 'right' }),
      txt(`${id}_name`, me ? `${name}（自分）` : name, LX.name, ty, 100, rankColor(anulus)),
      txt(`${id}_anulus`, `Anulus ${ROMAN[anulus - 1]}`, LX.anulus, ty, 60, '@ink.sub'),
      txt(`${id}_time`, time, LX.time, ty, 52, '@ink', { align: 'right' }),
      txt(`${id}_date`, date, LX.date, ty, 50, '@ink.faint'),
      txt(`${id}_umbra`, me ? '自分の Umbra' : 'Umbra と辿る', LX.umbra, ty, 84, '@verdigris.dark'),
    ];
  })));
  // 「Umbra と辿る」のクリック範囲は行ごとに共通（どの人かはプラグインがページから決める）
  for (let k = 0; k < 10; k++) out.push(click(`lg_r${k}_hs`, LX.umbra - 4, rowY(k), 92, act('dcg:ledger_umbra', { row: k }), 'この人の Umbra と辿る'));
  // ページ送りと「自分の順位へ」
  out.push(
    inst('lg_prev', 'ui_step', 166, 153, 12, 9, 'left', { tooltip: '前の 10 人', action: act('dcg:ledger_page', { delta: -1 }) }),
    ...Object.keys(LEDGER_PAGES).map((page) => txt(`lg_page_${page}`, `${page} / ${LEDGER_TOTAL_PAGES}`, 180, 153, 60, '@ink', { align: 'center', state: `page=${page}` })),
    inst('lg_next', 'ui_step', 242, 153, 12, 9, 'right', { tooltip: '次の 10 人', action: act('dcg:ledger_page', { delta: 1 }) }),
    inst('lg_top', 'ui_button', 8, 144, 80, 21, 'normal', { label: '1位から', tooltip: '1 ページ目（上位 10 人）に戻る', action: act('dcg:ledger_page', { page: 1 }) }),
    inst('lg_me', 'ui_button', 336, 144, 104, 21, 'normal', { label: '自分の順位へ', tooltip: '自分のいるページに跳ぶ', action: act('dcg:ledger_page', { me: true }) }),
  );
  return out;
}

const ledgerFooter = [
  ...heading('lg_me_h', { ja: 'あなたの記録', en: 'Your record' }, 8, 171, 436, '43 位 ・ 1,203 人中'),
  txt('lg_me_1', 'ベスト  1:12.40（10/18）　・　ひとつ上の順位まで  0.50 秒', 8, 189, 436, '@ink.sub'),
];
const LEDGER_STATE = { course: 'c01', period: 'week', page: '1' };
const ledger = screenBase(S('ledger'), '試作: 名簿', [...ledgerBase(A, 3), ...ledgerControls(false), ...ledgerTable(false), ...ledgerFooter], LEDGER_STATE);
const ledgerCourse = screenBase(S('ledger_course'), '試作: 名簿 - コースの一覧', [
  ...ledgerBase(A, 3), ...ledgerControls(true), ...ledgerTable(true),
  ...ddList('lg_list', 52, 27, 168, 'course', LEDGER_COURSES, 1, (value) => act('dcg:ledger_course', { course: value })),
], LEDGER_STATE);

// ======================= プロフィール =======================
// 見るだけの画面。左: 名刺（顔・名前・Anulus・相棒）、右: 自分の鎖・記録・刻まれた名（輪を繋いだ日）
// 見た目の状態: ring0〜10（done / now / lock、Passus と同じ）。値はこの試作では見本（本番はプラグインが差し込む）
// 顔は見本の絵。本番でプレイヤーの頭を出せるか（26.3 のテキストのオブジェクト）は作るときに確かめる
const PROFILE_STATS = [
  ['繋いだ Limen', '23'], ['越えた Culmen', '2'], ['展示場でクリア', '5 / 30'],
  ['総プレイ時間', '12:40'], ['Umbra と辿った', '18 回'],
];
const profile = screenBase(S('profile'), '試作: プロフィール', [
  ...ledgerBase(A, 4),
  // 左: 名刺
  nine('pf_card', A.card, 8, 21, 140, 184, { constraints: fixed }),
  img('pf_face', A.face, 16, 27, 32, 32),
  rect('pf_face_frame', 15, 26, 34, 34, '#00000000', { border: '@brass.dark', borderWidth: 1 }),
  txt('pf_name', 'Steve', 54, 27, 90, rankColor(3)),
  txt('pf_rank', 'Anulus III', 54, 36, 90, '@ink.sub'),
  rect('pf_color', 54, 47, 6, 5, rankColor(3)),
  txt('pf_color_t', '名前の色', 63, 45, 80, '@ink.faint'),
  rect('pf_rule', 16, 66, 124, 1, '@ink.faint'),
  ...heading('pf_comp_h', { ja: '相棒', en: 'Companion' }, 16, 72, 124),
  img('pf_comp', A.companion, 54, 90, 48, 40),
  txt('pf_comp_t', '姿と名前は未定', 16, 135, 124, '@ink.faint', { align: 'center' }),
  inst('pf_privacy', 'ui_button', 16, 171, 124, 21, 'normal', { label: '公開範囲を変える', tooltip: '設定のプロフィールを開く', action: open(S('settings_profile')) }),

  // 右上: 自分の鎖
  ...heading('pf_chain_h', { ja: '自分の鎖', en: 'Your chain' }, 156, 27, 288, '3 / 11 の輪'),
  ...ROMAN.flatMap((r, k) => {
    const x = 164 + k * 25;
    return [
      ...(k > 0 ? [rect(`pf_bar${k}`, x - 11, 49, 11, 1, '@brass.dark')] : []),
      ...['done', 'now', 'lock'].map((v) => img(`pf_ring${k}_${v}`, A[`ring_${v}`], x, 45, 14, 9, { state: `ring${k}=${v}` })),
      ...label(`pf_num${k}`, r, x - 5, 54, 24, '@ink.sub', { align: 'center' }),
    ];
  }),

  // 右中: 記録（2 列 × 3 行）
  ...heading('pf_rec_h', { ja: '記録', en: 'Records' }, 156, 72, 288),
  ...PROFILE_STATS.flatMap(([name, value], k) => {
    const x = 160 + (k % 2) * 148, y = 90 + Math.floor(k / 2) * 9; // 2 列。列の間は 12px あける
    return [txt(`pf_s${k}_l`, name, x, y, 72, '@ink.sub'), txt(`pf_s${k}_v`, value, x + 72, y, 64, '@ink', { align: 'right' })];
  }),
  txt('pf_fav_l', 'よく辿った試練', 160, 117, 72, '@ink.sub'), txt('pf_fav_v', '天空回廊（41 回）', 232, 117, 200, '@ink'),

  // 右下: 刻まれた名（守り手の本拠地に名が刻まれた日）
  ...heading('pf_carved_h', { ja: '刻まれた名', en: 'Names carved' }, 156, 135, 288, '輪を繋いだ日'),
  txt('pf_c1', 'Anulus II', 160, 153, 80, '@ink'), txt('pf_c1d', '10/12 に繋いだ', 240, 153, 120, '@ink.sub'),
  txt('pf_c2', 'Anulus I', 160, 162, 80, '@ink'), txt('pf_c2d', '10/03 に繋いだ（Catena の一員に）', 240, 162, 200, '@ink.sub'),
], Object.fromEntries(ROMAN.map((_, k) => [`ring${k}`, k < 2 ? 'done' : k === 2 ? 'now' : 'lock'])));

// ======================= お知らせ =======================
// 左: 一覧（1件 2 行 × 7 件、ページ送り）、右: 選んだお知らせの本文と、関係する画面へのボタン
// 見た目の状態: open（選んでいる件 0〜6）、unread0〜6（on / off）、has_link（選んでいる件に関係する画面があるか）。中身はこの試作では見本（本番はプラグインが差し込む）
const NOTICES = [
  ['10/17', '新しい試練「天空回廊」', ['展示場に、Alex さんの新しい試練', '「天空回廊」が並びました。★5 の難しい', '試練です。挑戦した人は 120 人を越えました。'], ['展示場で見る', S('gallery')]],
  ['10/15', 'Anulus IV の Culmen を公開', ['Anulus IV の最後の試練、Culmen を', '公開しました。越えると Anulus V の', '土地へ進めます。'], ['Passus を開く', S('passus')]],
  ['10/12', '設定に「コースの演出音」', ['コースのトリガーで鳴る音の大きさを、', '設定のサウンドで変えられるようにしました。'], ['設定を開く', S('settings_sound')]],
  ['10/01', '試験を始めました', ['Catena の試験を始めました。', '不具合を見つけたら、運営に知らせてください。'], null],
  ['09/28', 'ロビーの門を新しくしました', ['国の入口の門を作り直しました。'], null],
  ['09/20', '名簿に「全期間」を追加', ['名簿で、今週の記録と全期間の記録を', '切り替えられるようにしました。'], ['名簿を開く', S('ledger')]],
  ['09/15', 'Catena へようこそ', ['ようこそ。最初の Limen から、', '鎖を辿ってみてください。'], null],
];
// 1件 1 行（日付と題名）。件の間は 1 行あける。本文は行ごとに分けて渡す
// （Minecraft は空白のところで折り返すので、日本語の文を 1 つの文字にすると英字の後ろの空白で切れる）
const NOTICE_Y = (k) => 45 + k * 18;
const LIST_W = 196, SHEET_X = 210;

const notices = screenBase(S('notices'), '試作: お知らせ', [
  ...ledgerBase(A, -1),
  // 左: 一覧
  nine('nt_list', A.card, 8, 21, LIST_W, 184, { constraints: fixed }),
  ...heading('nt_h', { ja: 'お知らせ', en: 'Notices' }, 14, 27, LIST_W - 12, '未読 3 件'),
  ...NOTICES.flatMap(([date, title], k) => {
    const y = NOTICE_Y(k);
    return [
      nine(`nt_sel${k}`, A.dd_sel, 12, y - 5, LIST_W - 8, 18, { constraints: fixed, state: `open=${k}` }), // 字の中心（y+3.5）が帯の中心
      img(`nt_dot${k}`, A.badge_unread, 16, y + 1, 6, 6, { state: `unread${k}=on` }),
      txt(`nt_date${k}`, date, 26, y, 30, '@ink.faint'),
      txt(`nt_title${k}_u`, title, 62, y, LIST_W - 58, '@ink', { state: `unread${k}=on` }),
      txt(`nt_title${k}_r`, title, 62, y, LIST_W - 58, '@ink.sub', { state: `unread${k}!=on` }),
      click(`nt_hs${k}a`, 8, y, LIST_W, act('dcg:notice_open', { slot: k }), `${title}を読む`),
      click(`nt_hs${k}b`, 8, y + 9, LIST_W, act('dcg:notice_open', { slot: k }), `${title}を読む`),
    ];
  }),
  inst('nt_prev', 'ui_step', 72, 171, 12, 9, 'left', { tooltip: '新しいお知らせ', action: act('dcg:notice_page', { delta: -1 }) }),
  txt('nt_page', '1 / 2', 86, 171, 40, '@ink', { align: 'center' }),
  inst('nt_next', 'ui_step', 128, 171, 12, 9, 'right', { tooltip: '前のお知らせ', action: act('dcg:notice_page', { delta: 1 }) }),
  inst('nt_readall', 'ui_button', 14, 180, LIST_W - 12, 21, 'normal', { label: 'すべて既読にする', tooltip: '未読の印をすべて消す', action: act('dcg:notice_read_all', {}) }),

  // 右: 選んだお知らせ（印刷された掲示の見た目）
  nine('nt_sheet', A.card, SHEET_X, 21, 444 - SHEET_X, 184, { constraints: fixed }),
  ...NOTICES.flatMap(([date, title, lines, link], k) => when(`open=${k}`, [
    txt(`nt_v_title${k}`, title, SHEET_X + 10, 27, 214, '@ink'),
    txt(`nt_v_date${k}`, `2026/${date}`, SHEET_X + 10, 36, 120, '@ink.faint'),
    rect(`nt_v_rule${k}`, SHEET_X + 10, 46, 214, 1, '@ink.sub'),
    ...lines.map((line, j) => txt(`nt_v_body${k}_${j}`, line, SHEET_X + 10, 54 + j * 9, 214, '@ink.sub')),
    txt(link ? `nt_v_link${k}` : `nt_v_nolink${k}`, link ? link[0] : '関係する画面はありません', SHEET_X + 6, 189, 140, link ? '@ink' : '@ink.faint', { align: 'center' }),
  ])),
  // 関係する画面へ（ない件では使えない見た目。押したときの行き先はプラグインが選んでいる件から決める）
  inst('nt_go', 'ui_button_v', SHEET_X + 6, 180, 140, 21, 'normal', { label: '' }, { variantBy: { key: 'has_link', map: { off: 'disabled' }, else: 'normal' } }),
  click('nt_go_hs', SHEET_X + 6, 189, 140, act('dcg:notice_link', {}), 'このお知らせに関係する画面を開く'),
], { open: '0', has_link: 'on', ...Object.fromEntries(NOTICES.map((_, k) => [`unread${k}`, k < 3 ? 'on' : 'off'])) });

// 見出しの書体の見比べ（5x5 Pixel・美咲ゴシック 8 / 16・ゲーム内フォント）
const SAMPLE_JA = '言語　展示場から　守り手の掲示', SAMPLE_EN = 'Language  Anulus III  Passus';
const bakedRow = (id, label, family, size, y, text) => [
  txt(`${id}_l`, label, 14, y, 120, '@ink.sub'),
  { id, type: 'text', x: 140, y: y + (size > 8 ? -4 : 0), w: 300, h: size > 8 ? 17 : 9, text, render: 'baked', fontFamily: family, fontSize: size, pixel: true, wordSpacing: 0, color: '@ink', shadow: false, lineHeight: 1, constraints: fixed },
];
const fontsScreen = screenBase(S('fonts'), '試作: 見出しの書体', [
  ...ledgerBase(A, -1),
  ...heading('f_h', { ja: '見出しの書体', en: 'Heading fonts' }, 14, 27, 424),
  ...bakedRow('f_px', '5x5 Pixel 8（今まで）', '5x5 Pixel', 8, 45, 'LANGUAGE  ANULUS III  PASSUS'),
  ...bakedRow('f_m8', '美咲ゴシック 8', 'Misaki Gothic', 8, 63, SAMPLE_JA),
  ...bakedRow('f_m8e', '美咲ゴシック 8（英字）', 'Misaki Gothic', 8, 72, SAMPLE_EN),
  ...bakedRow('f_m16', '美咲ゴシック 16', 'Misaki Gothic', 16, 99, SAMPLE_JA),
  ...bakedRow('f_m16e', '美咲ゴシック 16（英字）', 'Misaki Gothic', 16, 126, SAMPLE_EN),
  txt('f_game_l', 'ゲーム内フォント（本文）', 14, 153, 120, '@ink.sub'),
  txt('f_game', '言語　展示場から　Language  Anulus III', 140, 153, 300, '@ink'),
]);

const settingsGeneral = [...settingsCommon('general'), ...general(null)];
const settingsSound = [...settingsCommon('sound'), ...sound()];
const screens = [
  home, ...(LIVE ? [] : [fontsScreen]), passus, gallery, ledger, ledgerCourse, profile, notices,
  screenBase(S('settings'), '試作: 設定 - 一般', settingsGeneral, DEFAULTS),
  screenBase(S('settings_ui_lang'), '試作: 設定 - UI言語の一覧', [...settingsCommon('general'), ...general('ui_lang')], DEFAULTS),
  screenBase(S('settings_translate'), '試作: 設定 - 翻訳先の一覧', [...settingsCommon('general'), ...general('translate_lang')], DEFAULTS),
  confirmScreen(S('settings_reset'), '試作: 設定 - 一般を初期値に戻す', settingsGeneral, '一般の設定を初期値に戻しますか？', S('settings'), ['ui_lang', 'translate_auto', 'translate_lang']),
  screenBase(S('settings_sound'), '試作: 設定 - サウンド', settingsSound, DEFAULTS),
  confirmScreen(S('settings_sound_reset'), '試作: 設定 - サウンドを初期値に戻す', settingsSound, 'サウンドの設定を初期値に戻しますか？', S('settings_sound'), ['sound.system', 'sound.course']),
  screenBase(S('settings_profile'), '試作: 設定 - プロフィール', [...settingsCommon('profile'), ...later('profile', 'Profile', '公開範囲・表示する内容など')]),
  screenBase(S('settings_friend'), '試作: 設定 - フレンド', [...settingsCommon('friend'), ...later('friend', 'Friend', 'フレンド申請の受け付けなど（フレンド機能を作るときに決める）')]),
  screenBase(S('settings_chat'), '試作: 設定 - チャット', [...settingsCommon('chat'), ...later('chat', 'Chat', 'チャットチャンネルなど（チャットを設計するときに決める）')]),
];

await ops([
  ...['proto_pause', 'proto_pause_practice'].map((screen) => ({ op: 'remove_screen', screen, ifExists: true })), // 以前の試作（コース中の操作はアイテムで行うのでやめた）
  ...screens.map((s) => ({ op: 'remove_screen', screen: s.id, ifExists: true })),
  ...screens.map((screen) => ({ op: 'add_screen', screen })),
]);
console.log(`作成: 画面 ${screens.map((s) => s.id).join(' / ')}`);
