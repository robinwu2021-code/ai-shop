// 权限矩阵单测。
//
// **这个文件里的 roleHas/roleHasModule 测的是「设计上这个岗位该有什么」**
// （矩阵 §2.3 的角色表），不是运行时判权 —— 判权走 can()，读后端下发的 perms。
//
// 两者分开测，是因为它们会不一致，而**不一致本身就是要看的东西**：
// 角色表说财务能执行分账，而后端的 4 个角色配置里根本没有 FINANCE ——
// 那不是测试该抹平的差异，是一份待办。
import { describe, expect, it } from "vitest";
import { BACKEND_ROLE_PERMS, CRITICAL_PERMS, ROLE_LABEL, backendPermsOf, can, permsOf, roleHas, roleHasModule } from "./permissions";
import { UI_PERM_MAP, UNIMPLEMENTED } from "./perm-map";
import type { Role } from "./auth";

const ALL_ROLES = Object.keys(ROLE_LABEL) as Role[];

describe("通配匹配", () => {
  it("'*' 全通", () => expect(roleHas("SUPER_ADMIN", "anything:at:all")).toBe(true));
  it("模块通配", () => expect(roleHas("RISK", "risk:blacklist:update")).toBe(true));
  it("不跨模块误命中", () => expect(roleHas("RISK", "risky:thing:read")).toBe(false));
  it("无角色一律 false", () => expect(roleHas(undefined, "dashboard:overview:read")).toBe(false));
  it("canModule 认前缀", () => {
    expect(roleHasModule("FINANCE", "finance")).toBe(true);
    expect(roleHasModule("FINANCE", "product")).toBe(false);
  });
});

describe("高危权限的持有者（矩阵 §2.3「高危权限」列）", () => {
  const holders = (code: string) => ALL_ROLES.filter((r) => roleHas(r, code));

  it("分账执行只有超管与财务", () => {
    expect(holders("finance:settle:execute").sort()).toEqual(["FINANCE", "SUPER_ADMIN"]);
  });

  it("提现审批只有超管与财务", () => {
    expect(holders("finance:withdraw:approve").sort()).toEqual(["FINANCE", "SUPER_ADMIN"]);
  });

  it("封禁商家只有超管、BD 与风控", () => {
    expect(holders("merchant:merchant:ban").sort()).toEqual(["MERCHANT_BD", "RISK", "SUPER_ADMIN"]);
  });

  it("授权（角色/数据域）只有超管", () => {
    expect(holders("iam:role:grant")).toEqual(["SUPER_ADMIN"]);
  });

  it("环境切换只有超管与技术运维", () => {
    expect(holders("system:env:switch").sort()).toEqual(["SUPER_ADMIN", "TECH_OPS"]);
  });

  it("每个高危码都有人持有（写错码会静默变成没人能做）", () => {
    for (const c of CRITICAL_PERMS) expect(holders(c).length, `${c} 无人持有`).toBeGreaterThan(0);
  });
});

describe("角色边界（矩阵 §2.3 的关键约束）", () => {
  it("数据分析只读脱敏：没有任何写权限码", () => {
    const writes = permsOf("ANALYST").filter((p) => /:(update|audit|grant|execute|approve|ban|issue|assign|proxy|modify|repair|handle)$/.test(p));
    expect(writes).toEqual([]);
  });

  it("审核员只能驳回/下架，不能封禁商家", () => {
    expect(roleHas("AUDITOR", "product:sku:audit")).toBe(true);
    expect(roleHas("AUDITOR", "merchant:merchant:ban")).toBe(false);
  });

  it("客服能审批阈值内退款，但不能执行分账", () => {
    expect(roleHas("CS", "aftersale:refund:approve")).toBe(true);
    expect(roleHas("CS", "finance:settle:execute")).toBe(false);
  });

  it("商品运营碰不到钱与营销预算", () => {
    expect(roleHas("PRODUCT_OPS", "finance:settle:read")).toBe(false);
    expect(roleHas("PRODUCT_OPS", "marketing:coupon:issue")).toBe(false);
  });

  it("所有角色都能看工作台（否则登录后首页就是空的）", () => {
    for (const r of ALL_ROLES) expect(roleHas(r, "dashboard:overview:read"), r).toBe(true);
  });
});
