// 门店主页治理规则测试（P-10.1）。
import { beforeEach, describe, expect, it } from "vitest";
import { storeMock } from "@/lib/api/mocks/store";
import { storeAcquisition, storeAudits } from "./store";

const A0 = JSON.parse(JSON.stringify(storeAudits)) as typeof storeAudits;
beforeEach(() => {
  storeAudits.length = 0; storeAudits.push(...(JSON.parse(JSON.stringify(A0)) as typeof storeAudits));
});

describe("店招/公告审核", () => {
  it("驳回必须带原因（原因会原样进商家 B 端）", async () => {
    await expect(storeMock.decideStoreAudit("SA9001", false)).rejects.toThrow(/原因/);
    await expect(storeMock.decideStoreAudit("SA9001", false, "   ")).rejects.toThrow(/原因/);
  });

  it("驳回落库并保留原因", async () => {
    await storeMock.decideStoreAudit("SA9002", false, "「全网最低」属绝对化用语，请改成具体优惠");
    const page = await storeMock.listStoreAudits({ status: "REJECTED", size: 100 });
    const row = page.records.find((a) => a.auditNo === "SA9002")!;
    expect(row.reason).toContain("绝对化");
  });

  it("通过后清空驳回原因（旧原因留着会在商家端显示成'通过但有问题'）", async () => {
    const a = await storeMock.decideStoreAudit("SA9003", true);
    expect(a.status).toBe("PASSED");
    expect(a.reason).toBeUndefined();
  });

  it("已处理的单子不能重复裁决", async () => {
    await expect(storeMock.decideStoreAudit("SA9004", true)).rejects.toThrow(/已处理/);
  });

  it("默认队列视图只出待审", async () => {
    const page = await storeMock.listStoreAudits({ status: "PENDING", size: 100 });
    expect(page.records.every((a) => a.status === "PENDING")).toBe(true);
  });

  it("机审命中原因随数据下发（人审要看到机器为什么标它）", async () => {
    const page = await storeMock.listStoreAudits({ status: "PENDING", size: 100 });
    expect(page.records.some((a) => a.hits.length > 0)).toBe(true);
  });
});

describe("获客漏斗（P-10.1.4）", () => {
  it("四段单调不增 —— 进店不可能多于扫码", async () => {
    const page = await storeMock.listStoreAcquisition({ size: 100 });
    for (const r of page.records) {
      expect(r.scan).toBeGreaterThanOrEqual(r.enter);
      expect(r.enter).toBeGreaterThanOrEqual(r.register);
      expect(r.register).toBeGreaterThanOrEqual(r.firstOrder);
    }
    expect(page.records.length).toBe(storeAcquisition.length);
  });
});
