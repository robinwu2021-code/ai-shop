#!/usr/bin/env node
/**
 * 皮肤 CSS 生成器 —— **调颜色的唯一入口**。
 *
 * 改色流程：改 `src/design/tokens.ts` 的 SKIN_HEX → `npm run gen:skins` → `npm test`。
 *
 * 为什么要有这个脚本：同一份色值要落到 4 个地方 ——
 *   1. tokens.ts（JS 侧，皮肤选择器的预览色）
 *   2. packages/ui/src/styles/base.css（CSS 变量，两端共用的样式基座）
 *   3. 两端 pages.json（**原生 tabBar 的选中色不吃 CSS 变量**，只能写死）
 * 手抄这几处必然漂移，而漂移的症状很隐蔽：页面颜色变了，底部菜单没变。
 *
 * 前景色（--sh-on-primary）不是手填的，是**按对比度算出来的**：
 * 白字与墨字哪个对比高就用哪个。深色档主色偏亮，算出来一律是墨字。
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SHARED = join(HERE, "..");
const ROOT = join(SHARED, "../..");
const APPS = ["c-app", "b-app"];

const WHITE = "#FFFFFF";
const INK = "#14161A";
const LIGHT_BG = "#F4F5F7";
const DARK_BG = "#0C0E12";

// ---- 对比度（WCAG 2.1 相对亮度） ----
const channel = (c) => {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
};
const luminance = (hex) => {
  const h = hex.replace("#", "");
  const [r, g, b] = [0, 2, 4].map((i) => channel(parseInt(h.slice(i, i + 2), 16)));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
const rgba = (hex, alpha) => {
  const h = hex.replace("#", "");
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};
/** 前景色按对比度选，不手填 */
const onColor = (bg) => (contrast(bg, WHITE) >= contrast(bg, INK) ? WHITE : INK);

/** 朝黑（k<1）或朝白（k>1）挪一档，保色相 */
const shift = (hex, k) => {
  const h = hex.replace("#", "");
  let [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  if (k <= 1) [r, g, b] = [r * k, g * k, b * k];
  else {
    const t = k - 1;
    [r, g, b] = [r + (255 - r) * t, g + (255 - g) * t, b + (255 - b) * t];
  }
  return `#${[r, g, b].map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, "0")).join("")}`.toUpperCase();
};

/**
 * **主色当文字用时的那一档**。同样是算出来的，不手填 —— 与 `onColor` 一个道理。
 *
 * 主色是为「压白字的按钮底」调的；同一个色拿去当**文字**压在页面底上就不一定够。
 * 实测八套皮肤里有六套不达 AA（brand 4.00 / blue 3.92 / promo 3.86 / teal 3.85 …），
 * 而这件事**没有任何症状**：颜色看着是对的，只是弱视用户读不清。
 *
 * 先朝深里挪（浅底场景），挪到底还不够就朝白挪（深色模式）。
 * 目标 4.55 而不是 4.5：留一点余量，底色微调时不至于立刻掉下去。
 */
const textColor = (primary, bg) => {
  if (contrast(primary, bg) >= 4.5) return primary;
  for (let k = 1; k > 0.3; k -= 0.005) {
    const c = shift(primary, k);
    if (contrast(c, bg) >= 4.55) return c;
  }
  for (let k = 1; k < 2; k += 0.005) {
    const c = shift(primary, k);
    if (contrast(c, bg) >= 4.55) return c;
  }
  return primary;
};

// ---- 从 tokens.ts 读 SKIN_HEX 与 SURFACES（唯一事实源） ----
const SRC = readFileSync(join(SHARED, "src/design/tokens.ts"), "utf8");

function readSkins() {
  const block = SRC.match(/export const SKIN_HEX[^{]*\{([\s\S]*?)\n\};/);
  if (!block) throw new Error("tokens.ts 里找不到 SKIN_HEX");
  const skins = [];
  // 条目可能是单行也可能是多行（纯白底组带 ink 覆盖），按「id: { ... }」整块切
  const re = /(\w+):\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}/g;
  const field = (body, name) => body.match(new RegExp(`\\b${name}:\\s*"([^"]+)"`))?.[1];
  let m;
  while ((m = re.exec(block[1]))) {
    const body = m[2];
    const light = field(body, "light");
    const dark = field(body, "dark");
    if (!light || !dark) continue;
    const inkBlock = body.match(/ink:\s*\{([^}]*)\}/)?.[1];
    // 品牌书写死的「主色当文字」那一档。不填就由 textColor 算
    const deepBlock = body.match(/deep:\s*\{([^}]*)\}/)?.[1];
    skins.push({
      id: m[1],
      light,
      dark,
      tone: field(body, "tone"),
      group: field(body, "group"),
      brandLocked: /brandLocked:\s*true/.test(body),
      ink: inkBlock
        ? { light: field(inkBlock, "light"), dark: field(inkBlock, "dark") }
        : undefined,
      deep: deepBlock
        ? { light: field(deepBlock, "light"), dark: field(deepBlock, "dark") }
        : undefined,
    });
  }
  if (!skins.length) throw new Error("SKIN_HEX 解析不出任何皮肤");
  return skins;
}

/** 面感色表：皮肤不只是主色，背景与文字也要配套 */
function readSurfaces() {
  const block = SRC.match(/export const SURFACES[^{]*\{([\s\S]*?)\n\};/);
  if (!block) throw new Error("tokens.ts 里找不到 SURFACES");
  const tones = {};
  const toneRe = /(\w+):\s*\{\s*light:\s*\{([^}]*)\},\s*dark:\s*\{([^}]*)\},\s*\}/g;
  const parse = (body) =>
    Object.fromEntries(
      [...body.matchAll(/(\w+):\s*"(#[0-9A-Fa-f]{6})"/g)].map((x) => [x[1], x[2]]),
    );
  let m;
  while ((m = toneRe.exec(block[1]))) tones[m[1]] = { light: parse(m[2]), dark: parse(m[3]) };
  if (!Object.keys(tones).length) throw new Error("SURFACES 解析不出任何面感");
  return tones;
}

function buildCss(skins, surfaces) {
  const head = `/* ---- 配色方案：主色 + 前景 + 中性面（背景与文字）。按明暗分别取值 ----

   ⚠️ 本段由 packages/shared/scripts/gen-skins.mjs 生成，**不要手改**。
   改色：改 tokens.ts 的 SKIN_HEX → npm run gen:skins → npm test

   一套配色 = 主色 + 前景 + 中性面。只换主色不换面，暖色主色配冷灰底会「脏」。

   三条：
   · 前景色按对比度算，不手填 —— 微信绿配墨字 6.80，配白字只有 2.66
   · mono 的近黑放到深色底上对比 1.09，主按钮会糊进背景，故两端反着取
   · 中性面来自「面感」(pure/neutral/warm/cool)，**纯白底方案保留**给要「白纸一张」的场景

   取值全部由 packages/shared/tests/design-tokens.test.ts 的对比度断言守着。 */`;

  const vars = (primary, tint, sf, deep) =>
    [
      `  --sh-primary: ${primary};`,
      `  --sh-primary-tint: ${tint};`,
      // 主色当**文字**用的那一档。品牌书填了就用它，否则按对比度算（见 textColor）
      `  --sh-primary-text: ${deep ?? textColor(primary, sf.bg)};`,
      `  --sh-on-primary: ${onColor(primary)};`,
      `  --sh-bg: ${sf.bg};`,
      `  --sh-surface: ${sf.surface};`,
      `  --sh-elev: ${sf.elev};`,
      `  --sh-ink: ${sf.ink};`,
      `  --sh-sub: ${sf.sub};`,
      `  --sh-faint: ${sf.faint};`,
      `  --sh-line: ${sf.line};`,
    ].join("\n");

  const blocks = skins.flatMap((s) => {
    const base = surfaces[s.tone];
    if (!base) throw new Error(`皮肤 ${s.id} 的面感 ${s.tone} 在 SURFACES 里不存在`);
    // 纯白底组「只改字体颜色」：底沿用纯白面感，ink 用皮肤自带的那一份
    const sf = s.ink
      ? {
          light: { ...base.light, ink: s.ink.light },
          dark: { ...base.dark, ink: s.ink.dark },
        }
      : base;
    const head0 = s.id === skins[0].id ? ":root,\n" : "";
    return [
      `${head0}:root[data-skin="${s.id}"],\n:root[data-skin="${s.id}"][data-theme="light"],\n.sh-root.skin-${s.id}.mode-light {\n${vars(s.light, rgba(s.light, 0.12), sf.light, s.deep?.light)}\n}`,
      `:root[data-skin="${s.id}"][data-theme="dark"],\n.sh-root.skin-${s.id}.mode-dark {\n${vars(s.dark, rgba(s.dark, 0.2), sf.dark, s.deep?.dark)}\n}`,
    ];
  });
  return [head, ...blocks].join("\n");
}

/**
 * 皮肤段落写进**组件库的样式基座**（两端共用的那一份）。
 * 以前是往两个 App.vue 各写一遍 —— 副本多一份，漂移的机会就多一处。
 */
function writeBaseCss(css) {
  const cssPath = join(ROOT, "packages/ui/src/styles/base.css");
  const src = readFileSync(cssPath, "utf8");
  const start = src.indexOf("/* ---- 配色方案：");
  // 中性面已并入皮肤块，旧的「明暗」块要一并 replace 掉 ——
  // 同一个变量在同一条继承链上声明两次，后写的会静默盖掉换肤结果
  const end = src.indexOf("/* ====", start);
  if (start < 0 || end < 0) throw new Error("base.css 找不到配色段落标记");
  writeFileSync(cssPath, src.slice(0, start) + css + "\n\n" + src.slice(end));
}

/** 原生 tabBar 的选中色不吃 CSS 变量，只能往每个 app 的 pages.json 里同步写死 */
/**
 * 各端的默认皮肤 —— **与 `App.vue` 里 `configureShell({ defaultSkin })` 必须一致**。
 *
 * 原先这里一律取 `skins[0]`，那在「两端同一个默认」时是对的；
 * 加了品牌皮肤之后 `skins[0]` 变成 brand，于是**给 B 端换红把 C 端也一起换了**——
 * 而原生 tabBar 不吃 CSS 变量，只能在这里写死，错了不会有任何报错，
 * 只是 C 端底部菜单莫名其妙变成了红的。
 */
const APP_DEFAULT_SKIN = {
  "b-app": "brand", // 商家端：品牌红
  "c-app": "fresh", // 消费端：维持微信绿
};

function writeTabBarColor(app, skins) {
  const wanted = APP_DEFAULT_SKIN[app] ?? skins[0].id;
  const skin = skins.find((s) => s.id === wanted);
  if (!skin) {
    throw new Error(`${app} 的默认皮肤 ${wanted} 不在 SKINS 里`);
  }
  const pagesPath = join(ROOT, app, "src/pages.json");
  const pages = JSON.parse(readFileSync(pagesPath, "utf8"));
  if (pages.tabBar) {
    pages.tabBar.selectedColor = skin.light;
    writeFileSync(pagesPath, JSON.stringify(pages, null, 2) + "\n");
  }
}

const skins = readSkins();
const surfaces = readSurfaces();
const css = buildCss(skins, surfaces);
writeBaseCss(css);
for (const app of APPS) writeTabBarColor(app, skins);

console.log(`已生成 ${skins.length} 套皮肤 × 明暗，写入 packages/ui 的样式基座与 ${APPS.join(" / ")} 的 pages.json`);
for (const s of skins) {
  const sf = surfaces[s.tone];
  const fmt = (hex, bg) =>
    `${hex} 前景${contrast(hex, onColor(hex)).toFixed(2)} 底${contrast(hex, bg).toFixed(2)}`;
  const lock = s.brandLocked ? " [品牌锁定]" : "";
  console.log(
    `  ${s.id.padEnd(9)} ${s.group.padEnd(5)} ${s.tone.padEnd(7)} light ${fmt(s.light, sf.light.bg)}   dark ${fmt(s.dark, sf.dark.bg)}${lock}`,
  );
}
