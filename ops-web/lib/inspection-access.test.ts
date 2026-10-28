import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { expect, it, vi } from "vitest";
const permissions = vi.hoisted(() => ({ codes: [] as string[] }));
vi.mock("@/lib/use-can", () => ({ useCan: () => (code: string) => permissions.codes.includes(code) }));
vi.mock("@/lib/points-i18n", () => ({ usePointsText: () => ({ t: (_zh: string, en: string) => en }) }));
vi.mock("@/components/ui/tab-header", () => ({ TabHeader: () => null }));
vi.mock("@/lib/api", () => ({ api: {}, IS_MOCK: false }));
import PrintPage from "../app/print/page";
import { ScopedMonitor } from "../app/print/monitor";
import { PointsInspectionLists } from "../app/points/inspection-lists";
import { NAV } from "./nav";
import { can } from "./permissions";
import { readFileSync } from "node:fs";

it("denies direct print and points inspection routes before mounting query hooks", () => {
  permissions.codes = [];
  expect(renderToStaticMarkup(createElement(PrintPage))).toContain("Print monitoring read permission required");
  expect(renderToStaticMarkup(createElement(PointsInspectionLists, { typeCode: "T" }))).toContain("Points ledger read permission required");
});
it("hides the reconciliation form from read-only staff and defaults authorized users to dry run", () => {
  const render = (canResolve: boolean) => renderToStaticMarkup(createElement(QueryClientProvider, { client: new QueryClient() }, createElement(ScopedMonitor, { scope: { tenantNo: "T", entityNo: "E", storeNo: "S" }, canResolve })));
  expect(render(false)).not.toContain('name="reason"');
  const writable = render(true);
  expect(writable).toMatch(/<input[^>]*required=""[^>]*name="reason"/);
  expect(writable).toMatch(/name="dryRun"[^>]*checked=""/);
});
it("registers the live print permission and runtime gate in both navigation and persisted menu", () => {
  const leaf = NAV.flatMap((s) => s.children ?? []).find((l) => l.href === "/print");
  expect(leaf).toMatchObject({ perm: "ops:print:read", gated: "shop.print.enabled" });
  expect(can([], leaf!.perm!)).toBe(false);
  expect(can(["ops:print:read"], leaf!.perm!)).toBe(true);
  const sql = readFileSync(new URL("../../backend/shop-app/src/main/resources/db/migration/V553__ops_print_monitor_menu.sql", import.meta.url), "utf8");
  expect(sql).toContain("'shop.print.enabled'");
  expect(sql).toContain("source.point_code='ACT__PRINT_READ'");
  expect(sql).toContain("existing.tenant_no=source.tenant_no");
});
