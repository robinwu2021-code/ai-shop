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

describe("建券 / 改券（TDD-营销预算前置）", () => {
  const base = { name: "测试券", totalCount: 10, validFrom: Date.now(), validTo: Date.now() + 86_400_000 };

  it("折扣券必须设置封顶——0=不封顶已取消", async () => {
    await expect(
      marketingMock.saveCoupon({ ...base, type: "DISCOUNT", discountRate: 8500 }),
    ).rejects.toThrow(/封顶/);
  });

  it("发行量必须大于 0", async () => {
    await expect(
      marketingMock.saveCoupon({ ...base, type: "FULL_CUT", faceMinor: 500, totalCount: 0 }),
    ).rejects.toThrow(/发行量/);
  });

  it("预算低于敞口（发行量 × 单张最大优惠）被拒", async () => {
    // 敞口 = 10 × 500 = 5000，预算给 1000 必须被拒
    await expect(
      marketingMock.saveCoupon({ ...base, type: "FULL_CUT", faceMinor: 500, budget: 1000 }),
    ).rejects.toThrow(/敞口/);
  });

  it("预算等于敞口——边界值放行", async () => {
    const c = await marketingMock.saveCoupon({ ...base, type: "FULL_CUT", faceMinor: 500, budget: 5000 });
    expect(c.couponNo).toBeTruthy();
    expect(c.budget).toBe(5000);
  });

  it("新建落库后能在列表里查到", async () => {
    await marketingMock.saveCoupon({ ...base, name: "新建的券", type: "FULL_CUT", faceMinor: 300 });
    const page = await marketingMock.listCoupons({ keyword: "新建的券" });
    expect(page.records).toHaveLength(1);
    expect(page.records[0].totalCount).toBe(10);
  });

  it("编辑：发行量不能改到低于已发放张数", async () => {
    // CP9002 已发 146 张
    await expect(
      marketingMock.saveCoupon({ couponNo: "CP9002", name: "满 39 减 8（生鲜）", type: "FULL_CUT",
        faceMinor: 800, totalCount: 100, validFrom: base.validFrom, validTo: base.validTo }),
    ).rejects.toThrow(/已发放/);
  });

  it("编辑：正常改名与发行量落库", async () => {
    const c = await marketingMock.saveCoupon({ couponNo: "CP9002", name: "改名后的券", type: "FULL_CUT",
      faceMinor: 800, totalCount: 200, validFrom: base.validFrom, validTo: base.validTo });
    expect(c.name).toBe("改名后的券");
    expect(c.totalCount).toBe(200);
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
