import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import uniModule from "@dcloudio/vite-plugin-uni";
import UnoCSS from "unocss/vite";

// 与 c-app 同构（见 c-app/vite.config.mts 的说明）。
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const uni = ((uniModule as any).default ?? uniModule) as () => any;

export default defineConfig({
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
  server: { port: Number(process.env.PORT) || 5273, strictPort: false },
  plugins: [uni(), UnoCSS()],
});
