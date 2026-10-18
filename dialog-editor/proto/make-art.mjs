// 試作メニュー（docs/gui-design.md）の部品の絵を、テーマの色から PNG で作る。
// 画像の色はテーマで変わらないので、テーマの色を変えたらこのスクリプトを流し直して素材を差し替える。
//
//   node proto/make-art.mjs [--url http://127.0.0.1:4320] [-o proto/art]
//
// 1画素 = 0.5 GUI px（テクスチャ倍率 2）。寸法のコメントは画素で書く。
import fs from 'node:fs';
import path from 'node:path';
import { encodePNG } from '../lib/binary.mjs';

const args = process.argv.slice(2);
const opt = (name, def) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : def; };
const BASE = (opt('--url', process.env.DCG_URL || 'http://127.0.0.1:4320')).replace(/\/$/, '');
const OUT = opt('-o', path.join(import.meta.dirname, 'art'));

// 試作で使うテーマの色（docs/gui-design.md の 2.1）。エディターに無い色だけ足す（エディターで変えた色はそのまま使う）
const PALETTE = {
  'frame.stone': '#4a4843ff', 'frame.stone.dark': '#2e2c29ff', 'frame.stone.hi': '#6a675fff',
  brass: '#b8924aff', 'brass.hi': '#e2c27aff', 'brass.dark': '#6e5424ff',
  paper: '#e9dfc6ff', 'paper.dark': '#cdbf9cff', 'paper.hi': '#f6efdcff',
  ink: '#2a241bff', 'ink.sub': '#5e5444ff', 'ink.faint': '#8f8470ff',
  verdigris: '#4e8a7cff', 'verdigris.dark': '#2c5249ff', vermilion: '#a5432fff', 'vermilion.dark': '#5e2116ff',
  'sky.1': '#9cc3d9ff', 'sky.2': '#e0a560ff', 'sky.3': '#b0574aff', 'sky.4': '#5a3d6bff', 'sky.5': '#232347ff',
  // ランク（Anulus I〜XI）の色。チャットや TAB の名前と同じ色（gauntlet の src/main/resources/display.yml の ranks.styles）。
  // VIII〜XI は2色のグラデーションで、rank.N.to が2色目。紙の上では読めない明るさなので、文字ではなく札の地に使う
  'rank.1': '#dfe6eaff', 'rank.2': '#a6d4f2ff', 'rank.3': '#74b6f0ff', 'rank.4': '#6f93eeff', 'rank.5': '#f7e07aff', 'rank.6': '#ffb98aff',
  'rank.7': '#f25d78ff', 'rank.8': '#e97ac8ff', 'rank.8.to': '#f25d78ff', 'rank.9': '#c98bf0ff', 'rank.9.to': '#e97ac8ff',
  'rank.10': '#9f8ff5ff', 'rank.10.to': '#c98bf0ff', 'rank.11': '#9f8ff5ff', 'rank.11.to': '#e8c97fff',
};
{
  const { revision } = await (await fetch(`${BASE}/api/project`)).json();
  const r = await fetch(`${BASE}/api/ops`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ revision, operations: Object.entries(PALETTE).map(([key, value]) => ({ op: 'set_theme_token', key, value, ifMissing: true })) }) });
  if (!r.ok) throw new Error(`テーマの色を足せません: ${await r.text()}`);
}

const res = await fetch(`${BASE}/api/project`);
if (!res.ok) throw new Error(`エディターに接続できません: ${res.status}`);
const { project } = await res.json();
const T = project.theme.tokens;

// ---- 色 ----
function rgba(hex) {
  const h = hex.replace('#', '');
  return [0, 2, 4, 6].map((i) => (i < h.length ? parseInt(h.slice(i, i + 2), 16) : 255));
}
const C = (name) => { if (!T[name]) throw new Error(`テーマの色 ${name} がありません`); return rgba(T[name]); };
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const alpha = (c, a) => [c[0], c[1], c[2], Math.round(255 * a)];

// ---- 画像 ----
class Img {
  constructor(w, h) { this.w = w; this.h = h; this.d = new Uint8Array(w * h * 4); }
  px(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || !c) return;
    const i = (y * this.w + x) * 4, a = c[3] / 255;
    if (a >= 1 || this.d[i + 3] === 0) { this.d.set(a >= 1 ? c : c, i); return; }
    const da = this.d[i + 3] / 255, oa = a + da * (1 - a);
    for (let k = 0; k < 3; k++) this.d[i + k] = Math.round((c[k] * a + this.d[i + k] * da * (1 - a)) / oa);
    this.d[i + 3] = Math.round(oa * 255);
  }
  clear(x, y) { if (x >= 0 && y >= 0 && x < this.w && y < this.h) this.d.fill(0, (y * this.w + x) * 4, (y * this.w + x) * 4 + 4); }
  rect(x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) this.px(i, j, c); }
  frame(x, y, w, h, t, c) { this.rect(x, y, w, t, c); this.rect(x, y + h - t, w, t, c); this.rect(x, y, t, h, c); this.rect(x + w - t, y, t, h, c); }
  draw(x, y, rows, map) { rows.forEach((r, j) => [...r].forEach((ch, i) => { if (map[ch]) this.px(x + i, y + j, map[ch]); })); }
  save(name) { fs.writeFileSync(path.join(OUT, `${name}.png`), encodePNG(this.d, this.w, this.h)); }
}

// 決まった並びの乱数（作り直しても同じ絵になる）
function rng(seed) { let s = seed >>> 0; return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32); }

// 紙の地（ごく薄いむら）
function paperFill(img, x, y, w, h, base, seed = 1) {
  const r = rng(seed), dark = C('paper.dark');
  img.rect(x, y, w, h, base);
  for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) {
    const v = r();
    if (v < 0.06) img.px(i, j, alpha(dark, 0.35));
    else if (v < 0.09) img.px(i, j, alpha(C('paper.hi'), 0.6));
  }
}

// 切石積みの石の地（32×32 でつながる）
function stoneTile(img, x0, y0, seed = 7) {
  const r = rng(seed), s = C('frame.stone'), d = C('frame.stone.dark'), hi = C('frame.stone.hi');
  img.rect(x0, y0, 32, 32, s);
  for (let j = 0; j < 32; j++) for (let i = 0; i < 32; i++) {
    const v = r();
    if (v < 0.08) img.px(x0 + i, y0 + j, alpha(d, 0.5));
    else if (v < 0.12) img.px(x0 + i, y0 + j, alpha(hi, 0.35));
  }
  // 目地: 高さ 16 の段、段ごとに 16 ずらす
  for (let row = 0; row < 2; row++) {
    const y = y0 + row * 16;
    for (let i = 0; i < 32; i++) img.px(x0 + i, y, alpha(d, 0.8));
    for (let i = 0; i < 32; i++) img.px(x0 + i, y + 1, alpha(hi, 0.25));
    const off = row ? 16 : 0;
    for (let j = 1; j < 16; j++) { img.px(x0 + off, y + j, alpha(d, 0.8)); img.px(x0 + off + 1, y + j, alpha(hi, 0.2)); }
  }
}

fs.mkdirSync(OUT, { recursive: true });
const made = [];
const out = (name, img, slice, note) => { img.save(name); made.push({ name, w: img.w, h: img.h, slice, note }); };

// 落ち影。状態で出し分けることのある部品（ボタン・セグメント・チップ・◀ ▶）は重ね絵になるので、半透明ではなく
// 紙の上の影と同じ色の不透明な色にする
const SHADOW = () => mix(C('paper'), C('ink'), 0.35);

// ---- ボタン（40×32 = 20×16 GUI px）----
// 縦: 0-1 縁 / 2-3 明るい縁 / 4-25 面 / 26-27 暗い縁 / 28-29 縁 / 30-31 落ち影
// 部品では y=5 に置く。y=9 の行の文字（8px）は画素 8〜23 に来るので、罫は 5 と 25 に引いて重ならないようにする
// 横: 両端の 10 画素（5 GUI px）は飾りの場所で伸ばさない。真ん中は横に伸ばすので、横に変化を付けない
const BW = 40, BH = 32, FACE0 = 4, FACE1 = 26; // 面は FACE0 から FACE1 の手前まで
const WHITE = [255, 255, 255, 255];
function button(kind) {
  const img = new Img(BW, BH);
  const metal = (face, hi, dark) => ({ edge: dark, hi, face, low: mix(face, dark, 0.55), shadow: true,
    grad: (y) => (y < 10 ? mix(hi, face, (y - 4) / 6) : y < 18 ? face : mix(face, dark, ((y - 18) / 8) * 0.45)) });
  const look = {
    // 真鍮の板: 彫りの線と、両端の鋲
    primary: { ...metal(C('brass'), C('brass.hi'), C('brass.dark')), engrave: true, stud: 'brass' },
    // 紙の札: 印刷物のような太い枠＋細い罫、両端に菱形の飾り
    normal: { edge: C('ink'), hi: C('paper.hi'), face: C('paper'), low: mix(C('paper'), C('paper.dark'), 0.8), shadow: true,
      grad: (y) => (y > 22 ? mix(C('paper'), C('paper.dark'), 0.4) : C('paper')), rule: C('ink.sub'), diamond: C('ink.sub') },
    // 朱の漆の板: 上に艶、彫りの線、暗い鋲
    danger: { ...metal(C('vermilion'), mix(C('vermilion'), WHITE, 0.35), C('vermilion.dark')), engrave: true, stud: 'vermilion', gloss: true },
    // 色あせた札: 影なし、罫と飾りは薄く、飾りは中抜き
    disabled: { edge: C('ink.faint'), hi: C('paper.dark'), face: C('paper.dark'), low: C('paper.dark'), shadow: false,
      grad: () => C('paper.dark'), rule: alpha(C('ink.faint'), 0.7), diamond: C('ink.faint'), hollow: true },
  }[kind];

  if (look.shadow) img.rect(2, 30, BW - 2, 2, SHADOW());
  img.rect(0, 0, BW, 30, look.edge);
  for (let y = FACE0; y < FACE1; y++) img.rect(2, y, BW - 4, 1, look.grad(y));
  img.rect(2, 2, BW - 4, 2, look.hi);
  img.rect(2, FACE1, BW - 4, 2, look.low);
  img.rect(2, FACE0, 1, FACE1 - FACE0, mix(look.hi, look.grad(15), 0.5)); // 左の縁を少し明るく
  img.rect(BW - 3, FACE0, 1, FACE1 - FACE0, look.low);                   // 右の縁を少し暗く
  if (look.gloss) img.rect(4, 4, BW - 8, 2, alpha(WHITE, 0.3));

  // 内側の線（左右は飾りの内側 x=9 / BW-10）
  const rx0 = 9, rx1 = BW - 10, ry0 = 5, ry1 = 24; // 文字は 8〜23 に来るので、罫はその外
  if (look.engrave) {
    const d = look.edge, h = look.hi;
    // 彫りの線: 暗い線の下に 1 画素の照り
    img.rect(rx0, ry0, rx1 - rx0 + 1, 1, d); img.rect(rx0 + 1, ry0 + 1, rx1 - rx0 - 1, 1, alpha(h, 0.6));
    img.rect(rx0, ry1, rx1 - rx0 + 1, 1, d); img.rect(rx0, ry1 + 1, rx1 - rx0 + 1, 1, alpha(h, 0.6));
    img.rect(rx0, ry0, 1, ry1 - ry0 + 1, d); img.rect(rx1, ry0, 1, ry1 - ry0 + 1, d);
  } else if (look.rule) {
    img.rect(rx0, ry0, rx1 - rx0 + 1, 1, look.rule); img.rect(rx0, ry1, rx1 - rx0 + 1, 1, look.rule);
    img.rect(rx0, ry0, 1, ry1 - ry0 + 1, look.rule); img.rect(rx1, ry0, 1, ry1 - ry0 + 1, look.rule);
  }

  // 両端の飾り（中心 x=5 / BW-6、y=15 のあたり＝面の中央）
  for (const [side, cx] of [[0, 5], [1, BW - 6]]) {
    if (look.stud) {
      const sx = side ? BW - 7 : 3; // 端から 3 画素
      const hi = look.stud === 'brass' ? C('brass.hi') : mix(C('vermilion'), WHITE, 0.45);
      const mid = look.stud === 'brass' ? C('brass') : C('vermilion');
      const dk = look.edge;
      img.draw(sx, 13, ['.dd.', 'dhmd', 'dmmd', '.dd.'], { d: dk, h: hi, m: mid });
      img.px(sx + 1, 17, alpha(WHITE, 0.35)); // 鋲の下の照り返し
    } else if (look.diamond) {
      const rows = look.hollow ? ['..#..', '.#.#.', '#...#', '.#.#.', '..#..'] : ['..#..', '.###.', '#####', '.###.', '..#..'];
      img.draw(cx - 2, 13, rows, { '#': look.diamond });
    }
  }

  // 角を丸める（半径 2）
  for (const [x, y] of [[0, 0], [1, 0], [0, 1], [BW - 1, 0], [BW - 2, 0], [BW - 1, 1], [0, 29], [1, 29], [0, 28], [BW - 1, 29], [BW - 2, 29], [BW - 1, 28]]) img.clear(x, y);
  out(`btn_${kind}`, img, { l: 10, t: 10, r: 10, b: 12 }, `ボタン（${kind}）`);
}
for (const k of ['normal', 'primary', 'danger', 'disabled']) button(k);

// 円（つまみ・鋲）。中心と半径は画素、照りは左上、影は右下
function disc(img, cx, cy, r, face, hi, dark) {
  for (let y = -r; y <= r; y++) for (let x = -r; x <= r; x++) {
    const d = Math.hypot(x + 0.5, y + 0.5);
    if (d > r) continue;
    const edge = d > r - 1.5;
    const lit = x + y < -r * 0.6;
    img.px(cx + x, cy + y, edge ? dark : lit ? hi : face);
  }
}

// 真鍮の丸いつまみ（トグルとスライダーで共通）。中心 (cx, cy) は画素の境目。直径 16 画素＝8 GUI px
function knob(img, cx, cy) {
  disc(img, cx, cy, 8, C('brass'), C('brass.hi'), C('brass.dark'));
  img.rect(cx - 1, cy - 1, 2, 2, C('brass.dark')); // 中心の刻み
}

// ---- トグル 44×18 = 22×9 GUI px（1行に収まる）----
// 本体は 0〜15 行で、中心（8 画素＝4 GUI px）を文字の行の中心とスライダーの溝の中心に揃える。下の 2 行は空き
// 印やつまみは偶数の幅・高さにして、中心がちょうど画素の境目に来るようにする
// 溝の中に真鍮のつまみ。ON はつまみが右で溝が緑青、OFF はつまみが左で溝が灰
function toggle(on) {
  const img = new Img(44, 18);
  const groove = on ? C('verdigris') : mix(C('frame.stone.hi'), C('paper.dark'), 0.35);
  img.rect(0, 0, 44, 16, C('ink.sub'));
  img.rect(2, 2, 40, 12, groove);
  img.rect(2, 2, 40, 2, alpha(C('ink'), 0.35)); // 溝の内側の影
  if (on) img.rect(2, 12, 40, 2, alpha(mix(groove, WHITE, 0.4), 0.8));
  for (const [x, y] of [[0, 0], [1, 0], [0, 1], [43, 0], [42, 0], [43, 1], [0, 15], [1, 15], [0, 14], [43, 15], [42, 15], [43, 14]]) img.clear(x, y);
  // 溝の目盛り（ON 側に小さな縦線、OFF 側に小さな丸）
  if (on) img.rect(9, 6, 2, 4, alpha(C('paper.hi'), 0.9));
  else img.draw(31, 6, ['.##.', '#..#', '#..#', '.##.'], { '#': C('ink.faint') });
  knob(img, on ? 35 : 9, 8); // 左右とも、溝の縁から 1 画素あけた位置
  out(on ? 'toggle_on' : 'toggle_off', img, null, `トグル（${on ? 'ON' : 'OFF'}）`);
}
toggle(true); toggle(false);

// ---- セグメントの区切り 24×32 = 12×16 GUI px（ボタンと同じ縦の作り）----
// pos: l（左端・左が丸い）/ m（中）/ r（右端・右に縁があり丸い）。左の縁が区切り線になる
function segment(pos, on) {
  const img = new Img(24, 32);
  const edge = on ? C('brass.dark') : C('ink');
  const face = on ? C('brass') : C('paper'), hi = on ? C('brass.hi') : C('paper.hi');
  const low = on ? mix(C('brass'), C('brass.dark'), 0.55) : mix(C('paper'), C('paper.dark'), 0.8);
  img.rect(0, 30, 24, 2, SHADOW());
  img.rect(0, 0, 24, 30, edge);
  const x0 = 2, x1 = pos === 'r' ? 22 : 24;
  for (let y = 4; y < 26; y++) img.rect(x0, y, x1 - x0, 1, on ? (y < 10 ? mix(hi, face, (y - 4) / 6) : y < 18 ? face : mix(face, C('brass.dark'), ((y - 18) / 8) * 0.45)) : y > 22 ? mix(face, C('paper.dark'), 0.4) : face);
  img.rect(x0, 2, x1 - x0, 2, hi);
  img.rect(x0, 26, x1 - x0, 2, low);
  if (!on) img.rect(x0, 4, x1 - x0, 1, alpha(C('ink'), 0.12)); // 選んでいない区切りは少し沈める
  // 控えめな装飾: 上下の細い罫（区切りをまたいで 1 本の帯につながる）と、全体の両端だけに小さな輪
  // 罫は 5 行と 24 行（文字は 8〜23 行）。選んでいる区切りは浅い彫り、ほかは薄いインク
  const rule = on ? alpha(C('brass.dark'), 0.6) : alpha(C('ink.faint'), 0.55);
  const shine = on ? alpha(C('brass.hi'), 0.5) : null;
  const r0 = pos === 'l' ? 8 : 2, r1 = pos === 'r' ? 15 : 23; // 罫の左右の端（両端の区切りは輪の内側で止める）
  for (const y of [5, 24]) { img.rect(r0, y, r1 - r0 + 1, 1, rule); if (shine) img.rect(r0, y + 1, r1 - r0 + 1, 1, shine); }
  const ring = ['.##.', '#..#', '#..#', '.##.'], ringC = on ? C('brass.dark') : alpha(C('ink.faint'), 0.7);
  if (pos === 'l') { img.rect(8, 5, 1, 20, rule); img.draw(3, 13, ring, { '#': ringC }); }
  if (pos === 'r') { img.rect(15, 5, 1, 20, rule); img.draw(17, 13, ring, { '#': ringC }); }
  const round = [];
  if (pos === 'l') round.push([0, 0], [1, 0], [0, 1], [0, 29], [1, 29], [0, 28]);
  if (pos === 'r') round.push([23, 0], [22, 0], [23, 1], [23, 29], [22, 29], [23, 28]);
  for (const [x, y] of round) img.clear(x, y);
  out(`seg_${pos}_${on ? 'on' : 'off'}`, img, { l: pos === 'l' ? 10 : 6, t: 10, r: pos === 'r' ? 10 : 6, b: 12 }, `セグメント（${pos}・${on ? '選択' : '未選択'}）`);
}
for (const pos of ['l', 'm', 'r']) for (const on of [true, false]) segment(pos, on);

// ---- チップ 24×24 = 12×12 GUI px。左の 12 画素は印（✓ / ○）の場所 ----
function chip(on) {
  const img = new Img(24, 24);
  const edge = on ? C('verdigris.dark') : C('ink.sub'), face = on ? C('verdigris') : C('paper');
  img.rect(1, 22, 23, 2, SHADOW());
  img.rect(0, 0, 24, 22, edge);
  img.rect(2, 2, 20, 18, face);
  img.rect(2, 2, 20, 2, on ? mix(face, WHITE, 0.3) : C('paper.hi'));
  img.rect(2, 18, 20, 2, on ? mix(face, C('verdigris.dark'), 0.4) : mix(face, C('paper.dark'), 0.7));
  // 丸い角（半径 4）
  for (const [x, y] of [[0, 0], [1, 0], [2, 0], [3, 0], [0, 1], [1, 1], [0, 2], [0, 3]]) { img.clear(x, y); img.clear(x, 21 - y); img.clear(23 - x, y); img.clear(23 - x, 21 - y); }
  for (const [x, y] of [[2, 2], [2, 19]]) img.px(x, y, edge); // 丸い角の縁
  for (const [x, y] of [[21, 2], [21, 19]]) img.px(x, y, edge);
  // 印は 6 行（8〜13 行）で、本体（0〜21 行）の上下の真ん中。横は左の印の場所（2〜11）の真ん中
  if (on) img.draw(3, 8, ['.......#', '......##', '#....##.', '##..##..', '.####...', '..##....'], { '#': C('paper.hi') });
  else img.draw(5, 8, ['.##.', '#..#', '#..#', '#..#', '#..#', '.##.'], { '#': C('ink.faint') });
  out(on ? 'chip_on' : 'chip_off', img, { l: 12, t: 8, r: 6, b: 8 }, `チップ（${on ? 'ON' : 'OFF'}）`);
}
chip(true); chip(false);

// ---- スライダー: 溝 200×16 = 100×8 GUI px（10目盛り）、満ち 16×12（9分割）、つまみ 16×16 ----
{
  const img = new Img(200, 16);
  img.rect(0, 0, 200, 16, C('ink.sub'));
  img.rect(2, 2, 196, 12, mix(C('paper.dark'), C('ink.faint'), 0.25));
  img.rect(2, 2, 196, 2, alpha(C('ink'), 0.3));
  for (let k = 1; k < 10; k++) img.rect(k * 20 - 1, 4, 2, 8, alpha(C('ink.sub'), 0.6)); // 目盛り
  for (const [x, y] of [[0, 0], [199, 0], [0, 15], [199, 15]]) img.clear(x, y);
  out('slider_track', img, null, 'スライダーの溝');
}
{
  const img = new Img(16, 12);
  img.rect(0, 0, 16, 12, C('brass.dark'));
  for (let y = 1; y < 11; y++) img.rect(0, y, 16, 1, y < 5 ? mix(C('brass.hi'), C('brass'), (y - 1) / 4) : C('brass'));
  img.rect(0, 10, 16, 1, mix(C('brass'), C('brass.dark'), 0.5));
  out('slider_fill', img, { l: 2, t: 2, r: 2, b: 2 }, 'スライダーの満ち');
}
{
  // トグルのつまみと同じ（16×16 = 8×8 GUI px）。溝（8 GUI px）と上下の中心が揃う
  const img = new Img(16, 16);
  knob(img, 8, 8);
  out('slider_knob', img, null, 'スライダーのつまみ');
}
// ◀ ▶ の小さな板 24×18 = 12×9 GUI px
function step(dir) {
  const img = new Img(24, 18);
  img.rect(0, 0, 24, 16, C('brass.dark'));
  img.rect(2, 2, 20, 12, C('brass'));
  img.rect(2, 2, 20, 2, C('brass.hi'));
  img.rect(2, 12, 20, 2, mix(C('brass'), C('brass.dark'), 0.5));
  img.rect(1, 16, 23, 2, SHADOW());
  for (const [x, y] of [[0, 0], [23, 0], [0, 15], [23, 15]]) img.clear(x, y);
  const tri = dir === 'left' ? ['....##', '..####', '######', '######', '..####', '....##'] : ['##....', '####..', '######', '######', '####..', '##....'];
  img.draw(9, 5, tri, { '#': C('ink') }); // 5〜10 行で、本体（0〜15 行）の真ん中
  out(`step_${dir}`, img, null, `スライダーの ${dir === 'left' ? '◀' : '▶'}`);
}
step('left'); step('right');

// ---- ドロップダウンの欄 64×32 = 32×16 GUI px。くぼんだ紙の欄と、右端の真鍮の ▼ ----
function dropdown(open) {
  const img = new Img(64, 32);
  const edge = open ? C('brass.dark') : C('ink.sub');
  img.rect(0, 0, 64, 30, edge);
  img.rect(2, 2, 60, 26, C('paper.hi'));
  img.rect(2, 2, 60, 2, alpha(C('ink'), 0.25)); // くぼみの影（上・左）
  img.rect(2, 2, 2, 26, alpha(C('ink'), 0.15));
  img.rect(2, 26, 60, 2, alpha(C('paper'), 1));
  // 右端の ▼ の板（24 画素）
  img.rect(40, 2, 2, 26, edge);
  img.rect(42, 2, 20, 26, C('brass'));
  img.rect(42, 2, 20, 3, C('brass.hi'));
  img.rect(42, 24, 20, 4, mix(C('brass'), C('brass.dark'), 0.5));
  const tri = open ? ['...##...', '..####..', '.######.', '########'] : ['########', '.######.', '..####..', '...##...'];
  img.draw(48, 13, tri, { '#': C('ink') });
  for (const [x, y] of [[0, 0], [63, 0], [0, 29], [63, 29]]) img.clear(x, y);
  out(open ? 'dropdown_open' : 'dropdown', img, { l: 6, t: 10, r: 26, b: 12 }, `ドロップダウンの欄（${open ? '開いている' : '閉じている'}）`);
}
dropdown(false); dropdown(true);
// 開いた一覧の面 32×32（右と下に影）、選んでいる行の帯 16×18、✓ 14×12
{
  const img = new Img(32, 32);
  img.rect(4, 4, 28, 28, alpha(C('ink'), 0.35));
  img.rect(0, 0, 28, 28, C('brass.dark'));
  img.rect(2, 2, 24, 24, C('paper.hi'));
  out('dd_list', img, { l: 6, t: 6, r: 10, b: 10 }, 'ドロップダウンの一覧の面');
}
{
  const img = new Img(16, 18);
  img.rect(0, 0, 16, 18, mix(C('paper.hi'), C('brass.hi'), 0.55));
  img.rect(0, 0, 3, 18, C('brass'));
  out('dd_sel', img, { l: 4, t: 0, r: 2, b: 0 }, '一覧の選んでいる行');
}
{
  const img = new Img(14, 12);
  img.draw(0, 1, ['..........##', '.........##.', '........##..', '##.....##...', '.##...##....', '..##.##.....', '...###......', '....#.......'], { '#': C('ink') });
  out('check', img, null, '✓');
}

// 状態で出し分ける絵（重ね絵）は半透明の画素を使わない。重ねて描くときの合成が、検証で再構成した画像とずれるため

// ---- 鎖の輪 28×18 = 14×9 GUI px（横長の輪）----
// done: つないだ輪（真鍮）/ now: 今の時代（明るい真鍮と、内側に空の色）/ lock: この先（薄いインクの線だけ）
function chainRing(kind) {
  const img = new Img(28, 18);
  const cx = 14, cy = 9;
  const look = {
    done: { face: C('brass'), hi: C('brass.hi'), dark: C('brass.dark') },
    now: { face: C('brass.hi'), hi: mix(C('brass.hi'), WHITE, 0.5), dark: C('brass.dark'), glow: mix(C('sky.2'), C('paper.hi'), 0.45) },
    lock: { face: C('ink.faint'), hi: mix(C('ink.faint'), C('paper'), 0.3), dark: C('ink.faint') },
  }[kind];
  for (let y = 0; y < 18; y++) for (let x = 0; x < 28; x++) {
    const dx = (x + 0.5 - cx), dy = (y + 0.5 - cy);
    const o = (dx / 13) ** 2 + (dy / 8) ** 2, n = (dx / 8.5) ** 2 + (dy / 3.8) ** 2;
    if (o > 1) continue;
    if (n < 1) { if (look.glow) img.px(x, y, look.glow); continue; }
    const rim = o > 0.78 || n < 1.35;
    img.px(x, y, rim ? look.dark : dy < 0 ? look.hi : look.face);
  }
  out(`ring_${kind}`, img, null, `鎖の輪（${kind}）`);
}
for (const k of ['done', 'now', 'lock']) chainRing(k);

// ---- Limen の札 12×12 = 6×6 GUI px と、Culmen の札 16×16 = 8×8 GUI px ----
function limenNode(kind) {
  const img = new Img(12, 12);
  if (kind === 'done') { img.rect(0, 0, 12, 12, C('brass.dark')); img.rect(2, 2, 8, 8, C('brass')); img.rect(2, 2, 8, 2, C('brass.hi')); }
  if (kind === 'now') { img.rect(0, 0, 12, 12, C('brass.dark')); img.rect(2, 2, 8, 8, C('paper.hi')); img.rect(4, 4, 4, 4, C('brass')); }
  if (kind === 'todo') { img.rect(0, 0, 12, 12, C('ink.faint')); img.rect(2, 2, 8, 8, C('paper')); }
  for (const [x, y] of [[0, 0], [11, 0], [0, 11], [11, 11]]) img.clear(x, y);
  out(`limen_${kind}`, img, null, `Limen の札（${kind}）`);
}
for (const k of ['done', 'now', 'todo']) limenNode(k);
{ // この先の時代の札（薄い枠と小さな錠）
  const img = new Img(12, 12);
  img.rect(0, 0, 12, 12, mix(C('ink.faint'), C('paper'), 0.4)); img.rect(2, 2, 8, 8, C('paper.dark'));
  img.draw(3, 2, ['.##.', '#..#', '####', '####', '####'].map((r) => r.padStart(5, '.')), { '#': C('ink.faint') });
  for (const [x, y] of [[0, 0], [11, 0], [0, 11], [11, 11]]) img.clear(x, y);
  out('limen_lock', img, null, 'Limen の札（この先）');
}
{ // 選んでいる時代の印（真鍮の上向きの三角）10×6 = 5×3 GUI px
  const img = new Img(10, 6);
  img.draw(0, 0, ['....##....', '...####...', '..######..', '.########.', '##########', 'dddddddddd'], { '#': C('brass.hi'), d: C('brass.dark') });
  out('sel_caret', img, null, '選んでいる時代の印');
}
{
  const img = new Img(16, 16);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const d = Math.abs(x + 0.5 - 8) + Math.abs(y + 0.5 - 8);
    if (d <= 8) img.px(x, y, d > 6.5 ? C('ink.sub') : y < 8 ? mix(C('paper.hi'), C('paper'), 0.5) : C('paper'));
  }
  img.draw(6, 4, ['.##.', '.##.', '####', '####', '####', '####', '####'], { '#': C('ink.sub') }); // 小さな尖塔
  out('culmen_todo', img, null, 'Culmen の札');
}

// ---- カード 24×24（9分割）: 紙の面の上に置く、明るい紙の札 ----
{
  const img = new Img(24, 24);
  img.rect(2, 22, 22, 2, alpha(C('ink'), 0.22));
  img.rect(0, 0, 24, 22, C('ink.faint'));
  img.rect(2, 2, 20, 18, C('paper.hi'));
  img.rect(2, 2, 20, 1, WHITE.map((v, i) => (i < 3 ? Math.round((v + C('paper.hi')[i]) / 2) : 255)));
  for (const [x, y] of [[0, 0], [23, 0], [0, 21], [23, 21]]) img.clear(x, y);
  out('card', img, { l: 6, t: 6, r: 6, b: 8 }, 'カード');
}

// ---- 検索欄 64×32（9分割）: くぼんだ欄と、左端の虫眼鏡 ----
{
  const img = new Img(64, 32);
  img.rect(0, 0, 64, 30, C('ink.sub'));
  img.rect(2, 2, 60, 26, C('paper.hi'));
  img.rect(2, 2, 60, 2, alpha(C('ink'), 0.25));
  img.rect(2, 2, 2, 26, alpha(C('ink'), 0.15));
  img.draw(7, 9, ['..####..', '.#....#.', '#......#', '#......#', '#......#', '#......#', '.#....#.', '..####..', '......###', '.......###', '........##'], { '#': C('ink.sub') });
  for (const [x, y] of [[0, 0], [63, 0], [0, 29], [63, 29]]) img.clear(x, y);
  out('search', img, { l: 22, t: 10, r: 6, b: 12 }, '検索欄');
}

// ---- 使えないドロップダウンの欄 64×32 ----
{
  const img = new Img(64, 32);
  img.rect(0, 0, 64, 30, C('ink.faint'));
  img.rect(2, 2, 60, 26, C('paper.dark'));
  img.rect(40, 2, 2, 26, C('ink.faint'));
  img.rect(42, 2, 20, 26, mix(C('paper.dark'), C('ink.faint'), 0.35));
  img.draw(48, 13, ['########', '.######.', '..####..', '...##...'], { '#': C('ink.faint') });
  for (const [x, y] of [[0, 0], [63, 0], [0, 29], [63, 29]]) img.clear(x, y);
  out('dropdown_disabled', img, { l: 6, t: 10, r: 26, b: 12 }, 'ドロップダウンの欄（使えない）');
}

// ---- コースのサムネイルの見本 168×56 = 84×28 GUI px（空・遠くの山・足場の列）。n で空の色を変える ----
// 本番ではコースごとの絵に差し替える。状態で出し分けるので半透明は使わない
function thumb(n) {
  const img = new Img(168, 56), r = rng(100 + n);
  const top = C(`sky.${[1, 2, 3, 4, 1, 2][n]}`), bottom = mix(top, C('paper.hi'), 0.35);
  for (let y = 0; y < 56; y++) img.rect(0, y, 168, 1, mix(top, bottom, y / 55));
  // 遠くの山（なだらかな稜線）
  const hill = mix(top, C('frame.stone.dark'), 0.45);
  for (let x = 0; x < 168; x++) { const h = Math.round(30 + 6 * Math.sin((x + n * 37) / 17) + 3 * Math.sin((x + n * 11) / 7)); img.rect(x, h, 1, 56 - h, hill); }
  // 足場（宙に浮いたブロックの列。だんだん高くなる）
  const block = [C('frame.stone.hi'), C('frame.stone'), C('frame.stone.dark')];
  for (let k = 0; k < 7; k++) {
    const x = 8 + k * 22 + Math.floor(r() * 6), y = 44 - k * 4 - Math.floor(r() * 4);
    img.rect(x, y, 10, 2, block[0]); img.rect(x, y + 2, 10, 6, block[1]); img.rect(x, y + 8, 10, 2, block[2]);
  }
  img.frame(0, 0, 168, 56, 2, C('ink.sub'));
  out(`thumb${n}`, img, null, `サムネイルの見本 ${n}`);
}
for (let n = 0; n < 6; n++) thumb(n);
{ // ♥（お気に入り）12×10 = 6×5 GUI px
  const img = new Img(12, 10);
  img.draw(0, 0, ['.###..###...', '#####.####..', '##########..', '##########..', '.########...', '..######....', '...####.....', '....##......'].map((r) => r.slice(0, 11)), { '#': C('vermilion') });
  out('heart', img, null, 'お気に入りの印');
}

// ---- 顔の見本 64×64 = 32×32 GUI px（8×8 の顔を 1 マス 8 画素で）。本番はプレイヤーの頭に差し替える ----
{
  const img = new Img(64, 64);
  const P = { h: [70, 44, 24, 255], s: [196, 140, 104, 255], d: [168, 112, 80, 255], w: [240, 240, 240, 255], e: [70, 70, 160, 255], m: [120, 64, 48, 255], n: [150, 96, 70, 255] };
  const face = ['hhhhhhhh', 'hhhhhhhh', 'hsssssh', 'sssssss', 'swessews', 'ssssssss', 'ssnmmnss', 'ssddddss'].map((r) => r.padEnd(8, 's'));
  face.forEach((row, y) => [...row].forEach((c, x) => img.rect(x * 8, y * 8, 8, 8, P[c] || P.s)));
  out('face', img, null, '顔の見本');
}
// ---- 相棒の影絵（姿が決まるまで）48×40 = 24×20 GUI px ----
{
  const img = new Img(48, 40), c = mix(C('ink.faint'), C('paper'), 0.25);
  for (let y = 0; y < 40; y++) for (let x = 0; x < 48; x++) {
    const body = ((x - 26) / 15) ** 2 + ((y - 27) / 10) ** 2 <= 1;        // 胴
    const head = ((x - 13) / 9) ** 2 + ((y - 17) / 8) ** 2 <= 1;          // 頭
    const ear = (x >= 6 && x <= 11 && y >= 4 && y <= 12 && x - 6 >= (12 - y) * 0.6) || (x >= 14 && x <= 19 && y >= 4 && y <= 12 && 19 - x >= (12 - y) * 0.6);
    const tail = ((x - 42) / 5) ** 2 + ((y - 18) / 9) ** 2 <= 1;
    const leg = y >= 34 && y <= 39 && ((x >= 16 && x <= 19) || (x >= 33 && x <= 36));
    if (body || head || ear || tail || leg) img.px(x, y, c);
  }
  img.draw(23, 21, ['.####.', '##..##', '...##.', '..##..', '......', '..##..'], { '#': C('paper.hi') }); // ？（胴の真ん中）
  out('companion', img, null, '相棒の影絵（未定）');
}

// ---- タブ（台帳のインデックス）----
// 選んでいるタブ: 32×32（GUI y=4〜19）。下の 2 行は紙の面の縁を覆ってつながる
// ほかのタブ:     32×28（GUI y=4〜17）。下は紙の面に隠れる
function tab(on) {
  const h = on ? 32 : 28, img = new Img(32, h);
  const edge = C('ink.sub'), face = on ? C('paper') : C('paper.dark');
  img.rect(0, 0, 32, h, edge);
  img.rect(2, 2, 28, h - 2, face);
  img.rect(2, 2, 28, 1, on ? C('paper.hi') : mix(face, C('paper.hi'), 0.4));
  if (!on) img.rect(2, h - 3, 28, 3, mix(face, C('frame.stone.dark'), 0.25)); // 帯に沈んで見せる
  for (const [x, y] of [[0, 0], [1, 0], [0, 1], [31, 0], [30, 0], [31, 1]]) img.clear(x, y); // 上の角を丸く
  out(on ? 'tab_on' : 'tab_off', img, { l: 4, t: 4, r: 4, b: on ? 4 : 4 }, on ? 'タブ（選んでいる）' : 'タブ（ほか）');
}
tab(true); tab(false);

// ---- 台帳の外枠（石・真鍮の線・角金具）64×64、四隅 16（8 GUI px）----
{
  const img = new Img(64, 64);
  stoneTile(img, 16, 16);
  // 縁の部分は伸ばすので、縁の向きにだけ変化させる
  const dark = C('frame.stone.dark'), s = C('frame.stone'), hi = C('frame.stone.hi'), b = C('brass'), bd = C('brass.dark'), bh = C('brass.hi');
  const band = [dark, dark, hi, s, s, s, bd, b]; // 外から内へ 0〜7
  for (let k = 0; k < 8; k++) {
    for (let i = k; i < 64 - k; i++) { img.px(i, k, band[k]); img.px(i, 63 - k, k === 2 ? s : band[k]); }
    for (let j = k; j < 64 - k; j++) { img.px(k, j, band[k]); img.px(63 - k, j, k === 2 ? s : band[k]); }
  }
  for (let i = 8; i < 56; i++) { img.px(i, 8, alpha(dark, 0.6)); img.px(8, i, alpha(dark, 0.6)); } // 真鍮の線の内側の影
  // 角金具（L字の真鍮の板と鋲）
  const corner = ['dddddddddddddd', 'drrhhhhhhhhhhd', 'drrhhhhhhhhhhd', 'dhhkkhhhhhhhhd', 'dhhkkddddddddd', 'dhhhd', 'dhhhd', 'dhhhd', 'dhhhd', 'dhhhd', 'dhhhd', 'dhhhd', 'dhhhd', 'ddddd'];
  const map = { d: bd, h: b, r: bh, k: C('frame.stone.dark') };
  const flip = (rows, fx, fy) => (fy ? [...rows].reverse() : rows).map((r) => (fx ? [...r.padEnd(14, ' ')].reverse().join('') : r));
  img.draw(1, 1, corner, map);
  img.draw(64 - 15, 1, flip(corner, true, false), map);
  img.draw(1, 64 - 15, flip(corner, false, true), map);
  img.draw(64 - 15, 64 - 15, flip(corner, true, true), map);
  out('frame_ledger', img, { l: 16, t: 16, r: 16, b: 16 }, '台帳の外枠（中央は石の地を並べる）');
}

// ---- 紙の面 48×48、四隅 8 ----
{
  const img = new Img(48, 48);
  img.rect(0, 0, 48, 48, C('paper'));
  paperFill(img, 8, 8, 32, 32, C('paper'), 11); // むらは並べる真ん中だけ
  img.frame(0, 0, 48, 48, 2, C('ink.sub'));
  img.rect(2, 2, 44, 1, alpha(C('paper.dark'), 0.9)); // 縁の内側の影（上・左）
  img.rect(2, 2, 1, 44, alpha(C('paper.dark'), 0.9));
  for (const [x, y] of [[0, 0], [47, 0], [0, 47], [47, 47]]) img.clear(x, y);
  out('panel_paper', img, { l: 8, t: 8, r: 8, b: 8 }, '紙の面（台帳の中身）');
}

// ---- 布張りの面（展示場）48×48、四隅 8 ----
{
  const img = new Img(48, 48);
  const a = mix(C('paper'), C('paper.dark'), 0.55), b = mix(C('paper.dark'), C('ink.sub'), 0.12);
  for (let j = 0; j < 48; j++) for (let i = 0; i < 48; i++) img.px(i, j, ((i >> 1) + (j >> 1)) % 2 ? a : b); // 織り目
  img.frame(0, 0, 48, 48, 2, C('ink.sub'));
  img.frame(2, 2, 44, 44, 1, mix(a, C('paper.hi'), 0.4));
  for (const [x, y] of [[3, 3], [43, 3], [3, 43], [43, 43]]) img.rect(x, y, 2, 2, C('brass')); // 鋲
  out('panel_cloth', img, { l: 8, t: 8, r: 8, b: 8 }, '布張りの面（展示場）');
}

// ---- 尖塔の影絵（正統な試練）64×36 = 32×18 GUI px、横に並べて使う ----
{
  const img = new Img(64, 36);
  const sil = C('frame.stone.dark'), edge = C('brass.dark');
  const spire = (cx, top, halfBase, baseY) => {
    for (let y = top; y < baseY; y++) {
      const hw = Math.max(0, Math.round(((y - top) / (baseY - top)) * halfBase));
      img.rect(cx - hw, y, hw * 2 + 1, 1, sil);
      img.px(cx - hw - 1, y, edge); img.px(cx + hw + 1, y, edge);
    }
    img.px(cx, top - 1, C('brass')); // 先端の飾り
  };
  img.rect(0, 30, 64, 6, sil);
  spire(32, 2, 6, 30);  // 中央の尖塔
  img.rect(28, 22, 9, 8, sil);
  spire(12, 14, 3, 30); // 小尖塔
  spire(52, 14, 3, 30);
  for (const x of [22, 42]) spire(x, 20, 2, 30);
  out('spires', img, null, '尖塔の影絵（横に並べる）');
}

// ---- ランクの札（Anulus I〜XI）52×20 = 26×10 GUI px。地はランクの色（VIII〜XI は左から右へのグラデーション）、
// 上に明るい縁、下に暗い縁、丸い角。数字はゲーム内フォントの文字を上に重ねる（画面の側で書く）。
// 文字の行（9px）より 1px 上から置き、縁の線は付けない（数字が縁に重ならないように）----
for (let n = 1; n <= 11; n++) {
  const img = new Img(52, 20), from = C(`rank.${n}`), to = T[`rank.${n}.to`] ? C(`rank.${n}.to`) : from;
  for (let x = 0; x < 52; x++) img.rect(x, 0, 1, 20, mix(from, to, x / 51));
  img.rect(0, 0, 52, 2, alpha(WHITE, 0.35));
  img.rect(0, 18, 52, 2, alpha(C('ink'), 0.3));
  for (const [x, y] of [[0, 0], [1, 0], [0, 1]]) { img.clear(x, y); img.clear(x, 19 - y); img.clear(51 - x, y); img.clear(51 - x, 19 - y); }
  out(`rank_${n}`, img, null, `ランクの札（Anulus ${n}）`);
}

// ---- 時代の空（左が Anulus I の昼、右へ暮れていく）160×4、横に引き伸ばして使う ----
{
  const img = new Img(160, 4), stops = [1, 2, 3, 4, 5].map((n) => C(`sky.${n}`));
  for (let x = 0; x < 160; x++) {
    const t = (x / 159) * (stops.length - 1), k = Math.min(stops.length - 2, Math.floor(t));
    img.rect(x, 0, 1, 4, mix(stops[k], stops[k + 1], t - k));
  }
  out('sky', img, null, '時代の空のグラデーション');
}

// ---- 右上のアイコン 32×32 = 16 GUI px ----
function icon(name, glyph) {
  const img = new Img(32, 32);
  img.rect(0, 0, 32, 32, C('brass.dark'));
  img.rect(1, 1, 30, 30, C('brass'));
  img.rect(1, 1, 30, 1, C('brass.hi'));
  img.rect(1, 1, 1, 30, C('brass.hi'));
  img.rect(3, 3, 26, 26, C('frame.stone.dark'));
  img.rect(3, 3, 26, 1, alpha([0, 0, 0, 255], 0.4));
  for (const [x, y] of [[0, 0], [31, 0], [0, 31], [31, 31]]) img.clear(x, y);
  img.draw(16 - (glyph[0].length >> 1), 16 - (glyph.length >> 1), glyph, { '#': C('paper'), '+': C('brass.hi') });
  out(`icon_${name}`, img, null, `アイコン（${name}）`);
}
icon('bell', [
  '.....##.....', '....####....', '...##++##...', '..##++++##..', '..#++++++#..', '..#++++++#..',
  '..#++++++#..', '.##++++++##.', '.#++++++++#.', '############', '.....##.....', '.....##.....',
]);
icon('settings', [ // 3本のつまみ（歯車は使わない）
  '..#....#....', '..#....#..#.', '.###...#..#.', '.###...#..#.', '..#...###.#.', '..#...###.#.',
  '..#....#.###', '..#....#.###', '..#....#..#.', '..#....#..#.', '..#....#..#.', '..#....#..#.',
]);
icon('close', [
  '##........##', '###......###', '.###....###.', '..###..###..', '...######...', '....####....',
  '....####....', '...######...', '..###..###..', '.###....###.', '###......###', '##........##',
]);

// 未読のバッジ（朱の丸）12×12 = 6 GUI px
{
  const img = new Img(12, 12), v = C('vermilion'), d = C('vermilion.dark');
  img.draw(0, 0, ['...dddddd...', '..dvvvvvvd..', '.dvvvvvvvvd.', 'dvvvvvvvvvvd', 'dvvvvvvvvvvd', 'dvvvvvvvvvvd',
    'dvvvvvvvvvvd', 'dvvvvvvvvvvd', 'dvvvvvvvvvvd', '.dvvvvvvvvd.', '..dvvvvvvd..', '...dddddd...'], { d, v });
  out('badge_unread', img, null, '未読のバッジ');
}

fs.writeFileSync(path.join(OUT, 'art.json'), JSON.stringify(made, null, 2));
console.log(made.map((m) => `${m.name}.png ${m.w}x${m.h}${m.slice ? ' 9分割' : ''}  ${m.note}`).join('\n'));
