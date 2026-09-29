import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";

// 纯函数单测（src/shared）。与 vite.config.mts 分开的理由同 b-app/vitest.config.mts：
// 那份挂着 uni 插件，node 里跑不起来。要加组件测试时照那份注释里的三样来。
export default defineConfig({
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "@shared": fileURLToPath(new URL("../packages/shared/src", import.meta.url)),
    },
  },
  test: {
    include: ["tests/**/*.test.ts"],
  },
});
