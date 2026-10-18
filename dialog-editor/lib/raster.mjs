// 画面の静的レイヤーを2D Canvasへ描く。ブラウザーのCanvasと@napi-rs/canvasで共通。
// 座標はGUI px。呼び出し側は (width*scale) x (height*scale) のキャンバスを用意する。
import { elementShown } from './states.mjs';

export function cssColor(hex) {
  if (!hex) return 'rgba(0,0,0,0)';
  const h = hex.slice(1);
  const r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), b = parseInt(h.slice(4, 6), 16);
  const a = h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1;
  return `rgba(${r},${g},${b},${a})`;
}

function alphaOf(hex) { return hex && hex.length === 9 ? parseInt(hex.slice(7, 9), 16) : 255; }

export function fontString(family, size, bold) {
  const fam = String(family || 'sans-serif').split(',').map((f) => {
    f = f.trim();
    return /^(serif|sans-serif|monospace)$/.test(f) ? f : `"${f.replace(/"/g, '')}"`;
  }).join(', ');
  return `${bold ? 'bold ' : ''}${size}px ${fam}, sans-serif`;
}

// 単語間隔（空白1つごとに追加するGUI px）を考慮した幅と描画
function measureLine(ctx, line, ws) {
  const w = ctx.measureText(line).width;
  return ws ? w + ws * (line.split(' ').length - 1) : w;
}
function fillLine(ctx, line, x, y, ws) {
  if (!ws) { ctx.fillText(line, x, y); return; }
  const space = ctx.measureText(' ').width + ws;
  for (const word of line.split(' ')) { ctx.fillText(word, x, y); x += ctx.measureText(word).width + space; }
}

// 1文字単位で折り返す（日本語向け）。英単語は可能なら単語境界で折る。
export function wrapText(ctx, text, maxWidth, ws = 0) {
  const out = [];
  for (const para of String(text).split('\n')) {
    if (!para) { out.push(''); continue; }
    let line = '';
    let lastBreak = -1;
    for (const ch of Array.from(para)) {
      const trial = line + ch;
      if (line && measureLine(ctx, trial, ws) > maxWidth) {
        if (lastBreak > 0 && /\S/.test(ch)) {
          out.push(line.slice(0, lastBreak).trimEnd());
          line = line.slice(lastBreak).trimStart() + ch;
        } else {
          out.push(line);
          line = ch === ' ' ? '' : ch;
        }
        lastBreak = -1;
      } else {
        line = trial;
      }
      if (ch === ' ') lastBreak = line.length;
    }
    out.push(line);
  }
  return out;
}

function drawText(ctx, t, box, env) {
  // t: {text, fontFamily, fontSize, bold, color, align, valign, lineHeight, shadow, shadowColor, pixel}
  if (!t.text || alphaOf(t.color) === 0) return;
  const pf = t.pixel ? env?.pixelFonts?.get(t.fontFamily) : null;
  if (pf) return drawPixelAtlasText(ctx, t, box, pf);
  if (t.pixel && env?.makeCanvas) return drawPixelText(ctx, t, box, env);
  ctx.save();
  ctx.beginPath();
  ctx.rect(box.x, box.y, box.w, box.h);
  ctx.clip();
  ctx.font = fontString(t.fontFamily, t.fontSize, t.bold);
  ctx.textBaseline = 'alphabetic';
  const ws = t.wordSpacing || 0;
  const lines = wrapText(ctx, t.text, box.w, ws);
  const lh = t.fontSize * (t.lineHeight || 1.2);
  const total = lines.length * lh;
  let y0 = box.y;
  if (t.valign === 'middle') y0 = box.y + (box.h - total) / 2;
  else if (t.valign === 'bottom') y0 = box.y + box.h - total;
  // ベースラインは行の上端から ascent 相当（fontSize*0.8 + 行間の半分）
  const base = (lh - t.fontSize) / 2 + t.fontSize * 0.86;
  const off = Math.max(1, Math.round(t.fontSize / 9));
  lines.forEach((line, i) => {
    const w = measureLine(ctx, line, ws);
    let x = box.x;
    if (t.align === 'center') x = box.x + (box.w - w) / 2;
    else if (t.align === 'right') x = box.x + box.w - w;
    const y = y0 + i * lh + base;
    if (t.shadow) {
      ctx.fillStyle = cssColor(t.shadowColor || '#3f3f3fff');
      fillLine(ctx, line, x + off, y + off, ws);
    }
    ctx.fillStyle = cssColor(t.color);
    fillLine(ctx, line, x, y, ws);
  });
  ctx.restore();
}

// 登録ドットフォント: サーバーで作ったドット単位のビットマップから描く（ブラウザーとNodeで同一）。
// 1ドット = fontSize / dotSize GUI px。大文字の上端を要素の上端（縦揃えに応じて）に合わせる。
function drawPixelAtlasText(ctx, t, box, pf) {
  const k = t.fontSize / pf.dotSize;
  const ws = t.wordSpacing || 0;
  const g = (ch) => pf.glyphs[ch];
  const adv = (ch) => (g(ch) ? g(ch)[0] * k : 0) + (ch === ' ' ? ws : 0);
  const lineW = (chars) => chars.reduce((a, ch) => a + adv(ch), 0);
  const lines = [];
  for (const para of String(t.text).split('\n')) {
    let cur = [], lastSpace = -1;
    for (const ch of Array.from(para)) {
      if (cur.length && lineW([...cur, ch]) > box.w && ch !== ' ') {
        if (lastSpace > 0) { lines.push(cur.slice(0, lastSpace)); cur = cur.slice(lastSpace + 1); } else { lines.push(cur); cur = []; }
        lastSpace = -1;
      }
      if (ch === ' ') lastSpace = cur.length;
      cur.push(ch);
    }
    lines.push(cur);
  }
  const lh = Math.round(t.fontSize * (t.lineHeight || 1.2));
  const capH = pf.ascent * k;
  const total = (lines.length - 1) * lh + capH;
  let y0 = box.y;
  if (t.valign === 'middle') y0 = box.y + Math.round((box.h - total) / 2);
  else if (t.valign === 'bottom') y0 = box.y + box.h - total;
  const off = Math.max(1, Math.round(k));
  ctx.save();
  ctx.beginPath(); ctx.rect(box.x, box.y, box.w, box.h); ctx.clip();
  const paint = (color, d) => {
    ctx.fillStyle = cssColor(color);
    lines.forEach((chars, i) => {
      const w = lineW(chars);
      let x = box.x;
      if (t.align === 'center') x = box.x + Math.round((box.w - w) / 2);
      else if (t.align === 'right') x = box.x + Math.round(box.w - w);
      x = Math.round(x);
      const baseline = Math.round(y0 + i * lh + capH);
      for (const ch of chars) {
        const gl = g(ch);
        if (gl) {
          const [, ox, oy, gw, gh, bits] = gl;
          for (let yy = 0; yy < gh; yy++) for (let xx = 0; xx < gw; xx++) {
            if (bits[yy * gw + xx] !== '1') continue;
            const px = x + (ox + xx) * k + d, py = baseline + (oy + yy) * k + d;
            ctx.fillRect(px, py, k, k);
            if (t.bold) ctx.fillRect(px + k, py, k, k);
          }
        }
        x += adv(ch) + (t.bold && gl ? k : 0);
      }
    });
  };
  if (t.shadow) paint(t.shadowColor || '#3f3f3fff', off);
  paint(t.color, 0);
  ctx.restore();
}

// ドットフォント用: テクスチャ解像度で描いてからアルファを2値化し、にじみを消す。
// 位置はGUI pxの整数、大文字の上端を行の上端に合わせる。
function drawPixelText(ctx, t, box, env) {
  const k = env.scale;
  const bx = Math.round(box.x), by = Math.round(box.y);
  const c = env.makeCanvas(Math.max(1, Math.ceil(box.w * k)), Math.max(1, Math.ceil(box.h * k)));
  const cx = c.getContext('2d');
  cx.scale(k, k);
  cx.font = fontString(t.fontFamily, t.fontSize, t.bold);
  cx.textBaseline = 'alphabetic';
  const ws = t.wordSpacing || 0;
  const lines = wrapText(cx, t.text, box.w, ws);
  const capTop = Math.round(cx.measureText('H').actualBoundingBoxAscent || t.fontSize * 0.7);
  const lh = Math.round(t.fontSize * (t.lineHeight || 1.2));
  const total = lines.length * lh;
  let y0 = 0;
  if (t.valign === 'middle') y0 = Math.round((box.h - total) / 2);
  else if (t.valign === 'bottom') y0 = box.h - total;
  const off = Math.max(1, Math.round(t.fontSize / 8));
  const draw = (color, dx) => lines.forEach((line, i) => {
    const w = measureLine(cx, line, ws);
    let x = 0;
    if (t.align === 'center') x = Math.round((box.w - w) / 2);
    else if (t.align === 'right') x = Math.round(box.w - w);
    cx.fillStyle = color;
    fillLine(cx, line, x + dx, y0 + i * lh + capTop + dx, ws);
  });
  if (t.shadow) draw('#000', off);
  draw('#fff', 0);
  // 2値化して色を付ける（影は黒、本体は白で描いたので区別できる）
  const img = cx.getImageData(0, 0, c.width, c.height);
  const [r, g, b] = rgbOf(t.color), [sr, sg, sb] = rgbOf(t.shadowColor || '#3f3f3fff');
  const ta = alphaOf(t.color), sa = alphaOf(t.shadowColor || '#3f3f3fff');
  const d = img.data;
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] < 128) { d[i + 3] = 0; continue; }
    const body = d[i] >= 128;
    d[i] = body ? r : sr; d[i + 1] = body ? g : sg; d[i + 2] = body ? b : sb; d[i + 3] = body ? ta : sa;
  }
  cx.putImageData(img, 0, 0);
  ctx.save();
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(c, bx, by, c.width / k, c.height / k);
  ctx.restore();
}

function rgbOf(hex) { const h = hex.slice(1); return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)]; }

function drawBox(ctx, e) {
  const bw = e.borderWidth | 0;
  if (alphaOf(e.border) && bw > 0) {
    ctx.fillStyle = cssColor(e.border);
    ctx.fillRect(e.x, e.y, e.w, e.h);
    if (alphaOf(e.fill) && e.w > bw * 2 && e.h > bw * 2) {
      // 枠の内側は塗り直す（半透明の塗りが枠色と混ざらないよう一度抜く）
      ctx.clearRect(e.x + bw, e.y + bw, e.w - bw * 2, e.h - bw * 2);
      ctx.fillStyle = cssColor(e.fill);
      ctx.fillRect(e.x + bw, e.y + bw, e.w - bw * 2, e.h - bw * 2);
    } else if (e.w > bw * 2 && e.h > bw * 2) {
      ctx.clearRect(e.x + bw, e.y + bw, e.w - bw * 2, e.h - bw * 2);
    }
  } else if (alphaOf(e.fill)) {
    ctx.fillStyle = cssColor(e.fill);
    ctx.fillRect(e.x, e.y, e.w, e.h);
  }
}

// clearRectは下のレイヤーまで消すので、要素ごとに別キャンバスへ描いて重ねる
function drawBoxLayer(ctx, e, makeCanvas, scale) {
  const c = makeCanvas(Math.max(1, e.w * scale), Math.max(1, e.h * scale));
  const cx = c.getContext('2d');
  cx.scale(scale, scale);
  drawBox(cx, { ...e, x: 0, y: 0 });
  ctx.drawImage(c, e.x, e.y, e.w, e.h);
}

function drawImageFit(ctx, img, e, fit, smooth, opacity = 1) {
  if (!img) return;
  const iw = img.width, ih = img.height;
  if (!iw || !ih) return;
  ctx.save();
  ctx.imageSmoothingEnabled = !!smooth;
  ctx.globalAlpha = opacity;
  ctx.beginPath();
  ctx.rect(e.x, e.y, e.w, e.h);
  ctx.clip();
  if (fit === 'contain' || fit === 'cover') {
    const k = fit === 'contain' ? Math.min(e.w / iw, e.h / ih) : Math.max(e.w / iw, e.h / ih);
    const dw = iw * k, dh = ih * k;
    ctx.drawImage(img, e.x + (e.w - dw) / 2, e.y + (e.h - dh) / 2, dw, dh);
  } else if (fit === 'nine' && e.nine) {
    drawNine(ctx, img, e, e.nine);
  } else if (fit === 'tile') {
    // 素材の1px = GUIの1px として敷き詰める
    for (let y = e.y; y < e.y + e.h; y += ih) for (let x = e.x; x < e.x + e.w; x += iw) ctx.drawImage(img, x, y, iw, ih);
  } else {
    ctx.drawImage(img, e.x, e.y, e.w, e.h);
  }
  ctx.restore();
}

/**
 * @param ctx 2Dコンテキスト（scale適用前）
 * @param screen 画面
 * @param opts { scale, images: Map<assetId, image>, makeCanvas(w,h), skipIds?: Set }
 */
// opts.include(e): 描く要素を選ぶ（省略時は状態の既定値 stateDefaults で見える要素）
// opts.noBackground: 画面の背景色を塗らない（状態の重ね絵用）
export function renderScreen(ctx, screen, opts) {
  const { scale = 1, images = new Map(), makeCanvas } = opts;
  const include = opts.include || ((e) => elementShown(e, screen.stateDefaults));
  ctx.save();
  ctx.scale(scale, scale);
  ctx.imageSmoothingEnabled = false;
  if (!opts.noBackground && alphaOf(screen.background)) {
    ctx.fillStyle = cssColor(screen.background);
    ctx.fillRect(0, 0, screen.width, screen.height);
  }
  for (const e of screen.elements) {
    if (!e.visible || opts.skipIds?.has(e.id) || !include(e)) continue;
    renderElement(ctx, e, images, makeCanvas, scale, opts.pixelFonts);
  }
  ctx.restore();
}

// 9分割: 四隅はそのままの大きさ、上下の辺は横だけ、左右の辺は縦だけ、真ん中は両方向に伸ばす（または並べる）。
// nine = { l, t, r, b（画像の画素）, ps（1画素の GUI px）, fill: stretch|tile }（model.mjs の nineOf）
function drawNine(ctx, img, e, nine) {
  const iw = img.width, ih = img.height;
  const { l, t, r, b, ps } = nine;
  // 要素が四隅より小さいときは、四隅を同じ比率で縮める
  let L = l * ps, R = r * ps, T = t * ps, B = b * ps;
  const kx = L + R > e.w ? e.w / (L + R) : 1, ky = T + B > e.h ? e.h / (T + B) : 1;
  L *= kx; R *= kx; T *= ky; B *= ky;
  const sx = [0, l, iw - r, iw], sy = [0, t, ih - b, ih];
  const dx = [e.x, e.x + L, e.x + e.w - R, e.x + e.w], dy = [e.y, e.y + T, e.y + e.h - B, e.y + e.h];
  for (let j = 0; j < 3; j++) for (let i = 0; i < 3; i++) {
    const sw = sx[i + 1] - sx[i], sh = sy[j + 1] - sy[j], dw = dx[i + 1] - dx[i], dh = dy[j + 1] - dy[j];
    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) continue;
    const stretchX = i !== 1 || nine.fill !== 'tile', stretchY = j !== 1 || nine.fill !== 'tile';
    if (stretchX && stretchY) { ctx.drawImage(img, sx[i], sy[j], sw, sh, dx[i], dy[j], dw, dh); continue; }
    // 並べる: 1枚の大きさは画素の大きさのまま（並べない方向は伸ばす）。はみ出しは切り取る
    const tw = stretchX ? dw : sw * ps, th = stretchY ? dh : sh * ps;
    ctx.save();
    ctx.beginPath(); ctx.rect(dx[i], dy[j], dw, dh); ctx.clip();
    for (let y = dy[j]; y < dy[j] + dh; y += th) for (let x = dx[i]; x < dx[i] + dw; x += tw) ctx.drawImage(img, sx[i], sy[j], sw, sh, x, y, tw, th);
    ctx.restore();
  }
}

export function renderElement(ctx, e, images, makeCanvas, scale, pixelFonts) {
  switch (e.type) {
    case 'rect':
      drawBoxLayer(ctx, e, makeCanvas, scale);
      break;
    case 'image':
      drawImageFit(ctx, images.get(e.asset), e, e.fit, e.smooth, e.opacity ?? 1);
      break;
    case 'text':
      if (e.render !== 'game') drawText(ctx, e, e, { makeCanvas, scale, pixelFonts });
      break;
    case 'button': {
      drawBoxLayer(ctx, e, makeCanvas, scale);
      if (e.image) drawImageFit(ctx, images.get(e.image), e, e.fit, false);
      if (e.labelRender === 'game') break; // ゲーム内フォントのラベルは本文の文字として出力する
      const pad = Math.max(1, e.borderWidth | 0);
      drawText(ctx, {
        text: e.label, fontFamily: e.fontFamily, fontSize: e.fontSize, bold: e.bold, color: e.labelColor, pixel: e.pixel, wordSpacing: e.wordSpacing,
        align: 'center', valign: 'middle', lineHeight: 1.15, shadow: e.shadow, shadowColor: '#3f3f3fff',
      }, { x: e.x + pad, y: e.y + pad, w: e.w - pad * 2, h: e.h - pad * 2 }, { makeCanvas, scale, pixelFonts });
      break;
    }
    default:
      break; // hotspotは描画しない
  }
}
