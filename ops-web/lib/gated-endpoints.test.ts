/**
 * 「被运行时开关门着的端点」与「菜单里的 `gated` 登记」必须对得上。
 *
 * 起因（2026-09-09）：`/merchants?tab=chain` 在菜单里是个正常项，点进去 404。
 * 整个进销存域被 `shop.inventory.enabled=false` 关着，关着时**一个 Bean 都不装**、
 * 控制器根本不注册；而这些点在 `sys_function_point` 里标着 IMPLEMENTED
 * （那是源码事实，算不到运行时开关）。运营看到的是一个和别的项一模一样的入口。
 *
 * 设计见 docs/technical/design/ops/TDD-ops-功能开关与菜单状态.md。
 *
 * ⚠️ **这条守的是「开关名的集合」，不是逐页对应。** 后端不知道哪个端点对应哪个前端路由
 * （权限码不行 —— `merchant:merchant:read` 同时出现在被门着的链条画像与没被门着的
 * 商家档案上）。所以这里两向比集合，另外对 `/inventory` 前缀做一条逐页规则。
 * 边界写在明处：**一个新加的、属于某个已知开关域但不在 `/inventory` 前缀下的页面，
 * 这条查不出来**。
 */
import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { NAV } from "./nav";

const BACKEND = join(process.cwd(), "..", "backend");

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) {
      if (e !== "target" && e !== "test") walk(p, out);
    } else if (p.endsWith("Controller.java")) out.push(p);
  }
  return out;
}

/** `@ConditionalOnInventory` 是 `shop.inventory.enabled` 的别名（见那个注解本身） */
const ALIAS: Record<string, string> = { ConditionalOnInventory: "shop.inventory.enabled" };

function gatesOfOpsControllers(): Map<string, string[]> {
  const out = new Map<string, string[]>();
  for (const f of walk(BACKEND)) {
    const src = readFileSync(f, "utf8");
    // 只看运营端：类里出现 /ops/ 路径的才算（B 端、开放平台不走这套菜单）
    if (!/"\/ops\//.test(src)) continue;
    let gate: string | null = null;
    for (const [alias, prop] of Object.entries(ALIAS)) {
      if (new RegExp(`@${alias}\\b`).test(src)) gate = prop;
    }
    const m = src.match(/@ConditionalOnProperty\(([^)]*)\)/);
    if (m) {
      const prefix = (m[1].match(/prefix\s*=\s*"([^"]+)"/) || [])[1];
      const name = (m[1].match(/\bname\s*=\s*"([^"]+)"/) || [])[1];
      const matchIfMissing = /matchIfMissing\s*=\s*true/.test(m[1]);
      // 默认开的开关没有「关着」的态，不需要菜单信号
      if (name && !matchIfMissing) gate = prefix ? `${prefix}.${name}` : name;
    }
    if (gate) out.set(gate, [...(out.get(gate) ?? []), f.split("/").pop()!]);
  }
  return out;
}

const declared = () => {
  const s = new Set<string>();
  for (const sec of NAV) for (const l of sec.children ?? []) if (l.gated) s.add(l.gated);
  return s;
};

describe("运行时开关 ↔ 菜单登记", () => {
  it("后端每个门着运营端端点的开关，菜单里都要有登记 —— 否则整域关掉时界面毫无信号", () => {
    const backend = gatesOfOpsControllers();
    const missing = [...backend.keys()].filter((g) => !declared().has(g))
      .map((g) => `${g}（门着 ${backend.get(g)!.join("、")}）`);
    expect(missing, `在 lib/nav.ts 对应的叶子上加 gated："${missing.join("、")}"`).toEqual([]);
  });

  it("菜单里登记的开关必须真的门着某个运营端端点 —— 陈了的登记会白白灰掉可用的页", () => {
    const backend = gatesOfOpsControllers();
    const stale = [...declared()].filter((g) => !backend.has(g));
    expect(stale, `这些 gated 登记在后端找不到对应的 @ConditionalOnProperty：\n${stale.join("\n")}`)
      .toEqual([]);
  });

  it("`/inventory` 下的每一页都要登记进销存开关 —— 新加一页最容易漏的就是这个", () => {
    const bad: string[] = [];
    for (const sec of NAV) {
      for (const l of sec.children ?? []) {
        if (l.href.startsWith("/inventory") && l.gated !== "shop.inventory.enabled") {
          bad.push(`${l.href}（${l.label}）gated=${l.gated ?? "无"}`);
        }
      }
    }
    expect(bad, `补上 gated: "shop.inventory.enabled"：\n${bad.join("\n")}`).toEqual([]);
  });

  it("分母断言：确实扫到了带开关的运营端控制器", () => {
    const backend = gatesOfOpsControllers();
    const n = [...backend.values()].reduce((a, b) => a + b.length, 0);
    expect(n, "一个带开关的运营端控制器都没扫到 —— 多半是路径或正则坏了").toBeGreaterThan(4);
  });
});
