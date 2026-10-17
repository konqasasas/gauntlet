// Node側のラスター化・コンパイル・検証・ZIP書き出し・プレビューPNG。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createCanvas, loadImage, GlobalFonts } from '@napi-rs/canvas';
import { renderScreen } from './raster.mjs';
import { compileScreen, spaceFontJson, textFontJson, fontId, spaceFontId, textFontId, manifestFor, glyphAdvance, edgePixels, EDGE_ADVANCE, COMPILER_VERSION } from './compile.mjs';
import { simulate, reconstruct, diffPixels } from './simulate.mjs';
import { frameLayout, drawFrame, drawHotspotOverlay } from './frame.mjs';
import { GlyphAtlas, gameTextItems, drawGameTextItems, drawGlyph } from './gametext.mjs';
import { getVanillaFont } from './vanilla.mjs';
import { buildPixelFont } from './pixelfont.mjs';
import { encodePNG, makeZip, sha1 } from './binary.mjs';
import { SKIN_SLOTS, LINE, clickRows, resolveScreen } from './model.mjs';
import { selectStates, stateKeys } from './states.mjs';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
export const FONTS_DIR = path.join(ROOT, 'fonts');

// ---- フォント -------------------------------------------------------------

let fontsLoaded = false;
let customFonts = [];
export function ensureFonts(extraDirs = []) {
  if (fontsLoaded) return;
  fontsLoaded = true;
  const dirs = [
    process.env.WINDIR && path.join(process.env.WINDIR, 'Fonts'),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Microsoft', 'Windows', 'Fonts'),
    '/usr/share/fonts', '/System/Library/Fonts', ...extraDirs,
  ].filter(Boolean);
  for (const d of dirs) if (fs.existsSync(d)) { try { GlobalFonts.loadFontsFromDir(d); } catch { /* 読めないフォントは無視 */ } }
  customFonts = loadCustomFonts();
  for (const f of customFonts) GlobalFonts.registerFromPath(path.join(FONTS_DIR, f.file), f.family);
  pixelFonts = new Map();
  for (const f of customFonts) if (f.pixel && f.dotSize) {
    try { pixelFonts.set(f.family, buildPixelFont(f, path.join(FONTS_DIR, f.file))); } catch (e) { console.warn(`ドットフォント ${f.family} を変換できません: ${e.message}`); }
  }
}

let pixelFonts = new Map();
export function getPixelFonts() { ensureFonts(); return pixelFonts; }

// fonts/fonts.json に登録したフォント（ドットフォントなど）
export function loadCustomFonts() {
  const file = path.join(FONTS_DIR, 'fonts.json');
  if (!fs.existsSync(file)) return [];
  return JSON.parse(fs.readFileSync(file, 'utf8')).filter((f) => fs.existsSync(path.join(FONTS_DIR, f.file)));
}

export function fontFamilies() {
  ensureFonts();
  return { families: [...new Set(GlobalFonts.families.map((f) => f.family))].sort(), custom: customFonts };
}

// ゲーム内フォント（無ければ null と理由）
export async function getAtlas(vanillaDir) {
  try {
    const vf = await getVanillaFont(vanillaDir);
    return { atlas: nodeAtlas(vf), font: vf, error: null };
  } catch (e) {
    return { atlas: null, font: null, error: e.message };
  }
}

const atlasCache = new WeakMap();
function nodeAtlas(vf) {
  if (atlasCache.has(vf)) return atlasCache.get(vf);
  const a = new GlyphAtlas({
    raw(cp) {
      const g = vf.glyph(cp);
      if (!g) return null;
      return { a: g.advance, b: g.boldOffset, so: g.shadowOffset, w: g.w, h: g.h, s: g.scale, t: g.top, alpha: g.alpha };
    },
  }, makeCanvas);
  atlasCache.set(vf, a);
  return a;
}

const makeCanvas = (w, h) => createCanvas(Math.max(1, Math.ceil(w)), Math.max(1, Math.ceil(h)));

// 私用領域の文字は \uXXXX で書く（ファイルを開いたときに区別できるように）
export function jsonPUA(v, indent) {
  return JSON.stringify(v, null, indent).replace(/[\uE000-\uF8FF]/g, (c) => `\\u${c.charCodeAt(0).toString(16)}`);
}

export async function loadAssetImages(store, project) {
  const images = new Map();
  for (const [id, a] of Object.entries(project.assets)) {
    const p = store.assetPath(a.file);
    if (fs.existsSync(p)) images.set(id, await loadImage(fs.readFileSync(p)));
  }
  return images;
}

// opts: renderScreen の include / noBackground（省略時は状態の既定値で見える要素）
export function rasterizeScreen(project, screen, images, opts = {}) {
  ensureFonts();
  const s = project.textureScale;
  const c = makeCanvas(screen.width * s, screen.height * s);
  renderScreen(c.getContext('2d'), screen, { scale: s, images, makeCanvas, pixelFonts, ...opts });
  const img = c.getContext('2d').getImageData(0, 0, c.width, c.height);
  return { canvas: c, raster: { data: img.data, width: img.width, height: img.height } };
}

function cropRGBA(r, sx, sy, sw, sh) {
  const out = new Uint8ClampedArray(sw * sh * 4);
  for (let y = 0; y < sh; y++) out.set(r.data.subarray(((sy + y) * r.width + sx) * 4, ((sy + y) * r.width + sx + sw) * 4), y * sw * 4);
  return { data: out, width: sw, height: sh };
}

function scanWidth(img) {
  for (let x = img.width - 1; x >= 0; x--) for (let y = 0; y < img.height; y++) if (img.data[(y * img.width + x) * 4 + 3]) return x + 1;
  return 0;
}

// 1画面のコンパイル＋独立検証
export function buildScreen(project, rawScreen, images, vanilla = { atlas: null, font: null }) {
  const screen = resolveScreen(project, rawScreen); // テーマの色の名前を実際の色に
  // 背景（状態なしの要素）と、状態ごとの重ね絵（その状態の要素だけ、透明の上に）を別々に描く
  const base = rasterizeScreen(project, screen, images, { include: (e) => !e.state }).raster;
  const states = [...new Set(screen.elements.filter((e) => e.visible && e.state).map((e) => e.state))];
  const overlays = states.map((state) => ({ state, raster: rasterizeScreen(project, screen, images, { include: (e) => e.state === state, noBackground: true }).raster }));
  const compiled = compileScreen(project, screen, base, vanilla.atlas, overlays);
  // 既定の状態だけ残したもの（データパック・検証・プレビュー用）
  const shown = selectStates(compiled.dialog, project.namespace, screen.stateDefaults || {});
  const { raster } = rasterizeScreen(project, screen, images);
  const tilePixels = new Map();
  const tileFiles = [];
  for (const t of compiled.tiles) {
    const px = cropRGBA(t.src, t.sx, t.sy, t.sw, t.sh);
    const png = encodePNG(px.data, px.width, px.height);
    tileFiles.push({ path: `assets/${project.namespace}/textures/font/${t.file}`, data: png });
    tilePixels.set(`${project.namespace}:font/${t.file}`, px);
  }
  // 検証: タイル画素から advance を求め直し、クライアント規則で配置を再現する
  const augmented = structuredClone(compiled.font);
  for (const p of augmented.providers) {
    const px = tilePixels.get(p.file);
    p.advance = glyphAdvance(scanWidth(px), p.height, px.height);
  }
  const space = spaceFontJson(project.namespace);
  for (const p of space.providers) if (p.type === 'bitmap') p.advance = EDGE_ADVANCE;
  const fonts = { [fontId(project, screen)]: augmented, [spaceFontId(project)]: space };
  if (vanilla.font) {
    const vf = vanilla.font;
    fonts[textFontId(project)] = { lookup: (ch) => { const g = vf.glyph(ch.codePointAt(0)); return g ? { advance: g.advance, boldOffset: g.boldOffset } : null; } };
  }
  const sim = simulate(shown, fonts);
  const rec = reconstruct(sim, screen, project.textureScale, tilePixels);
  const diff = diffPixels(rec, raster);
  const errors = [...compiled.errors, ...sim.errors];
  if (diff.different) errors.push(`再構成した画像が元の画像（既定の状態）と ${diff.different} 画素異なります`);
  // 全状態入りでも各行の幅が変わらないこと（重ね絵は描いて元の位置に戻るため）
  if (overlays.length) for (const e of simulate(compiled.dialog, fonts).errors) errors.push(`全状態入り: ${e}`);
  const defaults = screen.stateDefaults || {};
  for (const key of Object.keys(stateKeys(screen))) if (!(key in defaults)) errors.push(`状態 ${key} の既定値がありません（画面の stateDefaults）`);
  // 検証: 置いたクリック区間が、クライアントの判定（先頭からたどる）でもそのまま当たること
  sim.lines.forEach((line, r) => {
    const want = compiled.clicks.filter((c) => c.row === r);
    const covered = (list, x) => list.some((c) => c.x0 <= x && x < c.x1);
    for (let x = 0; x < compiled.stats.bodyWidth; x += 0.5) {
      if (covered(want, x) !== covered(line.clicks, x)) { errors.push(`行${r}: x=${x} のクリック判定が配置と一致しません`); break; }
    }
  });
  const warnings = [...compiled.warnings];
  for (const e of screen.elements) {
    if (!e.visible) continue;
    if (e.x < 0 || e.y < 0 || e.x + e.w > screen.width || e.y + e.h > screen.height) warnings.push(`${e.id}: 画面の外にはみ出しています（はみ出し部分は描かれません）`);
    if (e.type === 'button' || e.type === 'hotspot') {
      // クリック領域は9px行単位。見た目との差が上下どちらかで2pxを超えるときだけ知らせる
      const rows = clickRows(e.y, e.h, screen.height);
      if (!rows.length) warnings.push(`${e.id}: 高さが足りず、クリック領域ができません（9px行の半分以上に重なる必要があります）`);
      else if (Math.abs(rows[0] * LINE - e.y) > 2 || Math.abs((rows[rows.length - 1] + 1) * LINE - (e.y + e.h)) > 2) warnings.push(`${e.id}: クリック領域（y ${rows[0] * LINE}〜${(rows[rows.length - 1] + 1) * LINE}）が見た目とずれています。9px行に合わせてください`);
    }
    // after_action=close の画面では、本文のどのクリックでもクライアントが閉じるのでプラグインは不要
    if ((e.type === 'button' || e.type === 'hotspot') && e.action.type === 'close' && screen.afterAction !== 'close') warnings.push(`${e.id}: 本文内の「閉じる」はプラグイン（custom ${project.namespace}:close）が必要です（クリック後の動作が「閉じる」以外のため）`);
    if ((e.type === 'button' || e.type === 'hotspot') && e.action.type === 'none' && !e.tooltip) warnings.push(`${e.id}: アクションもツールチップもありません`);
    const text = e.type === 'text' && e.render === 'game' ? e.text : e.type === 'button' && e.labelRender === 'game' ? e.label : '';
    if (vanilla.font && text) {
      const ld = Array.from(text).filter((ch) => vanilla.font.langDependent.has(ch.codePointAt(0)));
      if (ld.length) warnings.push(`${e.id}: ${ld.join('')} は日本語クライアントで幅が変わります`);
    }
    if (e.type === 'button' && e.labelRender === 'game' && e.label) {
      const row = Math.max(0, Math.round((e.y + e.h / 2 - LINE / 2) / LINE));
      if (Math.abs(row * LINE + LINE / 2 - (e.y + e.h / 2)) > 2) warnings.push(`${e.id}: ラベルは9px行に置かれるため、上下の中央から ${Math.abs(row * LINE + LINE / 2 - (e.y + e.h / 2)).toFixed(1)}px ずれます`);
    }
  }
  if (screen.footer.type === 'none' && !screen.canCloseWithEscape && !screen.elements.some((e) => e.visible && (e.type === 'button' || e.type === 'hotspot') && e.action.type === 'close')) {
    errors.push('フッターなしで Esc でも閉じられず、本文に「閉じる」もないため、画面を閉じる手段がありません');
  }
  if (compiled.stats.jsonBytes > 1_000_000) warnings.push(`Dialog JSONが ${compiled.stats.jsonBytes} bytes と大きいです`);
  return { compiled, shown, tileFiles, raster, sim, errors, warnings };
}

export function packMcmeta(project) {
  return JSON.stringify({ pack: { description: project.description || project.name, min_format: [97, 1], max_format: [97, 1] } }, null, 2);
}

function dataMcmeta(project) {
  return JSON.stringify({ pack: { description: `${project.name} dialogs`, min_format: [121, 0], max_format: [121, 0] } }, null, 2);
}

const SKIN_META = {
  button: { width: 200, height: 20, border: 3 },
  button_highlighted: { width: 200, height: 20, border: 3 },
  button_disabled: { width: 200, height: 20, border: 1 },
  scroller: { width: 6, height: 32, border: 1 },
  scroller_background: { width: 6, height: 32, border: 1 },
};

// 焼き込みに使ったフォントのクレジット
function creditsFor(project) {
  ensureFonts();
  const used = new Set();
  for (const s of project.screens) for (const e of s.elements) {
    if (!e.visible) continue;
    if ((e.type === 'text' && e.render === 'baked') || (e.type === 'button' && e.labelRender === 'baked' && e.label)) used.add(e.fontFamily);
  }
  return customFonts.filter((f) => f.credit && used.has(f.family)).map((f) => f.credit);
}

export async function exportProject(store, outRoot, vanillaDir) {
  const project = store.project;
  const images = await loadAssetImages(store, project);
  const vanilla = await getAtlas(vanillaDir);
  const ns = project.namespace;
  const rp = [{ path: 'pack.mcmeta', data: packMcmeta(project) }];
  const dp = [{ path: 'pack.mcmeta', data: dataMcmeta(project) }];
  rp.push({ path: `assets/${ns}/font/space.json`, data: jsonPUA(spaceFontJson(ns), 1) });
  { const e = edgePixels(); rp.push({ path: `assets/${ns}/textures/font/edge.png`, data: encodePNG(e.data, e.width, e.height) }); }
  rp.push({ path: `assets/${ns}/font/text.json`, data: JSON.stringify(textFontJson(), null, 1) });
  const report = { screens: {}, errors: [], warnings: [] };
  const compiledById = {};
  const templates = []; // プラグインが読む全状態入りの Dialog JSON（templates/<画面>.json）
  for (const screen of project.screens) {
    const b = buildScreen(project, screen, images, vanilla);
    compiledById[screen.id] = b.compiled;
    rp.push({ path: `assets/${ns}/font/screen/${screen.id}.json`, data: jsonPUA(b.compiled.font, 1) });
    rp.push(...b.tileFiles);
    dp.push({ path: `data/${ns}/dialog/${screen.id}.json`, data: jsonPUA(b.shown) });
    templates.push({ path: `${screen.id}.json`, data: jsonPUA(b.compiled.dialog) });
    dp.push({ path: `data/${ns}/function/open/${screen.id}.mcfunction`, data: `dialog show @s ${ns}:${screen.id}\n` });
    report.screens[screen.id] = { stats: b.compiled.stats, errors: b.errors, warnings: b.warnings };
    report.errors.push(...b.errors.map((e) => `${screen.id}: ${e}`));
    report.warnings.push(...b.warnings.map((e) => `${screen.id}: ${e}`));
  }
  for (const slot of SKIN_SLOTS) {
    const a = project.skin?.[slot];
    if (!a) continue;
    const asset = project.assets[a];
    rp.push({ path: `assets/minecraft/textures/gui/sprites/widget/${slot}.png`, data: fs.readFileSync(store.assetPath(asset.file)) });
    rp.push({ path: `assets/minecraft/textures/gui/sprites/widget/${slot}.png.mcmeta`, data: JSON.stringify({ gui: { scaling: { type: 'nine_slice', ...SKIN_META[slot] } } }) });
    report.warnings.push(`skin.${slot}: Minecraft全体の標準ボタン/スクロールバーの見た目が変わります`);
  }
  const credits = creditsFor(project);
  if (credits.length) rp.push({ path: 'credits.txt', data: `Fonts used in this resource pack:\n${credits.map((c) => `- ${c}`).join('\n')}\n` });
  // shaderを含まないことを保証
  for (const f of rp) if (/shaders\//.test(f.path) || /\.(fsh|vsh|glsl)$/.test(f.path)) throw new Error(`shaderファイルは出力できません: ${f.path}`);

  const rpZip = makeZip(rp);
  const dpZip = makeZip(dp);
  const manifest = manifestFor(project, compiledById);
  manifest.resourcePack = { file: 'resourcepack.zip', sha1: sha1(rpZip), bytes: rpZip.length };
  manifest.dataPack = { file: 'datapack.zip', sha1: sha1(dpZip), bytes: dpZip.length };
  manifest.projectRevision = store.revision;
  manifest.compilerVersion = COMPILER_VERSION;
  manifest.textFont = textFontId(project);
  manifest.credits = credits;

  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const dir = path.join(outRoot, `${stamp}_r${store.revision}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, 'resourcepack.zip'), rpZip);
  fs.writeFileSync(path.join(dir, 'datapack.zip'), dpZip);
  fs.writeFileSync(path.join(dir, 'manifest.json'), JSON.stringify(manifest, null, 2));
  fs.writeFileSync(path.join(dir, 'report.json'), JSON.stringify(report, null, 2));
  fs.writeFileSync(path.join(dir, 'project.json'), JSON.stringify(project, null, 2));
  fs.mkdirSync(path.join(dir, 'templates'), { recursive: true });
  for (const t of templates) fs.writeFileSync(path.join(dir, 'templates', t.path), t.data);
  // latest へもコピー（プラグインや確認用に固定パスで参照できるよう）
  const latest = path.join(outRoot, 'latest');
  fs.rmSync(latest, { recursive: true, force: true });
  fs.cpSync(dir, latest, { recursive: true });
  return { dir, latest, manifest, report };
}

// コンパイル結果の文字配置（シミュレーターが解釈した位置）でゲーム内フォントを描く
function drawSimTexts(ctx, sim, atlas) {
  sim.lines.forEach((line, row) => {
    for (const t of line.texts) {
      const shadow = t.shadow ? `#${((t.shadow >>> 0) & 0xffffff).toString(16).padStart(6, '0')}${((t.shadow >>> 24) & 255).toString(16).padStart(2, '0')}` : null;
      drawGlyph(ctx, atlas, t.cp, t.x, row * LINE, `${t.color || '#ffffff'}ff`, { bold: t.bold, shadow });
    }
  });
}

// プレビューPNG。mode: 'body' 本文のみ / 'game' フレーム込みの概算 / 'compiled' コンパイル結果から再構成
export async function previewPNG(store, screenId, opts = {}) {
  const project = store.project;
  const raw = project.screens.find((s) => s.id === screenId);
  if (!raw) throw new Error(`画面 ${screenId} がありません`);
  const screen = resolveScreen(project, raw);
  const images = await loadAssetImages(store, project);
  const vanilla = await getAtlas(opts.vanillaDir);
  const mode = opts.mode || 'game';
  const zoom = Math.max(1, Math.min(6, opts.zoom || 2));
  const showClicks = !!opts.clicks;
  const s = project.textureScale;
  const b = buildScreen(project, screen, images, vanilla);
  // 本文（GUI px × テクスチャ倍率）
  const body = makeCanvas(screen.width * s, screen.height * s);
  const bctx = body.getContext('2d');
  bctx.imageSmoothingEnabled = false;
  if (mode === 'compiled') {
    const tilePixels = new Map();
    for (const t of b.compiled.tiles) tilePixels.set(`${project.namespace}:font/${t.file}`, cropRGBA(t.src, t.sx, t.sy, t.sw, t.sh));
    const rec = reconstruct(b.sim, screen, s, tilePixels);
    const id = bctx.createImageData(rec.width, rec.height);
    id.data.set(rec.data);
    bctx.putImageData(id, 0, 0);
    bctx.scale(s, s);
    if (vanilla.atlas) drawSimTexts(bctx, b.sim, vanilla.atlas);
  } else {
    bctx.drawImage(rasterizeScreen(project, screen, images).canvas, 0, 0);
    bctx.scale(s, s);
    if (vanilla.atlas) drawGameTextItems(bctx, gameTextItems(screen, vanilla.atlas), vanilla.atlas);
  }
  if (mode === 'body') {
    if (showClicks) drawHotspotOverlay(bctx, b.compiled.clicks, { x: 0, y: 0 });
    return body.toBuffer('image/png');
  }
  const vp = { width: opts.width || Math.max(427, screen.width + 40), height: opts.height || Math.max(240, screen.height + 33 + 33 + 20 + screen.inputs.length * 30) };
  const layout = frameLayout(screen, vp);
  const c = makeCanvas(vp.width * zoom, vp.height * zoom);
  const ctx = c.getContext('2d');
  ctx.imageSmoothingEnabled = false;
  ctx.scale(zoom, zoom);
  ensureFonts();
  drawFrame(ctx, screen, layout);
  ctx.save();
  ctx.beginPath(); ctx.rect(layout.bodyView.x, layout.bodyView.y, layout.bodyView.w, layout.bodyView.h); ctx.clip();
  ctx.drawImage(body, layout.image.x, layout.image.y, screen.width, screen.height);
  if (showClicks) drawHotspotOverlay(ctx, b.compiled.clicks, layout.image);
  ctx.restore();
  return c.toBuffer('image/png');
}
