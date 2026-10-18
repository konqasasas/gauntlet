// 2つのプロジェクトのデータを、画面ごと・状態の値ごとに比べる（見本の作り直しで見た目が変わっていないかの確認）。
//   node tools/compare-projects.mjs <前の project.json> <後の project.json> [画面ID...]
// project.json は data/project.json の形（{ revision, project }）でも、project だけでもよい。
// 比べるもの: 描いた画像（画素）、ゲーム内フォントの文字（位置・色・太字・影）、クリック範囲（位置と動作・ツールチップ）。
// 要素の id や書き出しの部品の順番は比べない（同じ見た目を別の作りで出してもよい）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildScreen, getAtlas, rasterizeScreen } from '../lib/export.mjs';
import { resolveScreen, migrateProject } from '../lib/model.mjs';
import { stateKeys, elementShown } from '../lib/states.mjs';
import { gameTextItems } from '../lib/gametext.mjs';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const [aFile, bFile, ...only] = process.argv.slice(2);
if (!aFile || !bFile) { console.error('使い方: node tools/compare-projects.mjs <前> <後> [画面ID...]'); process.exit(2); }
const load = (f) => { const j = JSON.parse(fs.readFileSync(f, 'utf8')); return migrateProject(j.project || j); };
const A = load(aFile), B = load(bFile);
const vanilla = await getAtlas(path.join(ROOT, 'data', 'vanilla'));
const images = new Map(); // 素材の画像を使う画面は今はない

// 比べる状態の値: 既定値、各状態の各値を1つずつ、「ほかの値」
function combos(p, s) {
  const r = resolveScreen(p, s);
  const keys = stateKeys(r);
  const base = { ...(s.stateDefaults || {}) };
  const out = [base];
  for (const [k, vs] of Object.entries(keys)) for (const v of [...vs, '_other']) if (base[k] !== v) out.push({ ...base, [k]: v });
  return out;
}

function snapshot(p, s, values) {
  const r = resolveScreen(p, s);
  const raster = rasterizeScreen(p, r, images, { include: (e) => elementShown(e, values) }).raster;
  const texts = vanilla.atlas ? gameTextItems(r, vanilla.atlas, (e) => elementShown(e, values)).flatMap((it) => it.layout.lines.flatMap((l) => l.chars.map((c) => `${l.row}:${c.x}:${c.ch}:${it.color}:${!!it.el.bold}:${!!it.shadow}`))).sort() : [];
  const b = buildScreen(p, s, images, vanilla, values);
  const clicks = b.compiled.clicks.map((c) => {
    const el = r.elements.find((e) => e.id === c.element);
    return `${c.row}:${c.x0}-${c.x1}:${JSON.stringify(el?.action)}:${el?.tooltip ?? ''}`;
  }).sort();
  return { raster, texts, clicks, errors: b.errors };
}

let bad = 0;
for (const sa of A.screens) {
  if (only.length && !only.includes(sa.id)) continue;
  const sb = B.screens.find((x) => x.id === sa.id);
  if (!sb) { console.log(`✖ ${sa.id}: 後のプロジェクトにありません`); bad++; continue; }
  const list = combos(A, sa);
  for (const v of combos(B, sb)) if (!list.some((x) => JSON.stringify(x) === JSON.stringify(v))) list.push(v);
  let diffs = [];
  for (const values of list) {
    const a = snapshot(A, sa, values), b = snapshot(B, sb, values);
    const tag = JSON.stringify(values);
    if (b.errors.length) diffs.push(`${tag}: 後の書き出しエラー ${b.errors[0]}`);
    if (a.raster.width !== b.raster.width || a.raster.height !== b.raster.height) { diffs.push(`${tag}: 大きさが違う`); continue; }
    let px = 0, first = null;
    for (let i = 0; i < a.raster.data.length; i += 4) {
      if (a.raster.data[i] !== b.raster.data[i] || a.raster.data[i + 1] !== b.raster.data[i + 1] || a.raster.data[i + 2] !== b.raster.data[i + 2] || a.raster.data[i + 3] !== b.raster.data[i + 3]) {
        px++;
        if (!first) { const k = i / 4; first = `(${k % a.raster.width}, ${Math.floor(k / a.raster.width)})`; }
      }
    }
    if (px) diffs.push(`${tag}: 画素が ${px} 個違う（最初 ${first}、テクスチャ px）`);
    const ta = new Set(a.texts), tb = new Set(b.texts);
    const onlyA = a.texts.filter((x) => !tb.has(x)), onlyB = b.texts.filter((x) => !ta.has(x));
    if (onlyA.length || onlyB.length) diffs.push(`${tag}: 文字が違う（前だけ ${onlyA.slice(0, 2).join(' ')} / 後だけ ${onlyB.slice(0, 2).join(' ')}）`);
    const ca = new Set(a.clicks), cb = new Set(b.clicks);
    const cA = a.clicks.filter((x) => !cb.has(x)), cB = b.clicks.filter((x) => !ca.has(x));
    if (cA.length || cB.length) diffs.push(`${tag}: クリック範囲が違う（前だけ ${cA.slice(0, 1).join(' ')} / 後だけ ${cB.slice(0, 1).join(' ')}）`);
  }
  if (diffs.length) { bad++; console.log(`✖ ${sa.id}（${list.length} 通り）`); for (const d of diffs.slice(0, 6)) console.log(`   ${d}`); if (diffs.length > 6) console.log(`   ほか ${diffs.length - 6} 件`); }
  else console.log(`✔ ${sa.id}（${list.length} 通り）`);
}
process.exit(bad ? 1 : 0);
