import type { MenuFunction, MenuPoint } from "@/lib/types";

/** Keep bookmarked legacy tabs working while the four points workspaces are split out. */
export function pointsLegacyRedirect(tab: string | null): string | null {
  if (tab === "overview") return "/points/types";
  if (tab === "configuration") return "/points/types";
  if (tab === "treasury") return "/points/budget";
  if (tab === "queries") return "/points/accounts";
  if (tab === "settlements") return "/points/transactions";
  return null;
}

const LEGACY_ROUTES: Record<string, { href: string; name: string; group: string; pointCode: string }> = {
  "/points?tab=overview": { href: "/points/types", name: "积分设置", group: "积分运营", pointCode: "OPS_FINANCE__TAB_POINTS" },
  "/points?tab=configuration": { href: "/points/types", name: "积分设置", group: "积分运营", pointCode: "OPS_POINTS__TAB_CONFIGURATION" },
  "/points?tab=treasury": { href: "/points/budget", name: "平台预算", group: "积分运营", pointCode: "OPS_POINTS__TAB_TREASURY" },
  "/points?tab=queries": { href: "/points/accounts", name: "积分账户", group: "积分运营", pointCode: "OPS_POINTS__TAB_ACCOUNTS" },
  "/points?tab=settlements": { href: "/points/transactions", name: "发放与核销", group: "积分运营", pointCode: "OPS_POINTS__TAB_TRANSACTIONS" },
};
const NEW_POINTS_ROUTES = new Set(["/points/types", "/points/earning-rules", "/points/budget", "/points/accounts", "/points/transactions"]);

/** Existing database menu rows used to authorize the corresponding frontend routes until its migration ships. */
export function pointsLegacyServerMenuHref(href: string): string {
  if (href === "/points" || href === "/points/types") return "/points?tab=configuration";
  if (href === "/points/budget") return "/points?tab=treasury";
  if (href === "/points/accounts") return "/points?tab=queries";
  if (href === "/points/transactions") return "/points?tab=settlements";
  if (href === "/points/store-access") return "/points?tab=overview";
  return href;
}

/**
 * Adapt the deployed legacy `/ops/menu` response to the four points workspaces. Legacy settings,
 * treasury, query and settlement grants map to settings, budget, accounts and transactions.
 * Store agreements are intentionally hidden: stores participate by default in this release.
 */
export function normalizeLegacyPointsMenu(functions: MenuFunction[]): MenuFunction[] {
  const pointsFunctions = functions.filter((fn) => fn.functionCode === "OPS_POINTS");
  const sources = functions.flatMap((fn) => fn.points ?? [])
    .filter((point) => point.href?.startsWith("/points"));
  if (!sources.length) return functions;

  const normalized = new Map<string, MenuPoint>();
  for (const point of sources) {
    const route = point.href ? LEGACY_ROUTES[point.href] : undefined;
    const href = route?.href ?? point.href!;
    if (!NEW_POINTS_ROUTES.has(href)) continue;
    normalized.set(href, {
      ...point,
      ...(route ? { pointCode: route.pointCode, name: route.name, groupName: route.group } : {}),
      href,
    });
  }

  const existing = pointsFunctions[0];
  const finance = functions.find((fn) => fn.functionCode === "OPS_FINANCE" || fn.href === "/finance");
  const pointFunction: MenuFunction = {
    functionCode: "OPS_POINTS",
    name: existing?.name ?? "积分运营",
    icon: existing?.icon ?? "Coins",
    href: "/points",
    sort: existing?.sort ?? (finance?.sort ?? 0) + 0.1,
    points: [...normalized.values()].sort((a, b) => a.sort - b.sort),
  };
  const rest = functions.map((fn) => {
    if (fn.functionCode === "OPS_POINTS") return undefined;
    if (!fn.points?.some((point) => point.href?.startsWith("/points"))) return fn;
    return { ...fn, points: fn.points.filter((point) => !point.href?.startsWith("/points")) };
  }).filter((fn): fn is MenuFunction => !!fn);
  return [...rest, pointFunction].sort((a, b) => a.sort - b.sort);
}
