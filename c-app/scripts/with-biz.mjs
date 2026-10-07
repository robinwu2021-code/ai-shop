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

/** 四屏 + 登录。登录页复用 b-app 已测流程；_entry 无令牌时跳它。 */
const PAGE_SLUGS = ["orders", "order", "verify", "goods-list", "after-sale", "login"];
/** 整目录拷（未被 6 页引用到的文件不会被编译，无害）。i18n 拷来给 local scope 当 messages。 */
const COPY_DIRS = ["api", "stores", "shared", "utils", "i18n"];

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

  // 页面 .vue：把 `const { t } = useI18n();` 换成 local scope + b-app 全量 messages。
  // 整页（含模板 $t 与脚本 t）对 b-app 词条解析，不与 c-app 全局同名词条互串。
  if (extname(file) === ".vue" && /const\s*\{\s*t\s*\}\s*=\s*useI18n\(\)/.test(out)) {
    out = out
      .replace(
        /(import\s*\{\s*useI18n\s*\}\s*from\s*["']vue-i18n["'];?)/,
        `$1\nimport __BIZ_MESSAGES from "@/${PKG}/_i18n";`,
      )
      .replace(
        /const\s*\{\s*t\s*\}\s*=\s*useI18n\(\)/,
        'const { t } = useI18n({ messages: __BIZ_MESSAGES, useScope: "local", inheritLocale: true })',
      )
      // 模板里全是 `$t(`——它是 globalInjection 注入的，**永远绑全局 composer**（c-app 词条），
      // 查不到 b-app 的 key，于是真机上整片露 key。改成解构出来的 `t(`（上面建的 local composer），
      // 整页模板才对 b-app 词条解析。脚本里的 `$t(` 只出现在注释里，改了无害。
      .replace(/\$t\(/g, "t(");
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
  const out = src.replace(/"\/pages\/([a-z0-9-]+)\/index"/g, (_m, slug) => {
    if (included.has(slug)) return `"/${PKG}/pages/${slug}/index"`;
    // home 作为「登录后落点」指向运营首页，其余重量页统一落占位页
    if (slug === "home") return `"/${PKG}/_entry/index"`;
    return `"/${PKG}/_app-only/index"`;
  });
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
    `const ENTRIES = [\n` +
    `  { t: "订单", d: "查看与处理今天的订单", url: ROUTES.orders },\n` +
    `  { t: "核销", d: "提货 / 核验码核销自提单", url: ROUTES.verify },\n` +
    `  { t: "商品上下架", d: "商品列表与上架开关", url: ROUTES.goods },\n` +
    `  { t: "售后", d: "处理退款与售后申请", url: ROUTES.afterSale },\n` +
    `];\n` +
    `/** 凭 C 端令牌换商家令牌。换不到（含 NOT_A_MERCHANT）一律回 null */\n` +
    `function exchange(ctk: string): Promise<string | null> {\n` +
    `  return new Promise((resolve) => {\n` +
    `    uni.request({\n` +
    `      url: (import.meta.env.VITE_API_BASE || "") + "/mp/user/switch-to-merchant",\n` +
    `      method: "POST",\n` +
    `      header: { Authorization: "Bearer " + ctk },\n` +
    `      success: (res) => {\n` +
    `        const body = res.data as { code?: number; data?: { token?: string } };\n` +
    `        resolve(body && body.code === 0 && body.data && body.data.token ? body.data.token : null);\n` +
    `      },\n` +
    `      fail: () => resolve(null),\n` +
    `    });\n` +
    `  });\n` +
    `}\n` +
    `onShow(async () => {\n` +
    `  await m.restore();\n` +
    `  if (m.isLogin) { state.value = "ready"; return; }   // 已有商家会话，直接用\n` +
    `  const ctk = uni.getStorageSync(C_STORAGE.token) as string;\n` +
    `  if (!ctk) { state.value = "need-c-login"; return; }\n` +
    `  const btk = await exchange(ctk);\n` +
    `  if (!btk) { state.value = "not-merchant"; return; }\n` +
    `  uni.setStorageSync(BIZ_STORAGE.token, btk);\n` +
    `  await m.restore();\n` +
    `  state.value = m.isLogin ? "ready" : "not-merchant";\n` +
    `});\n` +
    `function go(url: string) { uni.navigateTo({ url }); }\n` +
    `/** 去入驻：c-app「我的」里有「我也想开店」。它是 tab 页，只能 switchTab */\n` +
    `function goApply() { uni.switchTab({ url: "/pages/me/index" }); }\n` +
    `</script>\n\n` +
    `<template>\n` +
    `  <sh-scaffold title="商家运营">\n` +
    `    <view v-if="state === 'ready'" class="be">\n` +
    `      <view v-for="e in ENTRIES" :key="e.url" class="be__cell sh-row sh-row--between" @tap="go(e.url)">\n` +
    `        <view class="be__txt">\n` +
    `          <text class="be__t">{{ e.t }}</text>\n` +
    `          <text class="be__d">{{ e.d }}</text>\n` +
    `        </view>\n` +
    `        <sh-icon name="chevronRight" :size="36" />\n` +
    `      </view>\n` +
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
  if (existsSync(BACKUP)) throw new Error(`上一次没还原干净（${BACKUP} 还在）：先跑 restore`);
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
  app.subPackages = [...(app.subPackages ?? []), { root: PKG, pages }];
  writeFileSync(PAGES_JSON, JSON.stringify(app, null, 2) + "\n");
  console.log(`✓ 并包：${pages.length} 页 → ${PKG}/，改写 ${changed} 个文件`);
}

function restore() {
  if (existsSync(BACKUP)) {
    writeFileSync(PAGES_JSON, readFileSync(BACKUP, "utf8"));
    rmSync(BACKUP);
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
} catch (e) {
  console.error(`✗ ${e.message}`);
} finally {
  restore();
}
process.exit(code);
