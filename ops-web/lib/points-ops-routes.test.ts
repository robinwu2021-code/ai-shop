import { describe, expect, it } from "vitest";
import { findActiveSection, NAV } from "./nav";
import { normalizeLegacyPointsMenu, pointsLegacyRedirect, pointsLegacyServerMenuHref } from "./points-ops-routes";

describe("Points Operations first-group navigation", () => {
  it("owns the overview and first configuration pages under a separate top-level section", () => {
    const points = NAV.find((section) => section.key === "points");

    expect(points?.href).toBe("/points");
    expect(points?.modules).toEqual(["finance"]);
    expect(points?.children?.map((leaf) => leaf.href)).toEqual([
      "/points",
      "/points/types",
      "/points/store-access",
      "/points?tab=switches",
      "/points?tab=treasury",
      "/points?tab=settlements",
      "/points?tab=queries",
    ]);
    expect(NAV.find((section) => section.key === "finance")?.match ?? []).not.toContain("/points");
  });

  it.each(["/points", "/points/types", "/points/store-access"])(
    "selects the Points Operations section for %s",
    (href) => expect(findActiveSection(href)?.key).toBe("points"),
  );

  it.each([
    ["/points", "/points?tab=overview"],
    ["/points/types", "/points?tab=configuration"],
    ["/points/store-access", "/points?tab=overview"],
  ])("uses the deployed legacy permission href %s → %s", (href, serverHref) => {
    expect(pointsLegacyServerMenuHref(href)).toBe(serverHref);
  });

  it.each([
    ["overview", "/points"],
    ["configuration", "/points/types"],
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
          { ...legacy("payables", "OPS_FINANCE__TAB_PAYABLES", "自营应付账款", 30), href: "/finance?tab=payables" }] },
    ]);

    const finance = menu.find((item) => item.functionCode === "OPS_FINANCE");
    const points = menu.find((item) => item.functionCode === "OPS_POINTS");
    expect(finance?.points.map((point) => point.href)).toEqual(["/finance?tab=payables"]);
    expect(points?.points.map((point) => point.href)).toEqual([
      "/points", "/points/types", "/points/store-access", "/points?tab=treasury",
    ]);
    expect(points?.points.find((point) => point.href === "/points/store-access")).toMatchObject({
      uiPermCode: "finance:settle:read", permCode: "finance:settle:read", backendStatus: "IMPLEMENTED",
    });
  });

  it("does not invent a points menu for a user who has no points menu grant", () => {
    expect(normalizeLegacyPointsMenu([{ functionCode: "OPS_FINANCE", name: "结算与资金", sort: 12, points: [] }]))
      .toEqual([{ functionCode: "OPS_FINANCE", name: "结算与资金", sort: 12, points: [] }]);
  });
});
