// 商品与类目规则测试（P-3）。上架前的三条校验是本域的核心：
// 文案齐、各市场有价（B6）、商家有该类目授权。
import { beforeEach, describe, expect, it } from "vitest";
import { productMock } from "@/lib/api/mocks/product";
import { MAX_CATEGORY_LEVEL } from "@/lib/types";
import { categories, skus } from "./product";
import { merchants } from "./merchant";

const C0 = JSON.parse(JSON.stringify(categories)) as typeof categories;
const S0 = JSON.parse(JSON.stringify(skus)) as typeof skus;
const M0 = JSON.parse(JSON.stringify(merchants)) as typeof merchants;
beforeEach(() => {
  categories.length = 0; categories.push(...(JSON.parse(JSON.stringify(C0)) as typeof categories));
  skus.length = 0; skus.push(...(JSON.parse(JSON.stringify(S0)) as typeof skus));
  merchants.length = 0; merchants.push(...(JSON.parse(JSON.stringify(M0)) as typeof merchants));
});

describe("类目树（P-3.1）", () => {
  it(`最多 ${MAX_CATEGORY_LEVEL} 级：二级类目下不能再建子类目`, async () => {
    await expect(
      productMock.saveCategory({ categoryNo: "", name: "嫩叶菜", parentNo: "CAT110", template: "FRESH", qualifications: [] }),
    ).rejects.toThrow(new RegExp(String(MAX_CATEGORY_LEVEL)));
  });

  it("新建二级类目落库且层级自动算出", async () => {
    const c = await productMock.saveCategory({ categoryNo: "", name: "菌菇", parentNo: "CAT100", template: "FRESH", qualifications: [] });
    expect(c.level).toBe(2);
    expect(c.categoryNo).toMatch(/^CAT/);
  });

  it("有子类目的类目不能归档（归档后 C 端类目树会断枝）", async () => {
    await expect(productMock.archiveCategory("CAT100")).rejects.toThrow(/子类目/);
  });

  it("有在售商品的类目不能归档", async () => {
    await expect(productMock.archiveCategory("CAT110")).rejects.toThrow(/在售商品/);
  });

  it("空类目可以归档，且默认列表不再出现", async () => {
    await productMock.archiveCategory("CAT400");
    const list = (await productMock.listCategories()).records;
    expect(list.some((c) => c.categoryNo === "CAT400")).toBe(false);
  });
});

describe("商品审核（P-3.2.2）", () => {
  it("缺市场定价不予通过（B6：各市场必须分别定价）", async () => {
    // SKU1002 只有 CN 价
    await expect(productMock.auditSku("SKU1002", true)).rejects.toThrow(/SG/);
  });

  it("缺 en/ar 译文**不**拦上架（按 R9 回落到中文），只在界面上标出来", async () => {
    // SKU1003 只有 zh 标题，但价格齐、资质齐
    const s = await productMock.auditSku("SKU1003", true);
    expect(s.status).toBe("ON_SALE");
  });

  it("商家未获得该类目授权时不予通过", async () => {
    // M906 的 categoryCodes 是 ["FOOD"]，浆果类目要求 FRESH_FRUIT
    await expect(productMock.auditSku("SKU1004", true)).rejects.toThrow(/经营授权/);
  });

  it("补上授权后即可通过 —— 判据是 requiredCode，不是资质文案", async () => {
    merchants.find((m) => m.merchantNo === "M906")!.categoryCodes.push("FRESH_FRUIT");
    const s = await productMock.auditSku("SKU1004", true);
    expect(s.status).toBe("ON_SALE");
  });

  it("驳回必须带原因，且原因落库", async () => {
    await expect(productMock.auditSku("SKU1002", false)).rejects.toThrow(/原因/);
    const s = await productMock.auditSku("SKU1002", false, "缺新加坡市场定价");
    expect(s.status).toBe("REJECTED");
    expect(s.reason).toContain("新加坡");
  });

  it("非待审状态不能走审核（状态机拦截）", async () => {
    await expect(productMock.auditSku("SKU1001", true)).rejects.toThrow(/不允许/);
  });
});

describe("强制下架（P-3.2.3）", () => {
  it("必须带原因", async () => {
    await expect(productMock.forceOffSku("SKU1001", "")).rejects.toThrow(/原因/);
  });

  it("只有在售商品可下架", async () => {
    await expect(productMock.forceOffSku("SKU1005", "x")).rejects.toThrow(/在售/);
  });

  it("下架落库并保留原因", async () => {
    const s = await productMock.forceOffSku("SKU1001", "主图与实物不符，多次投诉");
    expect(s.status).toBe("OFF_SALE");
    expect(s.reason).toContain("投诉");
  });
});

describe("预售与超卖（P-3.3）", () => {
  it("截单时间必须早于到货时间（否则货到了还能下单，必然超卖）", async () => {
    await expect(
      productMock.setSkuPresale("SKU1002", 100, "2026-08-08T12:00:00Z"),
    ).rejects.toThrow(/早于到货/);
  });

  it("额度不能为负", async () => {
    await expect(productMock.setSkuPresale("SKU1002", -1, "2026-08-07T00:00:00Z")).rejects.toThrow(/不能为负/);
  });

  it("合法配置落库", async () => {
    const s = await productMock.setSkuPresale("SKU1002", 300, "2026-08-07T02:00:00Z");
    expect(s.presaleQuota).toBe(300);
  });

  it("超卖列表口径 = 已售 > 额度，且只报不处置", async () => {
    const list = await productMock.listOversellSkus();
    expect(list.length).toBeGreaterThan(0);
    for (const s of list) {
      expect(s.soldCount).toBeGreaterThan(s.presaleQuota);
      // 只报警不自动下架：补货还是退单要人判断
      expect(s.status).toBe("ON_SALE");
    }
  });
});

describe("goods 级强制下架（P-3.2.3）", () => {
  it("原因必填 —— 它原样进商家 B 端", async () => {
    await expect(productMock.forceOffGoods("SKU1001", "")).rejects.toThrow(/原因/);
    await expect(productMock.forceOffGoods("SKU1001", "  ")).rejects.toThrow(/原因/);
  });

  it("只有在售的才谈得上撤销过审", async () => {
    await expect(productMock.forceOffGoods("SKU1002", "图文不符")).rejects.toThrow(/在售/);
  });

  it("★ 落到 REJECTED 而不是 OFF_SALE —— 商家必须改完重新提审，不能一键复原", async () => {
    const g = await productMock.forceOffGoods("SKU1001", "图片盗用他人素材");
    expect(g.status).toBe("REJECTED");
    expect(g.auditReason).toContain("平台强制下架");
    expect(g.auditReason).toContain("盗用");
  });

  it("详情读得回驳回原因（商家看到的就是这句）", async () => {
    await productMock.forceOffGoods("SKU1001", "图片盗用他人素材");
    const d = await productMock.getGoodsDetail("SKU1001");
    expect(d.auditReason).toContain("平台强制下架");
    // 后端必发的四个数组：声明成必填才逼得出页面不写 `?? []`
    expect(Array.isArray(d.images)).toBe(true);
    expect(Array.isArray(d.skus)).toBe(true);
    expect(Array.isArray(d.specGroups)).toBe(true);
    expect(Array.isArray(d.fulfillments)).toBe(true);
  });
});
