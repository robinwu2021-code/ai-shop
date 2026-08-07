// 员工与权限规则测试（P-1.1）。这一域的错误代价最高：配错了要么锁死系统，
// 要么让人以为被限制了其实没有。
import { beforeEach, describe, expect, it } from "vitest";
import { iamMock } from "@/lib/api/mocks/iam";
import { permsOf } from "@/lib/permissions";
import { auditLogs, roleDefs, staffs } from "./iam";

const S0 = JSON.parse(JSON.stringify(staffs)) as typeof staffs;
const R0 = JSON.parse(JSON.stringify(roleDefs)) as typeof roleDefs;
const L0 = JSON.parse(JSON.stringify(auditLogs)) as typeof auditLogs;
beforeEach(() => {
  staffs.length = 0; staffs.push(...(JSON.parse(JSON.stringify(S0)) as typeof staffs));
  roleDefs.length = 0; roleDefs.push(...(JSON.parse(JSON.stringify(R0)) as typeof roleDefs));
  auditLogs.length = 0; auditLogs.push(...(JSON.parse(JSON.stringify(L0)) as typeof auditLogs));
});

describe("超管保护（锁死系统是最贵的错误）", () => {
  it("可以停用其中一个超管", async () => {
    const s = await iamMock.setStaffEnabled("E9002", false);
    expect(s.enabled).toBe(false);
  });

  it("不能停用最后一个启用中的超管", async () => {
    await iamMock.setStaffEnabled("E9002", false);
    await expect(iamMock.setStaffEnabled("E9001", false)).rejects.toThrow(/最后一个/);
  });

  it("不能把最后一个超管降级", async () => {
    await iamMock.setStaffEnabled("E9002", false);
    await expect(iamMock.setStaffRole("E9001", "CS")).rejects.toThrow(/最后一个/);
  });
});

describe("数据域授权（P-1.1.3）", () => {
  it("受限角色可以配数据域", async () => {
    const s = await iamMock.setStaffScope("E9003", { communityNo: "C002" });
    expect(s.communityNo).toBe("C002");
  });

  it("给全量角色配数据域直接拒绝 —— 配了不生效，反而让人以为被限制了", async () => {
    await expect(iamMock.setStaffScope("E9005", { communityNo: "C001" })).rejects.toThrow(/全量数据角色/);
  });

  it("换到不需要数据域的角色时，旧 scope 被清掉", async () => {
    const s = await iamMock.setStaffRole("E9003", "CS");
    expect(s.communityNo).toBeUndefined();
  });
});

describe("角色权限（P-1.1.2）", () => {
  it("内置角色（超管）不可编辑", async () => {
    await expect(iamMock.setRolePerms("SUPER_ADMIN", ["dashboard:overview:read"])).rejects.toThrow(/内置角色/);
  });

  it("普通角色可改权限并落库", async () => {
    const r = await iamMock.setRolePerms("CS", ["dashboard:overview:read", "order:order:read"]);
    expect(r.perms).toEqual(["dashboard:overview:read", "order:order:read"]);
  });

  it("⚠️ mock 阶段编辑权限**不改变** can() 的判定（真源是编译期常量）", async () => {
    await iamMock.setRolePerms("CS", ["dashboard:overview:read"]);
    // permsOf 读的是 lib/permissions.ts 的常量，不受 mock 编辑影响。
    // 这条断言是为了锁住这个已知边界：以后有人把 can() 改成读 mock，这里会红，
    // 那时必须同时把页面上的说明改掉，而不是偷偷让两者不一致。
    expect(permsOf("CS").length).toBeGreaterThan(1);
  });
});

describe("审计日志（P-1.1.4）", () => {
  it("写操作会追加审计，且高危操作被标记", async () => {
    const before = auditLogs.length;
    await iamMock.setRolePerms("ANALYST", ["dashboard:overview:read", "risk:blacklist:update"]);
    expect(auditLogs.length).toBe(before + 1);
    expect(auditLogs[0].critical).toBe(true);
    expect(auditLogs[0].detail).toContain("risk:blacklist:update");
  });

  it("非高危操作不打高危标", async () => {
    await iamMock.setStaffScope("E9003", { communityNo: "C003" });
    expect(auditLogs[0].critical).toBe(false);
  });

  it("只看高危的筛选", async () => {
    const page = await iamMock.listAuditLogs({ critical: "1", size: 100 });
    expect(page.records.every((l) => l.critical)).toBe(true);
    expect(page.records.length).toBeGreaterThan(0);
  });

  it("契约里没有删除/修改审计的方法（合规：不可篡改）", () => {
    const keys = Object.keys(iamMock);
    expect(keys.filter((k) => /audit/i.test(k))).toEqual(["listAuditLogs"]);
  });
});
