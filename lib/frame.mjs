// ゲーム画面上のDialogの配置を概算する（実際の描画はMinecraftクライアントが決める）。
// 26.3 の DialogScreen: ヘッダー/フッター付きレイアウト、本文はスクロール領域、本文要素間は10px。
import { cssColor, fontString } from './raster.mjs';

export const HEADER = 33;
export const FOOTER_ROW = 20;
export const BODY_GAP = 10;
export const PADDING = 4; // FocusableTextWidget の内側余白

export function frameLayout(screen, viewport) {
  const vw = viewport.width, vh = viewport.height;
  const f = screen.footer;
  const cols = f.type === 'notice' ? 1 : Math.max(1, f.columns);
  const rows = Math.ceil(f.buttons.length / cols);
  const footerH = f.type === 'none' ? 5 : Math.max(33, rows * (FOOTER_ROW + 2) + 12);
  const bodyTop = HEADER, bodyBottom = vh - footerH;
  const bodyW = screen.width + 1 + PADDING * 2;
  const bodyH = screen.height + PADDING * 2;
  const items = [{ kind: 'body', w: bodyW, h: bodyH }];
  for (const inp of screen.inputs) items.push({ kind: 'input', input: inp, w: inp.type === 'boolean' ? 200 : inp.width, h: inp.type === 'text' && inp.multiline ? 9 * (inp.maxLines ?? 4) + 8 : 20 });
  const contentH = items.reduce((a, it) => a + it.h, 0) + BODY_GAP * (items.length - 1);
  const viewH = bodyBottom - bodyTop;
  let y = bodyTop + Math.max(0, (viewH - contentH) / 2);
  const placed = items.map((it) => {
    const r = { ...it, x: Math.round((vw - it.w) / 2), y: Math.round(y) };
    y += it.h + BODY_GAP;
    return r;
  });
  const body = placed[0];
  const buttons = [];
  for (let r = 0; r < (f.type === 'none' ? 0 : rows); r++) {
    const rowBtns = f.buttons.slice(r * cols, r * cols + cols);
    const rowW = rowBtns.reduce((a, b) => a + b.width, 0) + 8 * (rowBtns.length - 1);
    let x = Math.round((vw - rowW) / 2);
    const by = Math.round(bodyBottom + (footerH - rows * (FOOTER_ROW + 2)) / 2 + r * (FOOTER_ROW + 2));
    rowBtns.forEach((b, i) => { buttons.push({ x, y: by, w: b.width, h: FOOTER_ROW, button: b, index: r * cols + i }); x += b.width + 8; });
  }
  return {
    viewport: { width: vw, height: vh },
    title: { x: vw / 2, y: (HEADER - 9) / 2 },
    bodyView: { x: 0, y: bodyTop, w: vw, h: viewH },
    contentH,
    scrolls: contentH > viewH,
    // 本文画像の左上（中央寄せ: 本文幅 = width+1、行の合計も width+1 なので左端はウィジェット左端+余白）
    image: { x: body.x + PADDING, y: body.y + PADDING },
    inputs: placed.slice(1),
    buttons,
  };
}

// フレーム（背景・タイトル・フッター・入力欄）を描く。本文画像は呼び出し側が image 位置へ描く。
export function drawFrame(ctx, screen, layout, opts = {}) {
  const { width: vw, height: vh } = layout.viewport;
  ctx.save();
  ctx.fillStyle = opts.worldColor || '#4b6d3a';
  ctx.fillRect(0, 0, vw, vh);
  ctx.fillStyle = 'rgba(16,16,16,0.75)';
  ctx.fillRect(0, 0, vw, vh);
  // ヘッダー/フッターの区切り線
  ctx.fillStyle = 'rgba(0,0,0,0.35)';
  ctx.fillRect(0, 0, vw, HEADER);
  ctx.fillRect(0, layout.bodyView.y + layout.bodyView.h, vw, vh - layout.bodyView.y - layout.bodyView.h);
  ctx.fillStyle = 'rgba(255,255,255,0.25)';
  ctx.fillRect(0, HEADER - 1, vw, 1);
  ctx.fillRect(0, layout.bodyView.y + layout.bodyView.h, vw, 1);
  ctx.font = fontString(opts.uiFont || 'Yu Gothic UI', 8.5, false);
  ctx.textBaseline = 'top';
  ctx.textAlign = 'center';
  if (screen.showTitle && screen.title) {
    ctx.fillStyle = '#3f3f3f'; ctx.fillText(screen.title, layout.title.x + 1, layout.title.y + 1);
    ctx.fillStyle = '#ffffff'; ctx.fillText(screen.title, layout.title.x, layout.title.y);
  }
  for (const b of layout.buttons) drawVanillaButton(ctx, b, b.button.label, opts.highlight === b.index);
  for (const it of layout.inputs) drawInput(ctx, it);
  if (layout.scrolls) {
    ctx.fillStyle = '#000'; ctx.fillRect(vw - 10, layout.bodyView.y, 6, layout.bodyView.h);
    ctx.fillStyle = '#c0c0c0'; ctx.fillRect(vw - 10, layout.bodyView.y, 6, Math.max(16, layout.bodyView.h * layout.bodyView.h / layout.contentH));
  }
  ctx.restore();
}

function drawVanillaButton(ctx, r, label, hi) {
  ctx.fillStyle = hi ? '#ffffff' : '#000000';
  ctx.fillRect(r.x, r.y, r.w, r.h);
  ctx.fillStyle = hi ? '#8d8d8d' : '#6f6f6f';
  ctx.fillRect(r.x + 1, r.y + 1, r.w - 2, r.h - 2);
  ctx.fillStyle = 'rgba(255,255,255,0.25)';
  ctx.fillRect(r.x + 1, r.y + 1, r.w - 2, 1);
  ctx.fillStyle = 'rgba(0,0,0,0.25)';
  ctx.fillRect(r.x + 1, r.y + r.h - 2, r.w - 2, 1);
  ctx.save();
  ctx.beginPath(); ctx.rect(r.x + 2, r.y, r.w - 4, r.h); ctx.clip();
  ctx.fillStyle = '#3f3f3f'; ctx.fillText(label, r.x + r.w / 2 + 1, r.y + 6 + 1);
  ctx.fillStyle = '#ffffff'; ctx.fillText(label, r.x + r.w / 2, r.y + 6);
  ctx.restore();
}

function drawInput(ctx, it) {
  const inp = it.input;
  if (inp.type === 'text') {
    ctx.fillStyle = '#a0a0a0'; ctx.fillRect(it.x, it.y, it.w, it.h);
    ctx.fillStyle = '#000'; ctx.fillRect(it.x + 1, it.y + 1, it.w - 2, it.h - 2);
    ctx.textAlign = 'left';
    ctx.fillStyle = '#e0e0e0'; ctx.fillText(inp.initial || '', it.x + 4, it.y + 6);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#ffffff'; ctx.fillText(inp.label || '', it.x + it.w / 2, it.y - 10);
  } else if (inp.type === 'boolean') {
    ctx.fillStyle = '#000'; ctx.fillRect(it.x, it.y, 17, 17);
    ctx.fillStyle = '#6f6f6f'; ctx.fillRect(it.x + 1, it.y + 1, 15, 15);
    if (inp.initial) { ctx.fillStyle = '#ffffff'; ctx.fillText('✔', it.x + 8.5, it.y + 4); }
    ctx.textAlign = 'left'; ctx.fillStyle = '#e0e0e0'; ctx.fillText(inp.label || '', it.x + 21, it.y + 5); ctx.textAlign = 'center';
  } else {
    let label = inp.label || '';
    if (inp.type === 'single_option') label += ': ' + (inp.options.find((o) => o.id === inp.initial)?.display ?? inp.options[0]?.display ?? '');
    else label += ': ' + inp.initial;
    drawVanillaButton(ctx, it, label, false);
  }
}

export function drawHotspotOverlay(ctx, compiledClicks, origin, color = 'rgba(80,200,255,0.35)') {
  ctx.save();
  for (const c of compiledClicks) {
    ctx.fillStyle = color;
    ctx.fillRect(origin.x + c.x0, origin.y + c.row * 9, c.x1 - c.x0, 9);
  }
  ctx.restore();
}

export { cssColor };
