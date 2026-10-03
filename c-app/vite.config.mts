import { readFileSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// .mts 走原生 ESM 加载（unocss/vite 是 ESM-only）。
// @dcloudio/vite-plugin-uni 是 CJS，ESM 下真正的工厂函数在 .default 上（Babel 互操作）。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

// uni 插件在前，UnoCSS 在后（uni-app 官方推荐顺序）。
// ⚠️ vite 精确锁 5.2.8：@dcloudio/vite-plugin-uni 的 peerDependencies 钉死此版本，勿升。
/*
 * 构建版本号：**versionName + 构建时刻**，注入成 `__BUILD_VERSION__`，
 * 显示在「我的 → 帮助中心」那一行。
 *
 * <b>为什么不只用 versionName</b>：它要人记得改。忘了改的那次，屏幕上还是同一个数 ——
 * 而这个数存在的全部意义，是回答「我手上这份是不是刚传的那一版」。
 * 一个不会变的版本号对这个问题永远答「是」，比没有更糟。
 * 带上构建时刻之后，它每次构建都不同，答错不了。
 *
 * 真源仍是 manifest.json（与 release-mp.sh 读 appid 同一个道理：只有一份）。
 */
const MANIFEST = fileURLToPath(new URL("./src/manifest.json", import.meta.url));
const VERSION_NAME =
  /"versionName"\s*:\s*"([^"]+)"/.exec(readFileSync(MANIFEST, "utf8"))?.[1] ?? "0.0.0";
// 北京时间的 MMDD-HHmm。构建机时区不定，所以按 UTC+8 自己算，别依赖本地时区
const D = new Date(Date.now() + 8 * 3600 * 1000);
const pad = (n: number) => String(n).padStart(2, "0");
const BUILD_STAMP =
  `${pad(D.getUTCMonth() + 1)}${pad(D.getUTCDate())}-${pad(D.getUTCHours())}${pad(D.getUTCMinutes())}`;

export default defineConfig({
  define: {
    __BUILD_VERSION__: JSON.stringify(`${VERSION_NAME} · ${BUILD_STAMP}`),
  },
  // 两端各自独立部署在自己的域名根路径下（ADR-008 §5）。
  // 这个开关只为「非要挂在某个子路径下」的场景保留 —— 但**别再用它把两端合到同一域名**：
  // 同源会让两端共用 localStorage（登录态、皮肤、mock 数据库全串在一起）
  base: process.env.H5_BASE || "/",
  // @shared 指向 packages/shared/src 的**源码**（不是构建产物）——
  // 由 vite 与 uni 编译器直接处理 TS，两端共享单一事实源，改完即时生效，无需先 build。
  resolve: {
    // 组件库 @ai-shop/ui 走**各 app 自己的 node_modules 软链**，且不解析到真实路径：
    // 小程序端编译会把每个组件的产物路径按「相对 app 根目录」写出来，
    // 一旦解析成 monorepo 里的真实路径就变成 `../../packages/…`，
    // rollup 明确拒绝这种越出根目录的 chunk 路径（构建直接失败，H5 却毫无察觉）。
    preserveSymlinks: true,
    alias: {
      "@shared": fileURLToPath(new URL("../packages/shared/src", import.meta.url)),
    },
  },
  // 组件库是**源码**，绝不能进依赖预打包：预打包会把它 import 的 `@shared/*` 一并内联，
  // 于是 `money.ts` / `datetime.ts` 里那些模块级 holder 出现两份 ——
  // store 调 `setCurrentCurrency("USD")` 改的是副本，页面读到的还是 CNY，
  // 表现为「切了市场，价格还是 ¥」，而类型检查、单测、构建全都不会报错。
  optimizeDeps: { exclude: ["@ai-shop/ui"] },
  server: {
    port: Number(process.env.PORT) || 5173,
    strictPort: false,
    /*
     * ⚠️ `preserveSymlinks: true`（见上）的副作用：**vite 只 watch 软链本身，
     * 不 watch 它指向的真实目录**。于是改 `packages/ui` 的组件或样式，
     * dev server 毫无反应 —— 页面用的还是启动那一刻的旧版本。
     *
     * 这个坑咬过两次，且两次都极难判断：改动明明在源码里、类型检查通过、
     * 构建产物也正确，唯独浏览器里没变化，看起来像"代码没生效"。
     * 显式把 monorepo 根加进 watch 白名单，改共享包即时热更。
     */
    watch: { ignored: ["!**/packages/**"] },
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
    /*
     * 反代到本地后端（`VITE_USE_MOCK=0` 时生效）。
     *
     * 走反代而不是让前端直连 8080：后端**没有任何 CORS 配置** —— 这是对的，
     * 生产上前后端同域，为本地联调去开一个 `allowedOrigins: *` 只会把一个
     * 只在开发期存在的口子带进生产配置。反代让浏览器眼里始终是同源。
     */
    proxy: {
      "/mp": { target: "http://localhost:8080", changeOrigin: true },
      "/biz": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
  plugins: [uni(), UnoCSS()],
});
