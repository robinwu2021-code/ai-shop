// 售后规则测试（P-6.1）。
import { beforeEach, describe, expect, it } from "vitest";
import { afterSaleMock } from "@/lib/api/mocks/aftersale";
import { afterSales, fastRefundRule } from "./aftersale";

const A0 = JSON.parse(JSON.stringify(afterSales)) as typeof afterSales;
const R0 = { ...fastRefundRule };
beforeEach(() => {
  afterSales.length = 0; afterSales.push(...(JSON.parse(JSON.stringify(A0)) as typeof afterSales));
  Object.assign(fastRefundRule, R0);
});

describe("平台介入裁决（P-6.1.3 / 6.1.4）", () => {
  it("只能裁决已上升到平台的单", async () => {
    // AS9002 是 APPLIED —— 球还在商家手里，平台不能替他做决定
    await expect(
      afterSaleMock.decideAfterSale({ afterSaleNo: "AS9002", refund: true, liability: "MERCHANT", verdict: "坏果属实" }),
    ).rejects.toThrow(/已上升到平台/);
  });

  it("裁决说明必填", async () => {
    await expect(
      afterSaleMock.decideAfterSale({ afterSaleNo: "AS9001", refund: true, liability: "MERCHANT", verdict: "  " }),
    ).rejects.toThrow(/裁决说明/);
  });

  it("支持退款：状态推进到 REFUNDING", async () => {
    const a = await afterSaleMock.decideAfterSale({
      afterSaleNo: "AS9001", refund: true, liability: "MERCHANT", verdict: "坏果 2 个属实，按商品单价全额退",
    });
    expect(a.status).toBe("REFUNDING");
    expect(a.liability).toBe("MERCHANT");
  });

  it("维持商家决定：状态推进到 CLOSED", async () => {
    const a = await afterSaleMock.decideAfterSale({
      afterSaleNo: "AS9001", refund: false, liability: "MERCHANT", verdict: "证据不足，维持商家驳回",
    });
    expect(a.status).toBe("CLOSED");
  });
});

describe("平台介入队列与极速退阈值", () => {
  it("intervene=1 只出平台介入的单", async () => {
    const page = await afterSaleMock.listAfterSales({ intervene: "1", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((a) => a.status === "ARBITRATING")).toBe(true);
  });

  it("金额上限必须大于 0", async () => {
    await expect(
      afterSaleMock.saveFastRefundRule({ enabled: true, maxAmount: 0, withinHours: 24, categories: [] }),
    ).rejects.toThrow(/大于 0/);
  });

  it("时限不能为 0（等于关掉极速退，但开关还显示已启用）", async () => {
    await expect(
      afterSaleMock.saveFastRefundRule({ enabled: true, maxAmount: 2000, withinHours: 0, categories: [] }),
    ).rejects.toThrow(/时限/);
  });

  it("合法配置落库", async () => {
    await afterSaleMock.saveFastRefundRule({ enabled: false, maxAmount: 5_000, withinHours: 48, categories: ["FRESH"] });
    const r = await afterSaleMock.getFastRefundRule();
    expect(r).toMatchObject({ enabled: false, maxAmount: 5_000, withinHours: 48 });
  });
});
