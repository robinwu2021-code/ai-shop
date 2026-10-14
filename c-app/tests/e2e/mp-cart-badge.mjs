/**
 * 探针：**在跑着的小程序里读/改 pinia 状态**，然后看界面。
 *
 * 起因（2026-09-06）：真机上购物车角标不显示，而同一份产物在开发者工具的
 * 模拟器里是好的。要判断「是数据没到，还是样式没生效」，就得能问运行时 ——
 * 截图只能看见结果，看不见原因。
 *
 * 用法（开发者工具要开着自动化端口，见下）：
 *   MP_SKIP_AUTO=1 node tests/e2e/mp-cart-badge.mjs
 *
 * <p><b>两处与别的 e2e 脚本不同，值得记下来：</b>
 *
 * 1. **`cli auto` 可能挂住。** 端口已经被上一轮开着时，再跑一次 `cli auto`
 *    不会立刻返回，脚本就卡在那儿一个字都不打印（不是连不上，是还没走到连接）。
 *    `MP_SKIP_AUTO=1` 跳过它、直接连 9420 —— 端口开着时这是最稳的一条路。
 *
 * 2. **`page.$$('.tabbar')` 一个都找不到，不代表节点不在。** 小程序的
 *    `SelectorQuery` 不跨自定义组件边界，而 `sh-tabbar` 在 `sh-scaffold` 里面。
 *    所以「查不到」是工具的边界，不是结论 —— 要判断渲染结果，截图比选择器可靠。
 *
 * <p><b>塞状态要塞进 pinia，不是 storage</b>：storage 只在启动时水合一次
 *（见 mp-store-visible.mjs 的注释）。模拟器发不出 wx.request，所以想造一个
 * 「车里有货」的态，只能直接改 store。
 */
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import automator from "miniprogram-automator";

/* 版本校验绕过的来历见 mp-store-visible.mjs —— 新版工具不回 SDKVersion 字段 */
createRequire(import.meta.url)("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion =
  async function noop() {};

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECT = resolve(HERE, "../../dist/build/mp-weixin");
const OUT = resolve(HERE, "out");
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PORT = Number(process.env.MP_AUTO_PORT || 9420);

function enableAutomation() {
  return new Promise((res, rej) => {
    const p = spawn(CLI, ["auto", "--project", PROJECT, "--auto-port", String(PORT)], {
      stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "";
    p.stdout.on("data", (d) => (out += d));
    p.stderr.on("data", (d) => (out += d));
    p.on("close", (c) => (c === 0 ? res(out) : rej(new Error(`cli auto 退出码 ${c}\n${out.slice(-500)}`))));
  });
}

async function connectWithRetry(tries = 15) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      return await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
    } catch (e) {
      last = e;
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  throw last;
}

if (process.env.MP_SKIP_AUTO !== "1") {
  console.log("[probe] cli auto …（端口已开时它可能不返回，那就用 MP_SKIP_AUTO=1）");
  console.log((await enableAutomation()).split("\n").slice(-3).join("\n"));
} else {
  console.log(`[probe] 跳过 cli auto，直接连 ${PORT}`);
}

const mp = await connectWithRetry();
console.log("[probe] connected");

await mp.reLaunch("/pages/cart/index").catch(() => {});
for (let i = 0; i < 15; i++) {
  await new Promise((r) => setTimeout(r, 800));
  const p = await mp.currentPage().catch(() => null);
  if (p && p.path === "pages/cart/index") break;
}
console.log("[probe] 当前页", (await mp.currentPage()).path);

/** 运行时的 pinia：`getApp().$vm` 是 Vue 应用实例的 proxy，$pinia 挂在全局属性上 */
const readCart = () =>
  mp.evaluate(() => {
    const pinia = getApp().$vm.$.appContext.config.globalProperties.$pinia;
    const cart = pinia.state.value.cart;
    return { items: cart.items.length, qtys: cart.items.map((i) => i.qty), keys: Object.keys(cart) };
  });

console.log("[probe] 塞之前 cart =", JSON.stringify(await readCart()));
await mp.screenshot({ path: resolve(OUT, "cart-badge-before.png") });

await mp.evaluate(() => {
  const pinia = getApp().$vm.$.appContext.config.globalProperties.$pinia;
  pinia.state.value.cart.items = [{
    skuNo: "PROBE-1", goodsNo: "G1", title: "探针商品", specText: "",
    price: 100, qty: 3, image: "", fulfillment: "PICKUP",
    merchantNo: "M1", merchantName: "探针店", available: 99,
  }];
  pinia.state.value.cart.loaded = true;
});
await new Promise((r) => setTimeout(r, 1500));
console.log("[probe] 塞之后 cart =", JSON.stringify(await readCart()));
await mp.screenshot({ path: resolve(OUT, "cart-badge-after.png") });
console.log(`[probe] 两张截图在 ${OUT}/ —— 角标要在「购物车」图标右上角显示为红底白字`);
process.exit(0);
