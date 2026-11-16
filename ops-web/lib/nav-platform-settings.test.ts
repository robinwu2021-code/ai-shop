import { execFileSync } from "node:child_process";
import actionOwnersBefore from "./__fixtures__/platform-settings-action-owners.json";
import { describe, expect, it } from "vitest";
import before from "./__fixtures__/platform-settings-before.json";
import { BACKEND_ROLE_PERMS } from "./permissions";
import { NAV, findActiveSection, groupedLeaves, overlayNav, visibleLeaves, visibleSections, visibleTabKeys, type NavSection } from "./nav";
import { tNav } from "./i18n/nav-labels";

const MOVED = [
  "/system?tab=storeIndustry", "/system?tab=industry", "/system?tab=authCode", "/system?tab=scope",
  "/messages", "/messages?tab=sms", "/messages?tab=mail", "/messages?tab=wxsub",
  "/messages?tab=apppush", "/messages?tab=inapp", "/messages?tab=routing", "/messages?tab=notifyLog",
  "/inventory?tab=link-health",
];
const REPORTING_HREFS = new Set(["/mall?tab=global-orders", "/mall?tab=global-claims", "/mall?tab=cash-summary", "/mall?tab=cash-details"]);
const LEGACY_ADMISSION_HREFS = new Set(["/merchants?tab=on-behalf", "/merchants?tab=self-operated"]);
const canonicalHrefs = (values: string[]) => [...new Set(values.map(href => LEGACY_ADMISSION_HREFS.has(href) ? "/merchants" : href))].sort();
const settings = () => NAV.find(s => s.key === "iam")!;
const hrefs = (nav: NavSection[], perms: string[], server?: Set<string>) =>
  visibleSections(perms, server, nav).flatMap(s => visibleLeaves(s, perms, server).map(l => l.href)).sort();

describe("platform settings incremental migration", () => {
  it("puts settings first and groups existing configuration without allocating missing pages", () => {
    expect(NAV[0].key).toBe("iam");
    expect(settings().label).toBe("平台设置");
    expect(groupedLeaves(settings().children ?? []).map(g => g.group)).toEqual(["基础配置", "权限管理", "系统维护"]);
    for (const href of MOVED) expect(settings().children?.some(l => l.href === href), href).toBe(true);
    expect(settings().children?.some(l => l.href.startsWith("/pay-ops"))).toBe(false);
  });

  it("preserves leaf identities and gates while consolidating legacy admission destinations", () => {
    const identities = (nav: NavSection[]) => nav.flatMap(s => s.children ?? []).map(l => ({
      href: l.href, perm: l.perm, ready: l.ready, phase: l.phase, soon: l.soon, gated: l.gated, matrix: l.matrix,
    })).sort((a, b) => a.href.localeCompare(b.href));
    expect(identities(before as NavSection[])).toHaveLength(151);
    expect(identities(NAV)).toHaveLength(153);
    const admission = NAV.flatMap(s => s.children ?? []).find(l => l.href === "/merchants")!;
    expect(admission.legacyHrefs).toEqual([...LEGACY_ADMISSION_HREFS]);
    expect(admission.anyPerms).toEqual(["merchant:apply:audit", "merchant:apply:onbehalf", "merchant:selfop:create"]);
    expect(identities(NAV).filter(l => !REPORTING_HREFS.has(l.href))).toEqual(identities(before as NavSection[]).filter(l => !LEGACY_ADMISSION_HREFS.has(l.href)));
    expect(identities(NAV).filter(l => REPORTING_HREFS.has(l.href)).map(l => l.href).sort()).toEqual([...REPORTING_HREFS].sort());
    for (const [role, perms] of Object.entries(BACKEND_ROLE_PERMS)) {
      expect(hrefs(NAV, perms).filter(href => REPORTING_HREFS.has(href))).toEqual(role === "SUPER_ADMIN" ? [...REPORTING_HREFS].sort() : []);
      expect(hrefs(NAV, perms).filter(href => !REPORTING_HREFS.has(href))).toEqual(canonicalHrefs(hrefs(before as NavSection[], perms)));
    }
    expect(hrefs(NAV, [])).toEqual(canonicalHrefs(hrefs(before as NavSection[], [])));
  });

  it("keeps server-menu filtering exact for moved mixed-permission leaves", () => {
    const allowed = new Set(["/system?tab=storeIndustry", "/messages?tab=sms"]);
    expect(hrefs(NAV, [], allowed)).toEqual([...allowed].sort());
    expect(visibleLeaves(settings(), [], allowed).map(l => l.href).sort()).toEqual([...allowed].sort());
    expect(visibleLeaves(settings(), ["system:param:read"]).map(l => l.href)).not.toContain("/system?tab=testPhone");
  });

  it("keeps cross-page tabs in their own module and settings details in settings", () => {
    for (const [path, tab] of [["/system", "storeIndustry"], ["/messages", "sms"], ["/messages", null], ["/inventory", "link-health"]]) {
      expect(findActiveSection(path!, ["*"], undefined, NAV, tab)?.key).toBe("iam");
    }
    expect(findActiveSection("/messages", ["*"], undefined, NAV, "tickets")?.key).toBe("review");
    expect(findActiveSection("/messages", ["*"], undefined, NAV, "broadcast")?.key).toBe("marketing");
    expect(findActiveSection("/inventory", ["*"], undefined, NAV, "ledger")?.key).toBe("inventory");
    expect(visibleTabKeys("/messages", ["channels", "sms", "tickets", "broadcast"], ["message:template:read"]))
      .not.toContain("tickets");
  });

  it("keeps settings grouping and translations when the server overrides presentation", () => {
    const tree = overlayNav(NAV, { sections: { "/iam": { name: "平台设置", sort: 5 } }, leaves: {
      "/messages": { name: "消息配置", group: "系统维护", sort: 130 },
    } });
    expect(tree[0].label).toBe("平台设置");
    expect(tree[0].children?.find(l => l.href === "/messages")?.group).toBe("系统维护");
    for (const label of ["平台设置", "基础配置", "权限管理", "系统维护", "地区配置", "系统参数", "消息配置", "运行监控", "菜单配置"]) {
      expect(tNav(label, "en"), label).not.toMatch(/[\u3400-\u9fff]/);
      expect(tNav(label, "zh-TW"), label).toBeTruthy();
    }
  });
});

it("keeps generated ACTION ownership independent of settings display order", () => {
  const sql = execFileSync("node", ["scripts/gen-perm-seed.mjs"], { encoding: "utf8" });
  const owners: Record<string, string> = {};
  for (const line of sql.split("\n")) if (line.startsWith("INSERT INTO sys_function_point") && line.includes("'ACTION'")) {
    const values = [...line.matchAll(/'([^']*)'/g)].map(m => m[1]);
    owners[values[0]] = values[1];
  }
  expect(owners).toEqual({ ...actionOwnersBefore, ACT__MERCHANT_SELFOP_CREATE: "OPS_MERCHANT", ACT__MERCHANT_APPLY_ONBEHALF: "OPS_MERCHANT" });
});

it.each(["product:sku:read", "message:ticket:read", "inventory:credential:read"])(
  "does not expose an empty settings section to a custom grant %s", permission => {
    expect(visibleLeaves(settings(), [permission])).toEqual([]);
    expect(visibleSections([permission]).some(s => s.key === "iam")).toBe(false);
  },
);
