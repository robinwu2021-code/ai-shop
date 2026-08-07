// mock 落库与状态机单测。mock 不是"随便返点数据"：它是后端行为的**前端替身**，
// 状态机与落库不真实，页面就会写出后端根本不允许的流程（架构 §10.5）。
import { beforeEach, describe, expect, it } from "vitest";
import { merchantMock } from "@/lib/api/mocks/merchant";
import { merchants } from "./merchant";

// 用例之间会改同一份内存数据，逐个用例复位受影响的行。
const SNAPSHOT = JSON.parse(JSON.stringify(merchants)) as typeof merchants;
beforeEach(() => {
  merchants.length = 0;
  merchants.push(...(JSON.parse(JSON.stringify(SNAPSHOT)) as typeof merchants));
});

describe("列表查询", () => {
  it("默认过滤已归档行", async () => {
    await merchantMock.archiveMerchant("M903");
    const page = await merchantMock.listMerchants({ size: 100 });
    expect(page.records.some((m) => m.merchantNo === "M903")).toBe(false);
  });

  it("showArchived 打开才带出已归档行", async () => {
    await merchantMock.archiveMerchant("M903");
    const page = await merchantMock.listMerchants({ size: 100, showArchived: true });
    expect(page.records.some((m) => m.merchantNo === "M903")).toBe(true);
  });

  it("数据域收敛：带 communityNo 只剩该社区（矩阵 §2.3）", async () => {
    const page = await merchantMock.listMerchants({ size: 100, communityNo: "C001" });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((m) => m.communityNo === "C001")).toBe(true);
  });

  it("关键词命中编号 / 名称 / 联系人", async () => {
    expect((await merchantMock.listMerchants({ keyword: "老张" })).records).toHaveLength(1);
    expect((await merchantMock.listMerchants({ keyword: "m90" })).records.length).toBeGreaterThan(1);
  });

  it("分页口径是 {records,total,page,size}", async () => {
    const page = await merchantMock.listMerchants({ page: 1, size: 2 });
    expect(page.records).toHaveLength(2);
    expect(page.total).toBe(SNAPSHOT.length);
    expect(page).toMatchObject({ page: 1, size: 2 });
  });
});

describe("审核状态机", () => {
  it("合法迁移落库（重新查能读回）", async () => {
    await merchantMock.setMerchantStatus("M901", "REVIEWING");
    expect((await merchantMock.getMerchant("M901")).status).toBe("REVIEWING");
  });

  it("非法迁移抛错（SUBMITTED 不能直接 APPROVED）", async () => {
    await expect(merchantMock.setMerchantStatus("M901", "APPROVED")).rejects.toThrow(/不允许/);
  });

  it("驳回意见落库，商家在 B 端看到的就是这段话", async () => {
    await merchantMock.setMerchantStatus("M901", "REVIEWING");
    await merchantMock.setMerchantStatus("M901", "REJECTED", "营业执照缺页");
    expect((await merchantMock.getMerchant("M901")).auditRemark).toBe("营业执照缺页");
  });

  it("认证标只给已通过审核的商家", async () => {
    await expect(merchantMock.setMerchantVerified("M901", true)).rejects.toThrow(/审核/);
    await merchantMock.setMerchantVerified("M903", true);
    expect((await merchantMock.getMerchant("M903")).verified).toBe(true);
  });

  it("不存在的商家直接抛错，不返回空对象", async () => {
    await expect(merchantMock.getMerchant("M000")).rejects.toThrow(/不存在/);
  });
});

describe("归档", () => {
  it("归档后可恢复，且历史字段保留", async () => {
    const before = await merchantMock.getMerchant("M905");
    await merchantMock.archiveMerchant("M905");
    expect((await merchantMock.getMerchant("M905")).archivedAt).toBeTruthy();
    await merchantMock.unarchiveMerchant("M905");
    const after = await merchantMock.getMerchant("M905");
    expect(after.archivedAt).toBeNull();
    expect(after.breachCount).toBe(before.breachCount);
  });
});
