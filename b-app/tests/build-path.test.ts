import { describe, expect, it } from "vitest";
import { buildPath, ENDPOINTS } from "@/api/endpoints";

/**
 * 端点表里的路径占位两种写法并存（`:x` 与 `{x}`）。buildPath 只认一种时，另一种会把字面的
 * `{memberNo}` 原样发给后端 —— 会员详情、券详情、发券、活动详情等 20 条接真后端全部打不开，
 * 而 mock 不走路径，演示时一切正常（2026-09-19 真机发现，线上日志 `/biz/activities/%7BactivityNo%7D`）。
 */
describe("buildPath", () => {
  it("两种占位写法都换掉", () => {
    expect(buildPath("/biz/members/{memberNo}", { memberNo: "MB1" })).toBe("/biz/members/MB1");
    expect(buildPath("/biz/period/:periodNo/cutoff", { periodNo: "P1" })).toBe("/biz/period/P1/cutoff");
  });

  it("端点表里每一条的占位都能被换干净（新加端点用哪种写法都不会漏）", () => {
    const bad: string[] = [];
    let withParams = 0;
    for (const [key, ep] of Object.entries(ENDPOINTS)) {
      const names = [...ep.path.matchAll(/\{([a-zA-Z]+)\}|:([a-zA-Z]+)/g)].map((m) => m[1] ?? m[2]!);
      if (!names.length) continue;
      withParams++;
      const params = Object.fromEntries(names.map((n) => [n, "X1"]));
      const out = buildPath(ep.path, params);
      if (/[{}]|:[a-zA-Z]/.test(out)) bad.push(`${key} ${ep.path} → ${out}`);
    }
    // 扫描面自检：表里带占位的端点有几十条，一条都没数到说明正则或表结构变了
    expect(withParams).toBeGreaterThan(20);
    expect(bad).toEqual([]);
  });
});
