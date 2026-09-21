/**
 * 下单页的优惠面板（原型 k03/k05，执行计划 B1）。
 *
 * 守三件事：
 * ① 券那一行**永远可点** —— 此前没有可用券时它是灰的、点了没反应，
 *    而券包里有券的人会以为券丢了；
 * ② **用不了的券也列出来**，并把原因说成人话（「还差 ¥20.00」而不是「还差 2000 分」）；
 * ③ 自动生效的活动列在同一个面板里，并写出活动名 —— 买家问的是「这 10 块哪来的」，
 *    他不关心后端把它算成券还是活动。
 */
import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { marketingMock } from "@/api/mocks/marketing";

function raw(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8");
}
/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return raw(rel)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const confirm = code("src/pages/order-confirm/index.vue");

describe("券那一行永远可点", () => {
  it("★★★ 不再按「有没有可用券」压暗", () => {
    const row = confirm.slice(confirm.indexOf('$t("confirm.coupon")') - 400,
      confirm.indexOf('$t("confirm.coupon")'));
    expect(row, "又把它压暗了 —— 点了没反应的一行，用户分不清是没券还是坏了")
      .not.toContain("!usableCoupons.length");
  });

  it("★★★ pickCoupon 不再在无券时提前 return", () => {
    const at = confirm.indexOf("async function pickCoupon");
    const body = confirm.slice(at, at + 500);
    expect(body).toContain("couponPanel.value = true");
    expect(body, "无可用券就 return = 那一行永远点不开").not.toContain("if (!usableCoupons.value.length) return");
  });

  it("有券但都用不了时，行里要说出「几张」", () => {
    expect(confirm).toContain("confirm.couponNoneUsable");
  });
});

describe("面板三段", () => {
  const tpl = raw("src/pages/order-confirm/index.vue");

  it("★★★ 自动活动段取自优惠明细，并显示活动名", () => {
    expect(confirm).toContain("const autoActivities");
    expect(confirm).toMatch(/discountLines\.value\.filter\(\(d\) => d\.kind === "ACTIVITY"\)/);
    expect(tpl).toContain("confirm.autoActivity");
    expect(tpl, "活动只给金额不给名字，等于没说").toContain("{{ d.name }}");
  });

  it("★★★ 不可用的券必须渲染出来", () => {
    expect(tpl).toContain("couponBest?.unusable.length");
    expect(tpl).toContain("unusableText(u)");
  });

  it("可用券每行带说明与选中态", () => {
    expect(tpl).toContain("couponBest?.usable");
    expect(tpl).toContain("couponMeta(u)");
  });
});

describe("原因说人话", () => {
  it("★★★ 门槛差额用元不用分", () => {
    const at = confirm.indexOf("function unusableText");
    const body = confirm.slice(at, at + 600);
    expect(body, "后端那句是「还差 2000 分」——照搬上去买家看不懂").toContain("money(u.gapMinor)");
    expect(body).toContain("BELOW_THRESHOLD");
  });

  it("★★★ 拿不到 code 时回落后端原句，不显示码", () => {
    const at = confirm.indexOf("function unusableText");
    const body = confirm.slice(at, at + 600);
    expect(body, "新增的原因码没映射时要有兜底，否则界面空一行").toContain("return u.reason");
  });
});

describe("mock 给得出三种券", () => {
  it("★★★ 可用与不可用都要有样本 —— 全可用的话「不可用」那一段改坏了也看不出来", async () => {
    const r = await marketingMock.couponBest!([{ goodsNo: "G001", skuNo: "G001S1", qty: 1 }]);
    expect(r.unusable.length, "一张不可用的都不给，面板那一段在本机永远不显示")
      .toBeGreaterThan(0);
    const gap = r.unusable.find((u) => u.code === "BELOW_THRESHOLD");
    expect(gap?.gapMinor, "门槛差额要给数，端上才能格式化成元").toBeGreaterThan(0);
  });
});

describe("契约登记齐了", () => {
  it("endpoints / http / openapi 三处都有 couponBest", () => {
    expect(code("src/api/endpoints.ts")).toContain("/mp/coupon/best");
    expect(code("src/api/http.ts")).toContain('call<CouponBestResult>("couponBest"');
    expect(code("scripts/gen-openapi.mjs"), "漏登记会让规格生成器直接失败").toContain("couponBest");
  });
});

vi.mock("@/api/mocks/_shared", async (orig) => {
  const m = await (orig() as Promise<Record<string, unknown>>);
  return { ...m, delay: <T>(v: T) => Promise.resolve(v) };
});
