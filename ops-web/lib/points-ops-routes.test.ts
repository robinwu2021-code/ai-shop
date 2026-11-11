import { describe, expect, it } from "vitest";
import { findActiveSection, NAV } from "./nav";
import { normalizeLegacyPointsMenu, pointsLegacyRedirect, pointsLegacyServerMenuHref } from "./points-ops-routes";

describe("Points Operations first-group navigation", () => {
  it("keeps the five points pages together as the 积分 group of 会员营销 (2026-11-11 merge)", () => {
    const marketing = NAV.find((section) => section.key === "marketing");

    expect(NAV.find((section) => section.key === "points")).toBeUndefined();
    expect(marketing?.modules).toContain("finance");
    expect(marketing?.children?.filter((leaf) => leaf.group === "积分").map((leaf) => leaf.href)).toEqual([
      "/points/types",
      "/points/earning-rules",
      "/points/budget",
      "/points/accounts",
      "/points/transactions",
    ]);
    // Points stay gated by the finance read code: merging menus must not widen who sees them.
    for (const leaf of marketing?.children ?? []) if (leaf.href.startsWith("/points")) expect(leaf.perm).toBe("finance:settle:read");
    expect(NAV.find((section) => section.key === "finance")?.match ?? []).not.toContain("/points");
  });

  it.each(["/points", "/points/types", "/points/budget", "/points/accounts", "/points/transactions"])(
    "selects 会员营销 for %s",
    (href) => expect(findActiveSection(href)?.key).toBe("marketing"),
  );

  it.each([
    ["/points/types", "/points?tab=configuration"],
    ["/points/budget", "/points?tab=treasury"],
    ["/points/accounts", "/points?tab=queries"],
    ["/points/transactions", "/points?tab=settlements"],
    ["/points/store-access", "/points?tab=overview"],
  ])("uses the deployed legacy permission href %s → %s", (href, serverHref) => {
    expect(pointsLegacyServerMenuHref(href)).toBe(serverHref);
  });

  it.each([
    ["overview", "/points/types"],
    ["configuration", "/points/types"],
    ["treasury", "/points/budget"],
    ["queries", "/points/accounts"],
    ["settlements", "/points/transactions"],
    ["switches", null],
    ["configuration-legacy", null],
    [null, null],
  ] as const)("maps legacy tab %s to %s", (tab, route) => {
    expect(pointsLegacyRedirect(tab)).toBe(route);
  });

  it("adapts the server's existing points menu to the new frontend routes without widening access", () => {
    const overview = { pointCode: "OPS_FINANCE__TAB_POINTS", name: "积分资金看板", groupName: "积分运营",
      href: "/points?tab=overview", uiPermCode: "finance:settle:read", permCode: "finance:settle:read",
      backendStatus: "IMPLEMENTED", uiReady: true, matrixCode: "P-12.1", pointType: "MENU", sort: 50 };
    const legacy = (tab: string, pointCode: string, name: string, sort: number) => ({ ...overview,
      pointCode, name, href: `/points?tab=${tab}`, sort });
    const menu = normalizeLegacyPointsMenu([
      { functionCode: "OPS_FINANCE", name: "结算与资金", icon: "Wallet", href: "/finance", sort: 12,
        points: [overview, legacy("configuration", "OPS_POINTS__TAB_CONFIGURATION", "积分类型与协议", 51),
          legacy("treasury", "OPS_POINTS__TAB_TREASURY", "积分资金工作台", 52),
          legacy("queries", "OPS_POINTS__TAB_QUERIES", "积分账务查询", 53),
          legacy("settlements", "OPS_POINTS__TAB_SETTLEMENTS", "积分账单", 54),
          { ...legacy("payables", "OPS_FINANCE__TAB_PAYABLES", "自营应付账款", 30), href: "/finance?tab=payables" }] },
    ]);

    const finance = menu.find((item) => item.functionCode === "OPS_FINANCE");
    const points = menu.find((item) => item.functionCode === "OPS_POINTS");
    expect(finance?.points.map((point) => point.href)).toEqual(["/finance?tab=payables"]);
    expect(points?.points.map((point) => point.href)).toEqual([
      "/points/types", "/points/budget", "/points/accounts", "/points/transactions",
    ]);
    expect(points?.points.some((point) => point.href === "/points/store-access")).toBe(false);
  });

  it("does not invent a points menu for a user who has no points menu grant", () => {
    expect(normalizeLegacyPointsMenu([{ functionCode: "OPS_FINANCE", name: "结算与资金", sort: 12, points: [] }]))
      .toEqual([{ functionCode: "OPS_FINANCE", name: "结算与资金", sort: 12, points: [] }]);
  });
});
