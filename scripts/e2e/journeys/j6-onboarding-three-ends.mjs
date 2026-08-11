// J6 · 从商户入驻开始，三端逐段对齐。
//
// 与 J1 的分工：J1 验「一家店能不能做成一单生意」（下单→发货→送达），
// 这条验**入驻这段路上三个端看到的是不是同一件事** ——
// C 端提交完能不能查回自己的申请、运营的待审队列里有没有它、
// 审核通过后 B 端拿到的作用域对不对、商家建的商品带没带类目、
// 上架后 C 端按类目筛得不筛得出来。
//
// 为什么单独一条：这一轮连着抓到三次「路径通、200、字段在，唯独值对不上」，
// 三次都出现在**跨端交接的那一步**。交接点是最容易各写各的地方，
// 因为两边的开发都能在自己那一端把功能跑通。
import { call, session, opsLogin, opsCall } from "../client.mjs";

export const name = "J6 · 入驻全流程三端对齐（C 端提交 → 运营审核 → B 端经营）";

const OTP = process.env.SHOP_AUTH_OTP_FIXED ?? "123456";

export async function run(step) {
  const seq = Date.now() % 100000;
  const phone = `1369${String(seq).padStart(7, "0")}`;

  // ── 1. C 端：注册并提交入驻 ─────────────────────────────────────
  const consumer = session("申请人");
  await call("c-app", "sendOtp", { body: { phone } });
  consumer.token = (await call("c-app", "login", {
    body: { grantType: "PHONE_OTP", principal: phone, credential: OTP, agreed: true },
  })).token;

  const apply = await call("c-app", "merchantApply", {
    sess: consumer,
    body: {
      name: `J6 回归店 ${seq}`,
      subject: "INDIVIDUAL_BIZ",
      industry: "RETAIL",
      contactName: "J6 老板",
      contactPhone: phone,
      category: "粮油",
      desc: "三端对齐回归",
      serviceScope: "COMMUNITY",
      communityNos: ["CM001"],
    },
  });
  if (apply.status !== "PENDING") {
    throw new Error(`提交后应当是 PENDING，实际 ${apply.status}`);
  }
  step("C 端提交入驻", `${apply.applyNo} / ${apply.status}`);

  // ── 2. C 端：查回自己的申请 ─────────────────────────────────────
  // 提交完查不到，商家就只能反复提交 —— 而「一人一份进行中」会把第二次挡掉，
  // 于是他看到的是「你已有进行中的申请」却哪儿也找不到它
  const mine = await call("c-app", "myMerchantApply", { sess: consumer });
  if (!mine || mine.applyNo !== apply.applyNo) {
    throw new Error(`查回的申请对不上：${mine && mine.applyNo}`);
  }
  step("C 端查回申请", mine.status);

  // ── 3. 运营：待审队列里有它 ─────────────────────────────────────
  const ops = await opsLogin("bd", "bd123");
  const queue = await opsCall("GET", "/ops/merchant/apply", {
    token: ops,
    query: { status: "PENDING" },
  });
  const rows = Array.isArray(queue) ? queue : queue.records ?? [];
  const found = rows.find((r) => r.applyNo === apply.applyNo);
  if (!found) {
    throw new Error(`待审队列里没有 ${apply.applyNo}（队列 ${rows.length} 条）`);
  }
  // 审核页要看到行业与服务范围：少了它们，运营无法判断该不该放行
  for (const f of ["industry", "serviceScope", "communityNos", "contactPhone"]) {
    if (found[f] === undefined) {
      throw new Error(`待审队列缺字段 ${f} —— 运营据此判断该不该放行`);
    }
  }
  step("运营待审队列命中", `${rows.length} 条`);

  // ── 4. 运营：审核通过 ───────────────────────────────────────────
  await opsCall("POST", `/ops/merchant/apply/${apply.applyNo}/audit`, {
    token: ops,
    body: { approved: true },
  });
  step("运营审核通过", apply.applyNo);

  // ── 5. B 端：登录后拿到完整作用域 ───────────────────────────────
  const biz = session("商家B端");
  await call("c-app", "sendOtp", { body: { phone } });
  const bizLogin = await call("b-app", "mLogin", {
    body: { grantType: "PHONE_OTP", principal: phone, credential: OTP, agreed: true },
  });
  biz.token = bizLogin.token;
  const ctx = await opsCall("GET", "/biz/context", { token: biz.token });
  if (!ctx.merchantNo || !ctx.currentStoreNo) {
    // 审核通过必须连默认门店一起建出来，否则商家登录进来没有可经营的门店
    throw new Error(`作用域不完整：merchantNo=${ctx.merchantNo} store=${ctx.currentStoreNo}`);
  }
  step("B 端作用域", `${ctx.merchantNo} @ ${ctx.currentStoreNo}`);

  // ── 6. B 端：工作台打得开，且这家店自己的活是 0 ──────────────────
  const todo = await opsCall("GET", "/biz/dashboard/todo", { token: biz.token });
  /*
   * **quotable 不在这里数**：它是「全平台还有多少条求团需求等着报价」，
   * 是摆在新店面前的**机会**，不是他欠下的活。库里只要有一条开着的需求，
   * 新店看到的就该是 1。
   *
   * 原先断言「全是 0」，只是因为跑的库里恰好没有存量需求 ——
   * 一条在真实数据上不成立的断言，早晚会以「回归失败」的样子炸给别人看，
   * 而真因是它从一开始就把全平台的池子当成了这家店的待办。
   */
  const { quotable, ...ownWork } = todo;
  if (Object.values(ownWork).some((v) => v !== 0)) {
    throw new Error(`新店自己的待办应当全是 0，实际 ${JSON.stringify(ownWork)}`);
  }
  step("B 端工作台", `自己的活全 0（求团池另有 ${quotable} 条，那是机会不是活）`);

  // ── 7. B 端：进件从「不能收钱」到「能收钱」───────────────────────
  const before = await opsCall("GET", "/biz/merchant/payment", { token: biz.token });
  if (before[0].canReceiveMoney) {
    throw new Error("激活后只该是占位记录，不该直接能收钱");
  }
  const paid = await opsCall("POST", "/biz/merchant/payment", {
    token: biz.token,
    body: {
      payChannel: "WECHAT",
      settleAccount: "6222020000123456789",
      licenses: ["https://cdn/j6.jpg"],
      contactName: "J6 老板",
      contactPhone: phone,
    },
  });
  if (!paid.canReceiveMoney) {
    throw new Error("进件通过后应当能收钱");
  }
  step("B 端进件开通", paid.payMerchantNo);

  // ── 8. B 端：门店与类目树都在 ───────────────────────────────────
  const stores = await opsCall("GET", "/biz/store/list", { token: biz.token });
  if (!stores.some((s) => s.storeNo === ctx.currentStoreNo && s.isDefault)) {
    throw new Error("门店列表里没有那家默认店");
  }
  const tree = await call("b-app", "mCategoryTree", { sess: biz });
  if (!tree.some((c) => c.categoryNo === "CAT200")) {
    throw new Error("B 端类目树里没有 CAT200 —— 商家编辑商品时选不到类目");
  }
  step("B 端门店与类目", `${stores.length} 店 / ${tree.length} 个一级类目`);

  // ── 9. B 端：建商品并把类目带上 ─────────────────────────────────
  const goods = await call("b-app", "mSaveGoods", {
    sess: biz,
    body: {
      title: `J6 五常大米 ${seq}`,
      subtitle: "三端对齐回归",
      titleI18n: { "zh-CN": `J6 五常大米 ${seq}` },
      subtitleI18n: { "zh-CN": "三端对齐回归" },
      type: "NORMAL",
      // CAT210 纸品清洁：无资质门槛，这条旅程验的是对齐不是准入
      categoryNo: "CAT210",
      specGroups: [],
      skus: [{ optionValues: [], price: 990, stock: 20 }],
    },
  });
  if (goods.categoryNo !== "CAT210") {
    // 端上传了、后端没接住的话，商品会落成「未归类」，而保存是成功的
    throw new Error(`类目没落库：${goods.categoryNo}`);
  }
  step("B 端建商品带类目", `${goods.goodsNo} → ${goods.categoryNo}`);

  // ── 10. 运营：待审商品队列里有它，审过 ──────────────────────────
  const goodsOps = await opsLogin("goods", "goods123");
  const queue2 = await opsCall("GET", "/ops/goods/audit-queue", { token: goodsOps });
  if (!(queue2.records ?? []).some((g) => g.goodsNo === goods.goodsNo)) {
    throw new Error("待审商品队列里没有它");
  }
  await opsCall("POST", `/ops/goods/${goods.goodsNo}/audit`, {
    token: goodsOps,
    body: { approved: true },
  });
  step("运营审商品", goods.goodsNo);

  // ── 11. B 端上架 → C 端按类目筛得出来 ───────────────────────────
  await call("b-app", "mToggleGoods", {
    sess: biz,
    params: { goodsNo: goods.goodsNo },
    body: { onSale: true },
  });
  const listed = await opsCall("GET", "/mp/goods", {
    query: { categoryNo: "CAT210", communityNo: "CM001", size: 50 },
  });
  if (!(listed.records ?? []).some((g) => g.goodsNo === goods.goodsNo)) {
    /*
     * 这一步同时验三件事：上架写进了社区池、类目落在了商品上、
     * C 端的类目筛选用的是同一个编号。任何一处对不上，买家都搜不到这件货，
     * 而商家那边显示「在售」—— 没有任何报错。
     */
    throw new Error("C 端按类目筛不出这件商品");
  }
  step("C 端按类目可见", `CAT210 → ${goods.goodsNo}`);
}
