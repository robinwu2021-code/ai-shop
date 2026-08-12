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

/*
 * 2026-08-12 换形：授权单位从**权限码**变成**功能点**，
 * 且预置角色一律只读（与后端 10440 同一条闸）——
 * 要调整就新建自定义角色。
 */
describe("角色与功能点（P-1.1.2）", () => {
  it("★★ 预置角色一律不可编辑 —— 不只是超管", async () => {
    await expect(iamMock.setRolePoints("SUPER_ADMIN", ["dashboard:overview:read"]))
      .rejects.toThrow(/预置角色/);
    // 客服也是预置的：它是 Perms.java 的镜像，改了会与回落表分叉
    await expect(iamMock.setRolePoints("CS", ["dashboard:overview:read"]))
      .rejects.toThrow(/预置角色/);
  });

  it("自定义角色可建、可改功能点、可删", async () => {
    await iamMock.createRole("TMP_ROLE", "临时角色");
    const r = await iamMock.setRolePoints("TMP_ROLE", ["dashboard:overview:read", "order:order:read"]);
    expect(r.pointCount).toBe(2);
    expect(await iamMock.getRolePoints("TMP_ROLE")).toEqual([
      "dashboard:overview:read", "order:order:read"]);
    await iamMock.removeRole("TMP_ROLE");
    expect((await iamMock.listRoles()).some((x) => x.roleCode === "TMP_ROLE")).toBe(false);
  });

  it("★ 还有人在用的角色不能删 —— 删了他们能登录但什么都点不动", async () => {
    await expect(iamMock.removeRole("CS")).rejects.toThrow();
  });

  it("功能点全集里，后端没做的标 NOT_IMPLEMENTED —— mock 不许比后端好看", async () => {
    const fns = await iamMock.listPermFunctions();
    const pts = fns.flatMap((f) => f.points);
    expect(pts.length).toBeGreaterThan(0);
    // 履约整域后端零实现，它的点必须诚实标出来
    const fulfil = pts.filter((p) => p.uiPermCode?.startsWith("fulfillment:"));
    expect(fulfil.length).toBeGreaterThan(0);
    expect(fulfil.every((p) => p.backendStatus === "NOT_IMPLEMENTED")).toBe(true);
  });
});

describe("审计日志（P-1.1.4）", () => {
  it("写操作会追加审计，且高危操作被标记", async () => {
    const before = auditLogs.length;
    await iamMock.createRole("TMP_AUDIT", "审计用临时角色");
    await iamMock.setRolePoints("TMP_AUDIT", ["dashboard:overview:read", "risk:blacklist:update"]);
    expect(auditLogs.length).toBe(before + 2);   // 建角色 + 改功能点
    expect(auditLogs[0].critical).toBe(true);
    expect(auditLogs[0].detail).toContain("risk:blacklist:update");
    await iamMock.removeRole("TMP_AUDIT");
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
