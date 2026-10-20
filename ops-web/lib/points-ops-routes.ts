import type { MenuFunction, MenuPoint } from "@/lib/types";

/** Temporary URL bridge while points pages move out of the tabbed workspace. */
export function pointsLegacyRedirect(tab: string | null): string | null {
  if (tab === "overview") return "/points";
  if (tab === "configuration") return "/points/types";
  return null;
}

const LEGACY_ROUTES: Record<string, { href: string; name: string; group: string; pointCode: string }> = {
  "/points?tab=overview": { href: "/points", name: "积分总览", group: "配置与接入", pointCode: "OPS_FINANCE__TAB_POINTS" },
  "/points?tab=configuration": { href: "/points/types", name: "积分类型与规则", group: "配置与接入", pointCode: "OPS_POINTS__TAB_CONFIGURATION" },
};
const NEW_POINTS_ROUTES = new Set(["/points", "/points/types", "/points/store-access"]);

/** Existing database menu rows used to authorize the corresponding frontend routes until its migration ships. */
export function pointsLegacyServerMenuHref(href: string): string {
  if (href === "/points") return "/points?tab=overview";
  if (href === "/points/types") return "/points?tab=configuration";
  if (href === "/points/store-access") return "/points?tab=overview";
  return href;
}

/**
 * Adapt the deployed legacy `/ops/menu` response to the points pages shipped by the frontend.
 * The store-access link inherits only the existing overview read grant; the page and its APIs
 * continue to enforce `finance:settle:read` / `finance:settle:execute` independently.
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
    if (!NEW_POINTS_ROUTES.has(href) && !href.startsWith("/points?tab=")) continue;
    normalized.set(href, {
      ...point,
      ...(route ? { pointCode: route.pointCode, name: route.name, groupName: route.group } : {}),
      href,
    });
  }

  const overview = normalized.get("/points");
  if (overview && !normalized.has("/points/store-access")) {
    const previous = normalized.get("/points/types") ?? overview;
    const nextSort = [...normalized.values()]
      .filter((point) => point.href?.startsWith("/points?tab="))
      .reduce<number | undefined>((next, point) => point.sort > previous.sort && (next === undefined || point.sort < next) ? point.sort : next, undefined);
    normalized.set("/points/store-access", {
      ...overview,
      pointCode: "OPS_POINTS__TAB_STORE_ACCESS",
      name: "门店协议与接入",
      groupName: "配置与接入",
      href: "/points/store-access",
      sort: nextSort === undefined ? previous.sort + 1 : (previous.sort + nextSort) / 2,
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
