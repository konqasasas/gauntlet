// ローカル専用のエディターサーバー。UI・CLIの両方がこのAPIを使う。
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { loadImage } from '@napi-rs/canvas';
import { Store } from './lib/store.mjs';
import { OPS, ELEMENT_TYPES, ACTION_TYPES, FOOTER_ACTION_TYPES, INPUT_TYPES, SKIN_SLOTS, defaultElement, defaultScreen, OpError } from './lib/model.mjs';
import { exportProject, previewPNG, buildScreen, loadAssetImages, fontFamilies, getAtlas, getPixelFonts, FONTS_DIR } from './lib/export.mjs';
import { auditScreen } from './lib/audit.mjs';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.DCG_PORT || 4320);
const HOST = '127.0.0.1';
const DATA = path.resolve(process.env.DCG_DATA || path.join(ROOT, 'data'));
const EXPORTS = path.resolve(process.env.DCG_EXPORTS || path.join(ROOT, 'exports'));
const VANILLA = path.resolve(process.env.DCG_VANILLA || path.join(DATA, 'vanilla'));
const store = new Store(DATA);

const STATIC = {
  '/': ['editor/index.html', 'text/html; charset=utf-8'],
  '/app.js': ['editor/app.js', 'text/javascript; charset=utf-8'],
  '/style.css': ['editor/style.css', 'text/css; charset=utf-8'],
};
// ブラウザーと共有するモジュール（Node専用のものは出さない）
const SHARED = new Set(['model.mjs', 'states.mjs', 'raster.mjs', 'frame.mjs', 'compile.mjs', 'simulate.mjs', 'gametext.mjs']);
const FONT_TYPES = { '.ttf': 'font/ttf', '.otf': 'font/otf', '.woff': 'font/woff', '.woff2': 'font/woff2' };
const IMAGE_TYPES = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif' };

const clients = new Set();
store.on('change', (ev) => {
  const msg = `event: change\ndata: ${JSON.stringify(ev)}\n\n`;
  for (const res of clients) res.write(msg);
});

function send(res, status, body, type = 'application/json; charset=utf-8') {
  const data = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(data);
}

function readBody(req, limit = 2 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', (c) => { size += c.length; if (size > limit) { reject(Object.assign(new Error('リクエストが大きすぎます'), { status: 413 })); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function readJson(req) {
  if (!/application\/json/.test(req.headers['content-type'] || '')) throw Object.assign(new Error('Content-Type: application/json が必要です'), { status: 415 });
  const buf = await readBody(req);
  try { return buf.length ? JSON.parse(buf.toString('utf8')) : {}; } catch { throw Object.assign(new Error('JSONが不正です'), { status: 400 }); }
}

// 他サイトからのリクエストを拒否（ローカルAPIへのCSRF対策）
function checkOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  try {
    const u = new URL(origin);
    return (u.hostname === '127.0.0.1' || u.hostname === 'localhost') && Number(u.port || 80) === PORT;
  } catch { return false; }
}

function schema() {
  return {
    ops: OPS,
    elementTypes: ELEMENT_TYPES,
    actionTypes: ACTION_TYPES,
    footerActionTypes: FOOTER_ACTION_TYPES,
    inputTypes: INPUT_TYPES,
    skinSlots: SKIN_SLOTS,
    defaults: { screen: defaultScreen('example'), elements: Object.fromEntries(ELEMENT_TYPES.map((t) => [t, defaultElement(t)])) },
    notes: [
      '座標・サイズはGUI px。画面の height は9の倍数。クリック領域は9px行単位に量子化される。',
      'POST /api/ops {revision, operations:[...]} で編集。revision が古いと409。全件検証に通った場合のみ保存。',
      'action.type=screen は show_dialog で別画面へ遷移（データパックのみで動作）。close/custom はPaperプラグインが受け取る。',
      'core shader は出力しない。標準ボタン/スクロールバーの skin は Minecraft 全体に影響する。',
    ],
  };
}

async function route(req, res) {
  const url = new URL(req.url, `http://${HOST}:${PORT}`);
  const p = url.pathname;
  if (req.method !== 'GET' && !checkOrigin(req)) return send(res, 403, { error: '許可されていないOriginです' });

  if (req.method === 'GET' && STATIC[p]) {
    const [file, type] = STATIC[p];
    return send(res, 200, fs.readFileSync(path.join(ROOT, file)), type);
  }
  if (req.method === 'GET' && p.startsWith('/lib/')) {
    const name = p.slice(5);
    if (!SHARED.has(name)) return send(res, 404, { error: 'not found' });
    return send(res, 200, fs.readFileSync(path.join(ROOT, 'lib', name)), 'text/javascript; charset=utf-8');
  }
  if (req.method === 'GET' && p.startsWith('/assets/')) {
    const file = path.basename(decodeURIComponent(p.slice(8)));
    const fp = store.assetPath(file);
    if (!fs.existsSync(fp)) return send(res, 404, { error: 'not found' });
    return send(res, 200, fs.readFileSync(fp), IMAGE_TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream');
  }

  if (req.method === 'GET' && p.startsWith('/fonts/')) {
    const file = path.basename(decodeURIComponent(p.slice(7)));
    const fp = path.join(FONTS_DIR, file);
    if (!FONT_TYPES[path.extname(file).toLowerCase()] || !fs.existsSync(fp)) return send(res, 404, { error: 'not found' });
    return send(res, 200, fs.readFileSync(fp), FONT_TYPES[path.extname(file).toLowerCase()]);
  }
  if (p === '/api/vanilla/status' && req.method === 'GET') {
    const v = await getAtlas(VANILLA);
    return send(res, 200, v.font ? { available: true, source: v.font.info, glyphs: v.font.base.size } : { available: false, error: v.error });
  }
  if (p === '/api/vanilla/glyphs' && req.method === 'GET') {
    const v = await getAtlas(VANILLA);
    if (!v.font) return send(res, 503, { error: v.error });
    const lang = url.searchParams.get('lang') === 'jp' ? 'jp' : 'base';
    const out = {};
    for (const h of (url.searchParams.get('cp') || '').split(',').slice(0, 2000)) {
      const cp = parseInt(h, 16);
      if (Number.isInteger(cp) && cp >= 0 && cp <= 0x10ffff) out[cp.toString(16)] = v.font.exportGlyph(cp, lang);
    }
    return send(res, 200, { glyphs: out });
  }
  if (p === '/api/project' && req.method === 'GET') return send(res, 200, store.snapshot());
  if (p === '/api/schema' && req.method === 'GET') return send(res, 200, schema());
  if (p === '/api/history' && req.method === 'GET') return send(res, 200, store.historyList());
  if (p === '/api/fonts' && req.method === 'GET') return send(res, 200, fontFamilies());
  if (p === '/api/pixelfonts' && req.method === 'GET') return send(res, 200, Object.fromEntries(getPixelFonts()));
  if (p === '/api/ops' && req.method === 'POST') {
    const body = await readJson(req);
    const snap = store.apply(body.revision ?? null, body.operations, { label: body.label, source: body.source || 'api' });
    return send(res, 200, snap);
  }
  if (p === '/api/undo' && req.method === 'POST') return send(res, 200, store.undo((await readJson(req)).source));
  if (p === '/api/redo' && req.method === 'POST') return send(res, 200, store.redo((await readJson(req)).source));

  if (p === '/api/assets' && req.method === 'POST') {
    const buf = await readBody(req, 16 * 1024 * 1024);
    const name = decodeURIComponent(req.headers['x-filename'] || 'image.png');
    const ext = path.extname(name).toLowerCase();
    if (!IMAGE_TYPES[ext]) throw Object.assign(new Error('PNG/JPEG/WebP/GIF のみ対応しています'), { status: 400 });
    let img;
    try { img = await loadImage(buf); } catch { throw Object.assign(new Error('画像を読み込めません'), { status: 400 }); }
    const hash = crypto.createHash('sha1').update(buf).digest('hex');
    const id = `img_${hash.slice(0, 10)}`;
    const file = `${id}${ext === '.jpeg' ? '.jpg' : ext}`;
    fs.writeFileSync(store.assetPath(file), buf);
    const asset = { id, file, name: path.basename(name), width: img.width, height: img.height, sha1: hash };
    if (store.project.assets[id]) return send(res, 200, { asset: store.project.assets[id], ...store.snapshot() });
    const snap = store.apply(null, [{ op: 'add_asset', asset }], { label: `素材追加 ${asset.name}`, source: req.headers['x-source'] || 'api' });
    return send(res, 200, { asset, ...snap });
  }

  let m;
  if ((m = p.match(/^\/api\/preview\/([a-z0-9_]+)\.png$/)) && req.method === 'GET') {
    const png = await previewPNG(store, m[1], {
      mode: url.searchParams.get('mode') || 'game',
      clicks: url.searchParams.get('clicks') === '1',
      zoom: Number(url.searchParams.get('zoom') || 2),
      width: Number(url.searchParams.get('vw') || 0) || undefined,
      height: Number(url.searchParams.get('vh') || 0) || undefined,
      state: parseStateParam(url.searchParams.get('state')),
      vanillaDir: VANILLA,
    });
    return send(res, 200, png, 'image/png');
  }
  if ((m = p.match(/^\/api\/compile\/([a-z0-9_]+)$/)) && req.method === 'GET') {
    const screen = store.project.screens.find((s) => s.id === m[1]);
    if (!screen) return send(res, 404, { error: `画面 ${m[1]} がありません` });
    const images = await loadAssetImages(store, store.project);
    const vanilla = await getAtlas(VANILLA);
    const b = buildScreen(store.project, screen, images, vanilla, parseStateParam(url.searchParams.get('state')));
    const full = url.searchParams.get('full') === '1';
    return send(res, 200, {
      revision: store.revision,
      stats: b.compiled.stats, errors: b.errors, warnings: b.warnings, clicks: b.compiled.clicks,
      audit: vanilla.atlas ? auditScreen(store.project, screen, vanilla.atlas) : [],
      ...(full ? { dialog: b.compiled.dialog, font: b.compiled.font } : {}),
    });
  }
  if (p === '/api/export' && req.method === 'POST') {
    const body = await readJson(req);
    if (body.revision != null && body.revision !== store.revision) return send(res, 409, { error: `revision が古いです（現在 ${store.revision}）` });
    const r = await exportProject(store, EXPORTS, VANILLA);
    return send(res, 200, { dir: r.dir, latest: r.latest, manifest: r.manifest, report: r.report });
  }
  if ((m = p.match(/^\/api\/exports\/latest\/(resourcepack\.zip|datapack\.zip|manifest\.json|report\.json)$/)) && req.method === 'GET') {
    const fp = path.join(EXPORTS, 'latest', m[1]);
    if (!fs.existsSync(fp)) return send(res, 404, { error: 'まだ書き出していません' });
    res.writeHead(200, { 'Content-Type': m[1].endsWith('.zip') ? 'application/zip' : 'application/json', 'Content-Disposition': `attachment; filename="${m[1]}"` });
    return res.end(fs.readFileSync(fp));
  }
  if (p === '/api/events' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store', Connection: 'keep-alive' });
    res.write(`event: hello\ndata: ${JSON.stringify({ revision: store.revision })}\n\n`);
    clients.add(res);
    const ping = setInterval(() => res.write(': ping\n\n'), 25000);
    req.on('close', () => { clients.delete(res); clearInterval(ping); });
    return;
  }
  send(res, 404, { error: 'not found' });
}

const server = http.createServer((req, res) => {
  route(req, res).catch((e) => {
    const status = e.status || (e instanceof OpError ? 400 : 500);
    if (status === 500) console.error(e);
    if (!res.headersSent) send(res, status, { error: e.message });
    else res.end();
  });
});

server.listen(PORT, HOST, () => {
  console.log(`Dialog Custom GUI エディター: http://${HOST}:${PORT}/`);
  console.log(`データ: ${DATA}`);
});

// ?state=名前:値,名前:値 （プレビュー・検証で見る状態の値）。なければ null（既定値）
function parseStateParam(v) {
  if (!v) return null;
  const out = {};
  for (const part of v.split(',')) {
    const i = part.indexOf(':');
    if (i > 0) out[part.slice(0, i)] = part.slice(i + 1);
  }
  return out;
}
