/**
 * 快递闭环（TDD-快递100商家寄件 §7）：小程序下单（快递 × 线下付，仅快递测试模式）→ 商家确认收款
 * → 商家叫快递（快递100 测试环境）→ 快递100 回调推进到「已取件」→ 运单号回填、订单已发货 → 买家确认收货。
 *
 *   npm run build:mp-weixin
 *   node tests/e2e/mp-express-loop.mjs <goodsNo> <商家端 user_no> <门店号>   # 第一段：下单 → 叫快递
 *   （在快递100「商家寄件下单回调」调试工具里，按第一段打印的 taskId / orderId 推「已取件」）
 *   node tests/e2e/mp-express-loop.mjs --finish                              # 第二段：核对回填 → 确认收货
 *
 * <p>**前提**：运营端开关「快递测试模式」(express.test-mode) 已打开 —— 否则快递单不给线下付，
 * 叫快递也会走正式环境（真派快递员、真扣费）。脚本第一步就核对这一点，不满足直接停。
 *
 * <p>两段之间的状态存在 out/express-loop.json。
 */
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
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
const STATE = resolve(OUT, "express-loop.json");
const CLI = "/Applications/wechatwebdevtools.app/Contents/MacOS/cli";
const PORT = Number(process.env.MP_AUTO_PORT || 9420);
const FINISH = process.argv[2] === "--finish";
const [GOODS_NO, MERCHANT_USER, STORE_NO] = FINISH ? [] : process.argv.slice(2);
if (!FINISH && (!GOODS_NO || !MERCHANT_USER || !STORE_NO)) {
  console.error("用法：node tests/e2e/mp-express-loop.mjs <goodsNo> <商家端 user_no> <门店号> | --finish");
  process.exit(2);
}

mkdirSync(OUT, { recursive: true });
const ok = (m) => console.log(`  ✓ ${m}`);
const fail = (m) => { console.error(`  ✗ ${m}`); process.exitCode = 1; };
const info = (m) => console.log(`  · ${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const cli = (args) => new Promise((res) => spawn(CLI, args, { stdio: "ignore" }).on("close", res));

/** 商家端：密钥票据换会话调接口（scripts/automation/api.py），门店用 X-Store-No */
function biz(user, store, method, path, body) {
  const args = [resolve(ROOT, "scripts/automation/api.py"), "B", user, method, path];
  if (body) args.push(JSON.stringify(body));
  const out = execFileSync("python3", args, { env: { ...process.env, AUTOMATION_STORE_NO: store } }).toString();
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

async function connect() {
  await cli(["open", "--project", PROJECT]);
  await cli(["auto", "--project", PROJECT, "--auto-port", String(PORT)]);
  for (let i = 0; i < 15; i++) {
    try {
      return await automator.connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
    } catch {
      await sleep(2000);
    }
  }
  throw new Error("连不上开发者工具的自动化端口");
}

/** 买家订单列表（订单视角）里找这一张主单下的子单 */
async function subOf(mp, orderNo) {
  const list = (await mpApi(mp, "GET", "/mp/order", { size: 20 })).data?.records ?? [];
  return list.find((r) => r.payOrderNo === orderNo);
}

async function placeAndBook(mp) {
  // ① 结算能力：快递 × 线下付只在快递测试模式下出现
  const g = (await mpApi(mp, "GET", `/mp/goods/${GOODS_NO}`)).data;
  const sku = g.skus[0].skuNo;
  info(`商品：${g.title} ¥${(g.price / 100).toFixed(2)}，履约 ${g.fulfillments.join("/")}`);
  const cap = (await mpApi(mp, "POST", "/mp/order/capability",
    { items: [{ goodsNo: GOODS_NO, skuNo: sku, qty: 1 }], fulfillment: "EXPRESS" })).data;
  if ((cap.usablePayModes || []).includes("OFFLINE")) ok("快递单可线下付 —— 快递测试模式开着");
  else throw new Error(`快递单没有线下付：快递测试模式没开？usablePayModes=${JSON.stringify(cap.usablePayModes)}`);

  // ② 走界面下单（接口加购端上的购物车看不见，见 mp-cod-loop.mjs）
  await mpApi(mp, "POST", "/mp/cart/remove", { skuNos: [sku] });
  try { await mp.reLaunch("/pages/home/index"); } catch { /* 跳转其实成功了 */ }
  await sleep(2000);
  try { await mp.navigateTo(`/pages/goods/index?goodsNo=${GOODS_NO}`); } catch { /* 同上 */ }
  await sleep(5000);
  const add = await (await page(mp)).$(".actionbar__add");
  if (!add) throw new Error("商品页没有「加入购物车」");
  await add.tap();
  await sleep(2500);
  try { await mp.navigateTo(`/pages/order-confirm/index?fulfillment=EXPRESS&skus=${sku}`); } catch { /* 同上 */ }
  await sleep(6000);
  let p = await page(mp);
  await mp.screenshot({ path: resolve(OUT, "express-1-confirm.png") });
  const pm = await p.$(".paymode");
  info(`结算页付法：「${pm ? (await pm.text()).trim() : "（无）"}」`);
  const btn = await p.$(".actionbar__btn");
  if (!btn) throw new Error("结算页没有提交按钮");
  await btn.tap();
  await sleep(6000);
  p = await page(mp);
  await mp.screenshot({ path: resolve(OUT, "express-2-submitted.png") });
  const orderNo = p.query?.orderNo;
  if (!orderNo) throw new Error(`提交后没拿到订单号（停在 ${p.path}）`);
  const sub = await subOf(mp, orderNo);
  if (sub?.status === "WAIT_OFFLINE_PAY") ok(`下单成功：${orderNo} / ${sub.orderNo}，待收款`);
  else fail(`下单后状态不对：${sub?.status}`);

  // ③ 商家确认收款 → 已付款
  const c1 = biz(MERCHANT_USER, STORE_NO, "POST", `/biz/order/${sub.orderNo}/confirm-offline-pay`, {});
  if (c1.code === 0 && c1.data?.status === "PAID") ok("商家确认收款：已付款");
  else throw new Error(`确认收款失败：${c1.code} ${c1.msg}`);

  // ④ 商家查价 → 叫快递（快递100 测试环境）
  const q = biz(MERCHANT_USER, STORE_NO, "GET", `/biz/order/${sub.orderNo}/express/quotes?weightKg=1`);
  info(`报价：${q.code} ${q.msg} ${JSON.stringify((q.data || []).map((x) => `${x.carrierName} ${x.priceMinor / 100}`))}`);
  const carrier = (q.data || [])[0]?.carrier || "ZTO";
  const b = biz(MERCHANT_USER, STORE_NO, "POST", `/biz/order/${sub.orderNo}/express`, { carrier, weightKg: 1 });
  if (b.code !== 0) throw new Error(`叫快递失败：${b.code} ${b.msg}`);
  if (b.data.sandbox) ok(`叫快递成功（测试环境）：${b.data.carrierName}，取件单 ${b.data.pickupNo}，${b.data.status}`);
  else fail("叫快递走了正式环境 —— 快递测试模式没生效");

  writeFileSync(STATE, JSON.stringify({ orderNo, subOrderNo: sub.orderNo, merchantUser: MERCHANT_USER,
    storeNo: STORE_NO, pickupNo: b.data.pickupNo, carrier }, null, 2));
  console.log(`\n下一步：在快递100「商家寄件下单回调」调试工具里推「已取件」，再跑 --finish。状态存在 ${STATE}`);
}

async function finish(mp) {
  const s = JSON.parse(readFileSync(STATE, "utf8"));
  const pk = biz(s.merchantUser, s.storeNo, "GET", `/biz/order/${s.subOrderNo}/express`).data;
  info(`取件单：${pk?.status} 运单号 ${pk?.trackingNo ?? "—"} 快递员 ${pk?.courierName ?? "—"} 运费 ${pk?.freightMinor ?? "—"}`);
  const o = biz(s.merchantUser, s.storeNo, "GET", `/biz/order/${s.subOrderNo}`).data;
  if (o.status === "FULFILLING" && o.expressNo) ok(`运单号已回填：${o.expressCompany} ${o.expressNo}，订单已发货`);
  else throw new Error(`订单还没发货：${o.status} / ${o.expressNo ?? "无运单号"} —— 回调推到「已取件」了吗`);

  let sub = await subOf(mp, s.orderNo);
  info(`买家侧：${sub?.status} ${sub?.expressCompany ?? ""} ${sub?.expressNo ?? ""}`);
  const r = await mpApi(mp, "POST", `/mp/order/${s.subOrderNo}/confirm-receipt`, {});
  info(`买家确认收货 → ${r.code} ${r.msg}`);
  sub = await subOf(mp, s.orderNo);
  if (sub?.status === "COMPLETED") ok("闭环走完：已完成");
  else fail(`买家确认收货后状态不对：${sub?.status}`);
}

const mp = await connect();
try {
  if (FINISH) await finish(mp);
  else await placeAndBook(mp);
} finally {
  mp.disconnect();
}
