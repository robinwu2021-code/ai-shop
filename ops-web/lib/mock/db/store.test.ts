// 门店主页治理规则测试（P-10.1）。
import { beforeEach, describe, expect, it } from "vitest";
import { storeMock } from "@/lib/api/mocks/store";
import { storeAcquisition, storeAudits, stores } from "./store";

const A0 = JSON.parse(JSON.stringify(storeAudits)) as typeof storeAudits;
const S0 = JSON.parse(JSON.stringify(stores)) as typeof stores;
beforeEach(() => {
  storeAudits.length = 0; storeAudits.push(...(JSON.parse(JSON.stringify(A0)) as typeof storeAudits));
  // 门店档案的写操作真落库（restoreStore 改 status），不复位的话下一条用例
  // 拿到的是上一条改过的状态 —— 而那种串味是按用例顺序才复现的，最难查
  stores.length = 0; stores.push(...(JSON.parse(JSON.stringify(S0)) as typeof stores));
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

describe("门店档案（P-11.2.1）", () => {
  it("检索含停用与强制下线的店 —— 治理视角更不能看不见", async () => {
    const page = await storeMock.listStores({ size: 100 });
    expect(page.records.map((s) => s.status)).toContain("SUSPENDED");
    expect(page.records.map((s) => s.status)).toContain("READONLY");
  });

  it("按主体筛只出这家的店", async () => {
    const page = await storeMock.listStores({ merchantNo: "M901", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((s) => s.merchantNo === "M901")).toBe(true);
  });

  it("payMerchantNo 为 null 表示「用主体默认收款号」，不是没配", async () => {
    // getStore 现在返回详情包（档案 + 覆盖/取货/扫码数，P-11.2.1c），档案在 .store 上
    const d = await storeMock.getStore("ST001");
    expect(d.store.payMerchantNo).toBeNull();
    expect(d.store.isDefault).toBe(true);
  });

  it("★ 只有平台强制下线的店解得开 —— 商家自助停用的由商家自己开", async () => {
    await expect(storeMock.restoreStore("ST004")).rejects.toThrow(/强制下线/);
    const s = await storeMock.restoreStore("ST003");
    expect(s.status).toBe("ACTIVE");
  });

  it("解除下线真落库（重新读回来是 ACTIVE，不是只改了返回值）", async () => {
    await storeMock.restoreStore("ST003");
    expect((await storeMock.getStore("ST003")).store.status).toBe("ACTIVE");
  });

  it("★ 详情带回覆盖社区/取货点/扫码数；没挂取货点的店是空数组，不是查不到", async () => {
    const withPickup = await storeMock.getStore("ST001");
    expect(withPickup.pickupNames.length).toBeGreaterThan(0);
    expect(withPickup.communityNames.length).toBeGreaterThan(0);
    expect(withPickup.scanCount30d).toBeGreaterThan(0);

    // ST002 没挂点：**空数组**。若实现成「查不到就不返回这个字段」，下面这行会炸
    const noPickup = await storeMock.getStore("ST002");
    expect(noPickup.pickupNames).toEqual([]);
  });

  it("经营状况按门店给，且带得回所属主体", async () => {
    const st = await storeMock.getStoreStats("ST001");
    expect(st.storeNo).toBe("ST001");
    expect(st.merchantNo).toBe("M901");
    expect(st.monthOrders).toBeGreaterThanOrEqual(st.todayOrders);
  });
});
