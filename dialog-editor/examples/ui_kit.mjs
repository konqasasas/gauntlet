// 見本の画面で共通に使う部品（立体的な枠のボタンなど）とテーマの色。矩形と文字・クリック領域の組み合わせで描く。
// 色はすべてテーマの色の名前（@name）で参照するので、エディターのテーマを変えれば全画面の色が変わる。
// 見た目の参考: ユーザー提示の設定画面の画像（resourcepack.ai の social サンプル風）。素材はコピーしていない。
// 使い方: const kit = createKit({ add, row }); kit.pill(...)

// 全画面で共通の外枠の大きさ（見出しを含む本文）。1080p・GUIスケール4（480×270）、フッターなしでスクロールせずに収まる最大
//   W: 本文の幅、H: 本文の高さ（9の倍数）、TOP: 上部の英語見出し・✕の行の高さ
// 例外: 標準の入力欄やフッターのボタンを使う画面（コース検索）は、その分だけ本文を小さくする
export const FRAME = { W: 452, H: 216, TOP: 18 };

// テーマの色の初期値（デザイナーが差し替える前提）。名前と用途は docs/theme.md
export const DEFAULT_TOKENS = {
  'screen.bg': ['#1b1f27f0', '本文全体の背景'],
  'panel.bg': ['#232834ff', '左パネル・内容欄の背景'],
  'panel.border': ['#3b4252ff', 'パネルの枠線'],
  'section.bg': ['#3b4252ff', '見出し帯（並べ替え・言語など）の背景'],
  'section.text': ['#f4e7b0ff', '見出し帯の文字'],
  'heading.text': ['#f4e7b0ff', '画面上部の英語見出し（COURSE SELECT など）'],
  'heading.shadow': ['#5a4a1aff', '英語見出しの影'],
  'text.main': ['#ffffffff', '通常の文字'],
  'text.body': ['#c9ced8ff', '説明文'],
  'text.sub': ['#8a93a3ff', '補足・薄い文字（条件: なし、OFF など）'],
  'text.faint': ['#5c6370ff', 'さらに薄い文字'],
  'text.accent': ['#f2c94cff', '強調の数字（クリア数など）'],
  'button.face': ['#c6c6c6ff', 'ボタンの面'],
  'button.hi': ['#ffffffff', 'ボタンの上・左の明るい線'],
  'button.shadow': ['#7a7a7aff', 'ボタンの下・右の暗い線、区切り線'],
  'button.text': ['#2b2b2bff', 'ボタンの文字'],
  'button.on.face': ['#3b6fe0ff', '選択中のボタン・一覧の行の面'],
  'button.on.hi': ['#7aa2ffff', '選択中のボタンの明るい線'],
  'button.on.shadow': ['#1d3e9cff', '選択中のボタンの暗い線'],
  'button.on.text': ['#ffffffff', '選択中のボタンの文字'],
  'button.danger.face': ['#c25b5bff', '危険な操作（閉じる・初期化）のボタンの面'],
  'button.danger.hi': ['#e89a9aff', '危険なボタンの明るい線'],
  'button.danger.shadow': ['#6e2a2aff', '危険なボタンの暗い線'],
  'button.danger.text': ['#ffffffff', '危険なボタンの文字'],
  'control.outline': ['#000000ff', 'ボタン・スイッチ・スライダーの外枠'],
  'control.track': ['#6f6f6fff', 'スイッチ（OFF）・スライダーの溝'],
  'control.track-shadow': ['#4a4a4aff', '溝の上端の影'],
  'control.on': ['#3b6fe0ff', 'スイッチ（ON）の溝・スライダーの塗り'],
  'control.on-shadow': ['#1d3e9cff', 'ON の溝の影'],
  'control.on-text': ['#7aa2ffff', 'スイッチ横の「ON」の文字'],
  'card.bg': ['#262c37ff', 'コースカードの背景'],
  'card.border': ['#4a5466ff', 'コースカードの枠線'],
  'badge.clear.bg': ['#2e7d32ff', 'クリア済みの印の背景'],
  'badge.clear.border': ['#0b3d0eff', 'クリア済みの印の枠'],
  'fav.on.bg': ['#8e2a4aff', 'お気に入り（登録済み）の背景'],
  'fav.on.border': ['#4a0f22ff', 'お気に入り（登録済み）の枠'],
  'fav.on.icon': ['#ff9ab8ff', 'お気に入り（登録済み）の♥'],
  'fav.off.bg': ['#1b1f27c0', 'お気に入り（未登録）の背景'],
  'fav.off.border': ['#4a5466ff', 'お気に入り（未登録）の枠'],
  'fav.off.icon': ['#8a93a3ff', 'お気に入り（未登録）の♡'],
};

// テーマの色を用意する操作（既にある色は上書きしない＝デザイナーの変更を消さない）
export const themeOps = () => Object.entries(DEFAULT_TOKENS).map(([key, [value]]) => ({ op: 'set_theme_token', key, value, ifMissing: true }));

// 部品の配色（テーマの色の名前で参照する）
export const THEMES = {
  light: { face: '@button.face', hi: '@button.hi', sh: '@button.shadow', text: '@button.text', shadow: false },
  blue: { face: '@button.on.face', hi: '@button.on.hi', sh: '@button.on.shadow', text: '@button.on.text', shadow: true },
  danger: { face: '@button.danger.face', hi: '@button.danger.hi', sh: '@button.danger.shadow', text: '@button.danger.text', shadow: true },
};

// ドット絵のアイコン（'#' が1 GUI px）。フォントの記号は9px行の中で位置が偏り、
// 枠の中央に揃わないため、UIの記号はこちらで描く（デザイナーの画像に差し替えるまでの仮のもの）
export const ICONS = {
  close: ['#...#', '.#.#.', '..#..', '.#.#.', '#...#'],
  left: ['..#', '.##', '###', '.##', '..#'],
  right: ['#..', '##.', '###', '##.', '#..'],
  down: ['#####', '.###.', '..#..'],
  check: ['......#', '.....#.', '#...#..', '.#.#...', '..#....'],
  heart: ['.##.##.', '#######', '#######', '.#####.', '..###..', '...#...'],
  heartOutline: ['.##.##.', '#..#..#', '#.....#', '.#...#.', '..#.#..', '...#...'],
  bell: ['...#...', '..###..', '.#####.', '.#####.', '.#####.', '#######', '...#...'],
  gear: ['..#.#..', '.#####.', '##...##', '.#...#.', '##...##', '.#####.', '..#.#..'],
  lock: ['.###.', '#...#', '#...#', '#####', '##.##', '##.##', '#####'],
};
export const iconSize = (name) => ({ w: ICONS[name][0].length, h: ICONS[name].length });

// ゲーム内フォントの文字のインク幅を測る関数（生成時に使う。data/vanilla が無ければ null）
export async function loadMeasure() {
  try {
    const { getAtlas } = await import('../lib/export.mjs');
    const { glyphInk } = await import('../lib/gametext.mjs');
    const path = await import('node:path');
    const { fileURLToPath } = await import('node:url');
    const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
    const v = await getAtlas(process.env.DCG_VANILLA || path.join(root, 'data', 'vanilla'));
    if (!v.atlas) return null;
    return (label, bold = false) => {
      let a = Infinity, b = -Infinity, pen = 0;
      for (const ch of Array.from(label)) {
        const cp = ch.codePointAt(0), k = glyphInk(v.atlas, cp), g = v.atlas.get(cp);
        if (k) { a = Math.min(a, pen + k.l); b = Math.max(b, pen + k.r + (bold ? g.b : 0)); }
        pen += g ? g.a + (bold ? g.b : 0) : 0;
      }
      return a === Infinity ? { a: 0, b: 0, w: 0 } : { a, b, w: b - a };
    };
  } catch { return null; }
}

/**
 * @param add 要素を追加する関数（add_element の element を受け取る）
 * @param row 行番号 → y（GUI px）
 * @param measure 文字のインク幅を測る関数（loadMeasure の結果。アイコンと文字をまとめて中央に置くのに使う）
 * @param components true なら、合う部品があるときは部品の使用箇所を置く（componentOps の部品）
 */
export function createKit({ add, row, measure = null, components = true }) {
  const rect = (id, x, y, w, h, fill, border = '#00000000', borderWidth = 0) => add({ type: 'rect', id, x, y, w, h, fill, border, borderWidth });
  const text = (id, x, y, w, t, over = {}) => add({ type: 'text', id, x, y, w, h: 9, text: t, render: 'game', color: '@text.main', ...over });
  // 黒枠＋上と左に明るい線、下と右に暗い線
  const bevel = (id, x, y, w, h, theme) => {
    const t = THEMES[theme];
    rect(`${id}_bg`, x, y, w, h, t.face, '@control.outline', 1);
    rect(`${id}_hi1`, x + 1, y + 1, w - 2, 1, t.hi);
    rect(`${id}_hi2`, x + 1, y + 1, 1, h - 2, t.hi);
    rect(`${id}_sh1`, x + 1, y + h - 2, w - 2, 1, t.sh);
    rect(`${id}_sh2`, x + w - 2, y + 1, 1, h - 2, t.sh);
  };
  // ドット絵のアイコン（横に続く画素はまとめて1つの矩形にする）
  const icon = (id, name, x, y, color) => {
    ICONS[name].forEach((line, yy) => {
      for (let xx = 0; xx < line.length;) {
        if (line[xx] !== '#') { xx++; continue; }
        let n = 1; while (line[xx + n] === '#') n++;
        rect(`${id}_${yy}_${xx}`, x + xx, y + yy, n, 1, color);
        xx += n;
      }
    });
  };
  // 枠の中央にアイコンを置く
  const iconIn = (id, name, box, color) => {
    const { w, h } = iconSize(name);
    icon(id, name, box.x + Math.floor((box.w - w) / 2), box.y + Math.floor((box.h - h) / 2), color);
  };
  const hint = (id, x, r, w, action, tooltip) => add({ type: 'hotspot', id: `${id}_hs`, x, y: row(r), w, h: 9, tooltip, action: action || { type: 'none', value: '', payload: '' } });
  // 1行のボタン（高さ13px、文字の行の2px上から）。on で青、opts.theme で指定も可、opts.left で左寄せ、
  // opts.compact で高さ10px（行の1px上から。下端ぎりぎりに置くとき）、opts.tall で高さ16px（アクセント記号が上に出る文字用）
  // opts.icon でアイコン（label が空なら中央、opts.iconRight なら右端、それ以外は左端に置き、文字はその隣）
  const pill = (id, x, r, w, label, on, action, tooltip, opts = {}) => {
    const theme = opts.theme || (on ? 'blue' : 'light');
    if (components) {
      const size = opts.compact ? 'compact' : opts.tall ? 'tall' : 'normal';
      let comp = null;
      if (!opts.icon && !opts.left) comp = BUTTON_COMPONENTS[size];
      else if (opts.icon && !label) comp = ICON_COMPONENTS[`${size}:${opts.icon}:${w}`] && { id: ICON_COMPONENTS[`${size}:${opts.icon}:${w}`].id, h: BUTTON_COMPONENTS[size].h };
      else if (opts.icon === 'left' && !opts.iconRight && !opts.left && label === 'メニュー' && size === 'normal') comp = { id: 'menu_button', h: BUTTON_COMPONENTS.normal.h };
      if (comp) {
        const props = { action: action || { type: 'none', value: '', payload: '' }, tooltip: tooltip || '' };
        if (comp.id.startsWith('button')) props.label = label;
        const el = { type: 'instance', id, name: label || id, component: comp.id, x, y: row(r) - 9, w, h: comp.h, props };
        // variantBy: { key, map, else } で、バリアントを状態に結び付ける（選択肢・タブ）
        if (opts.variantBy) el.variantBy = opts.variantBy;
        else if (theme !== 'light') el.variant = theme;
        return add(el);
      }
    }
    const box = opts.compact ? { x, y: row(r) - 1, w, h: 10 } : opts.tall ? { x, y: row(r) - 4, w, h: 16 } : { x, y: row(r) - 2, w, h: 13 };
    bevel(id, box.x, box.y, box.w, box.h, theme);
    const color = THEMES[theme].text;
    let tx = opts.left ? x + 5 : x, tw = opts.left ? w - 7 : w, align = opts.left ? 'left' : 'center';
    if (opts.icon) {
      const { w: iw, h: ih } = iconSize(opts.icon);
      const iy = box.y + Math.floor((box.h - ih) / 2);
      const m = label && measure && !opts.left ? measure(label) : null;
      if (!label) iconIn(`${id}_i`, opts.icon, box, color);
      else if (m) {
        // アイコンと文字をひとまとまりにして中央へ（間は4px）
        const gw = m.w + 4 + iw, gx = x + Math.round((w - gw) / 2);
        if (opts.iconRight) { tx = Math.round(gx - m.a); icon(`${id}_i`, opts.icon, Math.round(gx + m.w + 4), iy, color); }
        else { icon(`${id}_i`, opts.icon, gx, iy, color); tx = Math.round(gx + iw + 4 - m.a); }
        tw = x + w - tx; align = 'left';
      } else if (opts.iconRight) { icon(`${id}_i`, opts.icon, x + w - 6 - iw, iy, color); tw = w - iw - 8; }
      else { icon(`${id}_i`, opts.icon, x + 6, iy, color); tx = x + 6 + iw + 4; tw = w - (tx - x) - 4; align = 'left'; }
    }
    if (label) text(`${id}_t`, tx, row(r), tw, label, { align, color, shadow: THEMES[theme].shadow });
    hint(id, x, r, w, action, tooltip);
  };
  // アイコンだけの小さなボタン（y は任意。クリック領域は枠と同じで、書き出し時に9px行へ量子化される）
  const iconButton = (id, name, x, y, w, h, theme, action, tooltip) => {
    bevel(id, x, y, w, h, theme);
    iconIn(`${id}_i`, name, { x, y, w, h }, THEMES[theme].text);
    add({ type: 'hotspot', id: `${id}_hs`, x, y, w, h, tooltip, action: action || { type: 'none', value: '', payload: '' } });
  };
  // 縦の一覧（1つの立体枠の中に1行ずつ並べ、選択中の行だけ青。参考画像のドロップダウンの一覧風）
  const stack = (x, r0, w, items) => {
    const id0 = items[0].id;
    bevel(`${id0}_list`, x, row(r0) - 2, w, items.length * 9 + 4, 'light');
    items.forEach((it, i) => {
      if (it.on) rect(`${it.id}_sel`, x + 2, row(r0 + i) - 1, w - 4, 10, THEMES.blue.face);
      const color = it.on ? THEMES.blue.text : THEMES.light.text;
      let tx = x + 5;
      if (it.icon) { const { h: ih } = iconSize(it.icon); icon(`${it.id}_i`, it.icon, x + 5, row(r0 + i) + Math.floor((8 - ih) / 2), it.iconColor || color); tx += iconSize(it.icon).w + 3; }
      text(`${it.id}_t`, tx, row(r0 + i), w - (tx - x) - 2, it.label, { color, shadow: !!it.on });
      hint(it.id, x, r0 + i, w, it.action, it.tooltip);
    });
  };
  // 右上の閉じるボタン（本文の一番上の行。赤い立体ボタン）
  const closeX = (x, action = { type: 'close', value: '', payload: '' }, tooltip = '閉じる（Esc）') => {
    if (components) return add({ type: 'instance', id: 'close_btn', name: '閉じる', component: 'close_button', x, y: 0, w: 13, h: 11, props: { action, tooltip } });
    iconButton('close_btn', 'close', x, 0, 13, 11, 'danger', action, tooltip);
  };
  // 見出しの帯（背景色の帯＋太字）。x〜x+w の幅
  const section = (id, x, r, w, label) => {
    if (components) return add({ type: 'instance', id, name: label, component: 'section', x, y: row(r), w, h: 9, props: { label } });
    rect(`${id}_bar`, x + 1, row(r), w - 2, 9, '@section.bg');
    text(id, x + 6, row(r), w - 12, label, { bold: true, color: '@section.text' });
  };
  // ON/OFF スイッチ（x〜x+TOGGLE_W、文字の行 r）。key の値 on / off で見た目が変わる
  const toggle = (id, x, r, key, action, tooltip) => add({ type: 'instance', id, name: key, component: 'toggle', x, y: row(r) - 9, w: TOGGLE_W, h: 20, variantBy: { key, map: { on: 'on', off: 'off' }, else: 'none' }, props: { action, tooltip } });
  return { rect, text, bevel, hint, pill, stack, closeX, icon, iconIn, iconButton, section, toggle };
}

// ---- 部品（コンポーネント） --------------------------------------------------------
// 見本の画面で繰り返し使う見た目を部品にする。createKit の pill / closeX / section / toggle は、
// 合う部品があれば使用箇所（instance）を置く（デザイナーはマスターを直すだけで全画面を変えられる）。
// 部品の中は、行の上端が9pxの倍数になるよう、文字の行を y=9 に置く（使用箇所の y = 文字の行 - 9）。

const NONE_ACTION = { type: 'none', value: '', payload: '' };
const BUTTON_W = 60;
// ボタンの部品: 大きさの種類 → 部品の id・高さ（背景の枠は 文字の行 -2 / -1 / -4 から）
export const BUTTON_COMPONENTS = { normal: { id: 'button', h: 20 }, compact: { id: 'button_compact', h: 18 }, tall: { id: 'button_tall', h: 21 } };
// アイコンだけのボタン（アイコンを中央に置くので、使用箇所の幅は部品と同じものだけ使う）
export const ICON_COMPONENTS = {
  'compact:left:15': { id: 'icon_left_small', name: '◀（小）' },
  'compact:right:15': { id: 'icon_right_small', name: '▶（小）' },
  'normal:left:13': { id: 'icon_left', name: '◀' },
  'normal:right:13': { id: 'icon_right', name: '▶' },
  'normal:close:13': { id: 'icon_close', name: '✕（小）' },
};
const MENU_BUTTON_W = 92;
export const TOGGLE_W = 216;

// 要素の id の末尾から、伸び縮みの指定を決める（枠は左右に伸び、右下の影は右に、アイコンは中央）
function roleConstraint(id) {
  if (/_hi2$/.test(id)) return 'left';
  if (/_sh2$/.test(id) || /_(track|track_sh|knob_\w+)$/.test(id)) return 'right';
  if (/_i_\d+_\d+$/.test(id)) return 'center';
  return 'stretch';
}

/**
 * 見本で使う部品の定義（add_component の op。replace:true なので何度流しても同じ定義になる）と、
 * 英語の見出しの文字スタイル。各見本の先頭で themeOps() の後に流す。
 */
export function componentOps(measure) {
  const ops = [];
  const build = (fn) => {
    const els = [];
    const k = createKit({ add: (e) => els.push(e), row: (r) => 9 + r * 9, measure, components: false });
    fn(k);
    return els;
  };
  const withRoles = (els, extra = () => ({})) => els.map((e) => ({ ...e, constraints: { h: roleConstraint(e.id), v: 'top' }, ...extra(e) }));
  const clickBinds = { action: 'action', tooltip: 'tooltip' };
  const clickProps = [{ name: 'action', type: 'action', default: NONE_ACTION }, { name: 'tooltip', type: 'text', default: '' }];

  // ボタン（3つの大きさ × 明るい・青・赤）
  for (const [size, def] of Object.entries(BUTTON_COMPONENTS)) {
    const variants = ['light', 'blue', 'danger'];
    let els = [];
    for (const v of variants) {
      const part = build((k) => k.pill(v, 0, 0, BUTTON_W, 'ボタン', false, null, '', { theme: v, compact: size === 'compact', tall: size === 'tall' }));
      els.push(...part.filter((e) => e.type !== 'hotspot').map((e) => ({ ...e, state: `variant=${v}`, ...(e.type === 'text' ? { binds: { text: 'label' } } : {}) })));
      if (v === variants[0]) els.push(...part.filter((e) => e.type === 'hotspot').map((e) => ({ ...e, id: 'hs', binds: clickBinds })));
    }
    // クリック領域は最後（一番前面）に
    els = [...els.filter((e) => e.type !== 'hotspot'), ...els.filter((e) => e.type === 'hotspot')];
    ops.push({ op: 'add_component', replace: true, component: {
      id: def.id, name: { normal: 'ボタン', compact: 'ボタン（低い）', tall: 'ボタン（高い）' }[size], width: BUTTON_W, height: def.h, variants,
      props: [{ name: 'label', type: 'text', default: 'ボタン' }, ...clickProps], elements: withRoles(els),
    } });
  }
  // アイコンだけのボタン
  for (const [key, def] of Object.entries(ICON_COMPONENTS)) {
    const [size, icon, w] = key.split(':');
    const els = build((k) => k.pill('b', 0, 0, Number(w), '', false, null, '', { icon, compact: size === 'compact' }));
    ops.push({ op: 'add_component', replace: true, component: {
      id: def.id, name: def.name, width: Number(w), height: BUTTON_COMPONENTS[size].h, variants: ['light'], props: clickProps,
      elements: withRoles(els, (e) => (e.type === 'hotspot' ? { binds: clickBinds } : {})),
    } });
  }
  // 「メニュー」に戻るボタン（アイコンと文字のまとまりを中央に）
  {
    const els = build((k) => k.pill('b', 0, 0, MENU_BUTTON_W, 'メニュー', false, null, '', { icon: 'left' }));
    ops.push({ op: 'add_component', replace: true, component: {
      id: 'menu_button', name: 'メニューへ戻る', width: MENU_BUTTON_W, height: BUTTON_COMPONENTS.normal.h, variants: ['light'], props: clickProps,
      elements: withRoles(els, (e) => (e.type === 'hotspot' ? { binds: clickBinds } : e.type === 'text' ? { constraints: { h: 'center', v: 'top' } } : {})),
    } });
  }
  // 右上の閉じるボタン
  {
    const els = build((k) => k.closeX(0));
    ops.push({ op: 'add_component', replace: true, component: {
      id: 'close_button', name: '閉じる✕', width: 13, height: 11, variants: ['normal'],
      props: [{ name: 'action', type: 'action', default: { type: 'close', value: '', payload: '' } }, { name: 'tooltip', type: 'text', default: '閉じる（Esc）' }],
      elements: els.map((e) => ({ ...e, ...(e.type === 'hotspot' ? { binds: clickBinds } : {}) })),
    } });
  }
  // 見出しの帯（背景色の帯＋太字）
  ops.push({ op: 'add_component', replace: true, component: {
    id: 'section', name: '見出しの帯', width: 100, height: 9, variants: ['normal'], props: [{ name: 'label', type: 'text', default: '見出し' }],
    elements: [
      { type: 'rect', id: 'bar', x: 1, y: 0, w: 98, h: 9, fill: '@section.bg', border: '#00000000', borderWidth: 0, constraints: { h: 'stretch', v: 'top' } },
      { type: 'text', id: 'label', x: 6, y: 0, w: 88, h: 9, text: '見出し', render: 'game', bold: true, color: '@section.text', binds: { text: 'label' }, constraints: { h: 'stretch', v: 'top' } },
    ],
  } });
  // ON/OFF スイッチ（右寄せ。ON は青い溝につまみが右、OFF は灰色の溝につまみが左）。
  // バリアント none は何も出さない（状態の値が on / off 以外のとき）
  {
    const W = TOGGLE_W;
    const els = [];
    const k = createKit({ add: (e) => els.push(e), row: (r) => 9 + r * 9, measure, components: false });
    const w = 30, x = W - w, y = 7;
    for (const on of [true, false]) {
      const v = on ? 'on' : 'off';
      const start = els.length;
      k.rect(`${v}_track`, x, y + 2, w, 9, on ? '@control.on' : '@control.track', '@control.outline', 1);
      k.rect(`${v}_track_sh`, x + 1, y + 3, w - 2, 1, on ? '@control.on-shadow' : '@control.track-shadow');
      k.bevel(`${v}_knob`, on ? x + w - 11 : x, y, 11, 13, 'light');
      k.text(`${v}_state`, 0, 9, x - 6, on ? 'ON' : 'OFF', { align: 'right', color: on ? '@control.on-text' : '@text.sub' });
      for (let i = start; i < els.length; i++) els[i].state = `variant=${v}`;
    }
    k.hint('switch', 0, 0, W, null, '');
    ops.push({ op: 'add_component', replace: true, component: {
      id: 'toggle', name: 'ON/OFF スイッチ', width: W, height: 20, variants: ['off', 'on', 'none'], props: clickProps,
      elements: els.map((e) => ({ ...e, constraints: { h: e.type === 'text' || e.type === 'hotspot' ? 'stretch' : 'right', v: 'top' }, ...(e.type === 'hotspot' ? { binds: clickBinds } : {}) })),
    } });
  }
  // 英語の見出し（5x5 Pixel を焼き込み）
  ops.push({ op: 'set_text_style', key: 'heading', style: { name: '英語の見出し', render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '@heading.text', shadow: true, shadowColor: '@heading.shadow', lineHeight: 1 } });
  return ops;
}
