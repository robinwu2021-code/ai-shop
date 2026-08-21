import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";

/*
 * c-app 的组件级测试。**与 vite.config.mts 分开**：那份挂着 uni 插件与 UnoCSS，
 * 它们要一个真实的小程序/H5 编译上下文，在 node 里跑不起来；
 * 这里只要 vue 的 SFC 编译 + 一个 DOM。
 *
 * 为什么值得单开一套：三端的守卫（packages/shared/tests）全是**源码扫描式**的，
 * 拦得住 CSS 变量拼错、i18n 键缺失、硬编码颜色，
 * 但拦不住「这个 v-else-if 分支永远进不去」——
 * 同一个页面为此返工了三次，三次都只能靠人在真机上发现。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "@shared": fileURLToPath(new URL("../packages/shared/src", import.meta.url)),
    },
  },
  test: {
    environment: "happy-dom",
    include: ["tests/**/*.test.ts"],
    setupFiles: ["./tests/setup.ts"],
  },
});
