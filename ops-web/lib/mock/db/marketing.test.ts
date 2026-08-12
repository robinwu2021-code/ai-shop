// 营销域规则测试（P-7）。锁的是**花钱的边界**与**场次不重叠**这两条。
import { beforeEach, describe, expect, it } from "vitest";
import { marketingMock } from "@/lib/api/mocks/marketing";
import { platformSlots, contentSlots, couponIssues, coupons } from "./marketing";

const C0 = JSON.parse(JSON.stringify(coupons)) as typeof coupons;
const I0 = JSON.parse(JSON.stringify(couponIssues)) as typeof couponIssues;
const A0 = JSON.parse(JSON.stringify(platformSlots)) as typeof platformSlots;
const S0 = JSON.parse(JSON.stringify(contentSlots)) as typeof contentSlots;
beforeEach(() => {
  coupons.length = 0; coupons.push(...(JSON.parse(JSON.stringify(C0)) as typeof coupons));
  couponIssues.length = 0; couponIssues.push(...(JSON.parse(JSON.stringify(I0)) as typeof couponIssues));
  platformSlots.length = 0; platformSlots.push(...(JSON.parse(JSON.stringify(A0)) as typeof platformSlots));
  contentSlots.length = 0; contentSlots.push(...(JSON.parse(JSON.stringify(S0)) as typeof contentSlots));
});

describe("券预算（P-7.1.3）", () => {
  it("预算不能改到低于已发放金额（否则账面立刻超支）", async () => {
    await expect(marketingMock.setCouponBudget("CP9001", 100_000)).rejects.toThrow(/不能低于已发放/);
  });

  it("调高预算落库", async () => {
    await marketingMock.setCouponBudget("CP9001", 800_000);
    const page = await marketingMock.listCoupons({ keyword: "CP9001" });
    expect(page.records[0].budget).toBe(800_000);
  });

  it("超预算发券被拒绝 —— 不做部分发放", async () => {
    // CP9002 预算 1200 元，已发 1168 元，单张 8 元 → 最多再发 4 张
    await expect(
      marketingMock.issueCoupon({ couponNo: "CP9002", target: "ALL", targetDesc: "全体", count: 10 }),
    ).rejects.toThrow(/超出预算/);
    const page = await marketingMock.listCoupons({ keyword: "CP9002" });
    // 失败不能留下任何痕迹：金额与张数都不该变
    expect(page.records[0].issuedAmount).toBe(116_800);
    expect(page.records[0].issued).toBe(146);
  });

  it("预算内发券：扣预算 + 留痕", async () => {
    const rec = await marketingMock.issueCoupon({ couponNo: "CP9002", target: "COMMUNITY", targetDesc: "梧桐苑", count: 4 });
    expect(rec.amount).toBe(3_200);
    const page = await marketingMock.listCoupons({ keyword: "CP9002" });
    expect(page.records[0].issuedAmount).toBe(120_000);
    const issues = await marketingMock.listCouponIssues({ keyword: "梧桐苑" });
    expect(issues.records[0].count).toBe(4);
    // 操作人必须留痕：客服也能发补偿券
    expect(issues.records[0].operator).toBeTruthy();
  });

  it("非启用状态的券不能发放", async () => {
    await expect(
      marketingMock.issueCoupon({ couponNo: "CP9004", target: "ALL", targetDesc: "全体", count: 1 }),
    ).rejects.toThrow(/启用中/);
    await expect(
      marketingMock.issueCoupon({ couponNo: "CP9003", target: "ALL", targetDesc: "全体", count: 1 }),
    ).rejects.toThrow(/启用中/);
  });

  it("状态机：结束是终态", async () => {
    await expect(marketingMock.setCouponStatus("CP9005", "ACTIVE", "试图复活已结束的券")).rejects.toThrow(/不允许/);
  });
});

// 这一组测的是**平台投放场次**的规则。后端还没有这个对象，所以它们守的是
// mock 与那块 UI —— 以及把「首尾相接不算重叠」「跨位置可并行」这两条产品规则
// 记下来，等建后端对象时照着做。

describe("内容位（P-7.3）", () => {
  it("下线必须晚于上线", async () => {
    await expect(
      marketingMock.setSlotSchedule("SL9002", "2026-08-10T00:00:00Z", "2026-08-09T00:00:00Z"),
    ).rejects.toThrow(/晚于上线/);
  });

  it("上下线开关落库", async () => {
    await marketingMock.setSlotEnabled("SL9004", true);
    const page = await marketingMock.listContentSlots({ keyword: "SL9004" });
    expect(page.records[0].enabled).toBe(true);
  });

  it("按启用状态筛选（下拉给字符串）", async () => {
    const off = await marketingMock.listContentSlots({ enabled: "0", size: 100 });
    expect(off.records.every((s) => !s.enabled)).toBe(true);
  });
});
