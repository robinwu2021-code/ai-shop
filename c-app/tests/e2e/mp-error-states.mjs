/**
 * 小程序运行时：**出错态长什么样**。截图到 tests/e2e/out/。
 *
 *   1) 先把包指向一个不可达地址，让每个请求真的失败：
 *      改 .env.production 的 VITE_API_BASE → https://unreachable.invalid
 *      npm run build:mp-weixin
 *   2) 在开发者工具里打开 dist/build/mp-weixin（或 cli open --project ...）
 *   3) node tests/e2e/mp-error-states.mjs
 *   4) **改回 .env.production 并重新构建** —— 别把探针地址留在产物里
 *
 * **为什么不用 mockWxMethod 制造失败**（2026-09-09 试过，是死路）：
 * automator 与这版开发者工具的第五处不兼容 —— 工具在把参数交给 mock 函数之前
 * **把回调剥掉了**：mock 只收到 `{url}`，`success`/`fail`/`complete` 都不在，
 * 于是函数形式根本调不到 `fail`，表现是请求既不成功也不失败，一直挂着。
 * （前四处见 memory 的 mp-devtools-automation。）
 *
 * **为什么只能截图，不能断言文本**：小程序的 SelectorQuery 与 automator 的 `$$`
 * 都**进不了自定义组件内部**，而出错态整个在 `sh-empty` / `sh-scaffold` 里。
 * 页面级查询只看得到插槽内容 —— 那反而给了一个可用的信号：
 * `sh-empty` 在 failed 档下**不渲染 `#action` 插槽**，所以
 * 「引导型空态那颗按钮还在」就等于「failed 没置起来」。
 */
import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import automator from "miniprogram-automator";

// automator 0.12.1 读 SDKVersion，新版工具不给这个字段（见 memory 第 3 条）
createRequire(import.meta.url)("miniprogram-automator/out/MiniProgram")
  .default.prototype.checkVersion = async () => {};

const OUT = resolve(process.cwd(), "tests/e2e/out");
const PORT = Number(process.env.MP_AUTO_PORT || 9420);
const PAGES = [
  ["pages/merchants/index", "merchants"],
  ["pages/coupons/index", "coupons"],
  ["pages/community/index", "community"],
  ["pages/points/index", "points"],
  ["pages/order-confirm/index", "order-confirm"],
  ["pages/address-pick/index", "address-pick"],
  ["pages/orders/index", "orders"],
  ["pages/cart/index", "cart"],
];

const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PROJECT = resolve(process.cwd(), "dist/build/mp-weixin");

/*
 * **必须自己跑一次 `cli auto`**。`automator.launch()` 用不了（第 2 处不兼容：
 * 它从 stdout 抠 ws 地址，新版工具不打印）；而**重开过项目之后自动化会话会断**，
 * 直接 connect 会卡在 "timeout waiting for automator response" ——
 * 那句报错既不提端口也不提会话，看着像工具挂了。
 */
function enableAutomation() {
  return new Promise((res) => {
    const p = spawn(CLI, ["auto", "--project", PROJECT, "--auto-port", String(PORT)],
      { stdio: ["ignore", "pipe", "pipe"] });
    let done = false;
    const fin = () => { if (!done) { done = true; res(); } };
    p.stdout.on("data", () => setTimeout(fin, 1500));
    setTimeout(fin, 12000);
  });
}

mkdirSync(OUT, { recursive: true });
await enableAutomation();
const mp = await automator.connect({ wsEndpoint: `ws://localhost:${PORT}` });

// 先证明请求真的在失败 —— 不然下面截到的「空」说明不了任何事
const probe = await mp.evaluate(() => new Promise((res) => {
  wx.request({ url: "https://unreachable.invalid/probe",
    success: () => res("success"), fail: (e) => res("fail: " + (e && e.errMsg)) });
  setTimeout(() => res("超时"), 5000);
}));
console.log("请求探针 →", probe);
if (!String(probe).startsWith("fail")) {
  console.error("✗ 请求没有失败 —— 包还指着可达的地址，截图说明不了出错态");
  process.exit(1);
}

for (const [route, name] of PAGES) {
  try { await mp.reLaunch("/" + route); } catch { /* 返回值不能用，跳转其实成功了 */ }
  await new Promise((r) => setTimeout(r, 5000));
  await mp.screenshot({ path: resolve(OUT, `mp-err-${name}.png`) });
  console.log(`  ✓ ${name}`);
}
try { await mp.disconnect(); } catch { /* ignore */ }
