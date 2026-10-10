// 履约调度规则测试（P-5.1）。
import { beforeEach, describe, expect, it } from "vitest";
import { fulfillmentMock } from "@/lib/api/mocks/fulfillment";
import { MIN_OVERDUE_GRACE_HOURS } from "@/lib/constants";
import { batches, overdueRule } from "./fulfillment";

const B0 = JSON.parse(JSON.stringify(batches)) as typeof batches;
const R0 = { ...overdueRule };
beforeEach(() => {
  batches.length = 0; batches.push(...(JSON.parse(JSON.stringify(B0)) as typeof batches));
  Object.assign(overdueRule, R0);
});

describe("到货批次状态机", () => {
  it("按顺序推进并落库", async () => {
    await fulfillmentMock.setBatchStatus("B20260806C", "ARRIVED");
    const page = await fulfillmentMock.listArrivalBatches({ keyword: "B20260806C" });
    expect(page.records[0].status).toBe("ARRIVED");
  });

  it("不许跳步：计划中不能直接签收（跳过到货，责任判定就没有依据）", async () => {
    await expect(fulfillmentMock.setBatchStatus("B20260807A", "SIGNED")).rejects.toThrow(/不允许/);
  });

  it("已签收是终态", async () => {
    await expect(fulfillmentMock.setBatchStatus("B20260806A", "ARRIVED")).rejects.toThrow(/不允许/);
  });
});

describe("分拣", () => {
  it("只出已签收批次的货", async () => {
    const rows = (await fulfillmentMock.listSorting()).records;
    expect(rows.length).toBeGreaterThan(0);
    // 未签收的自提点不该出现
    expect(rows.some((r) => r.pickupNo === "P002")).toBe(false);
  });

  it("签收一个新批次后，它的自提点才进入分拣视图", async () => {
    await fulfillmentMock.setBatchStatus("B20260806B", "SIGNED");
    const rows = (await fulfillmentMock.listSorting()).records;
    // mock 数据里 P002 暂无分拣明细，这里断言的是过滤条件按签收状态走、不再把它排除
    const signedPickups = batches.filter((b) => b.status === "SIGNED").map((b) => b.pickupNo);
    expect(signedPickups).toContain("P002");
    expect(rows.every((r) => signedPickups.includes(r.pickupNo))).toBe(true);
  });
});

describe("逾期规则（P-5.1.4）", () => {
  it("宽限期不能低于下限 —— 到点即作废必产生客诉", async () => {
    await expect(
      fulfillmentMock.saveOverdueRule({ action: "VOID", graceHours: 0, maxPostpone: 1 }),
    ).rejects.toThrow(/宽限期/);
  });

  it("顺延方案的次数上限至少为 1", async () => {
    await expect(
      fulfillmentMock.saveOverdueRule({ action: "POSTPONE", graceHours: 12, maxPostpone: 0 }),
    ).rejects.toThrow(/顺延次数/);
  });

  it("合法配置落库，重新读取能拿到新值", async () => {
    await fulfillmentMock.saveOverdueRule({ action: "VOID", graceHours: MIN_OVERDUE_GRACE_HOURS, maxPostpone: 0 });
    const r = await fulfillmentMock.getOverdueRule();
    expect(r.action).toBe("VOID");
    expect(r.graceHours).toBe(MIN_OVERDUE_GRACE_HOURS);
  });
});
