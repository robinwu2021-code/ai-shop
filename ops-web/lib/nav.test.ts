// 导航结构单测。这里锁的是**结构约束**，不是文案：
// 文案会随产品走，结构一破（分组不相邻、href 重复、模块与权限码对不上）界面就会出错。
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { tNav } from "./i18n/nav-labels";
import { NAV, activeLeafIndex, breadcrumb, findActiveSection, groupedLeaves, leafParts, normPath, sectionDefaultHref, visibleLeaves, visibleSections } from "./nav";
import { permsOf, ROLE_LABEL } from "./permissions";

const ROOT = new URL("..", import.meta.url).pathname;
import type { Role } from "./auth";

const ALL_ROLES = Object.keys(ROLE_LABEL) as Role[];

describe("导航结构", () => {
  it("section key 与 href 唯一", () => {
    const keys = NAV.map((s) => s.key);
    expect(new Set(keys).size).toBe(keys.length);
    const hrefs = NAV.map((s) => s.href);
    expect(new Set(hrefs).size).toBe(hrefs.length);
  });

  it("叶子 href 在 section 内唯一", () => {
    for (const s of NAV) {
      const hrefs = (s.children ?? []).map((l) => l.href);
      expect(new Set(hrefs).size, `${s.key} 有重复 href`).toBe(hrefs.length);
    }
  });

  it("同一 group 的叶子必须相邻（否则会渲染出两个同名小标题）", () => {
    for (const s of NAV) {
      const segs = groupedLeaves(s.children ?? []);
      const named = segs.map((x) => x.group).filter(Boolean);
      expect(new Set(named).size, `${s.key} 的分组被打散了`).toBe(named.length);
    }
  });

  it("叶子的 href 归属本 section 或显式跨 section（跨链必须指向已存在的 section）", () => {
    const sectionPaths = NAV.map((s) => leafParts(s.href).path);
    for (const s of NAV) {
      for (const l of s.children ?? []) {
        expect(sectionPaths, `${s.key} 的 ${l.href} 指向了不存在的 section`).toContain(leafParts(l.href).path);
      }
    }
  });
});

describe("导航 × 权限", () => {
  it("每个 section 的 module 至少被一个角色持有（否则这个菜单谁都看不见）", () => {
    for (const s of NAV) {
      const someone = ALL_ROLES.some((r) => visibleSections(r).some((x) => x.key === s.key));
      expect(someone, `${s.key}(${s.module}) 对所有角色都不可见`).toBe(true);
    }
  });

  it("每个叶子的 perm 至少被一个角色持有（否则是死入口）", () => {
    const orphans: string[] = [];
    for (const s of NAV) {
      for (const l of s.children ?? []) {
        if (!l.perm) continue;
        const someone = ALL_ROLES.some((r) => visibleLeaves(s, r).some((x) => x.href === l.href && x.label === l.label));
        if (!someone) orphans.push(`${s.key} › ${l.label}(${l.perm})`);
      }
    }
    expect(orphans, `无人可见的叶子：\n${orphans.join("\n")}`).toEqual([]);
  });

  it("叶子的 perm 前缀必须等于所属 section 的 module（跨 section 深链除外）", () => {
    const sectionOf = new Map(NAV.map((s) => [leafParts(s.href).path, s.module]));
    for (const s of NAV) {
      for (const l of s.children ?? []) {
        if (!l.perm) continue;
        // 跨 section 深链（href 指向别的 section）用目标 section 的模块码
        const expected = sectionOf.get(leafParts(l.href).path) ?? s.module;
        expect(l.perm.split(":")[0], `${l.label} 的权限码模块与 section 不一致`).toBe(expected);
      }
    }
  });

  it("未登录（role=undefined）看不到任何 section", () => {
    expect(visibleSections(undefined)).toEqual([]);
  });

  it("角色都至少能进一个 section —— 登录后落到空导航是致命体验", () => {
    for (const r of ALL_ROLES) {
      expect(visibleSections(r).length, `${r} 登录后没有任何菜单`).toBeGreaterThan(0);
      expect(permsOf(r).length).toBeGreaterThan(0);
    }
  });
});

describe("矩阵覆盖率（docs/requirements/需求矩阵-三端.md §六）", () => {
  // 矩阵 §六 的全部 L3 条目。少一条 = 平台端漏了一块需求，多一条 = 编号写错了。
  const MATRIX = [
    "P-1.1", "P-2.1", "P-2.2", "P-3.1", "P-3.2", "P-3.3", "P-4.1", "P-4.2",
    "P-5.1", "P-5.2", "P-6.1", "P-7.1", "P-7.2", "P-7.3", "P-7.4", "P-8.1", "P-8.2",
    "P-9.1", "P-9.2", "P-10.1", "P-11.1", "P-12.1", "P-12.2", "P-13.1", "P-14.1",
    "P-14.2", "P-15.1", "P-15.2", "P-16.1", "P-16.2", "P-17.1",
  ];

  const covered = new Set(NAV.flatMap((s) => (s.children ?? []).map((l) => l.matrix).filter(Boolean) as string[]));

  it("每条矩阵条目都有对应叶子（P-16.1 由无子功能的看板 section 承担）", () => {
    const missing = MATRIX.filter((m) => m !== "P-16.1" && !covered.has(m));
    expect(missing, `矩阵未覆盖：${missing.join(", ")}`).toEqual([]);
  });

  it("不存在矩阵里没有的编号（写错编号比漏掉更难发现）", () => {
    const extra = [...covered].filter((m) => !MATRIX.includes(m));
    expect(extra, `编号不在矩阵中：${extra.join(", ")}`).toEqual([]);
  });
});

describe("待建域", () => {
  // 已交付的域清单。**页面存在才允许可点** —— 静态导出下点一个没有的路由就是 404。
  const BUILT = ["dashboard", "merchant", "order", "community", "fulfillment", "store", "marketing", "review", "aftersale", "group", "product", "finance", "iam", "growth", "risk", "message", "content", "system"];

  it("未交付的 section 必须 soon（否则 Rail 点进去 404）", () => {
    const clickableButUnbuilt = NAV.filter((s) => !s.soon && !BUILT.includes(s.key)).map((s) => s.key);
    expect(clickableButUnbuilt, `这些域还没有页面：${clickableButUnbuilt.join(", ")}`).toEqual([]);
  });

  it("已交付的 section 不该还挂着 soon", () => {
    const builtButGrey = NAV.filter((s) => s.soon && BUILT.includes(s.key)).map((s) => s.key);
    expect(builtButGrey).toEqual([]);
  });
});

describe("dev-only 页面", () => {
  it("/dev/* 不出现在导航里（工具页只能 URL 直达，不该出现在运营的菜单中）", () => {
    const hrefs = NAV.flatMap((s) => [s.href, ...(s.children ?? []).map((l) => l.href)]);
    expect(hrefs.filter((h) => h.startsWith("/dev"))).toEqual([]);
  });
});

describe("路由归属与面包屑", () => {
  it("normPath 去尾斜杠", () => {
    expect(normPath("/merchants/")).toBe("/merchants");
    expect(normPath("/")).toBe("/");
  });

  it('"/" 只精确匹配看板，不被别的 section 前缀吃掉', () => {
    expect(findActiveSection("/", "SUPER_ADMIN")?.key).toBe("dashboard");
  });

  it("最长前缀匹配（子路径归属父 section）", () => {
    expect(findActiveSection("/merchants/detail", "SUPER_ADMIN")?.key).toBe("merchant");
  });

  it("面包屑 = L1 › 分组 › 子功能", () => {
    expect(breadcrumb("/merchants", null, null, "SUPER_ADMIN")).toEqual(["商家治理", "入驻与资质", "入驻审核"]);
  });

  it("section 首页默认高亮首个可点叶子", () => {
    const s = NAV.find((x) => x.key === "merchant")!;
    const leaves = visibleLeaves(s, "SUPER_ADMIN");
    expect(activeLeafIndex(leaves, "/merchants", null, null)).toBe(0);
  });

  it("sectionDefaultHref 永不落到待建/锁定叶子（点 Rail 不该进 404 或灰页）", () => {
    // 通用断言而不是钉死某个 section：叶子的 ready/soon 会随开发进度反复变，
    // 钉死具体 section 的话，每交付一个域就要来改一次测试，改着改着就把断言改软了。
    for (const s of NAV) {
      if (s.soon) continue; // 整域待建：Rail 上本就不可点
      for (const r of ALL_ROLES) {
        const href = sectionDefaultHref(s, r);
        const leaves = visibleLeaves(s, r);
        const hit = leaves.find((l) => l.href === href);
        if (hit) expect(hit.soon, `${s.key} 的默认落地是待建叶 ${hit.label}`).toBeFalsy();
        else expect(href, `${s.key} 的默认落地既不是可点叶也不是 section 首页`).toBe(s.href);
      }
    }
  });
});

it("每个导航标签都有英文译名 —— 漏一条，切到 EN 时菜单里就会夹一行中文", () => {
  const labels = new Set<string>();
  for (const sec of NAV) {
    labels.add(sec.label);
    for (const leaf of sec.children ?? []) {
      labels.add(leaf.label);
      if (leaf.group) labels.add(leaf.group);   // L2 小标题也在屏幕上
    }
  }
  const missing = [...labels].filter((l) => tNav(l, "en") === l);
  expect(missing, `lib/i18n/nav-labels.ts 里补上：\n${missing.join("\n")}`).toEqual([]);
});

it("已解锁的 ?tab= 叶子，页面必须真的认这个 tab —— 否则点了只有面包屑变，内容没变", () => {
  // 实测踩过：nav 里「商家档案」标着 ready 指向 /merchants?tab=list，
  // 而 merchants 页当时根本不读 ?tab，点进去还是入驻审核那张表。
  const offenders: string[] = [];
  for (const sec of NAV) {
    for (const leaf of sec.children ?? []) {
      if (leaf.soon) continue;
      const m = /^\/([\w-]+)\?tab=([\w-]+)$/.exec(leaf.href);
      if (!m) continue;
      const file = join(ROOT, `app/${m[1]}/page.tsx`);
      if (!existsSync(file)) { offenders.push(`${leaf.label} ${leaf.href} ← 页面不存在`); continue; }
      const src = readFileSync(file, "utf8");
      if (!src.includes("usePageTab")) offenders.push(`${leaf.label} ${leaf.href} ← 页面不读 ?tab`);
      else if (!src.includes(`"${m[2]}"`)) offenders.push(`${leaf.label} ${leaf.href} ← TABS 里没有 key=${m[2]}`);
    }
  }
  expect(offenders, `要么把叶子标 soon，要么在页面里加上这个 tab：\n${offenders.join("\n")}`).toEqual([]);
});
