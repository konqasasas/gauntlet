#!/usr/bin/env node
// エディターのAPIを操作するCLI。エディター（node server.mjs）の起動中に使う。
// 変更はブラウザーの画面へ即時反映され、Undo履歴にも残る。
import fs from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const flags = {};
const args = [];
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === '-o' || a === '--out') flags.out = argv[++i];
  else if (a.startsWith('--')) {
    const [k, v] = a.slice(2).split('=');
    flags[k] = v ?? (argv[i + 1] && !argv[i + 1].startsWith('-') && ['mode', 'zoom', 'url', 'label', 'index', 'vw', 'vh'].includes(k) ? argv[++i] : true);
  } else args.push(a);
}
const BASE = (flags.url || process.env.DCG_URL || 'http://127.0.0.1:4320').replace(/\/$/, '');

const HELP = `使い方: node cli.mjs <コマンド> [引数] [--json]

閲覧
  status                         revision・画面の一覧
  schema                         操作(op)・要素の型・既定値
  get [画面] [要素]              JSONを表示（引数なしでプロジェクト全体）
  ls <画面>                      要素の一覧（重なり順、下が前面）
  history                        Undo/Redo履歴
  fonts                          登録フォント（fonts/fonts.json）とゲーム内フォントの状態
  compile <画面> [--full]        コンパイル検証（エラー・注意・統計。--fullでDialog JSONも）
  audit [画面]                   見た目の点検（文字の中央ずれ・はみ出し・重なり）。画面を省くと全画面
  preview <画面> [-o out.png] [--mode game|body|compiled] [--clicks] [--zoom 2] [--vw 480 --vh 270]
                                 プレビューPNGを保存（既定 preview-<画面>.png）

編集（値はJSONとして解釈、失敗したら文字列。ドット区切りで入れ子も可）
  add-screen <id> [k=v ...]      例: add-screen inventory title=持ち物 width=270 height=180
  rm-screen <id>
  dup-screen <id> [新id]
  add <画面> <type> [k=v ...]    type: rect|image|text|button|hotspot
                                 例: add main_menu button id=play x=9 y=54 w=100 h=18 label=遊ぶ action.type=screen action.value=shop
                                 文字: render=game（ゲーム内フォント, y は9の倍数）/ render=baked（焼き込み）
                                 例: add shop text id=title x=9 y=9 w=200 h=16 text=SHOP render=baked "fontFamily=5x5 Pixel" fontSize=16 pixel=true
  set <画面|-> <要素|-> k=v ...  例: set main_menu to_shop label=買い物 y=63
                                     set shop - title=お店 footer.buttons.0.label=戻る
                                     set - - textureScale=3        （プロジェクト）
  rm <画面> <要素>
  dup <画面> <要素> [新id]
  mv <画面> <要素> <index>       重なり順（0 = 最背面）
  upload <画像ファイル>          素材を登録し、素材IDを表示
  batch <ops.json | ->           操作の配列をまとめて適用（1件でも失敗したら全件中止）
  undo / redo

テーマ（色の名前。要素の色に @名前 を書くと全画面で共通の色になる）
  theme                          色の一覧
  theme set <名前>=<#RRGGBBAA> … 色を追加・変更        例: theme set panel.bg=#232834ff
  theme rename <名前> <新しい名前>  名前を変え、使っている要素も付け替える
  theme rm <名前>                未使用の色を削除
  theme export [-o file.json]    テーマをJSONで出力（デザイナーに渡す用）
  theme import <file.json> [--replace]  JSONを読み込む（既定は同じ名前を上書き・ない名前は追加）

出力
  export                         resourcepack.zip / datapack.zip / manifest.json を exports/ へ

共通: --json（機械向け出力） --url http://127.0.0.1:4320（または環境変数 DCG_URL）`;

async function call(method, url, body, headers = {}) {
  const init = { method, headers: { ...headers } };
  if (body !== undefined) {
    if (Buffer.isBuffer(body)) init.body = body;
    else { init.body = JSON.stringify(body); init.headers['Content-Type'] = 'application/json'; }
  }
  let r;
  try { r = await fetch(BASE + url, init); } catch {
    throw new Error(`エディターに接続できません（${BASE}）。先に node server.mjs を起動してください`);
  }
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json() : Buffer.from(await r.arrayBuffer());
  if (!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
  return data;
}

function parseValue(v) {
  if (v === undefined) return true;
  try { return JSON.parse(v); } catch { return v; }
}

function parseKV(list) {
  const out = [];
  for (const kv of list) {
    const i = kv.indexOf('=');
    if (i <= 0) throw new Error(`k=v 形式ではありません: ${kv}`);
    out.push([kv.slice(0, i), parseValue(kv.slice(i + 1))]);
  }
  return out;
}

function nest(pairs) {
  const o = {};
  for (const [k, v] of pairs) {
    const keys = k.split('.');
    let t = o;
    for (const key of keys.slice(0, -1)) t = t[key] ??= {};
    t[keys[keys.length - 1]] = v;
  }
  return o;
}

async function ops(operations, label) {
  const snap = await call('GET', '/api/project');
  const r = await call('POST', '/api/ops', { revision: snap.revision, operations, label, source: 'cli' });
  if (flags.json) console.log(JSON.stringify({ revision: r.revision }));
  else console.log(`OK r${r.revision}`);
  return r;
}

const out = (v) => console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));

async function main() {
  const [cmd, ...rest] = args;
  switch (cmd) {
    case undefined: case 'help': case '-h': return out(HELP);
    case 'status': {
      const s = await call('GET', '/api/project');
      if (flags.json) return out({ revision: s.revision, screens: s.project.screens.map((x) => ({ id: x.id, title: x.title, width: x.width, height: x.height, elements: x.elements.length })) });
      out(`revision ${s.revision} / namespace ${s.project.namespace} / テクスチャ ${s.project.textureScale}倍 / 素材 ${Object.keys(s.project.assets).length}`);
      for (const x of s.project.screens) out(`  ${x.id.padEnd(20)} ${String(x.width).padStart(4)}×${String(x.height).padEnd(4)} 要素${String(x.elements.length).padStart(3)}  ${x.title}`);
      return;
    }
    case 'schema': return out(await call('GET', '/api/schema'));
    case 'get': {
      const s = await call('GET', '/api/project');
      if (!rest[0]) return out(s);
      const sc = s.project.screens.find((x) => x.id === rest[0]);
      if (!sc) throw new Error(`画面 ${rest[0]} がありません`);
      if (!rest[1]) return out(sc);
      const el = sc.elements.find((e) => e.id === rest[1]);
      if (!el) throw new Error(`要素 ${rest[1]} がありません`);
      return out(el);
    }
    case 'ls': {
      const s = await call('GET', '/api/project');
      const sc = s.project.screens.find((x) => x.id === rest[0]);
      if (!sc) throw new Error(`画面 ${rest[0]} がありません`);
      if (flags.json) return out(sc.elements.map((e) => ({ id: e.id, type: e.type, x: e.x, y: e.y, w: e.w, h: e.h })));
      out(`${sc.id} ${sc.width}×${sc.height} 「${sc.title}」 footer=${sc.footer.type}[${sc.footer.buttons.map((b) => b.label).join(', ')}]`);
      sc.elements.forEach((e, i) => {
        const extra = e.type === 'text' ? e.text : e.type === 'button' ? `${e.label} → ${e.action.type}:${e.action.value}` : e.type === 'hotspot' ? `${e.action.type}:${e.action.value}` : e.type === 'image' ? e.asset : e.fill;
        out(`  ${String(i).padStart(2)} ${e.type.padEnd(7)} ${e.id.padEnd(18)} (${e.x},${e.y}) ${e.w}×${e.h}${e.visible ? '' : ' [非表示]'}  ${String(extra).replace(/\n/g, '⏎').slice(0, 60)}`);
      });
      return;
    }
    case 'history': return out(await call('GET', '/api/history'));
    case 'fonts': {
      const f = await call('GET', '/api/fonts');
      const v = await call('GET', '/api/vanilla/status');
      if (flags.json) return out({ custom: f.custom, vanilla: v });
      out(v.available ? `ゲーム内フォント: OK（${v.source.version ?? '?'}、${v.glyphs} 文字）` : `ゲーム内フォント: 使えません — ${v.error}`);
      for (const c of f.custom) out(`  ${c.family}  ${c.chars ?? ''}  ${c.dotSize ? `${c.dotSize}px=1ドット` : ''}  ${c.license ?? ''}`);
      return;
    }
    case 'compile': {
      if (!rest[0]) throw new Error('画面IDを指定してください');
      const r = await call('GET', `/api/compile/${rest[0]}${flags.full ? '?full=1' : ''}`);
      if (flags.json || flags.full) return out(r);
      out(`r${r.revision} タイル${r.stats.tiles} 行${r.stats.lines} 本文幅${r.stats.bodyWidth} 部品${r.stats.components} JSON ${r.stats.jsonBytes}B クリック区間${r.clicks.length}`);
      for (const e of r.errors) out(`エラー: ${e}`);
      for (const w of r.warnings) out(`注意: ${w}`);
      if (!r.errors.length) out('検証OK');
      return;
    }
    case 'audit': {
      const snap = await call('GET', '/api/project');
      const ids = rest[0] ? [rest[0]] : snap.project.screens.map((s) => s.id);
      const all = [];
      for (const id of ids) all.push(...(await call('GET', `/api/compile/${id}`)).audit);
      if (flags.json) return out(all);
      for (const i of all) out(`${i.screen} / ${i.element}: ${i.message}`);
      out(all.length ? `${all.length} 件` : '見た目の点検: 問題なし');
      return;
    }
    case 'preview': {
      if (!rest[0]) throw new Error('画面IDを指定してください');
      const q = new URLSearchParams({ mode: flags.mode || 'game', zoom: String(flags.zoom || 2), clicks: flags.clicks ? '1' : '0' });
      if (flags.vw) q.set('vw', String(flags.vw));
      if (flags.vh) q.set('vh', String(flags.vh));
      const png = await call('GET', `/api/preview/${rest[0]}.png?${q}`);
      const file = path.resolve(flags.out || `preview-${rest[0]}.png`);
      fs.writeFileSync(file, png);
      return out(flags.json ? { file } : `保存: ${file}`);
    }
    case 'add-screen': {
      if (!rest[0]) throw new Error('画面IDを指定してください');
      return ops([{ op: 'add_screen', screen: { id: rest[0], ...nest(parseKV(rest.slice(1))) } }], `画面追加 ${rest[0]}`);
    }
    case 'rm-screen': return ops([{ op: 'remove_screen', screen: rest[0] }], `画面削除 ${rest[0]}`);
    case 'dup-screen': return ops([{ op: 'duplicate_screen', screen: rest[0], newId: rest[1] }], `画面複製 ${rest[0]}`);
    case 'add': {
      const [screen, type, ...kv] = rest;
      if (!screen || !type) throw new Error('add <画面> <type> [k=v ...]');
      return ops([{ op: 'add_element', screen, element: { type, ...nest(parseKV(kv)) } }], `追加 ${type}`);
    }
    case 'set': {
      const [screen, element, ...kv] = rest;
      if (!screen || !element || !kv.length) throw new Error('set <画面|-> <要素|-> k=v ...');
      const base = {};
      if (screen !== '-') base.screen = screen;
      if (element !== '-') base.element = element;
      return ops(parseKV(kv).map(([k, v]) => ({ op: 'set', ...base, path: k, value: v })), `CLI set ${kv.join(' ')}`.slice(0, 80));
    }
    case 'rm': return ops([{ op: 'remove_element', screen: rest[0], element: rest[1] }], `削除 ${rest[1]}`);
    case 'dup': return ops([{ op: 'duplicate_element', screen: rest[0], element: rest[1], newId: rest[2] }], `複製 ${rest[1]}`);
    case 'mv': return ops([{ op: 'move_element', screen: rest[0], element: rest[1], index: Number(rest[2]) }], `順序 ${rest[1]}`);
    case 'upload': {
      const file = rest[0];
      if (!file) throw new Error('upload <画像ファイル>');
      const r = await call('POST', '/api/assets', fs.readFileSync(file), { 'X-Filename': encodeURIComponent(path.basename(file)), 'X-Source': 'cli' });
      return out(flags.json ? r.asset : `素材 ${r.asset.id} (${r.asset.width}×${r.asset.height}) r${r.revision}`);
    }
    case 'batch': {
      const src = rest[0] === '-' || !rest[0] ? fs.readFileSync(0, 'utf8') : fs.readFileSync(rest[0], 'utf8');
      const list = JSON.parse(src);
      return ops(Array.isArray(list) ? list : list.operations, flags.label || `batch ${(Array.isArray(list) ? list : list.operations).length}件`);
    }
    case 'undo': case 'redo': {
      const r = await call('POST', `/api/${cmd}`, { source: 'cli' });
      return out(flags.json ? { revision: r.revision } : `OK r${r.revision}`);
    }
    case 'theme': {
      const [sub, ...targs] = rest;
      const snap = await call('GET', '/api/project');
      const theme = snap.project.theme || { name: 'default', tokens: {} };
      if (!sub) {
        if (flags.json) return out(theme);
        out(`テーマ「${theme.name}」 ${Object.keys(theme.tokens).length} 色`);
        for (const [k, v] of Object.entries(theme.tokens).sort()) out(`  ${k.padEnd(28)} ${v}`);
        return;
      }
      if (sub === 'set') return ops(parseKV(targs).map(([key, value]) => ({ op: 'set_theme_token', key, value: String(value) })), `テーマ ${targs.join(' ')}`.slice(0, 80));
      if (sub === 'rename') return ops([{ op: 'rename_theme_token', key: targs[0], to: targs[1] }], `色の名前 ${targs[0]}→${targs[1]}`);
      if (sub === 'rm') return ops([{ op: 'remove_theme_token', key: targs[0] }], `テーマ削除 ${targs[0]}`);
      if (sub === 'export') {
        const json = JSON.stringify(theme, null, 2);
        if (flags.out) { fs.writeFileSync(path.resolve(flags.out), json); return out(`保存: ${path.resolve(flags.out)}`); }
        return out(json);
      }
      if (sub === 'import') {
        const theme2 = JSON.parse(fs.readFileSync(targs[0], 'utf8'));
        return ops([{ op: 'set_theme', theme: theme2, merge: !flags.replace }], `テーマ読み込み ${theme2.name ?? ''}`);
      }
      throw new Error(`theme ${sub} は未知のサブコマンドです`);
    }
    case 'export': {
      const snap = await call('GET', '/api/project');
      const r = await call('POST', '/api/export', { revision: snap.revision });
      if (flags.json) return out(r);
      out(`書き出し: ${r.dir}`);
      out(`  resourcepack.zip sha1=${r.manifest.resourcePack.sha1} (${r.manifest.resourcePack.bytes} bytes)`);
      out(`  datapack.zip     sha1=${r.manifest.dataPack.sha1}`);
      for (const e of r.report.errors) out(`エラー: ${e}`);
      for (const w of r.report.warnings) out(`注意: ${w}`);
      return;
    }
    default:
      throw new Error(`未知のコマンド: ${cmd}\n\n${HELP}`);
  }
}

main().catch((e) => { console.error(e.message); process.exit(1); });
