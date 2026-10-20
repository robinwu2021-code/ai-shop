import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { findActiveSection, NAV } from "./nav";
import { pointsLegacyRedirect } from "./points-ops-routes";

const MIGRATIONS = join(new URL("..", import.meta.url).pathname, "../backend/shop-app/src/main/resources/db/migration");

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
    ["overview", "/points"],
    ["configuration", "/points/types"],
    ["switches", null],
    ["configuration-legacy", null],
    [null, null],
  ] as const)("maps legacy tab %s to %s", (tab, route) => {
    expect(pointsLegacyRedirect(tab)).toBe(route);
  });

  it("registers the new menu routes under their live permission points", () => {
    const sql = readFileSync(join(MIGRATIONS, "V529__points_ops_first_pages_menu.sql"), "utf8");
    expect(sql).toContain("OPS_POINTS");
    expect(sql).toContain("'/points/types'");
    expect(sql).toContain("'/points/store-access'");
    expect(sql).toContain("finance:settle:read");
    expect(sql).toContain("source.point_code='OPS_FINANCE__TAB_POINTS'");
  });
});
