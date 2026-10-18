import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { applyOps, validateProject, migrateProject, defaultProject, defaultScreen, defaultElement, clickRows, LINE, resolveScreen, groupMembers } from '../lib/model.mjs';
import { spaceString, glyphAdvance, spaceFontJson, tileGeometry } from '../lib/compile.mjs';
import { buildScreen, getAtlas } from '../lib/export.mjs';
import { auditScreen } from '../lib/audit.mjs';
import { sampleProject } from '../lib/sample.mjs';
import { selectStates, stateMatches } from '../lib/states.mjs';
import { encodePNG, decodeOwnPNG, makeZip } from '../lib/binary.mjs';

const ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const VANILLA = path.join(ROOT, 'data', 'vanilla');
const HAS_VANILLA = fs.existsSync(path.join(VANILLA, 'jar', 'font', 'include', 'default.json'));
const vanilla = HAS_VANILLA ? await getAtlas(VANILLA) : { atlas: null, font: null };

test('サンプルは検証に通る', () => {
  assert.deepEqual(validateProject(sampleProject()), []);
});

test('操作: 追加・変更・検証失敗時は元のまま', () => {
  const p = sampleProject();
  const next = applyOps(p, [
    { op: 'add_element', screen: 'main_menu', element: { type: 'hotspot', id: 'hs', x: 0, y: 0, w: 9, h: 9 } },
    { op: 'set', screen: 'main_menu', element: 'hs', path: 'action', value: { type: 'screen', value: 'shop', payload: '' } },
  ]);
  assert.equal(next.screens[0].elements.at(-1).id, 'hs');
  assert.equal(p.screens[0].elements.some((e) => e.id === 'hs'), false, '元のオブジェクトは変更しない');
  assert.throws(() => applyOps(p, [{ op: 'set', screen: 'main_menu', path: 'height', value: 10 }]), /9の倍数/);
  assert.throws(() => applyOps(p, [{ op: 'set', screen: 'main_menu', element: 'to_shop', path: 'action.value', value: 'nowhere' }]), /遷移先/);
  assert.throws(() => applyOps(p, [{ op: 'set', path: '__proto__.x', value: 1 }]), /不正なパス/);
});

test('画面のリネームで遷移先も追従する', () => {
  const next = applyOps(sampleProject(), [{ op: 'set', screen: 'shop', path: 'id', value: 'store' }]);
  assert.equal(next.screens[0].elements.find((e) => e.id === 'to_shop').action.value, 'store');
  assert.equal(next.screens[1].footer.buttons[0].action.value, 'main_menu');
});

test('空白文字列の合計advance', () => {
  const adv = Object.fromEntries(Object.entries(spaceFontJson().providers[0].advances));
  for (const n of [0, 1, -1, 7, 253, -253, 1024, -1023, 2047, 0.5, -0.5, 12.5, -100.5]) {
    const sum = Array.from(spaceString(n)).reduce((a, c) => a + adv[c], 0);
    assert.equal(sum, n);
  }
});

test('BitmapProviderと同じadvance計算', () => {
  assert.equal(glyphAdvance(240, 117, 234), 121); // 2倍: 240px → 120 + 1
  assert.equal(glyphAdvance(0, 9, 9), 1);
  assert.equal(glyphAdvance(5, 72, 216), 3); // 3倍: (int)(0.5 + 5*0.33333334) + 1 = 2 + 1
  assert.equal(glyphAdvance(7, 54, 216), 3); // 4倍: (int)(0.5 + 1.75) + 1
});

test('クリック行の量子化', () => {
  assert.deepEqual(clickRows(9, 18, 90), [1, 2]);
  assert.deepEqual(clickRows(13, 9, 90), [1, 2].filter((r) => (Math.min(r * 9 + 9, 22) - Math.max(r * 9, 13)) * 2 >= 9));
  assert.deepEqual(clickRows(0, 4, 90), []);
});

test('v1 からの移行: 文字は焼き込みのまま', () => {
  const v1 = { ...sampleProject(), schemaVersion: 1 };
  for (const s of v1.screens) for (const e of s.elements) { delete e.render; delete e.labelRender; delete e.pixel; delete e.wordSpacing; }
  const m = migrateProject(structuredClone(v1));
  assert.equal(m.schemaVersion, 4);
  assert.equal(m.screens[0].elements.find((e) => e.id === 'lead').render, 'baked');
  assert.deepEqual(validateProject(m), []);
});

test('ゲーム内フォントの字幅（26.3）', { skip: !HAS_VANILLA && 'data/vanilla なし' }, () => {
  const f = vanilla.font;
  const w = (t, bold) => Array.from(t).reduce((a, c) => a + f.advance(c.codePointAt(0), bold), 0);
  assert.equal(w('A'), 6); assert.equal(w('i'), 2); assert.equal(w('l'), 3); assert.equal(w(' '), 4);
  assert.equal(w('Hello'), 24);
  assert.equal(w('あ'), 9); assert.equal(w('漢字'), 18);
  assert.equal(w('A', true), 7); assert.equal(w('あ', true), 9.5);
});

test('サンプル画面: 行幅・画素・クリック領域がクライアント規則で一致', { skip: !HAS_VANILLA && 'data/vanilla なし' }, () => {
  const p = sampleProject();
  for (const s of p.screens) {
    const b = buildScreen(p, s, new Map(), vanilla);
    assert.deepEqual(b.errors, [], s.id);
    const body = b.compiled.dialog.body[0];
    assert.equal(body.width, s.width + 1 + 8); // 本文幅 + plain_message の左右の余白
    assert.equal(b.sim.lines.length, s.height / LINE);
  }
});

// ランダムな配置・倍率でも、再構成画像が完全一致し、各行の幅がぴったりになること
test('ファズ: ランダム配置', () => {
  let seed = 12345;
  const rnd = (n) => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed % n; };
  for (let iter = 0; iter < 80; iter++) {
    const p = defaultProject();
    p.textureScale = 1 + (iter % 4);
    const W = 40 + rnd(500), H = LINE * (2 + rnd(40));
    const s = defaultScreen('fuzz', { width: W, height: H, background: iter % 3 ? '#00000000' : '#202020c0' });
    for (let i = 0; i < 12; i++) {
      const type = ['rect', 'text', 'button', 'hotspot', 'gtext'][rnd(HAS_VANILLA ? 5 : 4)];
      if (type === 'gtext') {
        s.elements.push(defaultElement('text', {
          id: `e${i}`, render: 'game', x: rnd(W) - 10, y: LINE * rnd(H / LINE), w: 20 + rnd(W), h: LINE * (1 + rnd(3)),
          text: ['Hello World', 'ゲーム内フォントの文字 ABC', 'ﾊﾝｶｸ and 全角！', 'iiiii lllll WWW'][rnd(4)], bold: !!rnd(2), shadow: !!rnd(2), align: ['left', 'center', 'right'][rnd(3)],
        }));
        continue;
      }
      const w = 1 + rnd(W), h = 1 + rnd(H);
      s.elements.push(defaultElement(type, {
        id: `e${i}`, x: rnd(W) - 10, y: rnd(H) - 10, w, h,
        ...(type === 'rect' ? { fill: `#${rnd(0xffffff).toString(16).padStart(6, '0')}${['ff', '80', '00'][rnd(3)]}`, borderWidth: rnd(3), border: '#000000ff' } : {}),
        ...(type === 'text' ? { text: 'テスト ABC 123 長い文章の折り返し', fontSize: 6 + rnd(14) } : {}),
        ...(type === 'button' || type === 'hotspot' ? { tooltip: `tip ${i}`, action: { type: 'custom', value: 'test:a', payload: '' } } : {}),
      }));
    }
    p.screens.push(s);
    const b = buildScreen(p, s, new Map(), vanilla);
    assert.deepEqual(b.errors, [], `iter ${iter} scale ${p.textureScale} ${W}x${H}`);
    const { tw, th } = tileGeometry(p.textureScale);
    for (const t of b.compiled.tiles) assert.ok(t.sw <= 240 && t.sh <= 240 && t.h % LINE === 0 && t.w <= tw && t.h <= th);
  }
});

test('状態: 値ごとの重ね絵を目印付きで出し、選んでも行の幅が変わらない', () => {
  const p = defaultProject();
  const s = defaultScreen('st', { width: 120, height: 36, background: '#202020ff', stateDefaults: { mode: 'a' } });
  s.footer = { type: 'none', columns: 1, exitAction: false, buttons: [] };
  s.elements.push(defaultElement('rect', { id: 'base', x: 0, y: 0, w: 40, h: 9, fill: '#ff0000ff' }));
  s.elements.push(defaultElement('rect', { id: 'on_a', x: 50, y: 9, w: 20, h: 9, fill: '#00ff00ff', state: 'mode=a' }));
  s.elements.push(defaultElement('rect', { id: 'not_a', x: 50, y: 9, w: 20, h: 9, fill: '#0000ffff', state: 'mode!=a' }));
  s.elements.push(defaultElement('hotspot', { id: 'hs', x: 50, y: 9, w: 20, h: 9, tooltip: 't', action: { type: 'screen', value: 'st', payload: '' } }));
  p.screens.push(s);
  assert.deepEqual(validateProject(p), []);
  const b = buildScreen(p, s, new Map(), vanilla);
  assert.deepEqual(b.errors, []);
  const extra = b.compiled.dialog.body[0].contents.extra;
  assert.ok(extra.some((c) => c.insertion === 'dcg:state:mode=a') && extra.some((c) => c.insertion === 'dcg:state:mode!=a'));
  // 既定（a）では mode!=a の部品が消え、目印も残らない
  const shown = b.shown.body[0].contents.extra;
  assert.ok(!shown.some((c) => 'insertion' in c));
  assert.equal(shown.length, selectStates(b.compiled.dialog, 'dcg', { mode: 'a' }).body[0].contents.extra.length);
  assert.ok(stateMatches('mode!=a', { mode: 'b' }) && !stateMatches('mode=a', {}));
  // 画面の移動はプラグイン経由（dcg:open）
  const click = extra.find((c) => c.click_event)?.click_event;
  assert.deepEqual(click, { action: 'custom', id: 'dcg:open', payload: { screen: 'st' } });
  // 状態はクリック領域に付けられない
  const bad = structuredClone(p);
  bad.screens[0].elements[3].state = 'mode=a';
  assert.ok(validateProject(bad).some((e) => e.includes('state')));
});

test('payload の null は出力しない', () => {
  const p = defaultProject();
  const s = defaultScreen('n', { width: 60, height: 18 });
  s.elements.push(defaultElement('hotspot', { id: 'h', x: 0, y: 0, w: 20, h: 9, action: { type: 'custom', value: 'dcg:x', payload: '{"key":"k","value":null,"list":[1,null]}' } }));
  p.screens.push(s);
  const b = buildScreen(p, s, new Map(), vanilla);
  const click = b.compiled.dialog.body[0].contents.extra.find((c) => c.click_event).click_event;
  assert.deepEqual(click.payload, { key: 'k', list: [1] });
  assert.ok(b.warnings.some((w) => w.includes('null')));
});

test('グループ: まとめる・入れ子・解除・空のグループは消える・非表示は中も非表示', () => {
  let p = defaultProject();
  const s = defaultScreen('g', { width: 100, height: 36 });
  for (const id of ['a', 'b', 'c', 'd']) s.elements.push(defaultElement('rect', { id, x: 0, y: 0, w: 9, h: 9 }));
  p.screens.push(s);
  p = applyOps(p, [{ op: 'group_elements', screen: 'g', elements: ['a', 'c'], group: { id: 'g1', name: '上' } }]);
  // 中身は一番前面にあった c の位置へ連続して集まる
  assert.deepEqual(p.screens[0].elements.map((e) => e.id), ['b', 'a', 'c', 'd']);
  p = applyOps(p, [{ op: 'group_elements', screen: 'g', elements: ['d'], groups: ['g1'], group: { id: 'g2' } }]);
  assert.equal(p.screens[0].groups.find((g) => g.id === 'g1').parent, 'g2');
  assert.deepEqual(groupMembers(p.screens[0], 'g2').map((e) => e.id).sort(), ['a', 'c', 'd']);
  // 非表示のグループの中は描かれない
  const hidden = applyOps(p, [{ op: 'set_group', screen: 'g', group: 'g2', values: { visible: false } }]);
  assert.deepEqual(resolveScreen(hidden, hidden.screens[0]).elements.filter((e) => e.visible).map((e) => e.id), ['b']);
  // 解除すると中身は親へ
  p = applyOps(p, [{ op: 'ungroup', screen: 'g', group: 'g1' }]);
  assert.equal(p.screens[0].elements.find((e) => e.id === 'a').group, 'g2');
  // 中身を全部消すとグループも消える
  p = applyOps(p, [{ op: 'remove_elements', screen: 'g', elements: ['a', 'c', 'd'] }]);
  assert.deepEqual(p.screens[0].groups, []);
  // 存在しないグループへの所属は検証エラー
  assert.throws(() => applyOps(p, [{ op: 'patch_elements', screen: 'g', patches: [{ element: 'b', values: { group: 'none' } }] }]), /グループ none/);
});

test('まとめての操作: 追加（グループ付き）・移動・重なり順', () => {
  let p = defaultProject();
  const s = defaultScreen('m', { width: 100, height: 36 });
  for (const id of ['a', 'b', 'c']) s.elements.push(defaultElement('rect', { id, x: 0, y: 0, w: 9, h: 9 }));
  p.screens.push(s);
  p = applyOps(p, [{ op: 'add_elements', screen: 'm', groups: [{ id: 'pg', name: '貼り付け' }], elements: [{ type: 'rect', id: 'x', group: 'pg' }, { type: 'text', id: 'y', group: 'pg', render: 'game', y: 9, h: 9 }], index: 1 }]);
  assert.deepEqual(p.screens[0].elements.map((e) => e.id), ['a', 'x', 'y', 'b', 'c']);
  assert.throws(() => applyOps(p, [{ op: 'add_elements', screen: 'm', elements: [{ type: 'rect', id: 'a' }] }]), /使用済み/);
  p = applyOps(p, [{ op: 'patch_elements', screen: 'm', patches: [{ element: 'a', values: { x: 5 } }, { element: 'b', values: { x: 7, y: 2 } }] }]);
  assert.deepEqual(p.screens[0].elements.filter((e) => e.id === 'a' || e.id === 'b').map((e) => [e.x, e.y]), [[5, 0], [7, 2]]);
  p = applyOps(p, [{ op: 'move_elements', screen: 'm', elements: ['a', 'b'], index: 3 }]);
  assert.deepEqual(p.screens[0].elements.map((e) => e.id), ['x', 'y', 'c', 'a', 'b']);
  // 要素付きで画面を追加できる（以前は自分自身と重複扱いになっていた）
  p = applyOps(p, [{ op: 'add_screen', screen: { id: 'n2', elements: [{ type: 'rect', id: 'r1' }, { type: 'rect', id: 'r2' }] } }]);
  assert.deepEqual(p.screens.find((x) => x.id === 'n2').elements.map((e) => e.id), ['r1', 'r2']);
});

test('文字スタイル: 適用・名前の変更・使用中の削除', () => {
  let p = defaultProject();
  const s = defaultScreen('t', { width: 100, height: 36 });
  s.elements.push(defaultElement('text', { id: 't1', render: 'baked', y: 4 }));
  s.elements.push(defaultElement('button', { id: 'b1' }));
  p.screens.push(s);
  p = applyOps(p, [
    { op: 'set_theme_token', key: 'head', value: '#f4e7b0ff' },
    { op: 'set_text_style', key: 'heading', style: { name: '見出し', render: 'baked', fontSize: 16, bold: true, color: '@head' } },
    { op: 'patch_elements', screen: 't', patches: [{ element: 't1', values: { textStyle: 'heading' } }, { element: 'b1', values: { textStyle: 'heading' } }] },
  ]);
  const r = resolveScreen(p, p.screens[0]);
  assert.equal(r.elements[0].fontSize, 16);
  assert.equal(r.elements[0].color, '#f4e7b0ff');
  assert.equal(r.elements[1].labelColor, '#f4e7b0ff');
  assert.equal(r.elements[1].labelRender, 'baked');
  // スタイルが game なら、y が9の倍数でない文字は検証エラー
  assert.throws(() => applyOps(p, [{ op: 'set_text_style', key: 'heading', style: { render: 'game' }, merge: true }]), /9の倍数/);
  p = applyOps(p, [{ op: 'rename_text_style', key: 'heading', to: 'title' }, { op: 'rename_theme_token', key: 'head', to: 'title.text' }]);
  assert.equal(p.screens[0].elements[0].textStyle, 'title');
  assert.equal(p.textStyles.title.color, '@title.text');
  assert.throws(() => applyOps(p, [{ op: 'remove_text_style', key: 'title' }]), /使用中/);
  p = applyOps(p, [{ op: 'remove_text_style', key: 'title', detach: true }]);
  assert.equal(p.screens[0].elements[0].textStyle, '');
  assert.equal(p.screens[0].elements[0].fontSize, 16);
});

test('v3 からの移行: グループと文字スタイルが足される', () => {
  const v3 = structuredClone(sampleProject());
  v3.schemaVersion = 3;
  delete v3.textStyles;
  for (const s of v3.screens) { delete s.groups; for (const e of s.elements) { delete e.group; delete e.textStyle; } }
  const m = migrateProject(v3);
  assert.equal(m.schemaVersion, 4);
  assert.deepEqual(validateProject(m), []);
});

test('フッターなし: dialog_list・exit_action なしで出力', () => {
  const p = sampleProject();
  const next = applyOps(p, [
    { op: 'set', screen: 'main_menu', path: 'footer.type', value: 'none' },
    { op: 'set', screen: 'main_menu', path: 'footer.buttons', value: [] },
  ]);
  const s = next.screens[0];
  const b = buildScreen(next, s, new Map(), vanilla);
  assert.equal(b.compiled.dialog.type, 'minecraft:dialog_list');
  assert.deepEqual(b.compiled.dialog.dialogs, []);
  assert.equal('exit_action' in b.compiled.dialog, false);
  assert.equal('action' in b.compiled.dialog || 'actions' in b.compiled.dialog, false);
  assert.throws(() => applyOps(next, [{ op: 'add_input', screen: 'main_menu', input: { type: 'text' } }]), /入力欄は使えません/);
  const closed = applyOps(next, [{ op: 'set', screen: 'main_menu', path: 'canCloseWithEscape', value: false }, { op: 'remove_element', screen: 'main_menu', element: 'close_btn' }]);
  assert.match(buildScreen(closed, closed.screens[0], new Map(), vanilla).errors.join(), /閉じる手段がありません/);
});

test('テーマ: 色の名前の参照・変更・削除の保護', () => {
  let p = applyOps(sampleProject(), [
    { op: 'set_theme_token', key: 'panel.bg', value: '#112233ff' },
    { op: 'set', screen: 'main_menu', element: 'header_bg', path: 'fill', value: '@panel.bg' },
  ]);
  const b = buildScreen(p, p.screens[0], new Map(), vanilla);
  assert.deepEqual(b.errors, []);
  const px = (r, x, y) => Array.from(r.data.slice((y * r.width + x) * 4, (y * r.width + x) * 4 + 4));
  assert.deepEqual(px(b.raster, 20, 10), [0x11, 0x22, 0x33, 255]);
  p = applyOps(p, [{ op: 'set_theme_token', key: 'panel.bg', value: '#445566ff' }]);
  assert.deepEqual(px(buildScreen(p, p.screens[0], new Map(), vanilla).raster, 20, 10), [0x44, 0x55, 0x66, 255]);
  assert.throws(() => applyOps(p, [{ op: 'remove_theme_token', key: 'panel.bg' }]), /使用中/);
  assert.throws(() => applyOps(p, [{ op: 'set', screen: 'main_menu', element: 'lead', path: 'color', value: '@nope' }]), /色が不正/);
  assert.equal(applyOps(p, [{ op: 'set_theme_token', key: 'panel.bg', value: '#000000ff', ifMissing: true }]).theme.tokens['panel.bg'], '#445566ff');
  const r = applyOps(p, [{ op: 'rename_theme_token', key: 'panel.bg', to: 'bg.panel' }]);
  assert.equal(r.screens[0].elements.find((e) => e.id === 'header_bg').fill, '@bg.panel');
});

test('中央寄せはインク（描かれる画素）で揃い、点検で検出できる', { skip: !HAS_VANILLA && 'data/vanilla なし' }, () => {
  const p = defaultProject();
  const s = defaultScreen('t', { width: 120, height: 36 });
  s.elements.push(defaultElement('rect', { id: 'box', x: 10, y: 7, w: 40, h: 13, fill: '#c6c6c6ff' }));
  s.elements.push(defaultElement('text', { id: 'lbl', render: 'game', x: 10, y: 9, w: 40, h: 9, text: 'OK', align: 'center' }));
  s.elements.push(defaultElement('rect', { id: 'box2', x: 60, y: 7, w: 40, h: 13, fill: '#c6c6c6ff' }));
  s.elements.push(defaultElement('text', { id: 'off', render: 'game', x: 60, y: 9, w: 30, h: 9, text: 'OK', align: 'center' }));
  p.screens.push(s);
  const issues = auditScreen(p, s, vanilla.atlas);
  assert.equal(issues.filter((i) => i.element === 'lbl').length, 0, JSON.stringify(issues));
  assert.ok(issues.some((i) => i.element === 'off' && i.kind === 'center-x'));
});

test('PNGとZIP', () => {
  const px = new Uint8ClampedArray([255, 0, 0, 255, 0, 255, 0, 128, 0, 0, 255, 0, 10, 20, 30, 40]);
  const back = decodeOwnPNG(encodePNG(px, 2, 2));
  assert.deepEqual([...back.data], [...px]);
  const z1 = makeZip([{ path: 'b.txt', data: 'b' }, { path: 'a.txt', data: 'aaaa' }]);
  const z2 = makeZip([{ path: 'a.txt', data: 'aaaa' }, { path: 'b.txt', data: 'b' }]);
  assert.ok(z1.equals(z2), '同じ内容なら同じZIP');
  assert.equal(z1.readUInt32LE(0), 0x04034b50);
});

test('API: 競合検出・Undo・書き出し・CLI', { skip: !HAS_VANILLA && 'data/vanilla なし' }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dcg-test-'));
  const port = 4400 + Math.floor(Math.random() * 500);
  const env = { ...process.env, DCG_PORT: String(port), DCG_DATA: path.join(dir, 'data'), DCG_EXPORTS: path.join(dir, 'exports'), DCG_VANILLA: VANILLA };
  const srv = spawn(process.execPath, [path.join(ROOT, 'server.mjs')], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    await new Promise((resolve, reject) => {
      srv.stdout.on('data', (d) => { if (String(d).includes('http://')) resolve(); });
      srv.on('exit', (c) => reject(new Error(`server exit ${c}`)));
      setTimeout(() => reject(new Error('timeout')), 10000);
    });
    const base = `http://127.0.0.1:${port}`;
    const j = (u, body) => fetch(base + u, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const snap = await (await j('/api/project')).json();
    const ok = await j('/api/ops', { revision: snap.revision, operations: [{ op: 'set', screen: 'main_menu', path: 'title', value: 'X' }] });
    assert.equal(ok.status, 200);
    const stale = await j('/api/ops', { revision: snap.revision, operations: [{ op: 'set', screen: 'main_menu', path: 'title', value: 'Y' }] });
    assert.equal(stale.status, 409);
    const bad = await j('/api/ops', { revision: snap.revision + 1, operations: [{ op: 'set', screen: 'main_menu', path: 'width', value: 5000 }] });
    assert.equal(bad.status, 400);
    const evil = await fetch(`${base}/api/undo`, { method: 'POST', headers: { 'Content-Type': 'application/json', Origin: 'https://evil.example' }, body: '{}' });
    assert.equal(evil.status, 403);
    const undone = await (await j('/api/undo', {})).json();
    assert.equal(undone.project.screens[0].title, 'メインメニュー');

    const cli = (...a) => new Promise((resolve) => {
      const c = spawn(process.execPath, [path.join(ROOT, 'cli.mjs'), ...a, '--url', base], { cwd: dir });
      let out = ''; c.stdout.on('data', (d) => { out += d; }); c.stderr.on('data', (d) => { out += d; });
      c.on('exit', (code) => resolve({ code, out }));
    });
    const add = await cli('add', 'shop', 'hotspot', 'id=cli_hs', 'x=0', 'y=0', 'w=18', 'h=9', 'action.type=screen', 'action.value=main_menu');
    assert.equal(add.code, 0, add.out);
    const comp = await cli('compile', 'shop');
    assert.match(comp.out, /検証OK/);
    const png = path.join(dir, 'p.png');
    const pv = await cli('preview', 'shop', '-o', png, '--mode', 'compiled');
    assert.equal(pv.code, 0, pv.out);
    assert.ok(fs.readFileSync(png).subarray(1, 4).toString() === 'PNG');
    const ex = await cli('export', '--json');
    const r = JSON.parse(ex.out);
    assert.deepEqual(r.report.errors, []);
    assert.ok(fs.existsSync(path.join(dir, 'exports', 'latest', 'resourcepack.zip')));
  } finally {
    srv.kill();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
