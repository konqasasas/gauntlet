#!/usr/bin/env node
// ゲーム内フォント（minecraft:default）の資源を data/vanilla/ に用意する。ローカル専用で、配布物には含めない。
//  - client.jar から: フォント定義（assets/minecraft/font/**）と英字等のフォント画像
//  - Mojang公式アセット配信から: Unifont（日本語など）。SHA-1 をアセット一覧と照合する
// 使い方: node tools/fetch-vanilla.mjs [--jar <client.jar>] [--version 26.3] [--download-jar]
//   --download-jar: client.jar が手元にない環境（CI・VPS）では、Mojang 公式の配布から取得して使う（SHA-1 を照合。保存はしない）
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { readZip } from '../lib/zipread.mjs';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const argv = process.argv.slice(2);
const opt = (k, d) => { const i = argv.indexOf(`--${k}`); return i >= 0 ? argv[i + 1] : d; };
const VERSION = opt('version', '26.3');
const DOWNLOAD_JAR = argv.includes('--download-jar');
const OUT = path.resolve(opt('out', process.env.DCG_DATA ? path.join(process.env.DCG_DATA, 'vanilla') : path.join(ROOT, 'data', 'vanilla')));
const JAR_CANDIDATES = [
  opt('jar'),
  process.env.DCG_MC_JAR,
  process.env.APPDATA && path.join(process.env.APPDATA, '.minecraft', 'versions', VERSION, `${VERSION}.jar`),
  // CurseForge の既定のインストール先
  process.env.USERPROFILE && path.join(process.env.USERPROFILE, 'curseforge', 'minecraft', 'Install', 'versions', VERSION, `${VERSION}.jar`),
].filter(Boolean);
const UNIFONT_OBJECTS = ['minecraft/font/include/unifont.json', 'minecraft/font/unifont.zip', 'minecraft/font/unifont_jp.zip'];
const JAR_TEXTURES = ['ascii.png', 'accented.png', 'nonlatin_european.png'];

const sha1 = (b) => crypto.createHash('sha1').update(b).digest('hex');

async function get(url, expectSha1) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  const b = Buffer.from(await r.arrayBuffer());
  if (expectSha1 && sha1(b) !== expectSha1) throw new Error(`${url}: SHA-1 が一致しません`);
  return b;
}

async function main() {
  fs.mkdirSync(path.join(OUT, 'jar'), { recursive: true });
  const manifest = JSON.parse(await get('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'));
  const v = manifest.versions.find((x) => x.id === VERSION);
  if (!v) throw new Error(`バージョン ${VERSION} が見つかりません`);
  const meta = JSON.parse(await get(v.url, v.sha1));

  let jarPath = JAR_CANDIDATES.find((p) => fs.existsSync(p));
  let jarBytes;
  if (jarPath) jarBytes = fs.readFileSync(jarPath);
  else if (DOWNLOAD_JAR) {
    const client = meta.downloads?.client;
    if (!client) throw new Error(`バージョン ${VERSION} に client.jar の配布がありません`);
    jarBytes = await get(client.url, client.sha1);
    jarPath = client.url;
  } else throw new Error(`client.jar が見つかりません。--jar で指定するか、--download-jar で取得してください（候補: ${JAR_CANDIDATES.join(', ')}）`);
  const jar = readZip(jarBytes);
  const version = JSON.parse(jar.get('version.json').read().toString('utf8'));
  if (version.id !== VERSION) console.warn(`注意: client.jar のバージョンは ${version.id} です（指定 ${VERSION}）`);
  let n = 0;
  for (const [name, e] of jar) {
    const m = name.match(/^assets\/minecraft\/(font\/.+\.json|textures\/font\/(.+\.png))$/);
    if (!m || (m[2] && !JAR_TEXTURES.includes(m[2]))) continue;
    const dest = path.join(OUT, 'jar', ...name.split('/').slice(2));
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, e.read());
    n++;
  }
  console.log(`client.jar (${version.id}) から ${n} ファイル: ${jarPath}`);

  const index = JSON.parse(await get(meta.assetIndex.url, meta.assetIndex.sha1));
  for (const key of UNIFONT_OBJECTS) {
    const o = index.objects[key];
    if (!o) throw new Error(`アセット一覧に ${key} がありません`);
    const file = path.join(OUT, 'assets', ...key.split('/').slice(1));
    if (fs.existsSync(file) && sha1(fs.readFileSync(file)) === o.hash) { console.log(`既存: ${key}`); continue; }
    const b = await get(`https://resources.download.minecraft.net/${o.hash.slice(0, 2)}/${o.hash}`, o.hash);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, b);
    console.log(`取得: ${key} (${b.length} bytes)`);
  }
  fs.writeFileSync(path.join(OUT, 'source.json'), JSON.stringify({ version: version.id, jar: jarPath, assetIndex: meta.assetIndex.id, fetchedAt: new Date().toISOString() }, null, 2));
  console.log(`完了: ${OUT}`);
}

main().catch((e) => { console.error(e.message); process.exit(1); });
