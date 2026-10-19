import { defineConfig } from "vitest/config";
import path from "node:path";

// lib纯函数默认node；页面挂载场景在测试文件内显式启用happy-dom。
export default defineConfig({
  test: {
    include: ["lib/**/*.test.ts"],
    environment: "node",
  },
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname) },
  },
});
