// 把 B 端**四屏轻量运营**（订单 / 核销 / 上下架 / 售后）临时并进 c-app 打一包：
// 用虹选好店的同一个小程序 appid，让店主在手机上顺手看单、核销、上下架、处理售后。
// 重量级经营（建品 / 压缩包导入 / 盘点 / 报表 / 会员 / 员工 / 门店注册）**不进小程序**，留给 b-app 原生应用。
//
// 设计见 docs/technical/design/TDD-B端小程序轻量运营并包.md。与 with-elec.mjs 同为「构建前拷、构建后还原」，
// 但 b-app 是**完整的第二个 App**（自有 i18n / 登录态 / 导航），并进单运行时要多处理三件事：
//
//   1. **i18n 不互相覆盖**：两端词条大面积同名不同义（goods/address/order…）。
//      每个页面的 `const { t } = useI18n()` 改成 **local scope + b-app 全量 messages**，
//      整页对 b-app 自己的词条解析，不与 c-app 全局互串。**不改写任何 $t 键** ——
//      动态键 / 三元 / 裸变量全部原样可用（这是选 local scope 而非「加前缀机械改写」的理由）。
//
//   2. **双令牌隔离**：共享的 http-client / merchant store 都读 `STORAGE.token`，
//      而 `STORAGE` 的命名空间是**整包一个值**（c-app 构建里恒为 shc）。
//      给 pkg-biz 一份把 NS 强制成 shb 的常量影子 + 一份读它的 http-client 拷贝（随构建重生成、不漂移），
//      商家令牌落 `shb*_token`、C 端落 `shc*_token`，满足「一个手机号分绑两套身份」。
//
//   3. **分包里没有 tabBar**：b-app 四个 tab 页的 `switchTab` 对分包页静默失效。
//      四屏走 navigateTo；`uni.switchTab(` 一律改成 `uni.reLaunch(`；
//      范围外的重量页（建品 / 盘点 / 配送设置 / 拣货…）ROUTES 全指向一个 `_app-only` 占位页。
//
// ⚠️ **别在共享工作区里跑 release**：发版脚本从当前目录构建，会把别的会话没提交的改动一起传上体验版。
//    在干净的 HEAD 副本里跑。
//
// 用法（在 c-app 目录下）：
//   node scripts/with-biz.mjs build                    # 并包构建小程序到 dist/build/mp-weixin
//   node scripts/with-biz.mjs release <版本> "<备注>"   # 并包构建并传体验版（走 release-mp.sh）
//   node scripts/with-biz.mjs restore                  # 中途被打断时手工还原
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join, dirname, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const HERE = join(dirname(fileURLToPath(import.meta.url)), "..");
const B = join(HERE, "..", "b-app", "src");
const SHARED = join(HERE, "..", "packages", "shared", "src");
const PKG = "pkg-biz";
const OUT = join(HERE, "src", PKG);
const PAGES_JSON = join(HERE, "src", "pages.json");
const BACKUP = `${PAGES_JSON}.with-biz.bak`;
const APP_VUE = join(HERE, "src", "App.vue");
const APP_BACKUP = `${APP_VUE}.with-biz.bak`;

/**
 * **全量并包**：b-app 的每一页都进小程序（2026-10-07 起，此前只收四屏轻量运营）。
 *
 * 页面清单直接取 b-app/pages.json —— 不再手工维护一份子集，否则 b-app 新增页面时
 * 这里会悄悄落下（而症状是「新页面在 App 里有、小程序里 navigateTo 没反应」）。
 */
const PAGE_SLUGS = JSON.parse(readFileSync(join(B, "pages.json"), "utf8")).pages
    .map((p) => p.path.replace(/^pages\//, "").replace(/\/index$/, ""));
/**
 * 整目录拷：b-app/src 下**除 pages 外的全部**目录（pages 另走 PAGE_SLUGS）。
 *
 * 不写死清单 —— 写死的话 b-app 新增一个顶层目录就会悄悄落下，
 * 而症状是构建期 `Cannot find module` 指向某个页面（2026-10-07 漏了 ports/ 就是这样）。
 */
const COPY_DIRS = readdirSync(B, { withFileTypes: true })
    .filter((e) => e.isDirectory() && e.name !== "pages")
    .map((e) => e.name);

/**
 * b-app 的中文词条，扁平成 `"goods.title" -> "商品"`。
 *
 * <p><b>为什么构建期就要把 key 换成文案</b>：b-app 页面并进分包后，页面自己的 `t`
 * 是 local scope（带着 b-app 的词条），但**传给库件的那些 key 不是页面在翻译** ——
 * `sh-scaffold` 的 `title-key`、`sh-tabbar` 的 `labelKey` 都在主包的库件里翻译，
 * 查的是 **c-app 的**全局词条。于是两种坏法：
 *   · c-app 没有这个键 → 露裸 key（`tab.orders`）；
 *   · c-app 有同名键 → 显示**另一个意思的中文**（`tab.home` b 端是「工作台」、c 端是「首页」）。
 * 后者不报错、看着也正常，只是写错了 —— 2026-10-08 真机上两种一起出现。
 *
 * <p>把词条并进主包的全局 messages 不行：那会把 b-app 全量词条塞进 2MB 的主包，
 * 而且主包 import 分包是小程序的硬禁忌。所以反过来：**构建期解引用，写成文案**。
 * 只做中文 —— pkg-biz 本来就不进版本库、不受 i18n 闸门管（占位页同策略）。
 */
const BIZ_ZH = flattenMessages((await import(`file://${join(B, "i18n", "locale", "zh-CN.ts")}`)).default);

function flattenMessages(obj, prefix = "", out = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flattenMessages(v, key, out);
    else if (typeof v === "string") out[key] = v;
  }
  return out;
}

/** 解引用一个 b-app 词条键；查不到就原样回去（由调用处的断言兜住） */
function bizText(key) {
  return BIZ_ZH[key];
}

/**
 * 从 b-app 的 shared/nav.ts 解析出底部菜单，并把路由补成分包路径。
 *
 * 为什么解析源码而不是 import：这份结果要**内联成字面量**写进 c-app 的 App.vue
 * （主包不能依赖分包，见注入处的说明）。nav.ts 是 TS，构建脚本里不能直接 require。
 */
function readBizTabs() {
  const src = readFileSync(join(B, "shared", "nav.ts"), "utf8");
  // ROUTES: key -> "/pages/x/index"
  const routes = {};
  for (const m of src.matchAll(/(\w+)\s*:\s*"(\/pages\/[a-z0-9-]+\/index)"/g)) {
    routes[m[1]] = m[2];
  }
  const block = /export const TABS = \[([\s\S]*?)\] as const;/.exec(src);
  if (!block) throw new Error("b-app/src/shared/nav.ts 里没找到 TABS —— tabsFor 注入无从取值");
  const tabs = [];
  for (const m of block[1].matchAll(
      /\{\s*key:\s*"([^"]+)",\s*route:\s*ROUTES\.(\w+),\s*icon:\s*"([^"]+)",\s*iconOn:\s*"([^"]+)",\s*labelKey:\s*"([^"]+)"\s*\}/g)) {
    const r = routes[m[2]];
    if (!r) throw new Error(`TABS 里的 ROUTES.${m[2]} 解析不到路径`);
    const label = bizText(m[5]);
    if (!label) throw new Error(`TABS 的 labelKey ${m[5]} 在 b-app 中文词条里查不到 —— 底部菜单会露裸 key`);
    // 带上**现成文案**：这一格由主包的 sh-tabbar 渲染，它的 $t 查的是 c-app 的词条
    tabs.push({
      key: m[1], route: `/${PKG}${r}`, icon: m[3], iconOn: m[4], labelKey: m[5], label,
      // 分包页不在 tabBar.list 里：switchTab 对它们**静默失败**（点了没反应）
      nav: "reLaunch",
    });
  }
  if (tabs.length !== 4) {
    throw new Error(`b-app 的 TABS 解析出 ${tabs.length} 条，预期 4 条 —— 正则跟不上 nav.ts 的写法了`);
  }
  return tabs;
}

/**
 * **主包不许 require 分包。** 这是小程序的硬规矩：分包代码在主包启动那一刻还没下载，
 * 主包里一句 `require("./pkg-biz/…")` 就让整个小程序**在真机上打不开**。
 *
 * 为什么要做成闸门而不是靠记性：这种错**编译期零报错**，开发者工具里又常因为缓存
 * 照样能跑 —— 2026-10-08 就是这么发了一版真机打不开的体验版，
 * 而本地所有检查（构建、vue-tsc、单测、pre-push）全是绿的。
 */
function assertMainPackageDoesNotRequireSubpackage() {
  const dist = join(HERE, "dist", "build", "mp-weixin");
  if (!existsSync(dist)) return;
  const bad = [];
  const scan = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (p === join(dist, PKG)) continue;   // 分包自己当然可以
        scan(p);
      } else if (e.name.endsWith(".js") && readFileSync(p, "utf8").includes(`require("./${PKG}`)) {
        bad.push(p.replace(dist + "/", ""));
      }
    }
  };
  scan(dist);
  if (bad.length) {
    console.error(`\n✗ 主包里 require 了分包，**真机上整个小程序打不开**（编译期不报错）：`);
    for (const f of bad) console.error(`    ${f}`);
    console.error(`  修：注入进 App.vue 的东西要内联成字面量，不要从 @/${PKG}/… import。\n`);
    process.exit(1);
  }
  console.log("✓ 主包没有 require 分包");
}

/**
 * b-app App.vue 里的全局 CSS 变量。
 *
 * 进程是 c-app 的，b-app 的 App.vue 不会被执行，于是它 <style> 里那几个变量
 * 对商家页面全部落空。它们都带回退值（`var(--sh-pad-page, 28rpx)`），
 * 所以不会塌，只是间距/字号比原生 App 里略松 —— 但「两端都正常」就该补上。
 *
 * 皮肤（defaultSkin=brand）没法这样补：theme 是整个 app init 一次、不按页面走，
 * 强行分会把买家的皮肤也改掉。那条是已知取舍，不在这里硬塞。
 */
function readBAppVars() {
  const src = readFileSync(join(B, "App.vue"), "utf8");
  const m = /<style>([\s\S]*?)<\/style>/.exec(src);
  if (!m) throw new Error("b-app/src/App.vue 里找不到 <style>");
  const vars = [...m[1].matchAll(/^\s*(--sh-[a-z-]+)\s*:\s*([^;]+);/gm)]
    .map((x) => `  ${x[1]}: ${x[2].trim()};`);
  if (!vars.length) throw new Error("b-app App.vue 的 <style> 里没解析到 --sh-* 变量 —— 写法变了？");
  return vars.join("\n");
}

function walk(dir) {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

/** 改写一个拷进来的源文件（.ts / .vue）。返回是否改过。 */
function rewrite(file) {
  const src = readFileSync(file, "utf8");
  let out = src
    // @/ → @/pkg-biz/（静态 import/export 与动态 import()）
    .replace(/(from\s+["'])@\//g, `$1@/${PKG}/`)
    .replace(/(import\(\s*["'])@\//g, `$1@/${PKG}/`)
    // 共享网络层 / 常量 → pkg-biz 的 shb 影子（双令牌隔离）
    .replace(/@shared\/net\/http-client/g, `@/${PKG}/_shared/http-client`)
    .replace(/@shared\/utils\/constants/g, `@/${PKG}/_shared/constants`)
    // 分包里没有 tabBar：switchTab 一律降级成 reLaunch
    .replace(/uni\.switchTab\(/g, "uni.reLaunch(");

  /*
   * **凡是用到 i18n 的 .vue，都要拿到 local composer。**
   *
   * 模板里的 `$t` 是 globalInjection 注入的，**永远绑全局 composer**（c-app 词条），
   * 查不到 b-app 的 key，真机上就是整片露键名。所以把 `$t(` 改成解构出来的 `t(`，
   * 并保证这个 `t` 来自带 b-app 全量词条的 local scope。
   *
   * ⚠️ 判据不能是「有没有 useI18n」：有 6 个文件（income/marketing/stats/messages/
   * points-records/biz-store-tag）模板里纯用 $t、脚本里压根不解构 t，
   * 按那个判据会被整个跳过 —— 它们恰恰是最该改的。2026-10-08 查出来的。
   */
  if (extname(file) === ".vue" && /(\$t|[^a-zA-Z_.$]t)\(/.test(out)) {
    // 解构写法不止 `{ t }`，还有 `{ t, te }` —— 只认前者会把后者当成「没用过 useI18n」
    // 而再补一行 const { t }，于是 t 重复声明、整个构建失败。
    const hasUseI18n = /const\s*\{[^}]*\bt\b[^}]*\}\s*=\s*useI18n\(\)/.test(out);
    if (hasUseI18n) {
      out = out
        .replace(
          /(import\s*\{\s*useI18n\s*\}\s*from\s*["']vue-i18n["'];?)/,
          `$1\nimport __BIZ_MESSAGES from "@/${PKG}/_i18n";`,
        )
        .replace(
          /const\s*\{([^}]*)\}\s*=\s*useI18n\(\)/,
          'const {$1} = useI18n({ messages: __BIZ_MESSAGES, useScope: "local", inheritLocale: true })',
        );
    } else if (/<script setup/.test(out)) {
      // 没解构过 t 的：自己补一行。放在 <script setup ...> 之后的第一行
      out = out.replace(
        /(<script setup[^>]*>\n)/,
        `$1import { useI18n as __useI18n } from "vue-i18n";\n`
        + `import __BIZ_MESSAGES from "@/${PKG}/_i18n";\n`
        + `const { t } = __useI18n({ messages: __BIZ_MESSAGES, useScope: "local", inheritLocale: true });\n`,
      );
    }
    out = out.replace(/\$t\(/g, "t(");
  }

  /*
   * **传给库件的 i18n key 要在构建期换成文案。**
   *
   * 上面那段只管页面**自己**翻译的字（`$t(` → local scope 的 `t(`）。而
   * `title-key="goods.title"` 是把**键**交出去，由主包里的 `sh-scaffold` 翻译 ——
   * 它的 `t` 查的是 c-app 的全局词条，b-app 的键在那儿要么没有（露裸 key）、
   * 要么同名不同义（`tab.home`：b 端「工作台」、c 端「首页」，显示成另一个意思，
   * 而且**不报错**）。2026-10-08 真机上两种一起出现在商家工作台上。
   *
   * 两种写法都要认：
   *   title-key="goods.title"                       → title="商品"
   *   title-key="isEdit ? 'a.b' : 'c.d'"            → :title="isEdit ? '…' : '…'"
   * 后者是动态的，所以换成绑定形式；前者保持静态字符串。
   */
  if (extname(file) === ".vue") {
    out = out.replace(/title-key="([^"]*)"/g, (whole, expr) => {
      const plain = /^[\w.]+$/.test(expr.trim());
      if (plain) {
        const text = bizText(expr.trim());
        if (!text) throw new Error(`${file}: title-key="${expr}" 在 b-app 中文词条里查不到`);
        return `title="${text}"`;
      }
      // 三元之类：把里面每个引号包着的键逐个解引用，整体换成 :title 绑定
      let missing = null;
      const rewritten = expr.replace(/'([\w.]+)'/g, (q, key) => {
        const text = bizText(key);
        if (!text) { missing = key; return q; }
        return `'${text}'`;
      });
      if (missing) throw new Error(`${file}: title-key 里的 '${missing}' 在 b-app 中文词条里查不到`);
      return `:title="${rewritten}"`;
    });
  }

  /*
   * **b-app App.vue 的全局 CSS 变量**要补给每个商家页面。
   *
   * 进程是 c-app 的，b-app 的 App.vue 不会被执行，它 <style> 里那几个变量
   * 对商家页面全部落空 —— 都带回退值所以不会塌，只是间距/字号比原生 App 里略松。
   *
   * 落在**页面自己的非 scoped <style> 的 page 选择器**上：小程序每个页面是独立的
   * page 节点，这样只影响这一页，不会漏到买家页面去。
   */
  if (extname(file) === ".vue" && /\/pages\//.test(file.replace(/\\/g, "/"))) {
    const vars = readBAppVars();
    out = /<\/style>\s*$/.test(out.trimEnd())
      ? out.replace(/(<style scoped>)/, `<style>\npage {\n${vars}\n}\n</style>\n\n$1`)
      : out + `\n<style>\npage {\n${vars}\n}\n</style>\n`;
  }
  if (out !== src) {
    writeFileSync(file, out);
    return true;
  }
  return false;
}

/** 改写拷进来的 shared/nav.ts：ROUTES 的 /pages/x/index → 分包路径，范围外的指向占位/入口页。 */
function rewriteRoutes() {
  const nav = join(OUT, "shared", "nav.ts");
  if (!existsSync(nav)) throw new Error("没拷到 shared/nav.ts，ROUTES 改写无从下手");
  const included = new Set(PAGE_SLUGS);
  const src = readFileSync(nav, "utf8");
  const missing = [];
  const out = src.replace(/"\/pages\/([a-z0-9-]+)\/index"/g, (_m, slug) => {
    if (included.has(slug)) return `"/${PKG}/pages/${slug}/index"`;
    // 全量并包后这里本该是空的。真出现了说明 ROUTES 指向一个 pages.json 里没有的页
    // （b-app 自己也会 navigateTo 失败），落占位页兜底并在构建日志里点名。
    missing.push(slug);
    return `"/${PKG}/_app-only/index"`;
  });
  if (missing.length) {
    console.log(`  ⚠ ROUTES 指向了 pages.json 里没有的页，已落占位页：${[...new Set(missing)].join(", ")}`);
  }
  writeFileSync(nav, out);
}

function generated() {
  mkdirSync(join(OUT, "_shared"), { recursive: true });

  // 常量影子：把 NS 从 c-app 的 shc 强制成 b-app 的 shb，其余照搬。
  // 这样商家令牌落 shb*_token、C 端落 shc*_token，同一运行时里互不覆盖。
  writeFileSync(
    join(OUT, "_shared", "constants.ts"),
    `// 构建期生成（with-biz.mjs）。pkg-biz 的本地存储 key 强制 shb 命名空间 ——\n` +
    `// 共享的 http-client / merchant store 都读 STORAGE.token，而 NS 整包一个值（c-app=shc）；\n` +
    `// 这里把前缀 shc→shb，让商家令牌与 C 端令牌分开存，满足「一个手机号两套身份」。\n` +
    `export * from "@shared/utils/constants";\n` +
    `import { STORAGE as __REAL, MOCK_DB_KEY as __REAL_DB } from "@shared/utils/constants";\n` +
    `const __biz = (v: string) => v.replace(/^shc/, "shb");\n` +
    `export const STORAGE = Object.fromEntries(\n` +
    `  Object.entries(__REAL).map(([k, v]) => [k, typeof v === "string" ? __biz(v) : v]),\n` +
    `) as typeof __REAL;\n` +
    `export const MOCK_DB_KEY = __biz(__REAL_DB);\n`,
  );

  // http-client 拷贝：唯一改动是常量来源指向上面的影子（随构建重生成，不与共享源漂移）。
  const client = readFileSync(join(SHARED, "net", "http-client.ts"), "utf8")
    .replace(/@shared\/utils\/constants/g, `@/${PKG}/_shared/constants`);
  writeFileSync(join(OUT, "_shared", "http-client.ts"), client);

  // local scope 用的 messages：b-app 三份词条（拷到了 pkg-biz/i18n/locale）。
  // **必须预编译成消息函数**：端上 vue-i18n runtime 只认消息函数这一种插值（与全局 createAppI18n
  // 内部的 compileMessages 同款，那个函数是模块私有的，这里复制同一段逻辑）。直接喂原始字符串词条，
  // 带 {0}/{name} 的那些在真机上不会插值。
  writeFileSync(
    join(OUT, "_i18n.ts"),
    `// 构建期生成（with-biz.mjs）。pkg-biz 页面以此作 vue-i18n local scope 的 messages（已预编译成消息函数）。\n` +
    `import zhCN from "./i18n/locale/zh-CN";\n` +
    `import en from "./i18n/locale/en";\n` +
    `import ar from "./i18n/locale/ar";\n` +
    `type Tree = { [k: string]: string | Tree };\n` +
    `function compile(node: string | Tree): unknown {\n` +
    `  if (typeof node === "string") {\n` +
    `    const s = node;\n` +
    `    // eslint-disable-next-line @typescript-eslint/no-explicit-any\n` +
    `    return (ctx: any) => s.replace(/\\{(\\w+)\\}/g, (_: string, k: string) => {\n` +
    `      const v = ctx?.named ? ctx.named(k) : undefined;\n` +
    `      return v == null ? \`{\${k}}\` : String(v);\n` +
    `    });\n` +
    `  }\n` +
    `  const out: Record<string, unknown> = {};\n` +
    `  for (const k of Object.keys(node)) out[k] = compile((node as Tree)[k]);\n` +
    `  return out;\n` +
    `}\n` +
    `// eslint-disable-next-line @typescript-eslint/no-explicit-any\n` +
    `export default { "zh-CN": compile(zhCN as any), en: compile(en as any), ar: compile(ar as any) } as any;\n`,
  );

  // 占位页：范围外的重量页都落这里。文案硬编码中文（pkg-biz 不进版本库、不受 i18n 闸门管）。
  mkdirSync(join(OUT, "_app-only"), { recursive: true });
  writeFileSync(
    join(OUT, "_app-only", "index.vue"),
    `<script setup lang="ts">\n` +
    `// 构建期生成（with-biz.mjs）。轻量运营不含的功能统一落这里，引导去商家版 App。\n` +
    `function back() { uni.navigateBack(); }\n` +
    `</script>\n\n` +
    `<template>\n` +
    `  <sh-scaffold title="商家版 App">\n` +
    `    <view class="ao">\n` +
    `      <text class="ao__t">这个功能在商家版 App 里</text>\n` +
    `      <text class="ao__d">小程序只做随手运营（看单 / 核销 / 上下架 / 售后）。建品、盘点、报表等请打开商家版 App 操作。</text>\n` +
    `      <view class="sh-btn sh-btn--primary ao__btn" @tap="back">知道了</view>\n` +
    `    </view>\n` +
    `  </sh-scaffold>\n` +
    `</template>\n\n` +
    `<style scoped>\n` +
    `.ao { padding: 48rpx 40rpx; display: flex; flex-direction: column; gap: 20rpx; align-items: center; text-align: center; }\n` +
    `.ao__t { font-size: 34rpx; font-weight: 600; color: var(--sh-text); }\n` +
    `.ao__d { font-size: 26rpx; color: var(--sh-sub); line-height: 1.6; }\n` +
    `.ao__btn { margin-top: 24rpx; }\n` +
    `</style>\n`,
  );

  // 运营首页：登录闸 + 四屏入口。文案硬编码中文（理由同上）。
  mkdirSync(join(OUT, "_entry"), { recursive: true });
  writeFileSync(
    join(OUT, "_entry", "index.vue"),
    `<script setup lang="ts">\n` +
    `// 构建期生成（with-biz.mjs）。c-app「我的」→ 这里。\n` +
    `// **不要手机号+验证码**：凭 C 端会话(ctk_)向 /mp/user/switch-to-merchant 换商家令牌(btk_)。\n` +
    `// 关联键是 mch_account.user_no == 当前 C 端 user_no（见 TDD-C端免登录切商家端）。\n` +
    `import { onShow } from "@dcloudio/uni-app";\n` +
    `import { ref } from "vue";\n` +
    `import { useMerchantStore } from "@/${PKG}/stores/merchant";\n` +
    `import { ROUTES } from "@/${PKG}/shared/nav";\n` +
    `// 商家令牌位：shb 命名空间影子（与 C 端令牌分开存，一个手机号两套身份）\n` +
    `import { STORAGE as BIZ_STORAGE } from "@/${PKG}/_shared/constants";\n` +
    `// C 端令牌位：**真实** @shared 常量（本包构建出来就是 shc）—— 换取时要带的就是它。\n` +
    `// 这个文件是生成的，不过并包改写，所以这里的 @shared 不会被替换成影子。\n` +
    `import { STORAGE as C_STORAGE } from "@shared/utils/constants";\n` +
    `const m = useMerchantStore();\n` +
    `/** loading | ready | not-merchant | need-c-login */\n` +
    `const state = ref("loading");\n` +
    `/**\n` +
    ` * 后端两个码对应端上两条不同的出路，别合并：\n` +
    ` *   10470 NOT_A_MERCHANT            → 真不是商家，引导「去开店」\n` +
    ` *   10471 PHONE_REQUIRED_FOR_MERCHANT → 没绑号判不了店员身份，引导「去绑手机号」\n` +
    ` */\n` +
    `const PHONE_REQUIRED = 10471;\n` +
    `/** 凭 C 端令牌换商家令牌。回 { token } 或 { code }（码用来决定引导去哪） */\n` +
    `function exchange(ctk: string): Promise<{ token?: string; code?: number }> {\n` +
    `  return new Promise((resolve) => {\n` +
    `    uni.request({\n` +
    `      url: (import.meta.env.VITE_API_BASE || "") + "/mp/user/switch-to-merchant",\n` +
    `      method: "POST",\n` +
    `      header: { Authorization: "Bearer " + ctk },\n` +
    `      success: (res) => {\n` +
    `        const body = res.data as { code?: number; data?: { token?: string } };\n` +
    `        if (body && body.code === 0 && body.data && body.data.token) resolve({ token: body.data.token });\n` +
    `        else resolve({ code: body ? body.code : undefined });\n` +
    `      },\n` +
    `      fail: () => resolve({}),\n` +
    `    });\n` +
    `  });\n` +
    `}\n` +
    `onShow(async () => {\n` +
    `  await m.restore();\n` +
    `  if (m.isLogin) { uni.reLaunch({ url: ROUTES.home }); return; }   // 已有商家会话，直接进\n` +
    `  const ctk = uni.getStorageSync(C_STORAGE.token) as string;\n` +
    `  if (!ctk) { state.value = "need-c-login"; return; }\n` +
    `  const r = await exchange(ctk);\n` +
    `  if (!r.token) {\n` +
    `    // 没绑号 ≠ 不是商家：店员绑完号就能进，劝他「去开店」是答非所问\n` +
    `    state.value = r.code === PHONE_REQUIRED ? "need-phone" : "not-merchant";\n` +
    `    return;\n` +
    `  }\n` +
    `  uni.setStorageSync(BIZ_STORAGE.token, r.token);\n` +
    `  await m.restore();\n` +
    `  if (!m.isLogin) { state.value = "not-merchant"; return; }\n` +
    `  // 全量并包：换到令牌就直接进 b 端工作台，之后完全是 b-app 自己的导航\n` +
    `  uni.reLaunch({ url: ROUTES.home });\n` +
    `});\n` +
    `/** 去入驻：c-app「我的」里有「我也想开店」。它是 tab 页，只能 switchTab */\n` +
    `function goApply() { uni.switchTab({ url: "/pages/me/index" }); }\n` +
    `</script>\n\n` +
    `<template>\n` +
    `  <sh-scaffold title="商家运营">\n` +
    `    <view v-if="state === 'need-phone'" class="be__hint">\n` +
    `      <text class="be__t">先绑手机号</text>\n` +
    `      <text class="be__d">店里给你开的账号认的是手机号。在「我的」绑好之后，回来就能直接进。</text>\n` +
    `      <view class="sh-btn sh-btn--primary be__btn" @tap="goApply">去绑手机号</view>\n` +
    `    </view>\n` +
    `    <view v-else-if="state === 'not-merchant'" class="be__hint">\n` +
    `      <text class="be__t">你还不是商家</text>\n` +
    `      <text class="be__d">开店之后，看单、核销、上下架、售后都能在这里随手做。</text>\n` +
    `      <view class="sh-btn sh-btn--primary be__btn" @tap="goApply">去开店</view>\n` +
    `    </view>\n` +
    `    <view v-else-if="state === 'need-c-login'" class="be__hint">\n` +
    `      <text class="be__t">先登录</text>\n` +
    `      <text class="be__d">登录之后才能切到商家运营。</text>\n` +
    `      <view class="sh-btn sh-btn--primary be__btn" @tap="goApply">去登录</view>\n` +
    `    </view>\n` +
    `  </sh-scaffold>\n` +
    `</template>\n\n` +
    `<style scoped>\n` +
    `.be { padding: 24rpx 32rpx; display: flex; flex-direction: column; gap: 20rpx; }\n` +
    `.be__hint { padding: 64rpx 48rpx; display: flex; flex-direction: column; gap: 16rpx; align-items: center; text-align: center; }\n` +
    `.be__btn { margin-top: 24rpx; }\n` +
    `.be__cell { background: var(--sh-surface); border-radius: var(--sh-radius); padding: 28rpx 32rpx; }\n` +
    `.be__txt { display: flex; flex-direction: column; gap: 6rpx; }\n` +
    `.be__t { font-size: 30rpx; font-weight: 600; color: var(--sh-text); }\n` +
    `.be__d { font-size: 24rpx; color: var(--sh-sub); }\n` +
    `</style>\n`,
  );
}

function prepare() {
  if (!existsSync(B)) throw new Error(`找不到 b-app 源码：${B}`);
  for (const b of [BACKUP, APP_BACKUP]) {
    if (existsSync(b)) throw new Error(`上一次没还原干净（${b} 还在）：先跑 restore`);
  }
  rmSync(OUT, { recursive: true, force: true });

  // 1) 拷整目录 + 6 个页面目录
  for (const d of COPY_DIRS) cpSync(join(B, d), join(OUT, d), { recursive: true });
  for (const slug of PAGE_SLUGS) cpSync(join(B, "pages", slug), join(OUT, "pages", slug), { recursive: true });

  // 2) 改写拷进来的源文件
  let changed = 0;
  for (const f of walk(OUT)) {
    if ([".ts", ".vue"].includes(extname(f)) && rewrite(f)) changed++;
  }
  rewriteRoutes();

  // 3) 生成 pkg-biz 专属文件（在改写之后写，避免被改写二次触碰）
  generated();

  /*
   * **两个 app 共用一个运行时，下面几条都是「编译期不报、真机才暴露」的。**
   * 每条都做成断言，别靠记性。
   */
  const vueFiles = walk(OUT).filter((f) => extname(f) === ".vue");
  // ① 残留的 $t：它绑全局 composer（c-app 词条），查不到 b-app 的 key，整片露键名
  const globalT = vueFiles.filter((f) => /\$t\(/.test(readFileSync(f, "utf8")));
  if (globalT.length) {
    throw new Error(`这些文件还在用全局 $t（会露键名）：\n  ${globalT.map((f) => f.replace(OUT + "/", "")).join("\n  ")}`);
  }
  // ② 用了 t( 却没拿到 local composer：要么露键名、要么 t 未定义
  const noLocalT = vueFiles.filter((f) => {
    const src = readFileSync(f, "utf8");
    return /[^a-zA-Z_.$]t\(/.test(src) && !/useI18n/.test(src);
  });
  if (noLocalT.length) {
    throw new Error(`这些文件用了 t( 却没有 local i18n：\n  ${noLocalT.map((f) => f.replace(OUT + "/", "")).join("\n  ")}`);
  }
  // ③ switchTab 残留：分包页面不是 tabBar 页，switchTab 静默不跳（点了没反应）
  // _entry 例外：它 switchTab 的是 c-app 主包的「我的」（真 tabBar 页），那是对的
  const leftSwitchTab = walk(OUT).filter(
    (f) => [".ts", ".vue"].includes(extname(f))
      && !f.endsWith(join("_entry", "index.vue"))
      && /uni\.switchTab\(/.test(readFileSync(f, "utf8")));
  if (leftSwitchTab.length) {
    throw new Error(`这些文件还有 uni.switchTab（分包里静默不跳）：\n  ${leftSwitchTab.map((f) => f.replace(OUT + "/", "")).join("\n  ")}`);
  }
  /*
   * ④ title-key 残留：它是把**键**交给主包里的 sh-scaffold 去翻译，而那边查的是
   * c-app 的词条 —— 没有就露裸 key，同名就显示另一个意思的中文。两种都不报错。
   * 这一条是 2026-10-08 真机回归（商家工作台的标题成了「首页」）加上的。
   */
  const leftTitleKey = walk(OUT).filter(
    (f) => extname(f) === ".vue" && /title-key=/.test(readFileSync(f, "utf8")));
  if (leftTitleKey.length) {
    throw new Error(`这些文件还有 title-key（主包的库件按 c-app 词条翻，会露 key 或显示错的字）：\n  ${leftTitleKey.map((f) => f.replace(OUT + "/", "")).join("\n  ")}`);
  }

  // 还留着指向 c-app 的 @/（非 @/pkg-biz/）导入 = 会静默解析到 c-app 同名模块。必须为零。
  const leaks = walk(OUT).filter(
    (f) => [".ts", ".vue"].includes(extname(f)) && /from\s+["']@\/(?!pkg-biz\/)/.test(readFileSync(f, "utf8")),
  );
  if (leaks.length) {
    throw new Error(`这些文件里还有指向 c-app 的 @/ 导入：\n  ${leaks.map((f) => f.replace(OUT + "/", "")).join("\n  ")}`);
  }

  // 4) pages.json 加分包。subPackages[].pages 要的是**对象** { path, style }，不是字符串
  //    —— 传字符串时 uni 插件取 page.path 得 undefined，vite 配置阶段就 path 报错。
  //    六个搬来的页沿用 b-app/pages.json 里自己的 style（标题 / custom 导航栏），自造两页给默认 custom。
  const raw = readFileSync(PAGES_JSON, "utf8");
  writeFileSync(BACKUP, raw);
  const app = JSON.parse(raw);
  const bPages = JSON.parse(readFileSync(join(B, "pages.json"), "utf8")).pages;
  const styleOf = (slug) => bPages.find((p) => p.path === `pages/${slug}/index`)?.style ?? {};
  const customBar = { navigationBarTitleText: "", "app-plus": { navigationStyle: "custom" }, h5: { navigationStyle: "custom" } };
  const pages = [
    ...PAGE_SLUGS.map((s) => ({ path: `pages/${s}/index`, style: styleOf(s) })),
    { path: "_entry/index", style: customBar },
    { path: "_app-only/index", style: customBar },
  ];
  /*
   * **给 c-app 的 App.vue 注入 tabsFor**：shell 是进程级单例，而这一包里同时住着
   * 买家页面和商家页面，一套 tabs 必然有一端是错的 —— 商家工作台底下会长出「购物车」，
   * 点了还跳去买家页。按路径分：/pkg-biz 开头用 b-app 的 TABS，其余保持原样。
   * 构建前改、构建后还原（同 pages.json）。
   */
  const appRaw = readFileSync(APP_VUE, "utf8");
  writeFileSync(APP_BACKUP, appRaw);
  if (!appRaw.includes("configureShell({")) {
    throw new Error("c-app/src/App.vue 里找不到 configureShell({ —— 注入点变了，tabsFor 没接上");
  }
  /*
   * ⚠️ **tabs 必须内联成字面量，绝不能从分包 import。**
   *
   * 2026-10-08 踩过：这里原本写 `import { TABS } from "@/pkg-biz/shared/nav"`，
   * 编译出来主包 app.js 第一行就 `require("./pkg-biz/shared/nav.js")` ——
   * 而分包代码在主包启动那一刻**还没下载**，于是小程序一启动就挂，
   * **真机上整个小程序打不开**（开发者工具里有缓存，常常看不出来）。
   *
   * 主包不能依赖分包，这是小程序的硬规矩。所以把 b-app 的 TABS 读出来、
   * 序列化成字面量写进去：路由字符串前缀在这里补上（nav.ts 里的 ROUTES 是 /pages/…，
   * 分包里要 /pkg-biz/pages/…）。
   */
  const bizTabs = readBizTabs();
  const appOut = appRaw
    .replace(/configureShell\(\{\n/,
             `configureShell({\n    // with-biz 注入：商家分包的页面用 b-app 自己的底部菜单。\n`
             + `    // **字面量内联** —— 从分包 import 会让主包启动时 require 分包、真机打不开\n`
             + `    tabsFor: (p: string) => (p.startsWith("/${PKG}/") ? (${JSON.stringify(bizTabs)} as never) : undefined),\n`);
  if (appOut === appRaw) {
    throw new Error("tabsFor 注入没生效（正则没命中）—— 商家页面会显示买家菜单");
  }
  writeFileSync(APP_VUE, appOut);

  app.subPackages = [...(app.subPackages ?? []), { root: PKG, pages }];
  /*
   * **biz-* 自定义组件要配 easycom**（15 个 b-app 页面在模板里直接写 <biz-xxx>）。
   * 不配的话编译期不报错、运行时那个节点整块不渲染 —— 页面打得开，只是少一片。
   * c-app 自己也有 `^biz-(.*)` 规则（指向它自己的 components），所以这里不能覆盖它：
   * 用更具体的前缀 `^biz-` 没法区分两端，改成把 b-app 的组件按**全名**逐个登记。
   */
  const bizDir = join(OUT, "components", "biz");
  if (existsSync(bizDir)) {
    const mine = {};
    for (const f of readdirSync(bizDir)) {
      if (!f.endsWith(".vue")) continue;
      const name = f.replace(/\.vue$/, "");
      mine[`^${name}$`] = `@/${PKG}/components/biz/${name}.vue`;
    }
    /*
     * **顺序要紧**：c-app 自己有一条 `^biz-(.*)` 通配规则，指向它自己的 components。
     * easycom 按配置顺序取第一个命中的，通配排在前面的话 `<biz-pickup-sheet>` 会被
     * 解析到 c-app 那个不存在的路径 —— 编译期不报错，运行时那一片整块不渲染。
     * 所以把这 9 条全名精确规则插到最前面。（两端组件名无重名，查过。）
     */
    app.easycom = app.easycom ?? {};
    app.easycom.custom = { ...mine, ...(app.easycom.custom ?? {}) };
  }
  writeFileSync(PAGES_JSON, JSON.stringify(app, null, 2) + "\n");
  console.log(`✓ 并包：${pages.length} 页 → ${PKG}/，改写 ${changed} 个文件`);
}

function restore() {
  if (existsSync(BACKUP)) {
    writeFileSync(PAGES_JSON, readFileSync(BACKUP, "utf8"));
    rmSync(BACKUP);
  }
  // App.vue 的 tabsFor 注入同样要还原 —— 它是 c-app 的提交源码，留下去就脏了工作区
  if (existsSync(APP_BACKUP)) {
    writeFileSync(APP_VUE, readFileSync(APP_BACKUP, "utf8"));
    rmSync(APP_BACKUP);
  }
  rmSync(OUT, { recursive: true, force: true });
  console.log("✓ 已还原 pages.json，删掉 src/pkg-biz");
}

function run(cmd, args) {
  const r = spawnSync(cmd, args, {
    cwd: HERE,
    stdio: "inherit",
    env: { ...process.env, VITE_WITH_BIZ: "1", VITE_BIZ_ROUTE_BASE: `/${PKG}` },
  });
  return r.status ?? 1;
}

const [cmd, ...rest] = process.argv.slice(2);
if (cmd === "restore") {
  restore();
  process.exit(0);
}
if (cmd === "prepare") {
  // 调试用：只 prepare 不构建、不还原，好手工看编译报错。跑完记得 restore。
  prepare();
  process.exit(0);
}
if (cmd !== "build" && cmd !== "release") {
  console.error('用法：node scripts/with-biz.mjs build | release <版本> "<备注>" | restore');
  process.exit(2);
}

let code = 1;
try {
  prepare();
  code = cmd === "build"
    ? run("npm", ["run", "build:mp-weixin"])
    : run("bash", ["scripts/release-mp.sh", ...rest]);
  if (code === 0) assertMainPackageDoesNotRequireSubpackage();
} catch (e) {
  console.error(`✗ ${e.message}`);
} finally {
  restore();
}
process.exit(code);
