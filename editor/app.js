import { renderScreen, cssColor } from '/lib/raster.mjs';
import { frameLayout, drawFrame } from '/lib/frame.mjs';
import { GlyphAtlas, gameTextItems, drawGameTextItems, gameTextCodepoints } from '/lib/gametext.mjs';
import {
  LINE, ELEMENT_TYPES, ACTION_TYPES, FOOTER_ACTION_TYPES, INPUT_TYPES, AFTER_ACTIONS, FITS, ALIGNS, SKIN_SLOTS,
  MAX_BODY_WIDTH, MIN_BODY_WIDTH, TEXT_RENDERS, clickRows, resolveScreen, isTokenRef, themeUsers,
} from '/lib/model.mjs';

// ---- 状態 ----------------------------------------------------------------
const st = {
  snap: null,          // {revision, project, canUndo, canRedo}
  screenId: null,
  sel: null,           // 選択中の要素id
  mode: 'edit',
  zoom: 0,             // 0 = 自動（ステージに収まる最大の整数倍）
  z: 3,                // 実際に使っている倍率
  images: new Map(),   // assetId -> HTMLImageElement
  drag: null,
  pending: null,       // ナッジの遅延コミット
  compileInfo: null,
  game: { screenId: null, scroll: 0, hover: null, message: '' },
  compiledImg: null,
};
const MARGIN = 12;
const TYPE_LABEL = { rect: '矩形', image: '画像', text: 'テキスト', button: 'ボタン', hotspot: 'クリック領域' };
const ACTION_LABEL = { none: 'なし', screen: '画面へ遷移', close: '閉じる（要プラグイン）', custom: 'カスタム（プラグイン）', command: 'コマンド実行', suggest: 'コマンド入力欄へ', url: 'URLを開く', copy: 'クリップボードへコピー', submit: '入力値を送信（dynamic/custom）' };

const $ = (s) => document.querySelector(s);
const makeCanvas = (w, h) => { const c = document.createElement('canvas'); c.width = Math.max(1, Math.ceil(w)); c.height = Math.max(1, Math.ceil(h)); return c; };

// ---- ゲーム内フォント（サーバーから必要なグリフだけ取得） -------------------------------
const glyphStore = new Map();   // cp -> glyph | null（フォントにない）
const glyphWanted = new Set();
let glyphTimer = null;
const vanilla = { available: null, error: '' };
const atlas = new GlyphAtlas({
  raw(cp) {
    if (glyphStore.has(cp)) return glyphStore.get(cp);
    requestGlyphs([cp]);
    return undefined;
  },
}, makeCanvas);

function requestGlyphs(cps) {
  let added = false;
  for (const cp of cps) if (!glyphStore.has(cp) && !glyphWanted.has(cp)) { glyphWanted.add(cp); added = true; }
  if (!added || glyphTimer || vanilla.available === false) return;
  glyphTimer = setTimeout(fetchGlyphs, 0);
}

async function fetchGlyphs() {
  glyphTimer = null;
  const cps = [...glyphWanted].slice(0, 1500);
  if (!cps.length) return;
  try {
    const r = await api('GET', `/api/vanilla/glyphs?cp=${cps.map((c) => c.toString(16)).join(',')}`);
    for (const cp of cps) {
      glyphWanted.delete(cp);
      const g = r.glyphs[cp.toString(16)];
      glyphStore.set(cp, g ? { a: g.a, b: g.b, so: g.so, w: g.w, h: g.h, s: g.s, t: g.t, alpha: g.m ? Uint8Array.from(atob(g.m), (c) => c.charCodeAt(0)) : null, ld: !!g.ld } : null);
      atlas.cache.delete(cp);
    }
    vanilla.available = true;
  } catch (e) {
    vanilla.available = false; vanilla.error = e.message;
    for (const cp of cps) glyphWanted.delete(cp);
    renderDiag();
  }
  if (glyphWanted.size) fetchGlyphs(); else { draw(); renderInspector(); }
}

function preloadGlyphs() {
  for (const sc of project().screens) requestGlyphs(gameTextCodepoints(sc));
}

// ---- 登録フォント（fonts/fonts.json） -------------------------------------------
let customFonts = [];
const pixelFonts = new Map(); // family -> ドット単位のビットマップ（サーバーで変換済み）
async function loadCustomFonts() {
  try {
    const [r, pf] = await Promise.all([api('GET', '/api/fonts'), api('GET', '/api/pixelfonts')]);
    for (const [k, v] of Object.entries(pf)) pixelFonts.set(k, v);
    const dl = $('#fontList');
    customFonts = r.custom || [];
    for (const f of customFonts) {
      if (!pixelFonts.has(f.family)) {
        // ドットフォント以外はブラウザーにも読み込む（読めないフォントはプレビューだけ代替表示）
        const face = new FontFace(JSON.stringify(f.family), `url(/fonts/${encodeURIComponent(f.file)})`);
        document.fonts.add(face);
        face.load().then(() => draw()).catch(() => {});
      }
      const o = document.createElement('option'); o.value = f.family; o.label = `${f.family}（${f.purpose || '登録フォント'}）`; dl.append(o);
    }
    for (const f of r.families) { const o = document.createElement('option'); o.value = f; dl.append(o); }
    draw();
  } catch { /* フォント一覧なしでも編集はできる */ }
}
const canvas = $('#canvas');
const ctx = canvas.getContext('2d');

const project = () => st.snap.project;
const screen = () => project().screens.find((s) => s.id === st.screenId) || project().screens[0];
const selected = () => screen()?.elements.find((e) => e.id === st.sel) || null;

// ---- 通信 ----------------------------------------------------------------
async function api(method, url, body, headers = {}) {
  const init = { method, headers: { ...headers } };
  if (body !== undefined) {
    if (body instanceof Blob || body instanceof ArrayBuffer) init.body = body;
    else { init.body = JSON.stringify(body); init.headers['Content-Type'] = 'application/json'; }
  }
  const r = await fetch(url, init);
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json() : await r.blob();
  if (!r.ok) { const e = new Error(data.error || r.statusText); e.status = r.status; throw e; }
  return data;
}

function setStatus(text, err = false) {
  const el = $('#status');
  el.textContent = text;
  el.title = text;
  el.classList.toggle('err', err);
}

async function load() {
  st.snap = await api('GET', '/api/project');
  if (!st.screenId || !project().screens.some((s) => s.id === st.screenId)) st.screenId = project().screens[0]?.id ?? null;
  if (st.sel && !selected()) st.sel = null;
  syncImages();
  preloadGlyphs();
  renderAll();
  scheduleCompileInfo();
}

async function commit(ops, label) {
  flushNudge(false);
  try {
    st.snap = await api('POST', '/api/ops', { revision: st.snap.revision, operations: ops, label, source: 'editor' });
    setStatus(`保存 r${st.snap.revision}`);
    if (st.sel && !selected()) st.sel = null;
    syncImages();
    preloadGlyphs();
    renderAll();
    scheduleCompileInfo();
    return true;
  } catch (e) {
    if (e.status === 409) { setStatus('他の編集を取り込みました', true); await load(); }
    else { setStatus(e.message.split('\n')[0], true); alert(e.message); renderAll(); }
    return false;
  }
}

function syncImages() {
  for (const [id, a] of Object.entries(project().assets)) {
    if (st.images.has(id)) continue;
    const img = new Image();
    img.onload = () => draw();
    img.src = `/assets/${encodeURIComponent(a.file)}`;
    st.images.set(id, img);
  }
}

let compileTimer = null;
function scheduleCompileInfo() {
  clearTimeout(compileTimer);
  compileTimer = setTimeout(async () => {
    const s = screen();
    if (!s) return;
    try {
      st.compileInfo = { screenId: s.id, ...(await api('GET', `/api/compile/${s.id}`)) };
      renderDiag();
      if (st.mode === 'compiled') loadCompiledImage();
    } catch (e) { $('#diag').innerHTML = `<div class="e">${esc(e.message)}</div>`; }
  }, 350);
}

function fitZoom(w, h) {
  const box = $('#stage');
  return Math.max(1, Math.min(8, Math.floor(Math.min((box.clientWidth - 32) / w, (box.clientHeight - 48) / h))));
}

function loadCompiledImage() {
  const s = screen();
  const vp = gameViewport(s);
  const z = st.zoom || fitZoom(vp.width, vp.height);
  const img = new Image();
  img.onload = () => { st.compiledImg = img; draw(); };
  img.src = `/api/preview/${s.id}.png?mode=compiled&clicks=${$('#clickChk').checked ? 1 : 0}&zoom=${z}&vw=${vp.width}&vh=${vp.height}&r=${st.snap.revision}`;
}

// ---- 描画 ----------------------------------------------------------------
function renderAll() {
  renderTabs();
  renderScreenList();
  renderLayers();
  renderAssets();
  renderInspector();
  $('#undoBtn').disabled = !st.snap.canUndo;
  $('#redoBtn').disabled = !st.snap.canRedo;
  draw();
}

const offscreen = document.createElement('canvas');
function renderBody(s) {
  const k = project().textureScale;
  offscreen.width = s.width * k;
  offscreen.height = s.height * k;
  const c = offscreen.getContext('2d');
  c.clearRect(0, 0, offscreen.width, offscreen.height);
  renderScreen(c, resolveScreen(project(), s), {
    scale: k,
    images: new Map([...st.images].filter(([, img]) => img.complete && img.naturalWidth)),
    makeCanvas,
    pixelFonts,
  });
  return offscreen;
}

function draw() {
  const s = screen();
  if (!s) { canvas.width = 1; return; }
  if (st.mode === 'edit') drawEdit(s);
  else if (st.mode === 'game') drawGame();
  else drawCompiled(s);
}

function setCanvasSize(w, h) {
  const dpr = window.devicePixelRatio || 1;
  st.z = st.zoom || fitZoom(w, h);
  canvas.width = Math.round(w * st.z * dpr);
  canvas.height = Math.round(h * st.z * dpr);
  canvas.style.width = `${w * st.z}px`;
  canvas.style.height = `${h * st.z}px`;
  ctx.setTransform(st.z * dpr, 0, 0, st.z * dpr, 0, 0);
  ctx.imageSmoothingEnabled = false;
}

function drawEdit(s) {
  const W = s.width + MARGIN * 2, H = s.height + MARGIN * 2;
  setCanvasSize(W, H);
  ctx.fillStyle = '#15171b'; ctx.fillRect(0, 0, W, H);
  ctx.save();
  ctx.translate(MARGIN, MARGIN);
  // 透明部分の目安（ゲームでは暗い背景の上に重なる）
  for (let y = 0; y < s.height; y += 6) for (let x = 0; x < s.width; x += 6) {
    ctx.fillStyle = ((x + y) / 6) % 2 ? '#2a2d33' : '#23262b';
    ctx.fillRect(x, y, 6, 6);
  }
  ctx.drawImage(renderBody(s), 0, 0, s.width, s.height);
  drawGameTextItems(ctx, gameTextItems(resolveScreen(project(), s), atlas), atlas);
  const lw = 1 / st.z;
  if ($('#gridChk').checked) {
    ctx.fillStyle = 'rgba(255,255,255,0.07)';
    for (let y = LINE; y < s.height; y += LINE) ctx.fillRect(0, y, s.width, lw);
  }
  ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = lw;
  ctx.strokeRect(-lw / 2, -lw / 2, s.width + lw, s.height + lw);
  const showClicks = $('#clickChk').checked;
  for (const e of s.elements) {
    if (!e.visible) continue;
    if (e.type === 'hotspot') {
      ctx.fillStyle = 'rgba(80,200,255,0.12)'; ctx.fillRect(e.x, e.y, e.w, e.h);
      ctx.setLineDash([3 * lw, 2 * lw]); ctx.strokeStyle = 'rgba(80,200,255,0.9)';
      ctx.strokeRect(e.x + lw / 2, e.y + lw / 2, e.w - lw, e.h - lw); ctx.setLineDash([]);
    }
    if (showClicks && (e.type === 'hotspot' || e.type === 'button')) {
      ctx.fillStyle = 'rgba(255,200,40,0.28)';
      for (const r of clickRows(e.y, e.h, s.height)) ctx.fillRect(Math.max(0, e.x), r * LINE, Math.min(e.w, s.width - e.x), LINE);
    }
  }
  const e = selected();
  if (e) {
    ctx.strokeStyle = '#3ccf86'; ctx.lineWidth = 2 * lw;
    ctx.strokeRect(e.x, e.y, e.w, e.h);
    if (!e.locked) for (const h of handles(e)) { ctx.fillStyle = '#fff'; ctx.fillRect(h.x - 2 * lw * 1.5, h.y - 2 * lw * 1.5, 6 * lw, 6 * lw); ctx.strokeRect(h.x - 2 * lw * 1.5, h.y - 2 * lw * 1.5, 6 * lw, 6 * lw); }
  }
  ctx.restore();
  $('#modeNote').textContent = `本文 ${s.width}×${s.height} GUI px / テクスチャ ${project().textureScale}倍`;
}

function handles(e) {
  const xs = [e.x, e.x + e.w / 2, e.x + e.w], ys = [e.y, e.y + e.h / 2, e.y + e.h];
  const out = [];
  const names = [['nw', 'n', 'ne'], ['w', null, 'e'], ['sw', 's', 'se']];
  ys.forEach((y, j) => xs.forEach((x, i) => { if (names[j][i]) out.push({ x, y, name: names[j][i] }); }));
  return out;
}

function gameViewport(s) {
  return { width: Math.max(427, s.width + 48), height: Math.max(240, Math.min(360, s.height + 33 + 60 + s.inputs.length * 30 + 20)) };
}

function drawGame() {
  const s = project().screens.find((x) => x.id === st.game.screenId) || screen();
  st.game.screenId = s.id;
  const vp = gameViewport(s);
  const layout = frameLayout(s, vp);
  st.game.layout = layout;
  st.game.screen = s;
  setCanvasSize(vp.width, vp.height);
  drawFrame(ctx, s, layout, { highlight: st.game.hover?.footer });
  const maxScroll = Math.max(0, layout.contentH - layout.bodyView.h);
  st.game.scroll = Math.max(0, Math.min(maxScroll, st.game.scroll));
  ctx.save();
  ctx.beginPath(); ctx.rect(layout.bodyView.x, layout.bodyView.y, layout.bodyView.w, layout.bodyView.h); ctx.clip();
  ctx.translate(0, -st.game.scroll);
  ctx.drawImage(renderBody(s), layout.image.x, layout.image.y, s.width, s.height);
  ctx.save(); ctx.translate(layout.image.x, layout.image.y);
  drawGameTextItems(ctx, gameTextItems(resolveScreen(project(), s), atlas), atlas);
  ctx.restore();
  if ($('#clickChk').checked) {
    ctx.fillStyle = 'rgba(255,200,40,0.28)';
    for (const seg of clickSegments(s)) ctx.fillRect(layout.image.x + seg.x0, layout.image.y + seg.row * LINE, seg.x1 - seg.x0, LINE);
  }
  ctx.restore();
  $('#modeNote').textContent = `ゲーム画面の概算（GUI ${vp.width}×${vp.height}）。クリックで遷移を試せます。${st.game.message}`;
}

function drawCompiled(s) {
  const img = st.compiledImg;
  if (!img) { setCanvasSize(200, 40); ctx.fillStyle = '#888'; ctx.fillText('読み込み中…', 10, 20); return; }
  const dpr = window.devicePixelRatio || 1;
  canvas.width = img.width * dpr / 1; canvas.height = img.height * dpr;
  canvas.style.width = `${img.width}px`; canvas.style.height = `${img.height}px`;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(img, 0, 0);
  $('#modeNote').textContent = 'サーバーで出力物（フォント・Dialog JSON）からクライアント規則で再構成した画像';
}

// ゲーム内の当たり判定（compile.mjs の rowSegments と同じ規則）
function clickSegments(s) {
  const out = [];
  const rows = s.height / LINE;
  for (let r = 0; r < rows; r++) {
    const hs = s.elements.map((e, z) => ({ e, z })).filter(({ e }) => e.visible && (e.type === 'button' || e.type === 'hotspot') && clickRows(e.y, e.h, s.height).includes(r));
    if (!hs.length) continue;
    const xs = [...new Set([0, s.width, ...hs.flatMap(({ e }) => [clamp(e.x, 0, s.width), clamp(e.x + e.w, 0, s.width)])])].sort((a, b) => a - b);
    for (let i = 0; i + 1 < xs.length; i++) {
      let top = null;
      for (const h of hs) if (h.e.x <= xs[i] && h.e.x + h.e.w >= xs[i + 1] && (!top || h.z > top.z)) top = h;
      if (top) out.push({ row: r, x0: xs[i], x1: xs[i + 1], el: top.e });
    }
  }
  return out;
}

const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

// ---- キャンバス操作 -----------------------------------------------------------
function toGui(ev) {
  const r = canvas.getBoundingClientRect();
  return { x: (ev.clientX - r.left) / st.z, y: (ev.clientY - r.top) / st.z };
}

function hitElement(s, x, y) {
  for (let i = s.elements.length - 1; i >= 0; i--) {
    const e = s.elements[i];
    if (!e.visible) continue;
    if (x >= e.x && x < e.x + e.w && y >= e.y && y < e.y + e.h) return e;
  }
  return null;
}

canvas.addEventListener('mousedown', (ev) => {
  if (st.mode === 'game') return gameClick(ev);
  if (st.mode !== 'edit' || ev.button !== 0) return;
  const s = screen();
  const p = toGui(ev); p.x -= MARGIN; p.y -= MARGIN;
  const cur = selected();
  if (cur && !cur.locked) {
    const tol = 5 / st.z;
    const h = handles(cur).find((h) => Math.abs(h.x - p.x) <= tol && Math.abs(h.y - p.y) <= tol);
    if (h) { st.drag = { kind: 'resize', handle: h.name, start: p, orig: { ...cur }, id: cur.id }; return; }
  }
  const hit = hitElement(s, p.x, p.y);
  st.sel = hit?.id ?? null;
  renderLayers(); renderInspector();
  if (hit && !hit.locked) st.drag = { kind: 'move', start: p, orig: { ...hit }, id: hit.id, moved: false };
  draw();
});

window.addEventListener('mousemove', (ev) => {
  if (st.mode === 'game') return gameHover(ev);
  if (st.mode !== 'edit') return;
  const p = toGui(ev); p.x -= MARGIN; p.y -= MARGIN;
  $('#cursorPos').textContent = `x ${Math.floor(p.x)}, y ${Math.floor(p.y)}（行 ${Math.floor(p.y / LINE)}）`;
  const d = st.drag;
  if (!d) return;
  const e = selected();
  if (!e || e.id !== d.id) return;
  const dx = Math.round(p.x - d.start.x), dy = Math.round(p.y - d.start.y);
  const grid = ev.shiftKey ? LINE : 1;
  const gameText = e.type === 'text' && e.render === 'game';
  const rowBound = e.type === 'button' || e.type === 'hotspot' || gameText;
  const snapY = gameText || ($('#snapChk').checked && rowBound) ? LINE : grid;
  const sn = (v, g) => Math.round(v / g) * g;
  if (d.kind === 'move') {
    e.x = sn(d.orig.x + dx, grid); e.y = sn(d.orig.y + dy, snapY);
    d.moved = d.moved || dx !== 0 || dy !== 0;
  } else {
    let { x, y, w, h } = d.orig;
    const hn = d.handle;
    if (hn.includes('e')) w = Math.max(1, sn(d.orig.x + d.orig.w + dx, grid) - x);
    if (hn.includes('s')) h = Math.max(1, sn(d.orig.y + d.orig.h + dy, snapY) - y);
    if (hn.includes('w')) { const nx = Math.min(sn(d.orig.x + dx, grid), d.orig.x + d.orig.w - 1); w = d.orig.x + d.orig.w - nx; x = nx; }
    if (hn.includes('n')) { const ny = Math.min(sn(d.orig.y + dy, snapY), d.orig.y + d.orig.h - 1); h = d.orig.y + d.orig.h - ny; y = ny; }
    Object.assign(e, { x, y, w, h });
    d.moved = true;
  }
  draw();
});

window.addEventListener('mouseup', () => {
  const d = st.drag;
  st.drag = null;
  if (!d || !d.moved) return;
  const e = selected();
  if (!e) return;
  commit([{ op: 'patch_element', screen: screen().id, element: e.id, values: { x: e.x, y: e.y, w: e.w, h: e.h } }], d.kind === 'move' ? `移動 ${e.id}` : `サイズ ${e.id}`);
});

// プレビュー（ゲーム風）での操作
function gamePoint(ev) {
  const p = toGui(ev);
  const L = st.game.layout, s = st.game.screen;
  if (!L) return null;
  for (const b of L.buttons) if (p.x >= b.x && p.x < b.x + b.w && p.y >= b.y && p.y < b.y + b.h) return { footer: b.index, button: b.button };
  if (p.y < L.bodyView.y || p.y >= L.bodyView.y + L.bodyView.h) return null;
  const bx = p.x - L.image.x, by = p.y + st.game.scroll - L.image.y;
  if (bx < 0 || by < 0 || bx >= s.width + 1 || by >= s.height) return null;
  const row = Math.floor(by / LINE);
  const seg = clickSegments(s).find((g) => g.row === row && bx >= g.x0 && bx < g.x1);
  return seg ? { element: seg.el } : null;
}

function gameHover(ev) {
  const r = canvas.getBoundingClientRect();
  if (ev.clientX < r.left || ev.clientX > r.right || ev.clientY < r.top || ev.clientY > r.bottom) { hideTip(); return; }
  const hit = gamePoint(ev);
  const prev = st.game.hover;
  st.game.hover = hit;
  const tip = hit?.element?.tooltip || hit?.button?.tooltip;
  const tt = $('#tooltip');
  if (tip) { tt.textContent = tip; tt.hidden = false; tt.style.left = `${ev.clientX + 12}px`; tt.style.top = `${ev.clientY - 18}px`; }
  else tt.hidden = true;
  canvas.style.cursor = hit ? 'pointer' : 'default';
  if ((prev?.footer ?? null) !== (hit?.footer ?? null)) draw();
}
function hideTip() { $('#tooltip').hidden = true; }

function gameClick(ev) {
  const hit = gamePoint(ev);
  if (!hit) return;
  hideTip();
  const a = hit.element ? hit.element.action : hit.button.action;
  const s = st.game.screen;
  let msg = '';
  switch (a.type) {
    case 'screen': st.game.screenId = a.value; st.game.scroll = 0; msg = `→ ${a.value} を開きました（show_dialog）`; break;
    case 'close': msg = hit.footer != null ? '閉じました' : `custom ${project().namespace}:close を送信 → プラグインが閉じる`; break;
    case 'custom': msg = `custom ${a.value} ${a.payload || ''} を送信`; break;
    case 'submit': msg = `dynamic/custom ${a.value} を入力値付きで送信`; break;
    case 'command': msg = `コマンド実行: /${a.value.replace(/^\//, '')}`; break;
    case 'suggest': msg = `チャット欄に入力: ${a.value}`; break;
    case 'url': msg = `URLを開く確認: ${a.value}`; break;
    case 'copy': msg = `コピー: ${a.value}`; break;
    default: msg = 'アクションなし';
  }
  if (s.afterAction === 'close' && a.type !== 'screen' && hit.element) msg += '（after_action=close のため画面は閉じます）';
  st.game.message = msg;
  setStatus(msg);
  draw();
}

canvas.addEventListener('wheel', (ev) => {
  if (st.mode !== 'game') return;
  ev.preventDefault();
  st.game.scroll += ev.deltaY > 0 ? 20 : -20;
  draw();
}, { passive: false });

// 画像のドロップ（ファイル・素材一覧から）
const stage = $('#stage');
stage.addEventListener('dragover', (ev) => { ev.preventDefault(); stage.classList.add('drop'); });
stage.addEventListener('dragleave', () => stage.classList.remove('drop'));
stage.addEventListener('drop', async (ev) => {
  ev.preventDefault();
  stage.classList.remove('drop');
  if (st.mode !== 'edit') return;
  const p = toGui(ev); p.x = Math.round(p.x - MARGIN); p.y = Math.round(p.y - MARGIN);
  const assetId = ev.dataTransfer.getData('text/x-asset');
  if (assetId) return addImageElement(assetId, p);
  for (const f of ev.dataTransfer.files) {
    const asset = await uploadAsset(f);
    if (asset) await addImageElement(asset.id, p);
  }
});

async function uploadAsset(file) {
  try {
    const r = await api('POST', '/api/assets', file, { 'X-Filename': encodeURIComponent(file.name), 'X-Source': 'editor' });
    st.snap = { revision: r.revision, project: r.project, canUndo: r.canUndo, canRedo: r.canRedo };
    syncImages(); renderAll();
    return r.asset;
  } catch (e) { alert(e.message); return null; }
}

function addImageElement(assetId, p) {
  const a = project().assets[assetId];
  const s = screen();
  const k = project().textureScale;
  let w = Math.max(1, Math.round(a.width / k)), h = Math.max(1, Math.round(a.height / k));
  const fit = Math.min(1, s.width / w, s.height / h);
  w = Math.max(1, Math.round(w * fit)); h = Math.max(1, Math.round(h * fit));
  const x = clamp(p?.x ?? 0, 0, s.width - w), y = clamp(p?.y ?? 0, 0, s.height - h);
  return addElement({ type: 'image', asset: assetId, x, y, w, h, name: a.name });
}

async function addElement(el) {
  const s = screen();
  const id = uniqueLocal(s, el.type);
  const ok = await commit([{ op: 'add_element', screen: s.id, element: { ...el, id } }], `追加 ${id}`);
  if (ok) { st.sel = id; renderAll(); }
}

function uniqueLocal(s, base) {
  const ids = new Set(s.elements.map((e) => e.id));
  for (let i = 1; ; i++) if (!ids.has(`${base}_${i}`)) return `${base}_${i}`;
}

document.querySelectorAll('[data-add]').forEach((b) => b.addEventListener('click', () => {
  const type = b.dataset.add;
  const s = screen();
  if (type === 'image') {
    const ids = Object.keys(project().assets);
    if (!ids.length) { $('#assetInput').click(); return; }
    return addImageElement(ids[ids.length - 1], { x: 9, y: 9 });
  }
  const sizes = { rect: [72, 36], text: [108, 9], button: [90, 18], hotspot: [36, 18] };
  const [w, h] = sizes[type];
  addElement({ type, x: Math.min(9, s.width - w), y: Math.min(9, s.height - h), w: Math.min(w, s.width), h: Math.min(h, s.height) });
}));

$('#assetInput').addEventListener('change', async (ev) => {
  for (const f of ev.target.files) await uploadAsset(f);
  ev.target.value = '';
});

// ---- キーボード -------------------------------------------------------------
window.addEventListener('keydown', (ev) => {
  const tag = document.activeElement?.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
  const mod = ev.ctrlKey || ev.metaKey;
  if (mod && ev.key.toLowerCase() === 'z' && !ev.shiftKey) { ev.preventDefault(); undo(); return; }
  if (mod && (ev.key.toLowerCase() === 'y' || (ev.key.toLowerCase() === 'z' && ev.shiftKey))) { ev.preventDefault(); redo(); return; }
  const e = selected();
  if (!e || st.mode !== 'edit') return;
  if (ev.key === 'Delete' || ev.key === 'Backspace') { ev.preventDefault(); removeSelected(); return; }
  if (mod && ev.key.toLowerCase() === 'd') { ev.preventDefault(); duplicateSelected(); return; }
  if (ev.key === 'Escape') { st.sel = null; renderAll(); return; }
  const step = ev.shiftKey ? LINE : 1;
  const mv = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[ev.key];
  if (mv && !e.locked) {
    ev.preventDefault();
    e.x += mv[0]; e.y += mv[1];
    draw(); renderInspector();
    clearTimeout(st.pending?.timer);
    st.pending = { id: e.id, screen: screen().id, timer: setTimeout(() => flushNudge(true), 400) };
  }
});

function flushNudge(send) {
  const p = st.pending;
  if (!p) return;
  clearTimeout(p.timer);
  st.pending = null;
  const s = project().screens.find((x) => x.id === p.screen);
  const e = s?.elements.find((x) => x.id === p.id);
  if (!e) return;
  const op = { op: 'patch_element', screen: s.id, element: e.id, values: { x: e.x, y: e.y } };
  if (send) commit([op], `移動 ${e.id}`);
  else api('POST', '/api/ops', { revision: st.snap.revision, operations: [op], label: `移動 ${e.id}`, source: 'editor' }).then((r) => { st.snap = r; }).catch(() => load());
}

async function undo() { try { st.snap = await api('POST', '/api/undo', { source: 'editor' }); if (!selected()) st.sel = null; renderAll(); scheduleCompileInfo(); } catch (e) { setStatus(e.message, true); } }
async function redo() { try { st.snap = await api('POST', '/api/redo', { source: 'editor' }); if (!selected()) st.sel = null; renderAll(); scheduleCompileInfo(); } catch (e) { setStatus(e.message, true); } }
$('#undoBtn').onclick = undo;
$('#redoBtn').onclick = redo;

function removeSelected() {
  const e = selected();
  if (!e) return;
  commit([{ op: 'remove_element', screen: screen().id, element: e.id }], `削除 ${e.id}`);
}
async function duplicateSelected() {
  const e = selected();
  if (!e) return;
  const id = uniqueLocal(screen(), e.id.replace(/_\d+$/, ''));
  if (await commit([{ op: 'duplicate_element', screen: screen().id, element: e.id, newId: id }], `複製 ${e.id}`)) { st.sel = id; renderAll(); }
}

// ---- 一覧 -----------------------------------------------------------------
function renderTabs() {
  const box = $('#screenTabs');
  box.innerHTML = '';
  for (const s of project().screens) {
    const b = document.createElement('button');
    b.textContent = s.title || s.id;
    b.className = s.id === screen()?.id ? 'on' : '';
    b.onclick = () => selectScreen(s.id);
    box.append(b);
  }
}

function selectScreen(id) {
  flushNudge(true);
  st.screenId = id; st.sel = null; st.game.screenId = id; st.game.scroll = 0; st.compiledImg = null;
  renderAll(); scheduleCompileInfo();
}

function renderScreenList() {
  const ul = $('#screenList');
  ul.innerHTML = '';
  for (const s of project().screens) {
    const li = document.createElement('li');
    li.className = s.id === screen()?.id ? 'on' : '';
    li.innerHTML = `<span class="name">${esc(s.title || s.id)}</span><span class="type">${esc(s.id)}</span>`;
    li.onclick = () => selectScreen(s.id);
    const dup = btn('⧉', '複製', (ev) => { ev.stopPropagation(); commit([{ op: 'duplicate_screen', screen: s.id }], `画面複製 ${s.id}`); });
    const del = btn('✕', '削除', (ev) => {
      ev.stopPropagation();
      if (project().screens.length <= 1) return alert('最後の画面は削除できません');
      if (confirm(`画面「${s.title}」を削除しますか？（元に戻すで復元できます）`)) commit([{ op: 'remove_screen', screen: s.id }], `画面削除 ${s.id}`);
    });
    li.append(dup, del);
    ul.append(li);
  }
}

$('#addScreen').onclick = async () => {
  const id = prompt('画面ID（英小文字・数字・_）', `screen_${project().screens.length + 1}`);
  if (!id) return;
  if (await commit([{ op: 'add_screen', screen: { id, title: id } }], `画面追加 ${id}`)) selectScreen(id);
};

function renderLayers() {
  const ul = $('#layerList');
  ul.innerHTML = '';
  const s = screen();
  if (!s) return;
  [...s.elements].reverse().forEach((e) => {
    const idx = s.elements.indexOf(e);
    const li = document.createElement('li');
    li.className = `${e.id === st.sel ? 'on' : ''} ${e.visible ? '' : 'hidden'}`;
    li.innerHTML = `<span class="type">${TYPE_LABEL[e.type]}</span><span class="name" title="${esc(e.id)}">${esc(e.name || e.id)}</span>`;
    li.onclick = () => { st.sel = e.id; renderLayers(); renderInspector(); draw(); };
    li.append(
      btn(e.visible ? '👁' : '–', '表示/非表示', (ev) => { ev.stopPropagation(); commit([{ op: 'set', screen: s.id, element: e.id, path: 'visible', value: !e.visible }], `表示切替 ${e.id}`); }),
      btn('▲', '前面へ', (ev) => { ev.stopPropagation(); if (idx < s.elements.length - 1) commit([{ op: 'move_element', screen: s.id, element: e.id, index: idx + 1 }], `前面へ ${e.id}`); }),
      btn('▼', '背面へ', (ev) => { ev.stopPropagation(); if (idx > 0) commit([{ op: 'move_element', screen: s.id, element: e.id, index: idx - 1 }], `背面へ ${e.id}`); }),
    );
    ul.append(li);
  });
}

function renderAssets() {
  const ul = $('#assetList');
  ul.innerHTML = '';
  for (const [id, a] of Object.entries(project().assets)) {
    const li = document.createElement('li');
    li.title = `${a.name}\n${a.width}×${a.height}px\n${id}`;
    li.draggable = true;
    li.addEventListener('dragstart', (ev) => ev.dataTransfer.setData('text/x-asset', id));
    li.ondblclick = () => addImageElement(id, { x: 0, y: 0 });
    const img = document.createElement('img');
    img.src = `/assets/${encodeURIComponent(a.file)}`;
    img.alt = a.name;
    li.append(img, btn('✕', '素材を削除（未使用のみ）', (ev) => { ev.stopPropagation(); commit([{ op: 'remove_asset', asset: id }], `素材削除 ${a.name}`); }));
    li.lastChild.classList.add('del');
    ul.append(li);
  }
}

function renderDiag() {
  const info = st.compileInfo;
  const box = $('#diag');
  if (!info || info.screenId !== screen()?.id) { box.innerHTML = ''; return; }
  const parts = [];
  if (vanilla.available === false) parts.push(`<div class="e">ゲーム内フォントを読み込めません: ${esc(vanilla.error)}</div>`);
  for (const e of info.errors) parts.push(`<div class="e">エラー: ${esc(e)}</div>`);
  for (const w of info.warnings) parts.push(`<div class="w">注意: ${esc(w)}</div>`);
  for (const a of info.audit || []) parts.push(`<div class="w">見た目: ${esc(a.element)} — ${esc(a.message)}</div>`);
  if (!info.errors.length) parts.push(`<div class="ok">検証OK — タイル ${info.stats.tiles} 枚 / 行 ${info.stats.lines} / テキスト部品 ${info.stats.components} / JSON ${(info.stats.jsonBytes / 1024).toFixed(1)} KB / クリック区間 ${info.clicks.length}（r${info.revision}）</div>`);
  box.innerHTML = parts.join('');
}

// ---- インスペクター ------------------------------------------------------------
function btn(text, title, onclick) {
  const b = document.createElement('button');
  b.type = 'button'; b.textContent = text; b.title = title; b.onclick = onclick;
  return b;
}
function esc(s) { return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]); }

function field(label, control, note) {
  const d = document.createElement('div');
  d.className = 'field';
  const l = document.createElement('span'); l.textContent = label;
  d.append(l, control);
  if (note) { const n = document.createElement('div'); n.className = 'note'; n.style.gridColumn = '1 / -1'; n.textContent = note; d.append(n); }
  return d;
}

function textInput(value, onChange, opts = {}) {
  const i = document.createElement(opts.multiline ? 'textarea' : 'input');
  if (!opts.multiline) i.type = 'text';
  i.value = value ?? '';
  if (opts.list) i.setAttribute('list', opts.list);
  if (opts.placeholder) i.placeholder = opts.placeholder;
  i.addEventListener('change', () => onChange(i.value));
  return i;
}

function numInput(value, onChange, opts = {}) {
  const i = document.createElement('input');
  i.type = 'number'; i.value = value;
  if (opts.min != null) i.min = opts.min;
  if (opts.max != null) i.max = opts.max;
  i.step = opts.step ?? 1;
  i.addEventListener('change', () => { const v = Number(i.value); if (Number.isFinite(v)) onChange(opts.float ? v : Math.round(v)); });
  return i;
}

function checkInput(value, onChange) {
  const i = document.createElement('input');
  i.type = 'checkbox'; i.checked = !!value;
  i.addEventListener('change', () => onChange(i.checked));
  const w = document.createElement('div'); w.append(i);
  return w;
}

function selectInput(value, options, onChange) {
  const s = document.createElement('select');
  for (const o of options) {
    const [v, label] = Array.isArray(o) ? o : [o, o];
    const opt = document.createElement('option'); opt.value = v; opt.textContent = label;
    if (String(v) === String(value)) opt.selected = true;
    s.append(opt);
  }
  s.addEventListener('change', () => onChange(s.value));
  return s;
}

// 色の入力欄。テーマの色（@名前）を選ぶか、直接指定する。opts.direct なら直接指定のみ（テーマ自体の編集用）
function colorInput(value, onChange, opts = {}) {
  const wrap = document.createElement('div'); wrap.className = 'color';
  const tokens = project().theme?.tokens || {};
  const isRef = isTokenRef(value);
  if (!opts.direct) {
    const sel = document.createElement('select');
    sel.className = 'token-sel';
    sel.title = 'テーマの色を使うと、テーマの変更が全画面に反映されます';
    sel.append(new Option('直接指定', ''));
    for (const k of Object.keys(tokens).sort()) sel.append(new Option(`@${k}`, `@${k}`));
    sel.value = isRef ? value : '';
    sel.addEventListener('change', () => onChange(sel.value || (isRef ? tokens[value.slice(1)] : value) || '#808080ff'));
    if (isRef) {
      const sw = document.createElement('span');
      sw.className = 'swatch';
      sw.style.background = cssColorOf(tokens[value.slice(1)]);
      sw.title = tokens[value.slice(1)] || '（テーマにない色）';
      wrap.append(sw, sel);
      return wrap;
    }
    wrap.append(sel);
  }
  const hex = (value || '#000000ff').slice(0, 7);
  const alpha = value?.length === 9 ? parseInt(value.slice(7), 16) : 255;
  const c = document.createElement('input'); c.type = 'color'; c.value = hex;
  const a = document.createElement('input'); a.type = 'range'; a.min = 0; a.max = 255; a.value = alpha; a.title = '不透明度';
  const t = document.createElement('input'); t.type = 'text'; t.value = value;
  const emit = (v) => { if (/^#[0-9a-fA-F]{8}$/.test(v)) onChange(v.toLowerCase()); };
  const fromParts = () => `${c.value}${Number(a.value).toString(16).padStart(2, '0')}`;
  c.addEventListener('change', () => emit(fromParts()));
  a.addEventListener('change', () => emit(fromParts()));
  t.addEventListener('change', () => { let v = t.value.trim(); if (/^#[0-9a-fA-F]{6}$/.test(v)) v += 'ff'; emit(v); });
  wrap.append(c, a, t);
  return wrap;
}

function cssColorOf(hex) {
  if (!hex) return 'repeating-linear-gradient(45deg,#f0f,#f0f 3px,#000 3px,#000 6px)';
  const h = hex.slice(1);
  return `rgba(${parseInt(h.slice(0, 2), 16)},${parseInt(h.slice(2, 4), 16)},${parseInt(h.slice(4, 6), 16)},${h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1})`;
}

// テーマ（色の一覧）の編集。名前の . より前で分類して表示する
function themeEditor(box) {
  const p = project();
  const tokens = p.theme?.tokens || {};
  box.append(h3('テーマ（色）'));
  box.append(note('要素の色に「@名前」を選ぶと、ここで色を変えるだけで全画面に反映されます。デザイナー向けの説明は docs/theme.md。'));
  const groups = new Map();
  for (const k of Object.keys(tokens).sort()) {
    const g = k.includes('.') ? k.slice(0, k.indexOf('.')) : '（その他）';
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(k);
  }
  for (const [g, keys] of groups) {
    const card = document.createElement('div'); card.className = 'card';
    const hd = document.createElement('div'); hd.className = 'head'; hd.innerHTML = `<span class="mono">${esc(g)}</span>`;
    card.append(hd);
    for (const k of keys) {
      const users = themeUsers(p, k).length;
      const lab = document.createElement('span');
      lab.className = 'mono';
      lab.textContent = k.slice(g === '（その他）' ? 0 : g.length + 1);
      lab.title = `@${k}（${users}か所で使用）。クリックで名前を変更`;
      lab.style.cursor = 'pointer';
      lab.onclick = () => {
        const to = prompt('新しい名前（使っている要素も付け替えます）', k);
        if (to && to !== k) commit([{ op: 'rename_theme_token', key: k, to }], `色の名前 ${k}→${to}`);
      };
      const f = field('', colorInput(tokens[k], (v) => commit([{ op: 'set_theme_token', key: k, value: v }], `テーマ ${k}`), { direct: true }));
      f.firstChild.replaceWith(lab);
      const del = btn('✕', users ? `使用中（${users}か所）のため削除できません` : '削除', () => commit([{ op: 'remove_theme_token', key: k }], `テーマ削除 ${k}`));
      del.disabled = users > 0;
      f.append(del);
      f.style.gridTemplateColumns = '96px 1fr auto';
      card.append(f);
    }
    box.append(card);
  }
  const tools = document.createElement('div');
  tools.style.display = 'flex'; tools.style.gap = '4px'; tools.style.flexWrap = 'wrap';
  tools.append(
    btn('＋ 色を追加', '', () => {
      const key = prompt('色の名前（例: panel.bg）');
      if (key) commit([{ op: 'set_theme_token', key, value: '#808080ff' }], `テーマ追加 ${key}`);
    }),
    btn('JSONを書き出す', 'テーマをファイルに保存（デザイナーに渡す用）', () => {
      const blob = new Blob([JSON.stringify({ name: p.theme?.name ?? 'default', tokens }, null, 2)], { type: 'application/json' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob); a.download = `theme-${p.theme?.name ?? 'default'}.json`; a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    }),
    btn('JSONを読み込む', '同じ名前の色を上書きします（ない名前は追加）', () => {
      const inp = document.createElement('input'); inp.type = 'file'; inp.accept = 'application/json,.json';
      inp.onchange = async () => {
        try {
          const theme = JSON.parse(await inp.files[0].text());
          commit([{ op: 'set_theme', theme, merge: true }], `テーマ読み込み ${theme.name ?? ''}`);
        } catch (e) { alert(`読み込めません: ${e.message}`); }
      };
      inp.click();
    }),
  );
  box.append(tools);
}

function h3(text) { const h = document.createElement('h3'); h.textContent = text; return h; }
function note(text, cls = '') { const n = document.createElement('p'); n.className = `note ${cls}`; n.textContent = text; return n; }

function renderInspector() {
  const box = $('#inspector');
  box.innerHTML = '';
  const s = screen();
  if (!s) return;
  const e = selected();
  if (e) inspectElement(box, s, e);
  else inspectScreen(box, s);
}

function setEl(s, e, path, value) { return commit([{ op: 'set', screen: s.id, element: e.id, path, value }], `${e.id}.${path}`); }
function setSc(s, path, value) { return commit([{ op: 'set', screen: s.id, path, value }], `${s.id}.${path}`); }
function setPj(path, value) { return commit([{ op: 'set', path, value }], `project.${path}`); }

function inspectElement(box, s, e) {
  const head = document.createElement('h2');
  head.innerHTML = `<span>${TYPE_LABEL[e.type]} <span class="mono muted">${esc(e.id)}</span></span>`;
  const tools = document.createElement('span');
  tools.append(btn('複製', 'Ctrl+D', duplicateSelected), btn('削除', 'Delete', removeSelected));
  tools.lastChild.classList.add('danger');
  head.append(tools);
  box.append(head);
  box.append(field('ID', textInput(e.id, (v) => commit([{ op: 'set', screen: s.id, element: e.id, path: 'id', value: v }], `id ${e.id}→${v}`).then((ok) => { if (ok) { st.sel = v; renderAll(); } }))));
  box.append(field('名前', textInput(e.name, (v) => setEl(s, e, 'name', v), { placeholder: '一覧での表示名' })));
  const geo = document.createElement('div'); geo.className = 'field row4';
  for (const k of ['x', 'y', 'w', 'h']) {
    const l = document.createElement('label'); l.textContent = k.toUpperCase();
    l.append(numInput(e[k], (v) => setEl(s, e, k, v), { min: k === 'w' || k === 'h' ? 1 : undefined }));
    geo.append(l);
  }
  box.append(geo);
  box.append(field('ロック', checkInput(e.locked, (v) => setEl(s, e, 'locked', v))));

  if (e.type === 'rect' || e.type === 'button') {
    box.append(h3(e.type === 'button' ? 'ボタンの見た目' : '塗り'));
    box.append(field('塗り', colorInput(e.fill, (v) => setEl(s, e, 'fill', v))));
    box.append(field('枠線', colorInput(e.border, (v) => setEl(s, e, 'border', v))));
    box.append(field('枠の太さ', numInput(e.borderWidth, (v) => setEl(s, e, 'borderWidth', v), { min: 0, max: 32 })));
  }
  if (e.type === 'image' || e.type === 'button') {
    const key = e.type === 'image' ? 'asset' : 'image';
    const opts = [['', '（なし）'], ...Object.entries(project().assets).map(([id, a]) => [id, `${a.name} (${a.width}×${a.height})`])];
    box.append(field('画像', selectInput(e[key] ?? '', opts, (v) => setEl(s, e, key, v || null))));
    box.append(field('配置', selectInput(e.fit, FITS.map((f) => [f, { stretch: '引き伸ばし', contain: '全体を収める', cover: '覆う（切り抜き）', tile: '敷き詰め' }[f]]), (v) => setEl(s, e, 'fit', v))));
    if (e.type === 'image') {
      box.append(field('補間', checkInput(e.smooth, (v) => setEl(s, e, 'smooth', v)), 'オフでドット絵のまま拡大します'));
      box.append(field('不透明度', numInput(e.opacity, (v) => setEl(s, e, 'opacity', clamp(v, 0, 1)), { min: 0, max: 1, step: 0.05, float: true })));
      const a = project().assets[e.asset];
      if (a) box.append(btn('素材の実寸に合わせる', `${a.width}/${project().textureScale} GUI px`, () => commit([{ op: 'patch_element', screen: s.id, element: e.id, values: { w: Math.max(1, Math.round(a.width / project().textureScale)), h: Math.max(1, Math.round(a.height / project().textureScale)) } }], `実寸 ${e.id}`)));
    }
  }
  if (e.type === 'text' || e.type === 'button') inspectText(box, s, e);
  if (e.type === 'button' || e.type === 'hotspot') {
    box.append(h3('クリック'));
    actionEditor(box, e.action, ACTION_TYPES, (a) => setEl(s, e, 'action', a));
    box.append(field('ツールチップ', textInput(e.tooltip, (v) => setEl(s, e, 'tooltip', v), { multiline: true, placeholder: 'ホバー時に表示' })));
    const rows = clickRows(e.y, e.h, s.height);
    box.append(note(`クリック領域: 行 ${rows.length ? `${rows[0]}〜${rows[rows.length - 1]}（y ${rows[0] * LINE}〜${(rows[rows.length - 1] + 1) * LINE}）` : 'なし（高さ不足）'}。9px行単位に量子化されます。ホバーで画像を変えることはできません。`, e.y % LINE || e.h % LINE ? 'warn' : ''));
  }
}

function inspectText(box, s, e) {
  const isText = e.type === 'text';
  const rk = isText ? 'render' : 'labelRender';
  const tk = isText ? 'text' : 'label';
  const ck = isText ? 'color' : 'labelColor';
  const game = e[rk] === 'game';
  box.append(h3(isText ? '文字' : 'ラベル'));
  box.append(field('描画', selectInput(e[rk], TEXT_RENDERS.map((r) => [r, r === 'game' ? 'ゲーム内フォント' : '画像に焼き込み']), (v) => {
    const ops = [{ op: 'set', screen: s.id, element: e.id, path: rk, value: v }];
    if (v === 'game' && isText) {
      ops.push({ op: 'set', screen: s.id, element: e.id, path: 'y', value: Math.round(e.y / LINE) * LINE });
      ops.push({ op: 'set', screen: s.id, element: e.id, path: 'h', value: Math.max(LINE, Math.round(e.h / LINE) * LINE) });
    }
    commit(ops, `描画方式 ${e.id}`);
  }), game ? 'ゲームの文字で表示（8px固定・日本語可）。y は9px行、h で行数が決まります。常に最前面に描かれます' : '任意のフォントを画像にします。見出しは英語のドットフォント推奨'));
  box.append(field(isText ? '本文' : 'ラベル', textInput(e[tk], (v) => setEl(s, e, tk, v), { multiline: true })));
  if (!game) {
    box.append(field('フォント', textInput(e.fontFamily, (v) => {
      const f = customFonts.find((x) => x.family === v);
      if (f?.pixel) {
        // 登録ドットフォント: 1ドット=2px、空白を見やすい幅に
        commit([
          { op: 'set', screen: s.id, element: e.id, path: 'fontFamily', value: v },
          { op: 'set', screen: s.id, element: e.id, path: 'pixel', value: true },
          { op: 'set', screen: s.id, element: e.id, path: 'fontSize', value: f.dotSize * 2 },
          { op: 'set', screen: s.id, element: e.id, path: 'wordSpacing', value: Math.max(0, ((f.spaceDots ?? 1) - 1) * 2) },
        ], `フォント ${v}`);
      } else setEl(s, e, 'fontFamily', v);
    }, { list: 'fontList' })));
    const cf = customFonts.find((f) => f.family === e.fontFamily);
    if (cf) box.append(note(`${cf.family}: ${cf.chars || ''}。${cf.license || ''}${cf.dotSize ? `。${cf.dotSize}px 指定で1ドット=1px（整数倍で使う）` : ''}`, cf.pixel && e.fontSize % cf.dotSize ? 'warn' : ''));
    box.append(field('サイズ', numInput(e.fontSize, (v) => setEl(s, e, 'fontSize', v), { min: 4, max: 128, step: cf?.dotSize || 0.5, float: true }), 'GUI px'));
    box.append(field('ドット化', checkInput(e.pixel, (v) => setEl(s, e, 'pixel', v)), 'アンチエイリアスを2値化して、にじみをなくす'));
    box.append(field('単語間隔', numInput(e.wordSpacing, (v) => setEl(s, e, 'wordSpacing', v), { min: -16, max: 64 }), '空白1つごとに足す幅（GUI px）'));
  }
  box.append(field('太字', checkInput(e.bold, (v) => setEl(s, e, 'bold', v))));
  box.append(field('色', colorInput(e[ck], (v) => setEl(s, e, ck, v))));
  box.append(field('影', checkInput(e.shadow, (v) => setEl(s, e, 'shadow', v))));
  if (e.shadow && isText) box.append(field('影の色', colorInput(e.shadowColor, (v) => setEl(s, e, 'shadowColor', v))));
  if (isText) {
    box.append(field('横揃え', selectInput(e.align, ALIGNS.map((a) => [a, { left: '左', center: '中央', right: '右' }[a]]), (v) => setEl(s, e, 'align', v))));
    if (!game) {
      box.append(field('縦揃え', selectInput(e.valign, [['top', '上'], ['middle', '中央'], ['bottom', '下']], (v) => setEl(s, e, 'valign', v))));
      box.append(field('行間', numInput(e.lineHeight, (v) => setEl(s, e, 'lineHeight', v), { min: 0.8, max: 3, step: 0.05, float: true })));
    }
  }
  if (game) {
    const missing = Array.from(e[tk] || '').filter((ch) => glyphStore.get(ch.codePointAt(0)) === null);
    if (missing.length) box.append(note(`ゲーム内フォントにない文字: ${[...new Set(missing)].join(' ')}`, 'warn'));
    if (!isText && (e.y % LINE || e.h % LINE)) box.append(note('ラベルは9px行に置かれます。y/h を9の倍数にすると中央に揃います', 'warn'));
  }
}

function actionEditor(box, action, types, onChange) {
  const a = { ...action };
  const footer = types === FOOTER_ACTION_TYPES;
  box.append(field('動作', selectInput(a.type, types.map((t) => [t, footer && t === 'close' ? '閉じる' : ACTION_LABEL[t] || t]), (v) => {
    const next = { ...a, type: v };
    if (v === 'screen') next.value = project().screens.find((x) => x.id !== screen().id)?.id || screen().id;
    else if (v === 'custom' || v === 'submit') next.value = /:/.test(a.value) ? a.value : `${project().namespace}:action`;
    else if (v === 'url') next.value = /^https?:/.test(a.value) ? a.value : 'https://example.com';
    else if (v === 'command' || v === 'suggest') next.value = a.value && !/:/.test(a.value) ? a.value : '/help';
    onChange(next);
  })));
  if (a.type === 'screen') box.append(field('遷移先', selectInput(a.value, project().screens.map((x) => [x.id, `${x.title} (${x.id})`]), (v) => onChange({ ...a, value: v }))));
  else if (['custom', 'submit', 'command', 'suggest', 'url', 'copy'].includes(a.type)) {
    const ph = { custom: 'namespace:id', submit: 'namespace:id', command: '/say hi', suggest: '/msg ', url: 'https://', copy: 'コピーする文字' }[a.type];
    box.append(field(a.type === 'custom' || a.type === 'submit' ? 'ID' : '値', textInput(a.value, (v) => onChange({ ...a, value: v }), { placeholder: ph })));
  }
  if (a.type === 'custom') box.append(field('payload', textInput(a.payload, (v) => onChange({ ...a, payload: v }), { placeholder: '{"item":"x"} または文字列' })));
  if (a.type === 'command') box.append(note('権限が必要なコマンドはクライアントで確認画面が出ます。プラグインのカスタムアクションを推奨します。'));
}

function inspectScreen(box, s) {
  const head = document.createElement('h2'); head.innerHTML = `<span>画面 <span class="mono muted">${esc(s.id)}</span></span>`;
  box.append(head);
  box.append(field('ID', textInput(s.id, (v) => commit([{ op: 'set', screen: s.id, path: 'id', value: v }], `画面id ${v}`).then((ok) => { if (ok) selectScreen(v); })), 'データパックの Dialog ID は namespace:ID になります'));
  box.append(field('タイトル', textInput(s.title, (v) => setSc(s, 'title', v))));
  box.append(field('タイトル表示', checkInput(s.showTitle, (v) => setSc(s, 'showTitle', v)), 'オフでも一時停止メニュー等の外部タイトルには使われます'));
  box.append(field('幅', numInput(s.width, (v) => setSc(s, 'width', v), { min: MIN_BODY_WIDTH, max: MAX_BODY_WIDTH })));
  box.append(field('高さ', numInput(s.height, (v) => setSc(s, 'height', Math.max(LINE, Math.round(v / LINE) * LINE)), { min: LINE, step: LINE }), '9の倍数。画面に収まらない場合はスクロールになります'));
  box.append(field('背景色', colorInput(s.background, (v) => setSc(s, 'background', v))));
  box.append(field('クリック後', selectInput(s.afterAction, AFTER_ACTIONS.map((a) => [a, { close: '閉じる', none: '開いたまま', wait_for_response: '応答待ち画面' }[a]]), (v) => setSc(s, 'afterAction', v)), '本文・フッターのクリック後の動作（画面遷移は常に遷移）'));
  box.append(field('Escで閉じる', checkInput(s.canCloseWithEscape, (v) => setSc(s, 'canCloseWithEscape', v))));
  box.append(field('一時停止', checkInput(s.pause, (v) => setSc(s, 'pause', v)), 'シングルプレイでのみ有効'));

  box.append(h3('フッター（標準ボタン）'));
  box.append(note('見た目は標準ボタン（skinで全体変更のみ可）。「なし」にすると下部のボタンが消え、本文の高さが約28px増えます。'));
  box.append(field('種類', selectInput(s.footer.type, [['notice', '1ボタン (notice)'], ['multi_action', '複数ボタン (multi_action)'], ['none', 'なし（本文の×などで閉じる）']], (v) => {
    const ops = [];
    if (v === 'none') ops.push({ op: 'set', screen: s.id, path: 'footer.buttons', value: [] });
    if (v !== 'none' && !s.footer.buttons.length) ops.push({ op: 'set', screen: s.id, path: 'footer.buttons', value: [{ label: '閉じる', width: 150, tooltip: '', action: { type: 'close', value: '', payload: '' } }] });
    if (v === 'notice' && s.footer.buttons.length > 1) ops.push({ op: 'set', screen: s.id, path: 'footer.buttons', value: [s.footer.buttons[0]] });
    ops.unshift({ op: 'set', screen: s.id, path: 'footer.type', value: v });
    commit(ops, 'フッター種類');
  })));
  if (s.footer.type === 'none') box.append(note('閉じる手段は Esc か、本文の「閉じる」アクション（プラグインが処理）になります。入力欄は使えません。', s.canCloseWithEscape ? '' : 'warn'));
  if (s.footer.type === 'multi_action') box.append(field('列数', numInput(s.footer.columns, (v) => setSc(s, 'footer.columns', v), { min: 1, max: 8 })));
  s.footer.buttons.forEach((b, i) => {
    const card = document.createElement('div'); card.className = 'card';
    const hd = document.createElement('div'); hd.className = 'head'; hd.innerHTML = `<span>ボタン ${i + 1}</span>`;
    if (s.footer.buttons.length > 1) hd.append(btn('✕', '削除', () => commit([{ op: 'remove_footer_button', screen: s.id, index: i }], 'フッターボタン削除')));
    card.append(hd);
    card.append(field('ラベル', textInput(b.label, (v) => setSc(s, `footer.buttons.${i}.label`, v))));
    card.append(field('幅', numInput(b.width, (v) => setSc(s, `footer.buttons.${i}.width`, v), { min: 1, max: 1024 })));
    card.append(field('ツールチップ', textInput(b.tooltip, (v) => setSc(s, `footer.buttons.${i}.tooltip`, v))));
    actionEditor(card, b.action, FOOTER_ACTION_TYPES, (a) => setSc(s, `footer.buttons.${i}.action`, a));
    box.append(card);
  });
  if (s.footer.type === 'multi_action') box.append(btn('＋ ボタンを追加', '', () => commit([{ op: 'add_footer_button', screen: s.id }], 'フッターボタン追加')));

  box.append(h3('入力欄（標準ウィジェット）'));
  box.append(note('本文の下に並びます。値は「入力値を送信」ボタンでプラグインへ送られます。'));
  s.inputs.forEach((inp, i) => {
    const card = document.createElement('div'); card.className = 'card';
    const hd = document.createElement('div'); hd.className = 'head'; hd.innerHTML = `<span>${esc(inp.type)} <span class="mono">${esc(inp.key)}</span></span>`;
    hd.append(btn('✕', '削除', () => commit([{ op: 'remove_input', screen: s.id, index: i }], '入力欄削除')));
    card.append(hd);
    const P = (k) => `inputs.${i}.${k}`;
    card.append(field('key', textInput(inp.key, (v) => setSc(s, P('key'), v))));
    card.append(field('ラベル', textInput(inp.label, (v) => setSc(s, P('label'), v))));
    if (inp.type !== 'boolean') card.append(field('幅', numInput(inp.width, (v) => setSc(s, P('width'), v), { min: 1, max: 1024 })));
    if (inp.type === 'text') {
      card.append(field('初期値', textInput(inp.initial, (v) => setSc(s, P('initial'), v))));
      card.append(field('最大文字数', numInput(inp.maxLength, (v) => setSc(s, P('maxLength'), v), { min: 1 })));
      card.append(field('複数行', checkInput(inp.multiline, (v) => setSc(s, P('multiline'), v))));
    } else if (inp.type === 'boolean') {
      card.append(field('初期値', checkInput(inp.initial, (v) => setSc(s, P('initial'), v))));
    } else if (inp.type === 'single_option') {
      card.append(field('選択肢', textInput(inp.options.map((o) => `${o.id}=${o.display}`).join('\n'), (v) => {
        const options = v.split('\n').map((l) => l.trim()).filter(Boolean).map((l) => { const [id, ...rest] = l.split('='); return { id: id.trim(), display: (rest.join('=') || id).trim() }; });
        commit([{ op: 'set', screen: s.id, path: P('options'), value: options }, { op: 'set', screen: s.id, path: P('initial'), value: options[0]?.id ?? '' }], '選択肢');
      }, { multiline: true }), '1行に1つ「id=表示名」'));
    } else {
      for (const k of ['start', 'end', 'step', 'initial']) card.append(field(k, numInput(inp[k], (v) => setSc(s, P(k), v), { float: true, step: 'any' })));
    }
    box.append(card);
  });
  const addIn = selectInput('', [['', '＋ 入力欄を追加…'], ...INPUT_TYPES.map((t) => [t, { text: 'テキスト', boolean: 'チェックボックス', single_option: '選択肢', number_range: 'スライダー' }[t]])], (v) => { if (v) commit([{ op: 'add_input', screen: s.id, input: { type: v } }], '入力欄追加'); });
  box.append(addIn);

  const p = project();
  box.append(h3('プロジェクト'));
  box.append(field('名前空間', textInput(p.namespace, (v) => setPj('namespace', v)), 'リソースパック/データパック内のID。英小文字'));
  box.append(field('名前', textInput(p.name, (v) => setPj('name', v))));
  box.append(field('パック説明', textInput(p.description, (v) => setPj('description', v))));
  box.append(field('テクスチャ倍率', selectInput(p.textureScale, [1, 2, 3, 4].map((n) => [n, `${n}倍`]), (v) => setPj('textureScale', Number(v))), '大きいほど文字が滑らかになり、パックが大きくなります'));
  themeEditor(box);
  box.append(h3('標準GUIスキン（全体に影響）'));
  box.append(note('ここで指定した画像はMinecraftの全ての標準ボタン/スクロールバーを置き換えます。画面単位では変えられません。', 'warn'));
  const assetOpts = [['', '（変更しない）'], ...Object.entries(p.assets).map(([id, a]) => [id, a.name])];
  for (const slot of SKIN_SLOTS) box.append(field(slot, selectInput(p.skin[slot] ?? '', assetOpts, (v) => setPj(`skin.${slot}`, v || null))));
}

// ---- 書き出し ---------------------------------------------------------------
$('#exportBtn').onclick = async () => {
  flushNudge(true);
  setStatus('書き出し中…');
  try {
    const r = await api('POST', '/api/export', { revision: st.snap.revision });
    const m = r.manifest;
    const errs = r.report.errors, warns = r.report.warnings;
    $('#exportBody').innerHTML = `
      <p>${errs.length ? `<span style="color:var(--danger)">エラー ${errs.length} 件</span>` : '検証OK'} / 注意 ${warns.length} 件</p>
      <p><a href="/api/exports/latest/resourcepack.zip">resourcepack.zip</a> <span class="mono">${m.resourcePack.sha1}</span> (${(m.resourcePack.bytes / 1024).toFixed(1)} KB)<br>
      <a href="/api/exports/latest/datapack.zip">datapack.zip</a> <span class="mono">${m.dataPack.sha1}</span><br>
      <a href="/api/exports/latest/manifest.json">manifest.json</a></p>
      <p class="note">保存先: <span class="mono">${esc(r.dir)}</span></p>
      <p class="note">試し方: リソースパックを有効にし、データパックをワールドの datapacks に入れて <span class="mono">/reload</span> 後、<span class="mono">/dialog show @s ${esc(m.namespace)}:${esc(m.screens[0]?.id || '')}</span>（または <span class="mono">/function ${esc(m.namespace)}:open/${esc(m.screens[0]?.id || '')}</span>）。</p>
      ${errs.length || warns.length ? `<pre>${esc([...errs, ...warns].join('\n'))}</pre>` : ''}`;
    $('#exportDlg').showModal();
    setStatus(`書き出し完了 r${m.projectRevision}`);
  } catch (e) { setStatus(e.message, true); alert(e.message); }
};

// ---- モード・表示設定 -------------------------------------------------------------
document.querySelectorAll('[data-mode]').forEach((b) => b.addEventListener('click', () => {
  st.mode = b.dataset.mode;
  document.querySelectorAll('[data-mode]').forEach((x) => x.classList.toggle('on', x === b));
  st.game.screenId = st.screenId; st.game.scroll = 0; st.game.message = '';
  hideTip();
  canvas.style.cursor = 'default';
  if (st.mode === 'compiled') { st.compiledImg = null; loadCompiledImage(); }
  draw();
}));
$('#zoomSel').addEventListener('change', (ev) => { st.zoom = Number(ev.target.value); if (st.mode === 'compiled') loadCompiledImage(); draw(); });
$('#gridChk').addEventListener('change', draw);
$('#clickChk').addEventListener('change', () => { if (st.mode === 'compiled') loadCompiledImage(); draw(); });

// ---- 同期（CLI/API からの変更を反映） -----------------------------------------------
function connectEvents() {
  const es = new EventSource('/api/events');
  es.addEventListener('change', (ev) => {
    const d = JSON.parse(ev.data);
    if (d.revision !== st.snap?.revision) { load(); if (d.source !== 'editor') setStatus(`外部の変更を反映 r${d.revision}（${d.source}）`); }
  });
  es.onerror = () => setStatus('サーバーとの接続が切れました', true);
  es.onopen = () => { if (st.snap) load(); };
}

loadCustomFonts();

load().then(connectEvents).catch((e) => setStatus(e.message, true));
window.addEventListener('resize', draw);
