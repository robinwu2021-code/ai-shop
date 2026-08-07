// 权限矩阵单测。锁的是**谁能做什么**这条业务规则（需求矩阵 §2.3），不是实现细节。
import { describe, expect, it } from "vitest";
import { CRITICAL_PERMS, ROLE_LABEL, can, canModule, permsOf } from "./permissions";
import type { Role } from "./auth";

const ALL_ROLES = Object.keys(ROLE_LABEL) as Role[];

describe("通配匹配", () => {
  it("'*' 全通", () => expect(can("SUPER_ADMIN", "anything:at:all")).toBe(true));
  it("模块通配", () => expect(can("RISK", "risk:blacklist:update")).toBe(true));
  it("不跨模块误命中", () => expect(can("RISK", "risky:thing:read")).toBe(false));
  it("无角色一律 false", () => expect(can(undefined, "dashboard:overview:read")).toBe(false));
  it("canModule 认前缀", () => {
    expect(canModule("FINANCE", "finance")).toBe(true);
    expect(canModule("FINANCE", "product")).toBe(false);
  });
});

describe("高危权限的持有者（矩阵 §2.3「高危权限」列）", () => {
  const holders = (code: string) => ALL_ROLES.filter((r) => can(r, code));

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
    expect(can("AUDITOR", "product:sku:audit")).toBe(true);
    expect(can("AUDITOR", "merchant:merchant:ban")).toBe(false);
  });

  it("客服能审批阈值内退款，但不能执行分账", () => {
    expect(can("CS", "aftersale:refund:approve")).toBe(true);
    expect(can("CS", "finance:settle:execute")).toBe(false);
  });

  it("商品运营碰不到钱与营销预算", () => {
    expect(can("PRODUCT_OPS", "finance:settle:read")).toBe(false);
    expect(can("PRODUCT_OPS", "marketing:coupon:issue")).toBe(false);
  });

  it("所有角色都能看工作台（否则登录后首页就是空的）", () => {
    for (const r of ALL_ROLES) expect(can(r, "dashboard:overview:read"), r).toBe(true);
  });
});
