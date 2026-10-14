/**
 * 货到付款闭环（TDD-货到付款闭环与门店级配送圆心 AC4）：小程序下单 → 商家确认收款 → 已送达。
 *
 *   npm run build:mp-weixin
 *   node tests/e2e/mp-cod-loop.mjs <goodsNo> <商家端 user_no> <门店号>
 *
 * <p>**会在生产上真的下一单**（0.1 元测试商品、当面付款，不经过任何支付通道），并由商家端走完。
 * 买家是开发者工具里登录的那个微信；收货地址用他的默认地址（要在门店配送半径内）。
 *
 * <p>判据：
 *   · 结算页：付法是「当面付款」（没进件 + 门店开了货到付款），没有拦单提示
 *   · 提交后订单是 WAIT_OFFLINE_PAY；商家确认收款后 PAID；已送达后是完成态
 */
import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn, execFileSync } from "node:child_process";
import { createRequire } from "node:module";

const nodeRequire = createRequire(import.meta.url);
nodeRequire("miniprogram-automator/out/MiniProgram").default.prototype.checkVersion =
  async function noop() {};
const automator = nodeRequire("miniprogram-automator");

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "../../..");
const PROJECT = resolve(HERE, "../../dist/build/mp-weixin");
const OUT = resolve(HERE, "out");
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PORT = Number(process.env.MP_AUTO_PORT || 9420);
const [GOODS_NO, MERCHANT_USER, STORE_NO] = process.argv.slice(2);
if (!GOODS_NO || !MERCHANT_USER || !STORE_NO) {
  console.error("用法：node tests/e2e/mp-cod-loop.mjs <goodsNo> <商家端 user_no> <门店号>");
  process.exit(2);
}

mkdirSync(OUT, { recursive: true });
const ok = (m) => console.log(`  ✓ ${m}`);
const fail = (m) => { console.error(`  ✗ ${m}`); process.exitCode = 1; };
const info = (m) => console.log(`  · ${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const cli = (args) => new Promise((res) => spawn(CLI, args, { stdio: "ignore" }).on("close", res));

/** 商家端：密钥票据换会话调接口（scripts/automation/api.py），门店用 X-Store-No */
function biz(method, path, body) {
  const args = [resolve(ROOT, "scripts/automation/api.py"), "B", MERCHANT_USER, method, path];
  if (body) args.push(JSON.stringify(body));
  const out = execFileSync("python3", args, { env: { ...process.env, AUTOMATION_STORE_NO: STORE_NO } }).toString();
  return JSON.parse(out);
}

/** 买家端：用小程序自己的会话调接口 */
async function mpApi(mp, method, path, data) {
  return mp.evaluate((m, p, d) => new Promise((res) => {
    const t = wx.getStorageSync("shcr_token");
    const tok = typeof t === "string" && t.startsWith("\"") ? JSON.parse(t) : t;
    wx.request({ url: `https://www.hxmall.top${p}`, method: m, data: d,
      header: { Authorization: "Bearer " + tok },
      success: (r) => res(r.data), fail: (e) => res({ err: e && e.errMsg }) });
  }), method, path, data);
}

async function page(mp) {
  for (let i = 0; i < 10; i++) {
    try {
      const p = await mp.currentPage();
      if (p) return p;
    } catch {
      // 页面栈切换中
    }
    await sleep(500);
  }
  throw new Error("拿不到当前页");
}

async function textOf(p) {
  const out = [];
  for (const n of await p.$$("text")) {
    try {
      const t = await n.text();
      if (t && t.trim()) out.push(t.trim());
    } catch {
      // 重渲染
    }
  }
  return out.join(" | ");
}

// ---------------------------------------------------------------- 跑

await cli(["open", "--project", PROJECT]);
await cli(["auto", "--project", PROJECT, "--auto-port", String(PORT)]);
let mp;
for (let i = 0; i < 15 && !mp; i++) {
  try {
    mp = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
  } catch {
    await sleep(2000);
  }
}

try {
  // ① 商品与规格
  const g = (await mpApi(mp, "GET", `/mp/goods/${GOODS_NO}`)).data;
  const sku = g.skus[0].skuNo;
  info(`商品：${g.title} ¥${(g.price / 100).toFixed(2)}，履约 ${g.fulfillments.join("/")}`);

  // ② 结算页能力：商家配送 × 当面付款
  const cap = (await mpApi(mp, "POST", "/mp/order/capability",
    { items: [{ goodsNo: GOODS_NO, skuNo: sku, qty: 1 }], fulfillment: "MERCHANT_DELIVERY" })).data;
  info(`capability：usablePayModes=${JSON.stringify(cap.usablePayModes)} usablePayMethods=${JSON.stringify(cap.usablePayMethods)}`);
  if ((cap.usablePayModes || []).includes("OFFLINE")) ok("商家配送下可当面付款（门店开了货到付款）");
  else fail("商家配送下没有当面付款");

  // 购物车里之前留下的同一件先清掉：同一 SKU 加购会叠数量，这一单要的是 1 件
  await mpApi(mp, "POST", "/mp/cart/remove", { skuNos: [sku] });

  // ③ 加购走界面（商品页「加入购物车」）→ 结算页（商家配送）。
  // 不能直接调 /mp/cart/add：结算页读的是端上的购物车 store，接口加进去的它看不见（「这些商品已不在购物车」）
  try {
    await mp.reLaunch("/pages/home/index");
  } catch {
    // 跳转其实成功了
  }
  await sleep(2000);
  try {
    await mp.navigateTo(`/pages/goods/index?goodsNo=${GOODS_NO}`);
  } catch {
    // 同上
  }
  await sleep(5000);
  const add = await (await page(mp)).$(".actionbar__add");
  if (!add) throw new Error("商品页没有「加入购物车」");
  await add.tap();
  await sleep(2500);
  await mp.screenshot({ path: resolve(OUT, "cod-0-goods.png") });
  try {
    await mp.navigateTo(`/pages/order-confirm/index?fulfillment=MERCHANT_DELIVERY&skus=${sku}`);
  } catch {
    // 同上
  }
  await sleep(6000);
  let p = await page(mp);
  const txt = await textOf(p);
  await mp.screenshot({ path: resolve(OUT, "cod-1-confirm.png") });
  const pm = await p.$(".paymode");
  const pmText = pm ? (await pm.text()).trim() : "";
  if (pmText === "当面付款") ok("结算页付法：当面付款");
  else fail(`结算页付法不对：「${pmText}」`);
  const warns = [];
  for (const n of await p.$$(".recv__warn")) {
    const t = (await n.text()).trim();
    if (t && !/^距你约/.test(t)) warns.push(t);
  }
  if (warns.length) fail(`结算页有拦单提示：${warns.join("；")}`);
  else ok("结算页没有拦单提示");
  info(`结算页：${txt.slice(0, 160)}`);

  // ④ 提交
  const btn = await p.$(".actionbar__btn");
  if (!btn) throw new Error("结算页没有提交按钮");
  await btn.tap();
  await sleep(6000);
  p = await page(mp);
  await mp.screenshot({ path: resolve(OUT, "cod-2-submitted.png") });
  info(`提交后页面：${p.path} ${JSON.stringify(p.query)}`);
  const orderNo = p.query?.orderNo;
  if (!orderNo) throw new Error("提交后没拿到订单号");
  let o = (await mpApi(mp, "GET", `/mp/order/${orderNo}`)).data;
  info(`订单 ${orderNo}：${o.status}，实付 ${o.payAmount ?? o.payableMinor ?? ""}，履约 ${o.fulfillment}`);
  if (o.status === "WAIT_OFFLINE_PAY") ok("下单成功，状态：待收款（WAIT_OFFLINE_PAY）");
  else fail(`下单后状态不对：${o.status}`);

  // ⑤ 商家端：找到这一单 → 确认收款 → 已送达
  // 订单视角：records 的 orderNo 是子单号，主单号在 payOrderNo。查「待收款」页签，顺带验它只列待收款单
  const rows = biz("GET", "/biz/order?status=WAIT_OFFLINE_PAY&size=50").data.records;
  const mine = rows.find((r) => r.payOrderNo === orderNo);
  if (!mine) throw new Error(`商家端「待收款」里找不到 ${orderNo}`);
  const bizNo = mine.orderNo;
  if (mine.status === "WAIT_OFFLINE_PAY") ok(`商家端显示待收款（子单 ${bizNo}）`);
  else fail(`商家端状态不对：${mine.status}（b-app 的「确认收款」按钮只认 WAIT_OFFLINE_PAY）`);
  const stray = rows.filter((r) => r.status !== "WAIT_OFFLINE_PAY");
  if (stray.length) fail(`「待收款」页签混进了 ${stray.length} 条别的状态`);
  const c1 = biz("POST", `/biz/order/${bizNo}/confirm-offline-pay`, {});
  info(`确认收款 → ${c1.code} ${c1.msg} 状态 ${c1.data?.status}`);
  const c2 = biz("POST", `/biz/order/${bizNo}/delivered`, {});
  info(`已送达 → ${c2.code} ${c2.msg} 状态 ${c2.data?.status}`);

  // ⑥ 买家侧回读
  o = (await mpApi(mp, "GET", `/mp/order/${orderNo}`)).data;
  info(`买家侧回读：${o.status}`);
  if (c1.code === 0 && c2.code === 0 && o.status !== "WAIT_OFFLINE_PAY") ok(`闭环走完：${o.status}`);
  else fail("闭环没走完");
} finally {
  mp.disconnect();
}
