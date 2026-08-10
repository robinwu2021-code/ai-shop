// J5 · 运营端真链路：类目维护 + 经营准入 + 商品审核队列。
//
// 这条旅程守的是「三处各自自洽、合起来不通」那一类缺口 —— 类目树在补齐之前，
// 表建了、tree() 写了、ops-web 页面也做了，唯独没有数据也没有后端 CRUD，
// 而每一处单看都没问题。
//
// 它也是唯一一条**跨越运营端与商家端**的旅程：门槛（类目的 requiredCode）
// 由运营设，发证（商家的 categoryCodes）也由运营做，撞上门槛的却是商家。
// 三方分处两个端，任何一方单测都测不出「门槛只会拒绝」这种故障。
import { call, session, opsLogin, opsCall } from "../client.mjs";

export const name = "J5 · 运营维护类目与经营准入（跨运营端与商家端）";

const OTP = process.env.SHOP_AUTH_OTP_FIXED ?? "123456";

export async function run(step) {
  const seq = Date.now() % 100000;
  const phone = `1362${String(seq).padStart(7, "0")}`;

  // ── 1. 运营登录 ────────────────────────────────────────────────
  const ops = await opsLogin();
  step("运营登录", "admin");

  // ── 2. 类目树有数据（补齐之前这里是空数组，且不报错）──────────────
  const tree = await opsCall("GET", "/mp/category/tree");
  const roots = tree.map((c) => c.categoryNo);
  if (!roots.includes("CAT100")) {
    throw new Error(`类目树里没有 CAT100，实际只有 [${roots}]`);
  }
  step("类目树有数据", `${tree.length} 个一级类目`);

  // ── 3. 平台端能列出类目，且带着**校验依据**而不只是展示文案 ────────
  // 运营端列表端点一律是分页壳 {records,total} —— 行在 records 里
  const rows = (await opsCall("GET", "/ops/categories", { token: ops })).records;
  const leafy = rows.find((c) => c.categoryNo === "CAT111");
  if (!leafy) {
    throw new Error("平台端类目列表里没有 CAT111");
  }
  if (leafy.requiredCode !== "FRESH_VEG") {
    // 只下发 qualifications（人读文案）是不够的：端上没法据此判断门槛
    throw new Error(`CAT111 的 requiredCode 是 ${leafy.requiredCode}，期望 FRESH_VEG`);
  }
  step("类目带资质门槛", `CAT111 → ${leafy.requiredCode}`);

  // ── 4. 三级封顶 ────────────────────────────────────────────────
  await opsCall("POST", "/ops/categories", {
    token: ops,
    body: { name: `E2E2 第四级 ${seq}`, parentNo: "CAT111" },
    expectFail: true,
  });
  step("三级封顶", "在三级下建子类目被拒");

  // ── 5. 有子类目时不能归档 ───────────────────────────────────────
  await opsCall("POST", "/ops/categories/CAT100/archive", { token: ops, expectFail: true });
  step("归档保护", "CAT100 下面还有子类目");

  // ── 6. 新建 → 归档 → 从 C 端树里消失 ────────────────────────────
  const created = await opsCall("POST", "/ops/categories", {
    token: ops,
    body: { name: `E2E2 临时类目 ${seq}`, template: "STANDARD" },
  });
  if (!(await opsCall("GET", "/mp/category/tree")).some((c) => c.categoryNo === created.categoryNo)) {
    throw new Error("新建的一级类目没有出现在 C 端树里");
  }
  await opsCall("POST", `/ops/categories/${created.categoryNo}/archive`, { token: ops });
  if ((await opsCall("GET", "/mp/category/tree")).some((c) => c.categoryNo === created.categoryNo)) {
    // 留在树里的话，用户点进去是空列表 —— 而这不是个错误状态，只是没东西
    throw new Error("已归档的类目仍然出现在 C 端树里");
  }
  step("新建→归档→离开树", created.categoryNo);

  // ── 7. 造一个商家，把商品归到有门槛的类目下 ──────────────────────
  const buyerLike = session("申请人");
  await call("c-app", "sendOtp", { body: { phone } });
  buyerLike.token = (await call("c-app", "login", {
    body: { grantType: "PHONE_OTP", principal: phone, credential: OTP, agreed: true },
  })).token;
  const apply = await call("c-app", "merchantApply", {
    sess: buyerLike,
    body: {
      name: `E2E2 菜摊 ${seq}`,
      subject: "MICRO",
      industry: "RETAIL",
      contactName: "E2E2 摊主",
      contactPhone: phone,
      category: "生鲜",
      desc: "准入旅程",
      serviceScope: "COMMUNITY",
      communityNos: ["CM001"],
    },
  });
  await opsCall("POST", `/ops/merchant/apply/${apply.applyNo}/audit`, {
    token: ops,
    body: { approved: true },
  });

  const biz = session("商家B端");
  await call("c-app", "sendOtp", { body: { phone } });
  const bizLogin = await call("b-app", "mLogin", {
    body: { grantType: "PHONE_OTP", principal: phone, credential: OTP, agreed: true },
  });
  biz.token = bizLogin.token;
  step("商家就位", apply.applyNo);

  const goods = await call("b-app", "mSaveGoods", {
    sess: biz,
    body: {
      // 线上格式：基准语言那一份 + 三语 map（不是一个三语对象）
      title: `E2E2 叶菜 ${seq}`,
      subtitle: "准入测试",
      titleI18n: { "zh-CN": `E2E2 叶菜 ${seq}` },
      subtitleI18n: { "zh-CN": "准入测试" },
      type: "FRESH",
      // 保存到有门槛的类目下 —— **这一步必须成功**：
      // 商家可能正准备去申请那张证，保存就拦住等于逼他归到错误的类目
      categoryNo: "CAT111",
      specGroups: [],
      skus: [{ optionValues: [], price: 500, stock: 10 }],
    },
  });
  step("草稿归到有门槛的类目", goods.goodsNo);

  // ── 8. 过审后仍然上不了架：没有经营授权 ─────────────────────────
  await opsCall("POST", `/ops/goods/${goods.goodsNo}/audit`, {
    token: ops,
    body: { approved: true },
  });
  const denied = await call("b-app", "mToggleGoods", {
    sess: biz,
    params: { goodsNo: goods.goodsNo },
    body: { onSale: true },
    expectFail: true,
  });
  if (denied.code !== 70002) {
    throw new Error(`期望 70002（没有经营授权），实际 ${denied.code}：${denied.msg}`);
  }
  step("无授权上架被拒", `code=${denied.code}`);

  // ── 9. 运营发证之后，同一件商品就能上架 ──────────────────────────
  // 这一步是整条旅程的重点：**门槛必须有发证的一侧**。
  // 没有它，挂了门槛的类目永远拒绝所有人，而看起来一切都在正常工作。
  const codes = await opsCall("GET", "/ops/merchants/auth-codes", { token: ops });
  if (!codes.some((c) => c.code === "FRESH_VEG")) {
    throw new Error("授权码列表里没有 FRESH_VEG");
  }
  // merchantNo 直接来自登录响应 —— 商家身份是登录时解析进会话的
  const merchantNo = bizLogin.merchant?.merchantNo;
  if (!merchantNo) {
    throw new Error("登录响应里没有 merchantNo，没法授权");
  }
  await opsCall("PUT", `/ops/merchants/${merchantNo}/auth-codes`, {
    token: ops,
    body: { codes: ["FRESH_VEG"], reason: "E2E2：已核验食品经营许可证" },
  });
  await call("b-app", "mToggleGoods", {
    sess: biz,
    params: { goodsNo: goods.goodsNo },
    body: { onSale: true },
  });
  step("发证后可上架", `${merchantNo} ← FRESH_VEG`);

  // ── 10. 授权不能撤空 ────────────────────────────────────────────
  await opsCall("PUT", `/ops/merchants/${merchantNo}/auth-codes`, {
    token: ops,
    body: { codes: [], reason: "试试撤空" },
    expectFail: true,
  });
  step("授权不能撤空", "要停经营请走封禁");
}
