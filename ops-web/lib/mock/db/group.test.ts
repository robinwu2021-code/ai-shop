// 团购与求团规则测试（P-8）。约束来自 ADR-003：不做事前审核，用改价公示 + 信用。
import { beforeEach, describe, expect, it } from "vitest";
import { groupMock } from "@/lib/api/mocks/group";
import { MAX_QUOTE_PRICE_CHANGES } from "@/lib/constants";
import { demandOrders, groupCampaigns, quotes } from "./group";
import { merchants } from "./merchant";

const G0 = JSON.parse(JSON.stringify(groupCampaigns)) as typeof groupCampaigns;
const D0 = JSON.parse(JSON.stringify(demandOrders)) as typeof demandOrders;
const Q0 = JSON.parse(JSON.stringify(quotes)) as typeof quotes;
const M0 = JSON.parse(JSON.stringify(merchants)) as typeof merchants;
beforeEach(() => {
  groupCampaigns.length = 0; groupCampaigns.push(...(JSON.parse(JSON.stringify(G0)) as typeof groupCampaigns));
  demandOrders.length = 0; demandOrders.push(...(JSON.parse(JSON.stringify(D0)) as typeof demandOrders));
  quotes.length = 0; quotes.push(...(JSON.parse(JSON.stringify(Q0)) as typeof quotes));
  merchants.length = 0; merchants.push(...(JSON.parse(JSON.stringify(M0)) as typeof merchants));
});

describe("团模板审核（P-8.1.1）", () => {
  it("团购价必须低于原价（否则「团购」是假的）", async () => {
    const g = groupCampaigns.find((x) => x.groupNo === "GB9003")!;
    g.groupPrice = g.originPrice;
    await expect(groupMock.auditGroupCampaign("GB9003", true)).rejects.toThrow(/低于原价/);
  });

  it("起团人数至少 2（1 个人不叫团）", async () => {
    const g = groupCampaigns.find((x) => x.groupNo === "GB9003")!;
    g.minCount = 1;
    await expect(groupMock.auditGroupCampaign("GB9003", true)).rejects.toThrow(/至少为 2/);
  });

  it("驳回必须带原因", async () => {
    await expect(groupMock.auditGroupCampaign("GB9003", false)).rejects.toThrow(/原因/);
  });

  it("通过后进入进行中", async () => {
    const g = await groupMock.auditGroupCampaign("GB9003", true);
    expect(g.status).toBe("OPEN");
  });

  it("已审核的团不能重复审", async () => {
    await expect(groupMock.auditGroupCampaign("GB9001", true)).rejects.toThrow(/已审核/);
  });
});

describe("求团指派报价（P-8.2.2）", () => {
  it("指派后需求进入报价中且报价数 +1", async () => {
    const q = await groupMock.assignQuote({ demandNo: "RQ9002", merchantNo: "M903", price: 88_00, minQty: 10, validTo: "2026-08-20T16:00:00Z" });
    expect(q.quoteNo).toMatch(/^QT/);
    const page = await groupMock.listDemands({ keyword: "RQ9002" });
    expect(page.records[0].status).toBe("QUOTING");
    expect(page.records[0].quoteCount).toBe(1);
  });

  it("同一需求同一商家不能重复报价（要改价而不是再发一条）", async () => {
    await expect(
      groupMock.assignQuote({ demandNo: "RQ9001", merchantNo: "M903", price: 100_00, minQty: 10, validTo: "2026-08-20T16:00:00Z" }),
    ).rejects.toThrow(/已对本需求报过价/);
  });

  it("毁约达上限的商家禁止报价（取商家档案的 breachCount）", async () => {
    // M906 档案里 breachCount = 3
    await expect(
      groupMock.assignQuote({ demandNo: "RQ9002", merchantNo: "M906", price: 50_00, minQty: 5, validTo: "2026-08-20T16:00:00Z" }),
    ).rejects.toThrow(/限制报价/);
  });

  it("已关闭/已选定的需求不能再指派", async () => {
    await expect(
      groupMock.assignQuote({ demandNo: "RQ9004", merchantNo: "M903", price: 50_00, minQty: 5, validTo: "2026-08-20T16:00:00Z" }),
    ).rejects.toThrow(/已关闭或已选定/);
  });
});

describe("改价与毁约（ADR-003）", () => {
  it("改价累加次数并落库", async () => {
    const q = await groupMock.changeQuotePrice("QT9001", 120_00, "与商家沟通后下调");
    expect(q.price).toBe(120_00);
    expect(q.priceChanges).toBe(1);
  });

  it("改价达上限后禁止再改", async () => {
    // QT9002 已改 3 次
    await expect(groupMock.changeQuotePrice("QT9002", 100_00, "再降一点")).rejects.toThrow(
      new RegExp(String(MAX_QUOTE_PRICE_CHANGES)),
    );
  });

  it("记毁约会同步累加商家信用档案（两处不同步，限制报价就按一个永远长不大的数判）", async () => {
    const before = merchants.find((m) => m.merchantNo === "M903")!.breachCount;
    await groupMock.markQuoteBreached("QT9001");
    expect(merchants.find((m) => m.merchantNo === "M903")!.breachCount).toBe(before + 1);
  });

  it("同一条报价不能重复记毁约", async () => {
    await expect(groupMock.markQuoteBreached("QT9004")).rejects.toThrow(/已标记毁约/);
  });
});
