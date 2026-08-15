// mock 落库与状态机单测。mock 不是"随便返点数据"：它是后端行为的**前端替身**，
// 状态机与落库不真实，页面就会写出后端根本不允许的流程（架构 §10.5）。
import { beforeEach, describe, expect, it } from "vitest";
import { merchantMock } from "@/lib/api/mocks/merchant";
import { merchants } from "./merchant";
import { stores } from "./store";

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
    // 一家店可以服务多个社区 —— 筛选命中的是「包含该社区」
    expect(page.records.every((m) => m.communityNos.includes("C001"))).toBe(true);
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

describe("经营状态机（不是审核状态机）", () => {
  /*
   * 商家档案上的 status 是**经营状态**：ACTIVE / SUSPENDED / FROZEN。
   * 审核状态（PENDING/REVIEWING/APPROVED/REJECTED）在**申请单**上。
   *
   * 这一组用例此前测的是审核迁移（SUBMITTED→REVIEWING→APPROVED），
   * 建在一个把两件事揉进一个字段的模型上 —— 而「已在经营、又提交了第二张
   * 执照」的商家在那个模型里 status 无解，那正是「一人多主体」的常见情形。
   */
  it("封禁与解封是一对：ACTIVE ⇄ SUSPENDED", async () => {
    await merchantMock.setMerchantStatus("M901", "SUSPENDED", "售假处罚");
    expect((await merchantMock.getMerchant("M901")).status).toBe("SUSPENDED");
    await merchantMock.setMerchantStatus("M901", "ACTIVE", "整改完成");
    expect((await merchantMock.getMerchant("M901")).status).toBe("ACTIVE");
  });

  it("非法迁移抛错（封禁中不能直接冻结 —— 两者是不同性质的处置）", async () => {
    await merchantMock.setMerchantStatus("M901", "SUSPENDED", "售假处罚");
    await expect(merchantMock.setMerchantStatus("M901", "FROZEN")).rejects.toThrow(/不允许/);
  });

  it("处置意见落库，商家在 B 端看到的就是这段话", async () => {
    await merchantMock.setMerchantStatus("M901", "SUSPENDED", "营业执照缺页");
    expect((await merchantMock.getMerchant("M901")).auditRemark).toBe("营业执照缺页");
  });

  it("认证标只给正常经营中的商家 —— 封禁中的店挂着平台背书，赔的是平台信用", async () => {
    await merchantMock.setMerchantStatus("M901", "SUSPENDED", "售假处罚");
    await expect(merchantMock.setMerchantVerified("M901", true)).rejects.toThrow(/正常经营/);
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

describe("门店级违规处置（STORE_OFFLINE，P-11.2）", () => {
  const S0 = JSON.parse(JSON.stringify(stores)) as typeof stores;
  beforeEach(() => {
    stores.length = 0; stores.push(...(JSON.parse(JSON.stringify(S0)) as typeof stores));
  });

  const base = { merchantNo: "M901", type: "SERVICE" as const, detail: "多次超时未发货，见工单 T-9001" };

  it("门店强制下线必须指定门店", async () => {
    await expect(merchantMock.recordViolation({ ...base, action: "STORE_OFFLINE" })).rejects.toThrow(/门店/);
  });

  it("★ 只有门店级动作能带门店号 —— 主体级处置带上它会被读成「只压了那一家」", async () => {
    await expect(
      merchantMock.recordViolation({ ...base, action: "WARN", storeNo: "ST001" }),
    ).rejects.toThrow(/门店/);
  });

  it("不能压别人家的店", async () => {
    await expect(
      merchantMock.recordViolation({ ...base, action: "STORE_OFFLINE", storeNo: "ST004" }),
    ).rejects.toThrow(/不属于/);
  });

  it("★ 处置与压下是同一次提交：记录落库，门店状态真的变 SUSPENDED", async () => {
    const v = await merchantMock.recordViolation({ ...base, action: "STORE_OFFLINE", storeNo: "ST002" });
    expect(v.storeNo).toBe("ST002");
    expect(stores.find((s) => s.storeNo === "ST002")!.status).toBe("SUSPENDED");
  });

  it("已下线的店不重复压 —— 静默重复会在信用档案里堆出一串同样的记录", async () => {
    await expect(
      merchantMock.recordViolation({ ...base, merchantNo: "M906", action: "STORE_OFFLINE", storeNo: "ST003" }),
    ).rejects.toThrow(/已被强制下线/);
  });
});
