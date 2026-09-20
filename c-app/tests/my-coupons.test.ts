/**
 * 「我的券」与结算页的可用券，走的必须是 `/mp/coupon/mine`（TDD-C端我的券接真接口）。
 *
 * 这两处原先都读领券中心（`/mp/coupon`）。那个端点回答的是「现在能领哪些」：
 * 活动下架、被抢光、过了可领期之后，用户手里那张就从返回里消失 ——
 * 「我的券」凭空少几张、结算页说「无可用券」，而券并没有失效。
 * 症状看起来像「券丢了」，而任何一条现有守卫都不会红。
 */
import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { marketingMock } from "@/api/mocks/marketing";

/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const couponsPage = code("src/pages/coupons/index.vue");
const confirmPage = code("src/pages/order-confirm/index.vue");

describe("两处都读「我的券」", () => {
  it("★★★ 我的券页调 myCoupons，不再用领券中心凑", () => {
    expect(couponsPage, "领券中心回答的是「能领哪些」，不是「我有哪些」")
      .toContain("api.myCoupons()");
    expect(couponsPage, "还在用 couponList 拼「我的券」").not.toContain("api.couponList()");
  });

  it("★★★ 结算页的可用券也来自 myCoupons", () => {
    expect(confirmPage).toContain("api.myCoupons()");
    expect(confirmPage, "结算页还在读领券中心 —— 券在手却选不到")
      .not.toContain("api.couponList()");
  });

  it("★★★ 能不能用以服务端的 usableNow 为准，端上只再判这一单的门槛", () => {
    const at = confirmPage.indexOf("const usableCoupons");
    const body = confirmPage.slice(at, at + 600);
    expect(body).toContain("usableNow");
    expect(body, "端上自己按 endAt 判有效期，会把用掉的券算成可用").not.toContain("endAt >");
  });
});

describe("券的具体信息要说出来", () => {
  it("★★★ 选择弹层每行带门槛与到期", () => {
    const at = confirmPage.indexOf("async function pickCoupon");
    const body = confirmPage.slice(at, at + 900);
    expect(body, "只给「名字 -金额」，用户看不出为什么这张能用、也不知道快过期了")
      .toContain("coupon.threshold");
    expect(body).toContain("coupon.until");
  });

  it("我的券那一行也给范围 · 门槛 · 到期", () => {
    const at = couponsPage.indexOf("function mineRow");
    const body = couponsPage.slice(at, at + 900);
    expect(body).toContain("coupon.until");
    expect(body).toContain("coupon.scopeAll");
  });
});

describe("哪一栏由 status 决定，不靠时间猜", () => {
  it("★★★ 用掉的券不进「可用」——它多半还没过期", () => {
    const at = couponsPage.indexOf("function mineRow");
    const body = couponsPage.slice(at, at + 400);
    expect(body).toContain('u.status === "USED"');
    expect(body).toContain('u.status === "EXPIRED"');
  });

  it("★★★ mock 三种状态各给一张 —— 全是可用的话，另两栏改坏了也看不出来", async () => {
    const rows = await marketingMock.myCoupons!();
    const states = new Set(rows.map((r) => r.status));
    expect(rows.length, "mock 一张都不给，页面在本机永远是空的").toBeGreaterThan(0);
    expect(states.size, "三栏要各有样本").toBeGreaterThan(1);
    // 形状按真接口来：页面读的是 u.coupon.*，mock 给平铺字段的话接真后端就散架
    expect(rows[0]).toHaveProperty("userCouponNo");
    expect(rows[0]!.coupon).toHaveProperty("couponNo");
    expect(rows[0]).toHaveProperty("usableNow");
  });
});

describe("契约登记齐了", () => {
  it("endpoints / http / openapi 三处都有 myCoupons", () => {
    expect(code("src/api/endpoints.ts")).toContain("/mp/coupon/mine");
    expect(code("src/api/http.ts")).toContain('call<UserCoupon[]>("myCoupons")');
    expect(code("scripts/gen-openapi.mjs"), "漏登记会让规格生成器直接失败")
      .toContain("myCoupons");
  });
});

// mock 的 delay 会拖慢用例，这里不需要真实延时
vi.mock("@/api/mocks/_shared", async (orig) => {
  const m = await (orig() as Promise<Record<string, unknown>>);
  return { ...m, delay: <T>(v: T) => Promise.resolve(v) };
});
