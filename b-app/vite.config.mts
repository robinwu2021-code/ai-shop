import { fileURLToPath, URL } from "node:url";
import { readFileSync } from "node:fs";
import { defineConfig } from "vite";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// 与 c-app 同构（见 c-app/vite.config.mts 的说明）。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

/*
 * 构建版本号：**versionName + 构建时刻**，注入成 `__BUILD_VERSION__`，显示在「我的」页尾。
 * 与 c-app 同构：只用 versionName 的话忘了改就恒不变，而这个数存在的意义是回答
 * 「我手上这份是不是刚传的那一版」——带上构建时刻每次都不同，答错不了。真源是 manifest.json。
 */
const MANIFEST = fileURLToPath(new URL("./src/manifest.json", import.meta.url));
const VERSION_NAME =
  /"versionName"\s*:\s*"([^"]+)"/.exec(readFileSync(MANIFEST, "utf8"))?.[1] ?? "0.0.0";
// 北京时间 MMDD-HHmm：构建机时区不定，按 UTC+8 自己算
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
  // 与 C 端错开默认端口，方便两端同时起来对照「B 端核销 → C 端订单完成」
  // 组件库是**源码**，绝不能进依赖预打包：预打包会把它 import 的 `@shared/*` 一并内联，
  // 于是 `money.ts` / `datetime.ts` 里那些模块级 holder 出现两份 ——
  // store 调 `setCurrentCurrency("USD")` 改的是副本，页面读到的还是 CNY，
  // 表现为「切了市场，价格还是 ¥」，而类型检查、单测、构建全都不会报错。
  optimizeDeps: { exclude: ["@ai-shop/ui"] },
  server: {
    port: Number(process.env.PORT) || 5273,
    strictPort: false,
    /*
     * ⚠️ `preserveSymlinks: true`（见上）的副作用：**vite 只 watch 软链本身，
     * 不 watch 它指向的真实目录**。于是改 `packages/ui` 的组件或样式，
     * dev server 毫无反应 —— 页面用的还是启动那一刻的旧版本。
     *
     * 这个坑咬过两次，且两次都极难判断：改动明明在源码里、类型检查通过、
     * 构建产物也正确，唯独浏览器里没变化，看起来像"代码没生效"。
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
