// scripts/figma/seed-variables.js
// 用途:作为 mcp__figma__use_figma 的 code 参数,把 design-tokens.json 的相机先导子集
//      播种为 Figma Variables。
// 范围:spacing/radius/icon/alpha/elevation + 相机相关组件(shutter/beautyPanel/camera/
//      appSlider/bottomSheet/bottomTab/chip/badge/topBar)+ color/statusColor/scheme。
// 约束(已实证):
//  1) Starter plan 限 1 mode/collection → 单 mode,名为 "Dark"。
//     相机 UI 是强制深色 overlay,Dark-only 即满足先导。未来浅色屏(设置等)需升级套餐
//     或改双 collection 策略。
//  2) Typography 不进 Variables(字阶=多值,Figma 用 Text Styles 承载)→ 留代码。
// 复用:若已存在同名 collection 则复用(幂等的前置:变量名固定,重复跑会报重名 → 先删后跑)。
let collections = await figma.variables.getLocalVariableCollectionsAsync();
let collection = collections.find(function(c){ return c.name === 'PoLang Tokens'; });
if (!collection) { collection = figma.variables.createVariableCollection('PoLang Tokens'); }
const modeId = collection.modes[0].modeId;
try { collection.renameMode(modeId, 'Dark'); } catch(e){}

function hex(h){ h=h.replace('#',''); return { a:parseInt(h.slice(0,2),16)/255, r:parseInt(h.slice(2,4),16)/255, g:parseInt(h.slice(4,6),16)/255, b:parseInt(h.slice(6,8),16)/255 }; }
function num(group, name, val){ const v=figma.variables.createVariable(group+'/'+name, collection.id, 'FLOAT'); v.setValueForMode(modeId, val); }
function color(name, hexVal){ const v=figma.variables.createVariable(name, collection.id, 'COLOR'); v.setValueForMode(modeId, hex(hexVal)); }

const spacing={xs:4,sm:8,md:12,lg:16,xl:24,xxl:32};
const radius={panel:24,card:12,button:10,small:8,thumbnail:2};
const icon={sm:18,md:22,lg:24,xl:32};
const alpha={scrimModal:0.7,scrim:0.55,scrimDeep:0.82,surfaceTranslucent:0.95,fieldBackground:0.7,primaryTint:0.12,primaryTintSoft:0.15,primaryTintMedium:0.2,primaryTintStrong:0.25,emphasisHigh:0.85,emphasis:0.8,secondary:0.6,hint:0.5,placeholder:0.4,faint:0.3,ghost:0.2};
const elevation={none:0,low:1,medium:2,high:4,floating:6,sheet:16};
const shutter={diameter:76,innerDiameter:58,recordingInnerDiameter:28,recordingInnerCornerRadius:4,ringWidth:4,pressDebounceMs:500,flashAlpha:0.6,flashFadeMs:80};
const beautyPanel={topCornerRadius:24,iconSize:24,sliderThumbSize:18,sliderTrackHeight:6,heightRatio:0.35,heightRatioMin:0.2,heightRatioMax:0.75};
const camera={focusRingDiameter:100,focusRingStrokeWidth:3,focusRingCornerRadius:20,focusRingCrossLength:16,controlButtonSize:48,controlButtonIconSize:24,controlButtonIdleAlpha:0.5,modeTabFontSize:13,modeTabUnselectedAlpha:0.6,voicePulseMs:1200,voicePulseAlphaFrom:0.3,voicePulseAlphaTo:1.0,filterSelectorColumns:5,filterSelectorHeight:280,filterSelectedScale:1.08,filterSelectedBorderWidth:2.5};
const appSlider={trackHeight:6,thumbSize:18,thumbPressedScale:1.15,thumbShadowElevation:2,thumbBorderWidth:2,inactiveTrackAlpha:0.12,animDurationMs:150};
const bottomSheet={topCornerRadius:24,shadowElevation:16,surfaceAlpha:0.95,borderWidth:0.5,borderColorAlpha:0.25,handleWidth:36,handleHeight:4,handleColorAlpha:0.2,contentPaddingH:24,contentPaddingV:8,contentSpacing:12};
const bottomTab={cornerRadius:28,tonalElevation:3,shadowElevation:6,containerPaddingH:12,containerPaddingV:8,itemPaddingH:16,itemPaddingVIconOnly:10,itemPaddingVWithLabel:4,iconSize:24,labelTopPadding:2};
const chip={height:36,smallHeight:32,unselectedContainerAlpha:0.5,selectedShadowElevation:2};
const badge={tagRadius:6,tagBackgroundAlpha:0.12,tagDotSize:6,tagPaddingH:8,tagPaddingV:3,tagDotLabelGap:4,miniRadius:4,requiredRadius:4};
const topBar={height:48,buttonSize:36,iconSize:22,titleFontSize:17,spacing:8,horizontalPadding:8};

for(const k in spacing) num('spacing',k,spacing[k]);
for(const k in radius) num('radius',k,radius[k]);
for(const k in icon) num('icon',k,icon[k]);
for(const k in alpha) num('alpha',k,alpha[k]);
for(const k in elevation) num('elevation',k,elevation[k]);
for(const k in shutter) num('shutter',k,shutter[k]);
for(const k in beautyPanel) num('beautyPanel',k,beautyPanel[k]);
for(const k in camera) num('camera',k,camera[k]);
for(const k in appSlider) num('appSlider',k,appSlider[k]);
for(const k in bottomSheet) num('bottomSheet',k,bottomSheet[k]);
for(const k in bottomTab) num('bottomTab',k,bottomTab[k]);
for(const k in chip) num('chip',k,chip[k]);
for(const k in badge) num('badge',k,badge[k]);
for(const k in topBar) num('topBar',k,topBar[k]);

const colors={focusRing:'#FF00E5FF',panelBackground:'#CC000000',shutterRing:'#FFFFFFFF',sliderThumb:'#FFFFFFFF',vibrantGreen:'#FF00E676',vibrantBlue:'#FF2979FF',vibrantOrange:'#FFFF9100',vibrantPink:'#FFFF4081'};
const status={success:'#FF4CAF50',warning:'#FFFF9800',warningAmber:'#FFFFA000',error:'#FFE53935',info:'#FF2196F3'};
for(const k in colors) color('color/'+k, colors[k]);
for(const k in status) color('statusColor/'+k, status[k]);

const schemeD={primary:'#FFD0BCFF',onPrimary:'#FF381E72',primaryContainer:'#FF4F378B',onPrimaryContainer:'#FFEADDFF',secondary:'#FFCCC2DC',onSecondary:'#FF332D41',secondaryContainer:'#FF4A4458',onSecondaryContainer:'#FFE8DEF8',tertiary:'#FFEFB8C8',onTertiary:'#FF492532',tertiaryContainer:'#FF633B48',onTertiaryContainer:'#FFFFD8E4',error:'#FFF2B8B5',onError:'#FF601410',errorContainer:'#FF8C1D18',onErrorContainer:'#FFF9DEDC',background:'#FF1C1B1F',onBackground:'#FFE6E1E5',surface:'#FF1C1B1F',onSurface:'#FFE6E1E5',surfaceVariant:'#FF49454F',onSurfaceVariant:'#FFCAC4D0',outline:'#FF938F99',outlineVariant:'#FF49454F',surfaceContainerLowest:'#FF0F0D13',surfaceContainerLow:'#FF1D1B20',surfaceContainer:'#FF211F26',surfaceContainerHigh:'#FF2B2930',surfaceContainerHighest:'#FF36343B'};
for(const r in schemeD) color('scheme/'+r, schemeD[r]);

const all = await figma.variables.getLocalVariablesAsync();
return { ok:true, collectionId:collection.id, modes:collection.modes.map(function(m){return m.name;}), varCount: all.length };
