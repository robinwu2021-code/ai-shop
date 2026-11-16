import { execFileSync } from "node:child_process";
import { describe, expect, it } from "vitest";
import { NAV, findActiveSection, groupedLeaves, visibleLeaves, visibleSections } from "./nav";
import { tNav } from "./i18n/nav-labels";

const REPORTING_HREFS = new Set(["/mall?tab=global-orders", "/mall?tab=global-claims", "/mall?tab=cash-summary", "/mall?tab=cash-details"]);
const mall = () => NAV.find(s => s.key === "commerce")!;
describe("platform mall incremental regrouping", () => {
  it("places platform sales second with existing model and operation groups", () => {
    expect(NAV.slice(0, 2).map(s => s.key)).toEqual(["iam", "commerce"]);
    expect(mall().label).toBe("平台商城");
    expect(mall().children?.filter(l => !REPORTING_HREFS.has(l.href))).toHaveLength(10);
    expect(mall().children).toHaveLength(14);
    expect(groupedLeaves(mall().children ?? []).map(g => g.group)).toEqual(["套餐方案", "商品目录", "权益管理", "商城订单", "收入统计", "销售设置"]);
    expect(mall().children?.find(l => l.href === "/merchants?tab=plans")?.label).toBe("主体套餐");
    expect(mall().children?.find(l => l.href === "/mall")?.label).toBe("门店套餐");
  });
  it("keeps entity/store purchases separate from operating and payment orders", () => {
    expect(findActiveSection("/merchants", ["*"], undefined, NAV, "plans")?.key).toBe("commerce");
    expect(findActiveSection("/merchants", ["*"], undefined, NAV, "plan-defs")?.key).toBe("commerce");
    expect(findActiveSection("/merchants", ["*"], undefined, NAV, "list")?.key).toBe("merchant");
    expect(findActiveSection("/orders")?.key).toBe("order");
    expect(mall().children?.some(l => l.href.startsWith("/pay-ops") || l.href.startsWith("/finance"))).toBe(false);
  });
  it("uses server point grants without widening access across both models", () => {
    const server = new Set(["/merchants?tab=plans", "/mall?tab=orders"]);
    expect(visibleLeaves(mall(), [], server).map(l => l.href).sort()).toEqual([...server].sort());
    expect(visibleLeaves(mall(), ["commerce:mall:order:read"]).map(l => l.href)).toEqual(["/mall?tab=orders"]);
    expect(visibleLeaves(mall(), ["merchant:merchant:read"]).map(l => l.href).sort()).toEqual(["/merchants?tab=plan-defs", "/merchants?tab=plans"]);
  });
  it.each(["merchant:apply:audit", "commerce:catalog:edit", "commerce:mall:offline:confirm"])("does not show an empty mall to a custom action-only grant %s", permission => {
    expect(visibleLeaves(mall(), [permission])).toEqual([]);
    expect(visibleSections([permission]).some(s => s.key === "commerce")).toBe(false);
  });
  it("keeps short translated labels for the regrouped sales entries", () => {
    for (const label of [mall().label, ...(mall().children ?? []).flatMap(l => [l.label, l.group!])]) {
      expect([...label].length).toBeLessThanOrEqual(4);
      expect(tNav(label, "en")).not.toMatch(/[\u3400-\u9fff]/);
      expect(tNav(label, "zh-TW")).toBeTruthy();
    }
  });
});

 it("shows reporting leaves only for their explicit read grants", () => {
   for (const [permission, hrefs] of [
     ["commerce:platform:orders:read", ["/mall?tab=global-orders"]],
     ["commerce:platform:claims:read", ["/mall?tab=global-claims"]],
     ["commerce:platform:cash:read", ["/mall?tab=cash-summary", "/mall?tab=cash-details"]],
   ] as const) expect(visibleLeaves(mall(), [permission]).map(l => l.href)).toEqual(hrefs);
   expect(visibleLeaves(mall(), ["commerce:mall:offline:confirm"]).some(l => REPORTING_HREFS.has(l.href))).toBe(false);
 });

it("generates the four new menu grants for SUPER_ADMIN only", () => {
  const sql = execFileSync("node", ["scripts/gen-perm-seed.mjs"], { encoding: "utf8" });
  const codes = ["GLOBAL_ORDERS", "GLOBAL_CLAIMS", "CASH_SUMMARY", "CASH_DETAILS"];
  for (const code of codes) {
    const point = `OPS_COMMERCE__TAB_${code}`;
    const grants = sql.split("\n").filter(line => line.startsWith("INSERT INTO sys_role_point") && line.includes(`'${point}'`));
    expect(grants).toHaveLength(1);
    expect(grants[0]).toContain("'SUPER_ADMIN'");
  }
});
