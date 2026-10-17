// 初回起動時のサンプル。画像素材なしで、矩形・文字・ボタンだけで作る。
// 本文の文字はゲーム内フォント（9px行）、英語の見出しは 5x5 Pixel を焼き込み。
import { defaultProject, defaultScreen, defaultElement, defaultFooterButton } from './model.mjs';

const HEADING = { render: 'baked', fontFamily: '5x5 Pixel', fontSize: 16, pixel: true, wordSpacing: 6, color: '#f4e7b0ff', shadow: true, shadowColor: '#5a4a1aff', lineHeight: 1 };

export function sampleProject() {
  const p = defaultProject();
  const btn = (id, x, y, w, h, label, action, tooltip = '', extra = {}) => defaultElement('button', {
    id, x, y, w, h, label, action, tooltip, labelRender: 'game',
    fill: '#3d6b4fff', border: '#173323ff', borderWidth: 1, ...extra,
  });
  const text = (id, x, y, w, h, t, over = {}) => defaultElement('text', { id, x, y, w, h, text: t, render: 'game', ...over });
  const rect = (id, x, y, w, h, fill, over = {}) => defaultElement('rect', { id, x, y, w, h, fill, ...over });

  p.screens.push(defaultScreen('main_menu', {
    title: 'メインメニュー',
    width: 252,
    height: 162,
    background: '#1b1f27f0',
    footer: { type: 'notice', columns: 1, exitAction: false, buttons: [defaultFooterButton({ label: '閉じる', width: 120 })] },
    elements: [
      rect('frame', 0, 0, 252, 162, '#00000000', { border: '#596273ff', borderWidth: 1 }),
      rect('header_bg', 1, 1, 250, 26, '#2b3240ff'),
      text('heading', 9, 6, 234, 16, 'SERVER MENU', HEADING),
      text('lead', 9, 36, 234, 9, '行き先を選んでください。', { color: '#c9ced8ff' }),
      btn('to_shop', 9, 54, 234, 27, 'ショップ', { type: 'screen', value: 'shop', payload: '' }, 'アイテムを購入する画面へ'),
      btn('to_warp', 9, 90, 234, 27, 'ワープ', { type: 'custom', value: 'dcg:warp_menu', payload: '' }, 'プラグインの custom アクション dcg:warp_menu を送ります', { fill: '#3b5a7aff', border: '#14263aff' }),
      btn('close_btn', 9, 126, 234, 27, 'とじる', { type: 'close', value: '', payload: '' }, '本文内の閉じるボタン（プラグインが処理）', { fill: '#6b3d3dff', border: '#331717ff' }),
    ],
  }));

  const card = (i, name, price, color) => {
    const x = 9 + (i % 3) * 81, y = 36 + Math.floor(i / 3) * 63;
    return [
      rect(`card${i}_bg`, x, y, 72, 54, '#262c37ff', { border: '#4a5466ff', borderWidth: 1 }),
      rect(`card${i}_icon`, x + 26, y + 4, 20, 20, color, { border: '#00000080', borderWidth: 1 }),
      text(`card${i}_name`, x + 1, y + 27, 70, 9, name, { align: 'center', color: '#ffffffff' }),
      text(`card${i}_price`, x + 1, y + 36, 70, 9, `${price} G`, { align: 'center', color: '#f2c94cff' }),
      defaultElement('hotspot', { id: `card${i}_click`, x, y, w: 72, h: 54, tooltip: `${name} を購入（${price} G）`, action: { type: 'custom', value: 'dcg:buy', payload: JSON.stringify({ item: `item_${i}` }) } }),
    ];
  };
  p.screens.push(defaultScreen('shop', {
    title: 'ショップ',
    width: 252,
    height: 162,
    background: '#1b1f27f0',
    footer: {
      type: 'multi_action', columns: 2, exitAction: false,
      buttons: [
        defaultFooterButton({ label: 'メニューへ戻る', width: 120, action: { type: 'screen', value: 'main_menu', payload: '' } }),
        defaultFooterButton({ label: '閉じる', width: 120 }),
      ],
    },
    elements: [
      text('heading', 9, 11, 234, 16, 'SHOP', HEADING),
      ...card(0, '鉄の剣', 120, '#c0c6ccff'),
      ...card(1, '金のりんご', 300, '#f2c94cff'),
      ...card(2, 'エンダーパール', 80, '#2f8f83ff'),
      ...card(3, '弓', 90, '#9b6b3cff'),
      ...card(4, 'ポーション', 60, '#c04f7aff'),
      ...card(5, 'ダイヤ', 999, '#5fd3e6ff'),
    ],
  }));
  return p;
}
