import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const read = (p: string) => readFileSync(resolve(__dirname, "..", p), "utf8");
const goodsPage = read("src/pages/goods/index.vue");
const zh = read("src/i18n/locale/zh-CN.ts");

/**
 * 商品详情页那一行「销售范围」。
 *
 * 后端那侧的语义（空 ≠ 不限）由 GoodsSaleScopeFlowTest 守；这里守的是端上
 * **别把那个判断又做一遍**：`saleScope` 里只要出现一次按 areaNames.length 推 unlimited，
 * 只做自提的商家就会被说成「不限地区」—— 一句正好相反的承诺，页面上看不出异常。
 */
describe("商品详情的销售范围", () => {
  /** 扫描面非空：模板被重排、这几段挪走了的话，下面的断言会在「什么都没扫到」时全绿 */
  it("★ 量具自身有效", () => {
    expect(goodsPage.length).toBeGreaterThan(5000);
    expect(goodsPage).toContain("biz-merchant-bar");
  });

  it("★★★ 那一行只跟着 saleScopeText 走 —— 空与不限是两件事", () => {
    expect(goodsPage).toContain('v-if="saleScopeText"');
    const body = goodsPage.slice(
      goodsPage.indexOf("const saleScopeText = computed("),
      goodsPage.indexOf("const isService = computed("),
    );
    expect(body, "没有 saleScopeText").not.toHaveLength(0);
    // 「不限」这一支必须先于「有没有地名」判 —— 反过来的话只做自提的空会走进不限
    expect(body.indexOf("s.unlimited")).toBeLessThan(body.indexOf("s.areaNames.length"));
    expect(body).toContain("goods.scopeUnlimited");
  });

  it("★★ 列表卡片不标范围 —— 后端也恒不下发，两边一起松就是一屏几十次查询", () => {
    const card = read("src/components/biz/biz-goods-card.vue");
    expect(card).not.toContain("saleScope");
  });

  it("★★ 三个词条都在", () => {
    for (const k of ["scopeShort:", "scopeUnlimited:", "scopeMore:"]) {
      expect(zh, `zh-CN 缺 ${k}`).toContain(k);
    }
  });
});
