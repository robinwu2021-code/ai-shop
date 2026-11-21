// 菜单外页面的页面级兜底（v2 页面按角色验证：直接打开菜单外的页面，10 个里 7 个是空白页）。
import { describe, expect, it } from "vitest";
import { NAV, routeDenied } from "./nav";

const menu = (...hrefs: string[]) => new Set(hrefs);

describe("routeDenied", () => {
  it("菜单没拿到时不拦", () => {
    expect(routeDenied("/system", "market", null, new Set(), [])).toBe(false);
  });

  it("菜单外、也不持这一页的码：拦", () => {
    expect(routeDenied("/system", "market", null, menu("/orders"), ["order:order:read"])).toBe(true);
  });

  it("菜单里有：放", () => {
    expect(routeDenied("/system", "market", null, menu("/system?tab=market"), [])).toBe(false);
  });

  it("菜单里没有但持有这一页的码（跨页深链）：放", () => {
    expect(routeDenied("/system", "market", null, menu("/orders"), ["system:param:read"])).toBe(false);
  });

  it("不带 tab 的路径：同路径下任一叶子在菜单里就放", () => {
    const anySystemLeaf = NAV.flatMap((s) => s.children ?? []).find((l) => l.href.startsWith("/system?tab="))!;
    expect(routeDenied("/system/", null, null, menu(anySystemLeaf.href), [])).toBe(false);
    expect(routeDenied("/system/", null, null, menu("/orders"), [])).toBe(true);
  });

  it("不是菜单叶子的路径（详情页）：不判", () => {
    expect(routeDenied("/merchants/detail", null, null, menu("/orders"), [])).toBe(false);
  });
});
