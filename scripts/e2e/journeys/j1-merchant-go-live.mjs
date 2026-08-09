// J1（前后端整合版）：商家从零到能做生意。
//
// 与后端那条 E2E-1 的 J1 走同一条路，但**每一次调用都取自端上的端点表**，
// 并按端上的类型声明校验响应形状。两条各自能发现不同的问题：
//   E2E-1 红 → 后端自己坏了
//   E2E-2 红 → 后端没坏，但**端拿到的东西不是它以为的样子**
import { call, payCallback, session } from "../client.mjs";

export const name = "J1 · 商家从零到能做生意（按端上契约驱动）";

/** OTP 在开发环境是固定的 —— 端上登录页也写着同一条口径 */
const OTP = "1234";

export async function run(step) {
  const seq = Date.now() % 100000;
  const merchantPhone = `1360${String(seq).padStart(7, "0")}`;
  const buyerPhone = `1361${String(seq).padStart(7, "0")}`;

  // ── 1. 消费者登录（c-app 的端点）───────────────────────────────
  const merchant = session("商家");
  await call("c-app", "sendOtp", { body: { phone: merchantPhone } });
  const login = await call("c-app", "login", {
    body: {
      grantType: "PHONE_OTP",
      principal: merchantPhone,
      credential: OTP,
      agreed: true,
    },
  });
  merchant.token = login.token;
  step("消费者登录", merchantPhone);

  // ── 2. 提交入驻申请 ────────────────────────────────────────────
  const apply = await call("c-app", "merchantApply", {
    sess: merchant,
    body: {
      name: `E2E2 粮油店 ${seq}`,
      subject: "MICRO",
      industry: "RETAIL",
      contactName: "E2E2 老板",
      contactPhone: merchantPhone,
      category: "粮油",
      desc: "前后端整合旅程",
      serviceScope: "COMMUNITY",
      communityNos: ["CM001"],
    },
  });
  step("提交入驻申请", apply.applyNo);

  // ── 3. 运营审核（ops 端点后端已实现，但 ops-web 的表在别处，直接打）──
  const ops = session("运营");
  const opsLogin = await fetch(`${process.env.E2E_BASE ?? "http://localhost:8080"}/ops/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "admin123" }),
  }).then((r) => r.json());
  ops.token = opsLogin.data.token;
  await fetch(
    `${process.env.E2E_BASE ?? "http://localhost:8080"}/ops/merchant/apply/${apply.applyNo}/audit`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${ops.token}` },
      body: JSON.stringify({ approved: true }),
    },
  );
  step("运营审核通过", apply.applyNo);

  // ── 4. 商家登录 B 端：作用域在登录时解析 ────────────────────────
  const biz = session("商家B端");
  await call("c-app", "sendOtp", { body: { phone: merchantPhone } });
  const bizLogin = await call("b-app", "mLogin", {
    body: { grantType: "PHONE_OTP", principal: merchantPhone, credential: OTP, agreed: true },
  });
  biz.token = bizLogin.token;
  step("商家登录 B 端", bizLogin.merchant?.status ?? "—");

  // ── 5. 进件：先看状态，再补资料 ────────────────────────────────
  const payments = await call("b-app", "mPayments", { sess: biz });
  step("进件初始态", `${payments[0].applyStatus} / 能收钱=${payments[0].canReceiveMoney}`);
  if (payments[0].canReceiveMoney) {
    throw new Error("激活后不该直接能收钱 —— 那只是一条占位记录");
  }

  const done = await call("b-app", "mSubmitPayment", {
    sess: biz,
    body: {
      payChannel: "WECHAT",
      settleAccount: "6222020000765432101",
      licenses: ["https://cdn/e2e2.jpg"],
      contactName: "E2E2 老板",
      contactPhone: merchantPhone,
    },
  });
  step("进件开通", done.payMerchantNo);
  if (!done.canReceiveMoney) throw new Error("补齐资料后应当能收钱");

  // ── 6. 上架商品 ────────────────────────────────────────────────
  const goods = await call("b-app", "mSaveGoods", {
    sess: biz,
    body: {
      title: `E2E2 大米 ${seq}`,
      subtitle: "整合旅程",
      type: "NORMAL",
      cover: "🍚",
      images: [],
      specGroups: [],
      skus: [{ optionValues: [], price: 2980, stock: 15 }],
    },
  });
  await fetch(
    `${process.env.E2E_BASE ?? "http://localhost:8080"}/ops/goods/${goods.goodsNo}/audit`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${ops.token}` },
      body: JSON.stringify({ approved: true }),
    },
  );
  await call("b-app", "mToggleGoods", {
    sess: biz,
    params: { goodsNo: goods.goodsNo },
    body: { onSale: true },
  });
  step("商品上架", goods.goodsNo);

  // ── 7. 买家下单并支付 ──────────────────────────────────────────
  const buyer = session("买家");
  await call("c-app", "sendOtp", { body: { phone: buyerPhone } });
  buyer.token = (
    await call("c-app", "login", {
      body: { grantType: "PHONE_OTP", principal: buyerPhone, credential: OTP, agreed: true },
    })
  ).token;
  const detail = await call("c-app", "goodsDetail", { params: { goodsNo: goods.goodsNo } });
  const order = await call("c-app", "createOrder", {
    sess: buyer,
    body: {
      fulfillment: "EXPRESS",
      items: [{ goodsNo: goods.goodsNo, skuNo: detail.skus[0].skuNo, qty: 1 }],
    },
  });
  await payCallback(order.payOrderNo);
  step("买家下单并支付", order.payOrderNo);

  // ── 8. 商家发货 → 买家能看到快递单号 ───────────────────────────
  const bizOrders = await call("b-app", "mOrderList", { sess: biz });
  if (!bizOrders.records?.length) throw new Error("商家必须看得到卖出去的单");
  const subOrderNo = bizOrders.records[0].orderNo;

  const expressNo = `SF-E2E2-${seq}`;
  const shipped = await call("b-app", "mShip", {
    sess: biz,
    params: { orderNo: subOrderNo },
    body: { expressNo },
  });
  step("发货", `${shipped.status} / ${shipped.expressNo}`);

  const buyerView = await call("c-app", "orderDetail", {
    sess: buyer,
    params: { orderNo: subOrderNo },
  });
  if (buyerView.expressNo !== expressNo) {
    throw new Error(
      `买家看不到快递单号（拿到 ${buyerView.expressNo}）—— 那发货这件事对他没有意义`,
    );
  }
  step("买家可见物流号", buyerView.expressNo);

  // ── 9. 标记送达 ────────────────────────────────────────────────
  const delivered = await call("b-app", "mDelivered", {
    sess: biz,
    params: { orderNo: subOrderNo },
  });
  step("标记送达", delivered.status);
  if (delivered.status !== "COMPLETED") throw new Error("送达后应当是 COMPLETED");
}
