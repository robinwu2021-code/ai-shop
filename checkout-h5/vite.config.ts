import { defineConfig } from "vitest/config";
import { checkoutMock } from "./mock";

// 收银台与 /checkout/v1 同域（安全规范 §2.5：同源、不开 CORS）。本机开发经代理到后端，生产由 nginx 同域分流；
// CHECKOUT_MOCK=1 时不代理，挂本机假接口看界面（mock.ts，不进构建产物）
const mock = process.env.CHECKOUT_MOCK === "1";

export default defineConfig({
  build: { target: "es2020", cssCodeSplit: false, sourcemap: false },
  plugins: mock ? [checkoutMock()] : [],
  server: mock ? {} : {
    proxy: { "/checkout/v1": { target: process.env.CHECKOUT_API ?? "http://localhost:8081", changeOrigin: false } },
  },
  test: { environment: "jsdom" },
});
