// scripts/figma/build-frames.js
// 用途:作为 mcp__figma__use_figma 的 code,在 "PoLang UI Spec" 文件的 Camera 页
//      重建相机页各状态帧(idle/各面板/focusing/capturing)。幂等(同名帧先删后建)。
// 数据源:specs/screens/refs/android/camera-<state>.txt(a11y 树 bounds ×0.3125)+ 真机截图;
//        色全绑 PoLang Tokens 变量(单 Dark mode)。
// 注:camera/panel_beauty_face 由更早的脚本(首帧验证)单独构建,本脚本建其余 9 帧。
// 保真度:结构+布局+token 配色对齐;图标用文字占位、相机预览区留空(overlay 帧),后续精修。
// 坑:fills 即使用 boundVariables 也必须带 color:{r,g,b} 占位;outline 用 fills=[]+strokes。
const vars = await figma.variables.getLocalVariablesAsync();
const V = (n) => { const v = vars.find(x => x.name === n); if (!v) throw Error('no var ' + n); return { type: 'VARIABLE_ALIAS', id: v.id }; };
const B = (fv) => ({ type: 'SOLID', color: { r: 0, g: 0, b: 0 }, boundVariables: { color: V(fv) } });
const K = (a) => ({ type: 'SOLID', color: { r: 0, g: 0, b: 0 }, opacity: a != null ? a : 1 });
const W = (a) => ({ type: 'SOLID', color: { r: 1, g: 1, b: 1 }, opacity: a != null ? a : 1 });
await figma.loadFontAsync({ family: 'Inter', style: 'Regular' });
await figma.loadFontAsync({ family: 'Inter', style: 'Semi Bold' });
let page = figma.root.children.find(p => p.name === 'Camera'); if (!page) { page = figma.createPage(); page.name = 'Camera'; }
await figma.setCurrentPageAsync(page);
function newFrame(name) { const ex = page.findOne(n => n.type === 'FRAME' && n.name === name); if (ex) ex.remove(); const f = figma.createFrame(); f.name = name; f.resize(375, 834); f.fills = [B('color/panelBackground')]; f.clipsContent = true; page.appendChild(f); return f; }
function R(p, x, y, w, h, fill, rad) { const r = figma.createRectangle(); r.resize(w, h); r.x = x; r.y = y; r.fills = [fill]; if (rad) r.cornerRadius = rad; p.appendChild(r); return r; }
function E(p, x, y, d, fill) { const e = figma.createEllipse(); e.resize(d, d); e.x = x; e.y = y; e.fills = [fill]; p.appendChild(e); return e; }
function T(p, x, y, s, str, fv, w) { const t = figma.createText(); t.fontName = { family: 'Inter', style: w || 'Regular' }; t.fontSize = s; t.characters = str; t.x = x; t.y = y; t.fills = fv ? [B(fv)] : [W(1)]; p.appendChild(t); return t; }
const COLS = [['美颜', 60], ['比例', 138], ['辅助线', 197], ['场景', 274], ['滤镜', 333], ['专业', 410]];
function chrome(f, active) { R(f, 62, 12, 251, 36, K(0.5), 18); T(f, 78, 22, 11, 'Beauty: ACTIVE  30fps', 'scheme/onSurface'); COLS.forEach(([lbl, y], i) => { const act = i === active; R(f, 310, y, 49, 49, act ? B('scheme/primary') : K(0.5), 14); T(f, 310, y + 56, 9, lbl, act ? 'scheme/primary' : 'scheme/onSurface', 'Semi Bold'); }); }
function panelShell(f, top, h) { R(f, 0, top, 375, h, B('scheme/surface'), 24); R(f, 169.5, top + 8, 36, 4, W(0.3), 2); }
function sliderRow(f, y, lbl, val, pct) { T(f, 24, y, 13, lbl, 'scheme/onSurface'); T(f, 300, y, 13, val, 'scheme/onSurface', 'Semi Bold'); R(f, 24, y + 28, 327, 6, W(0.12), 3); const fw = 327 * pct; if (fw > 2) R(f, 24, y + 28, fw, 6, B('scheme/primary'), 3); E(f, 24 + fw - 9, y + 25, 18, W(1)); }
function chips(f, y, opts, sel) { let x = 24; opts.forEach((o, i) => { const act = i === sel; const w = 12 * String(o).length + 24; R(f, x, y, w, 34, act ? B('scheme/primary') : K(0.4), 17); T(f, x + 12, y + 9, 11, String(o), act ? 'scheme/onPrimary' : 'scheme/onSurface'); x += w + 8; }); }

{ const f = newFrame('camera/idle'); chrome(f, -1);
  let zx = 96; ['0.6x', '1x', '2x', '3.2x'].forEach((z, i) => { R(f, zx, 609, 40, 30, i === 1 ? W(0.85) : K(0.4), 15); T(f, zx + 8, 617, 10, z, 'scheme/onSurface'); zx += 48; });
  let mx = 112; [['视频', false], ['照片', true], ['文档', false]].forEach(([m, a]) => { T(f, mx, 685, 13, m, 'scheme/onSurface', a ? 'Semi Bold' : 'Regular'); mx += 52; });
  E(f, 155, 743, 65, W(0.9)); R(f, 41, 751, 48, 48, K(0.5), 8); R(f, 286, 751, 48, 48, K(0.5), 14); R(f, 306, 599, 53, 51, K(0.5), 12); T(f, 314, 616, 8, 'AI', 'scheme/onSurface'); }
{ const f = newFrame('camera/panel_beauty_makeup'); chrome(f, 0); panelShell(f, 500, 334);
  T(f, 24, 569, 13, '唇色', 'scheme/onSurface'); T(f, 320, 569, 13, '--', 'scheme/onSurface', 'Semi Bold');
  const cxx = [24, 81, 138, 195, 252, 309]; [605, 650].forEach(ry => cxx.forEach(cx => R(f, cx, ry, 50, 40, W(0.15), 6)));
  T(f, 24, 727, 12, '腮红色系', 'scheme/onSurfaceVariant'); let bx = 24; [['粉色', true], ['橙色', false], ['梅子色', false]].forEach(([c, a]) => { const w = 12 * c.length + 24; R(f, bx, 745, w, 32, a ? B('scheme/primary') : K(0.4), 16); T(f, bx + 12, 753, 11, c, a ? 'scheme/onPrimary' : 'scheme/onSurface'); bx += w + 8; });
  T(f, 60, 800, 12, '面部精修', 'scheme/onSurfaceVariant'); T(f, 230, 800, 12, '妆容调节', 'scheme/primary', 'Semi Bold'); R(f, 230, 822, 56, 2, B('scheme/primary'), 1); }
{ const f = newFrame('camera/panel_filter'); chrome(f, 4); panelShell(f, 470, 364);
  const cx = [37, 98, 159, 220, 281], ry = [498, 586, 674]; const names = [['原图', '徕卡经典', '徕卡生动', '徕卡黑白', '胶片金'], ['富士胶片', '复古', '冷色', '暖色', '卡通'], ['素描', '色块', '浮雕', '交叉线']];
  ry.forEach((y, r) => cx.forEach((x, c) => { if (r === 2 && c > 3) return; const sel = r === 0 && c === 0; R(f, x, y, 55, 54, W(0.2), 6); if (sel) R(f, x - 2, y - 2, 59, 58, B('scheme/primary'), 8); T(f, x, y + 58, 8, names[r][c], sel ? 'scheme/primary' : 'scheme/onSurface', 'Semi Bold'); })); }
{ const f = newFrame('camera/panel_ratio'); chrome(f, 1); panelShell(f, 600, 234); T(f, 24, 540, 13, '画面比例', 'scheme/onSurface'); chips(f, 640, ['4:3', '16:9', 'FULL'], 0); }
{ const f = newFrame('camera/panel_scene'); chrome(f, 3); panelShell(f, 600, 234); T(f, 24, 540, 13, '场景', 'scheme/onSurface'); chips(f, 640, ['无', '夜景', '月亮'], 0); }
{ const f = newFrame('camera/panel_grid'); chrome(f, 2); panelShell(f, 600, 234); T(f, 24, 540, 13, '辅助线', 'scheme/onSurface'); chips(f, 640, ['无', '三分', '黄金'], 0); }
{ const f = newFrame('camera/panel_pro'); chrome(f, 5); panelShell(f, 420, 414); T(f, 24, 450, 12, '白平衡', 'scheme/onSurface'); chips(f, 480, ['自动', '晴天', '多云', '白炽灯', '荧光灯'], 0); sliderRow(f, 549, '曝光', '+0', 0.5); sliderRow(f, 625, '对比度', '--', 0); sliderRow(f, 701, '饱和度', '--', 0); sliderRow(f, 777, '色温(K)', '--', 0); }
{ const f = newFrame('camera/focusing'); chrome(f, -1);
  const ring = figma.createRectangle(); ring.resize(75, 75); ring.x = 150; ring.y = 360; ring.cornerRadius = 20; ring.fills = []; ring.strokes = [B('color/focusRing')]; ring.strokeWeight = 3; f.appendChild(ring); T(f, 182, 386, 16, '+', 'color/focusRing'); }
{ const f = newFrame('camera/capturing'); chrome(f, -1); R(f, 0, 0, 375, 834, K(0.6)); }
return { ok: true, frames: page.children.filter(n => n.type === 'FRAME').map(n => n.name) };
