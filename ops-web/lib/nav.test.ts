// 导航结构单测。这里锁的是**结构约束**，不是文案：
// 文案会随产品走，结构一破（分组不相邻、href 重复、模块与权限码对不上）界面就会出错。
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";
import { tNav } from "./i18n/nav-labels";
import { BACKEND_ROLE_PERMS, backendPermsOf } from "./permissions";
import { NAV, activeLeafIndex, breadcrumb, findActiveSection, groupedLeaves, isLeafDisabled, leafParts, matchesQuery, navSearchEntries, navTabs, normPath, overlayNav, visibleTabKeys, sectionDefaultHref, visibleLeaves, visibleSections } from "./nav";
import { permsOf, ROLE_LABEL } from "./permissions";
import { UI_PERM_MAP } from "./perm-map";

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
  /**
   * 整域后端未实现 —— 这些 section 对**所有人**都不可见，而那是对的：
   * 实测它们打开即 404（docs/technical/运营端死按钮实测清单.md）。
   *
   * 单列成清单而不是放宽断言：后端把某一块做出来之后，
   * 这里要能提醒人把它移走 —— 否则菜单会一直藏着一个已经能用的功能。
   */
  // store 2026-08-12 移走：店招公告审核（/ops/stores/audits）一直是有后端的 ——
  // 三方对齐那份 review 把 P-10.1 整域记成「后端零实现」，那一条是错的。
  // 这条守卫替我把它抓了出来。
  // 2026-08-13：三域后端全部落地（履约 V130–V135 / 风控 V120 / 增长 V121），
  // 从清单里移走。**这个动作是被反向棘轮逼出来的** —— 后端做完却不移，
  // 页面对所有人（含超管）继续隐藏，那正是「有能力没有消费方」的形状。
  const UNBUILT_SECTIONS: string[] = [];

  /**
   * 域已经活了，但**这几个叶子**后端还没有 —— 它们对所有人不可见，也是对的。
   * 与 UNBUILT_SECTIONS 分开列：整域没做和零星缺一两个，
   * 后者才是「域活了、漏了几条」那种最容易变成死按钮的情况
   * （见 packages/shared/tests/ops-endpoint-exists.test.ts 的同名判断）。
   */
  const UNBUILT_LEAVES = [
    "message:faq:update",   // 帮助中心维护
    /*
     * 2026-08-12 权限码细化时**新增**的两条。它们此前映到 marketing:govern ——
     * 一个覆盖不到它们的粗码，于是"看着有人能进"而实际点进去是 mock。
     * 细化之后没有粗码可躲，缺口显式了：content-slots 与会员卡后端都没有。
     */
    "marketing:slot:update",   // 首页楼层与 Banner
    "marketing:member:update", // 会员卡与权益（P-7.4 二期）
    /*
     * store 域从 UNBUILT_SECTIONS 移走之后，它的叶子开始被逐个检查 ——
     * 而后端只有店招公告审核那一条。这两个（主页模板配置 / 获客效果看板共用
     * store:page:read、店铺码导出）确实还没有。
     * **这正是分域清单与分叶子清单要分开的理由**：整域没做和域里缺几条，
     * 后者才是最容易变成死按钮的那种。
     */
    "store:page:read",
    "store:qrcode:export",
  ];

  it("每个 section 的 module 至少被一个角色持有（否则这个菜单谁都看不见）", () => {
    for (const s of NAV) {
      if (UNBUILT_SECTIONS.includes(s.key)) continue;
      const someone = ALL_ROLES.some((r) => visibleSections(backendPermsOf(r)).some((x) => x.key === s.key));
      expect(someone, `${s.key}(${s.module}) 对所有角色都不可见`).toBe(true);
    }
  });

  it("★★ 整域未实现的 section 一旦后端做出来，要从清单里移走", () => {
    const nowVisible = UNBUILT_SECTIONS.filter((key) =>
      ALL_ROLES.some((r) => visibleSections(backendPermsOf(r)).some((x) => x.key === key)));
    expect(
      nowVisible,
      "这些 section 已经有人能看见了 —— 从 UNBUILT_SECTIONS 删掉。\n" +
        "  留着的害处：它是「这块还没做」的证据，而它已经做出来了",
    ).toEqual([]);
  });

  it("每个叶子的 perm 至少被一个角色持有（否则是死入口）", () => {
    const orphans: string[] = [];
    for (const s of NAV) {
      if (UNBUILT_SECTIONS.includes(s.key)) continue;
      for (const l of s.children ?? []) {
        if (!l.perm || UNBUILT_LEAVES.includes(l.perm)) continue;
        const someone = ALL_ROLES.some((r) => visibleLeaves(s, backendPermsOf(r)).some((x) => x.href === l.href && x.label === l.label));
        if (!someone) orphans.push(`${s.key} › ${l.label}(${l.perm})`);
      }
    }
    expect(orphans, `无人可见的叶子：\n${orphans.join("\n")}`).toEqual([]);
  });

  it("叶子的 perm 前缀必须等于所属 section 的 module（跨 section 深链除外）", () => {
    const sectionOf = new Map(NAV.map((s) => [leafParts(s.href).path, s.module]));
    for (const s of NAV) {
      if (UNBUILT_SECTIONS.includes(s.key)) continue;
      for (const l of s.children ?? []) {
        if (!l.perm || UNBUILT_LEAVES.includes(l.perm)) continue;
        // 跨 section 深链（href 指向别的 section）用目标 section 的模块码
        const expected = sectionOf.get(leafParts(l.href).path) ?? s.module;
        expect(l.perm.split(":")[0], `${l.label} 的权限码模块与 section 不一致`).toBe(expected);
      }
    }
  });

  it("未登录（role=undefined）看不到任何 section", () => {
    expect(visibleSections(undefined)).toEqual([]);
  });

  it("★★★ 后端配了权限的角色，登录后至少能进一个 section", () => {
    for (const r of Object.keys(BACKEND_ROLE_PERMS)) {
      expect(visibleSections(backendPermsOf(r as Role)).length,
        `${r} 登录后没有任何菜单`).toBeGreaterThan(0);
    }
  });

  it("★★★ 矩阵里的每个岗位，后端都要有权限配置 —— 空 perms = 空后台", () => {
    /*
     * 这条断言在 2026-08-11 之前是**反过来**写的：它把「7 个角色后端没配」
     * 钉成一份清单，只准变短不准变长。后端补齐之后，清单空了，断言跟着翻面 ——
     * **棘轮走完就要拆掉**，留着一份写死了 7 个名字的期望值，
     * 下次谁删掉一个角色配置反而会红得莫名其妙。
     *
     * 现在守的是另一头：ops-web 的角色（矩阵 §2.3 的岗位）后端必须都配。
     * 漏一个的表现是**静默降级** —— 那个角色登录成功、拿到空 perms、
     * 看到一个空后台，而没有任何报错。
     *
     * 注意「配了」不等于「够用」：风控/技术运维拿到的清单很短，
     * 因为后端那几块还没有端点（见 Perms.java 逐条说明）。
     * 那是功能缺口，由 perm-map 的 UNIMPLEMENTED 与 ops-endpoint-exists 守着，
     * 不归这条管。
     */
    const missing = ALL_ROLES.filter((r) => !(r in BACKEND_ROLE_PERMS)).sort();
    expect(
      missing,
      "这些角色 ops-web 有、后端 Perms.ROLE_PERMS 没配。\n" +
        "  他们登录后 perms 是空的 —— 空后台，且没有任何报错。",
    ).toEqual([]);
  });
});

describe("矩阵覆盖率（docs/requirements/需求矩阵-三端.md §六）", () => {
  // 矩阵 §六 的全部 L3 条目。少一条 = 平台端漏了一块需求，多一条 = 编号写错了。
  const MATRIX = [
    "P-1.1", "P-2.1", "P-2.2", "P-3.1", "P-3.2", "P-3.3", "P-3.4", "P-3.5", "P-3.6", "P-4.1", "P-4.2",
    "P-5.1", "P-5.2", "P-6.1", "P-7.1", "P-7.2", "P-7.3", "P-7.4", "P-8.1", "P-8.2",
    "P-9.1", "P-9.2", "P-10.1", "P-11.1", "P-11.2", "P-12.1", "P-12.2", "P-13.1", "P-14.1",
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
  const BUILT = ["dashboard", "merchant", "order", "community", "fulfillment", "store", "marketing", "review", "aftersale", "group", "product", "finance", "iam", "growth", "risk", "message", "content", "system", "member"];

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
    expect(findActiveSection("/", backendPermsOf("SUPER_ADMIN"))?.key).toBe("dashboard");
  });

  it("最长前缀匹配（子路径归属父 section）", () => {
    expect(findActiveSection("/merchants/detail", backendPermsOf("SUPER_ADMIN"))?.key).toBe("merchant");
  });

  it("★★★ 生成器造得出 Perms.ROLE_PERMS 用到的**每一个**后端码 —— 造不出的授权会静默蒸发", () => {
    /*
     * 2026-08-12 实测的回归：权限码从 16 细化到 68 之后，Perms.java 长出了 22 个
     * UI_PERM_MAP 映射不出来的后端码。角色→功能点是靠 perm_code 对上的 ——
     * 一个后端码没有任何功能点带它，那条授权就**无处安放**，重新生成种子时直接消失。
     * FINANCE 一个角色丢了 9/16，而没有任何报错：表现只是那个角色调某个接口 403。
     *
     * **真的跑一遍生成器再比对**，不查字符串。
     * 第一版这条是 `seed.includes("roleBackendCodes")` —— 把兜底那段的变量名
     * 改成 `XroleBackendCodes` 它照样绿（子串还在）。一条验不出问题的守卫比没有更坏。
     */
    const sql = execFileSync("node", ["scripts/gen-perm-seed.mjs"],
      { cwd: ROOT, encoding: "utf8", maxBuffer: 8 << 20 });

    // point_code → perm_code（第 7 个字段；NULL 表示不受权限约束）
    const permOfPoint = new Map<string, string | null>();
    for (const m of sql.matchAll(
      /INSERT INTO sys_function_point \([^)]*\) VALUES \('([^']+)',(?:\s*(?:'(?:[^']|'')*'|NULL),){5}\s*(?:'([^']*)'|NULL)/g)) {
      permOfPoint.set(m[1], m[2] ?? null);
    }
    const granted = new Map<string, Set<string>>();
    for (const m of sql.matchAll(/INSERT INTO sys_role_point \([^)]*\) VALUES \('([^']+)', '([^']+)'/g)) {
      const perm = permOfPoint.get(m[2]);
      if (perm) (granted.get(m[1]) ?? granted.set(m[1], new Set()).get(m[1])!).add(perm);
    }

    const permsJava = readFileSync(
      join(ROOT, "../backend/shop-base/src/main/java/ai/neargo/shop/auth/Perms.java"), "utf8");
    const consts: Record<string, string> = {};
    for (const m of permsJava.matchAll(/String\s+([A-Z_]+)\s*=\s*"([^"]+)"/g)) consts[m[1]] = m[2];
    const block = permsJava.slice(permsJava.indexOf("ROLE_PERMS = Map."));

    const problems: string[] = [];
    for (const m of block.matchAll(/Map\.entry\("([A-Z_]+)",\s*List\.of\(([\s\S]*?)\)\)/g)) {
      const want = new Set<string>();
      for (const l of m[2].matchAll(/"(\*|[a-z][a-z:_-]*)"|\b([A-Z][A-Z_]+)\b/g)) {
        if (l[1]) want.add(l[1]);
        else if (l[2] && consts[l[2]]) want.add(consts[l[2]]);
      }
      if (want.has("*")) continue;   // 通配角色另有短路，不逐码展开
      const got = granted.get(m[1]) ?? new Set<string>();
      const missing = [...want].filter((c) => !got.has(c));
      if (missing.length) problems.push(`${m[1]} 少了 ${missing.length} 个码：${missing.join(", ")}`);
    }
    expect(problems,
      "生成器造不出这些角色的部分权限码 —— 重新生成种子时这些授权会静默消失：\n"
      + problems.join("\n")
      + "\n补 gen-perm-seed.mjs 末段「按后端码兜底」那一块。").toEqual([]);
  });

  it("★★★ 每个叶子的 perm 必须登记进 UI_PERM_MAP —— 没登记的码 can() 判无权限，连超管都看不见", () => {
    /*
     * 2026-08-12 实测踩过：把「准入与保证金」的 perm 改成看着更贴切的
     * merchant:admission:read，而那个码没登记进 UI_PERM_MAP。
     * can() 是**先查映射再判通配**的（permissions.ts 里解释了为什么），
     * 所以未登记 = 一律无权限，超管也不例外 —— 那个叶子直接从菜单里消失，
     * 而**没有任何报错**，看起来就像「这个功能没做」。
     */
    const missing: string[] = [];
    for (const s of NAV) {
      for (const l of s.children ?? []) {
        if (l.perm && !(l.perm in UI_PERM_MAP)) missing.push(`${s.key} › ${l.label}(${l.perm})`);
      }
    }
    expect(missing, "这些叶子的 perm 没登记进 lib/perm-map.ts 的 UI_PERM_MAP，"
      + "它们对**所有人**（含超管）都不可见：\n" + missing.join("\n")).toEqual([]);
  });

  it("★★★ 每个页面 tab 都能在 nav.ts 找到叶子 —— 找不到 = 这个功能在菜单里进不去", () => {
    /*
     * 2026-08-12 全量核查装的守卫。此前页面在自己的 copy.ts 里另写一份 tab 文案，
     * 与菜单各说各的：38 处文案不一致（菜单「店招公告审核」vs 页面「合规审核」），
     * 外加 6 个页面 tab **压根没在菜单里登记** —— 那几个功能只能靠手改 URL 进去。
     *
     * 文案分岔现在从根上没有了（页面只声明 key，名字回 nav.ts 取），
     * 这条守的是剩下那一半：**新加 tab 时别忘了在菜单里也加一条**。
     */
    const dirs = readdirSync(join(ROOT, "app"), { withFileTypes: true })
      .filter((d) => d.isDirectory()).map((d) => d.name);
    const missing: string[] = [];
    for (const d of dirs) {
      const pageFile = join(ROOT, "app", d, "page.tsx");
      if (!existsSync(pageFile)) continue;
      const m = readFileSync(pageFile, "utf8").match(/const TAB_KEYS = \[([^\]]*)\] as const;/);
      if (!m) continue;
      const keys = [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
      for (const t of navTabs(`/${d}`, keys)) {
        if (!t.label) missing.push(`/${d} 的 tab "${t.key}"`);
      }
    }
    expect(missing, "这些页面 tab 在 lib/nav.ts 里没有叶子 —— 补一条，否则菜单里进不去：\n"
      + missing.join("\n")).toEqual([]);
  });

  describe("服务端菜单覆盖（overlayNav）", () => {
    it("空 overlay 原样返回**同一个引用** —— 否则每次渲染都是新数组，下游全量重渲染", () => {
      expect(overlayNav(NAV, undefined)).toBe(NAV);
      expect(overlayNav(NAV, {})).toBe(NAV);
      expect(overlayNav(NAV, { sections: {}, leaves: {} })).toBe(NAV);
    });

    it("★★★ 库里的名字盖过本地：section 名、叶子名、分组名都跟着变", () => {
      const out = overlayNav(NAV, {
        sections: { "/merchants": { name: "商户中心", icon: "Building" } },
        leaves: { "/merchants?tab=list": { name: "商户名册", group: "档案" } },
      });
      const s = out.find((x) => x.key === "merchant")!;
      expect(s.label).toBe("商户中心");
      expect(s.icon).toBe("Building");
      const leaf = s.children!.find((l) => l.href === "/merchants?tab=list")!;
      expect(leaf.label).toBe("商户名册");
      expect(leaf.group).toBe("档案");
    });

    it("★★★ section 与它的默认叶子 href 相同，两者不能互相盖 —— 扁平表会让分区名变成叶子名", () => {
      // `/merchants` 既是分区首页，也是「入驻审核」这个叶子的 href
      const out = overlayNav(NAV, {
        sections: { "/merchants": { name: "商户中心" } },
        leaves: { "/merchants": { name: "新店审核" } },
      });
      const s = out.find((x) => x.key === "merchant")!;
      expect(s.label).toBe("商户中心");
      expect(s.children!.find((l) => l.href === "/merchants")!.label).toBe("新店审核");
    });

    it("★★ 不覆盖结构 —— href / perm 这些不能被服务端文案改掉", () => {
      const out = overlayNav(NAV, { leaves: { "/merchants?tab=ban": { name: "改个名" } } });
      const leaf = out.find((x) => x.key === "merchant")!
        .children!.find((l) => l.href === "/merchants?tab=ban")!;
      expect(leaf.href).toBe("/merchants?tab=ban");
      expect(leaf.perm).toBe("merchant:merchant:ban");
    });

    it("★★★ 少数项没有 sort 时，其余仍按服务端顺序排 —— 不能整体静默丢弃", () => {
      /*
       * 实测踩过：/ops/menu 只返回**有菜单功能点**的分区，而「经营看板」没有子功能，
       * 于是它不在服务端菜单里。旧规则是「全部兄弟都有 sort 才排」，少这一个，
       * 整个 L1 排序被整体丢弃 —— 运营在配置页点了上移，Rail 纹丝不动，且不报错。
       */
      const sections = NAV.map((s) => s.href);
      const secOv: Record<string, { sort: number }> = {};
      // 除首个（模拟经营看板缺席）外，全部给服务端 sort，且把第 2、3 个对调
      NAV.slice(1).forEach((s, i) => { secOv[s.href] = { sort: (i + 1) * 10 }; });
      secOv[sections[1]].sort = 20;
      secOv[sections[2]].sort = 10;

      const out = overlayNav(NAV, { sections: secOv }).map((s) => s.href);
      expect(out[0], "没有 sort 的首项应当留在原位").toBe(sections[0]);
      expect(out.slice(1, 3), "其余按服务端 sort 重排").toEqual([sections[2], sections[1]]);
    });

    it("★★ 一个 sort 都没有时原样返回 —— 服务端菜单没到手就别动顺序", () => {
      expect(overlayNav(NAV, { sections: { [NAV[0].href]: { name: "x" } } })
        .map((s) => s.href)).toEqual(NAV.map((s) => s.href));
    });

    it("★★ 叶子层同理：缺 sort 的跟着前一项走", () => {
      const s = NAV.find((x) => x.key === "store")!;
      const hrefs = s.children!.map((l) => l.href);
      const partial = overlayNav(NAV, { leaves: { [hrefs[1]]: { sort: 1 } } })
        .find((x) => x.key === "store")!.children!.map((l) => l.href);
      expect(partial).toEqual(hrefs);
      const all: Record<string, { sort: number }> = {};
      hrefs.forEach((h, i) => { all[h] = { sort: hrefs.length - i }; });
      const sorted = overlayNav(NAV, { leaves: all })
        .find((x) => x.key === "store")!.children!.map((l) => l.href);
      expect(sorted).toEqual([...hrefs].reverse());
    });

    it("★★ 覆盖后的树能被 breadcrumb / navTabs 读到 —— 换源没换到位就会在这里露馅", () => {
      // 用一个**所有角色都可见**的叶子：store 的几个 tab 后端未实现，
      // visibleLeaves 会把它们滤掉，拿它做断言只会验到「看不见」
      const nav = overlayNav(NAV, { leaves: { "/merchants?tab=list": { name: "商户名册" } } });
      expect(navTabs("/merchants", ["audit", "list"], nav).map((t) => t.label))
        .toEqual(["入驻审核", "商户名册"]);
      expect(breadcrumb("/merchants", "list", null, backendPermsOf("SUPER_ADMIN"), undefined, nav))
        .toContain("商户名册");
    });
  });

  describe("tab 判权（visibleTabKeys）", () => {
    const KEYS = ["audit", "list", "categories", "admission", "verify", "credit", "ban"];

    it("★★★ 与菜单同一口径 —— 服务端菜单里没有的 tab 也看不到", () => {
      // 只授权了两条：tab 也只该剩这两条
      const hrefs = new Set(["/merchants", "/merchants?tab=list"]);
      expect(visibleTabKeys("/merchants", KEYS, ["*"], hrefs)).toEqual(["audit", "list"]);
    });

    it("★★★ 全站遍历：tab 集合永远 ⊆ 菜单可见集合，且确实发生过收窄", () => {
      /*
       * 不手挑角色举例 —— 挑错了会写出假测试：第一版拿 MERCHANT_BD 当「没有封禁权」
       * 的反例，而商家运营**本来就有** merchant:merchant:ban。
       * 遍历全站则不依赖任何一条人工判断。
       */
      let narrowed = 0;
      for (const sec of NAV) {
        const leaves = sec.children ?? [];
        if (!leaves.length) continue;
        const path = leafParts(sec.href).path;
        // 只取**落在本 section 路径上**的叶子：nav 允许跨 section 深链
        // （售后域里挂着 /finance?tab=refund-back），那种不是本页的 tab
        const own = leaves.filter((l) => leafParts(l.href).path === path);
        const keys = own.map((l, i) => (i === 0 ? "__first" : leafParts(l.href).tab)).filter(Boolean) as string[];
        if (keys.length < 2) continue;
        for (const r of ALL_ROLES) {
          const perms = backendPermsOf(r);
          const visible = new Set(visibleLeaves(sec, perms)
            .filter((l) => leafParts(l.href).path === path).map((l) => l.href));
          if (visible.size === 0) continue;   // 走「只留默认 tab」兜底，另一条测试管
          for (const k of visibleTabKeys(path, keys, perms, undefined)) {
            const href = k === "__first" ? sec.href : `${path}?tab=${k}`;
            expect(visible.has(href), `${r} 在 ${sec.key}：tab「${k}」菜单里不可见却出现在 tab 条`).toBe(true);
          }
          if (visible.size < keys.length) narrowed++;
        }
      }
      expect(narrowed, "全站没有任何一处发生收窄 —— 过滤根本没生效").toBeGreaterThan(0);
    });

    it("★★ 判不了就不判 —— perms 与 serverHrefs 都为空时原样返回", () => {
      // can(undefined, x) 恒 false，不特判的话整条 tab 会凭空消失
      expect(visibleTabKeys("/merchants", KEYS, undefined, undefined)).toEqual(KEYS);
      expect(visibleTabKeys("/merchants", KEYS, [], new Set())).toEqual(KEYS);
    });

    it("★★★ 一个都不剩时只留默认那一个 —— 不给白页，也不把无权的功能名摊出来", () => {
      // 授权集合与本页毫不相干 → 全部落空
      expect(visibleTabKeys("/merchants", KEYS, ["*"], new Set(["/orders"]))).toEqual(["audit"]);
      // 零 merchant 权限的角色直连 URL 进来，同样只看到默认 tab
      const cs = backendPermsOf("CS");
      expect(visibleTabKeys("/merchants", KEYS, cs, undefined).length).toBeLessThanOrEqual(1);
    });

    it("★★ 超管看得到全部 tab —— 收紧不能误伤有权的人", () => {
      const admin = backendPermsOf("SUPER_ADMIN");
      expect(visibleTabKeys("/merchants", KEYS, admin, undefined)).toEqual(KEYS);
    });

    it("★★ 与 visibleLeaves 结果一致 —— 两边分岔就是这条守卫存在的理由", () => {
      const s = NAV.find((x) => x.key === "merchant")!;
      for (const r of ALL_ROLES) {
        const perms = backendPermsOf(r);
        const leafHrefs = new Set(visibleLeaves(s, perms).map((l) => l.href));
        // 一条都看不见的角色走的是「只留默认 tab」那条兜底，不在本条断言范围内
        if (leafHrefs.size === 0) continue;
        for (const k of visibleTabKeys("/merchants", KEYS, perms, undefined)) {
          const href = k === "audit" ? "/merchants" : `/merchants?tab=${k}`;
          expect(leafHrefs.has(href), `${r} 的 tab ${k} 在菜单里不可见，却出现在 tab 条`).toBe(true);
        }
      }
    });
  });

  it("面包屑 = L1 › 分组 › 子功能", () => {
    expect(breadcrumb("/merchants", null, null, backendPermsOf("SUPER_ADMIN"))).toEqual(["商家治理", "入驻与资质", "入驻审核"]);
  });

  it("⌘K 候选：排除待建/锁定叶 —— 面板是「去某处」，列出去不了的只是噪音", () => {
    for (const r of ALL_ROLES) {
      const perms = backendPermsOf(r);
      const entries = navSearchEntries(perms);
      const byHref = new Map(NAV.flatMap((s) => (s.children ?? []).map((l) => [l.href, l] as const)));
      for (const e of entries) {
        if (e.isSection) continue;
        const leaf = byHref.get(e.href);
        expect(leaf && isLeafDisabled(leaf), `${e.label} 是待建/锁定叶却进了搜索候选`).toBeFalsy();
      }
    }
  });

  it("⌘K 候选：每个可见 section 都能被搜到，且落地页可点", () => {
    const perms = backendPermsOf("SUPER_ADMIN");
    const entries = navSearchEntries(perms);
    const sections = entries.filter((e) => e.isSection);
    // 待建整域不该出现（点进去是 404）
    for (const e of sections) {
      const s = NAV.find((x) => x.label === e.label)!;
      expect(s.soon, `${s.key} 整域待建却进了搜索候选`).toBeFalsy();
    }
    expect(sections.map((e) => e.label)).toContain("商家治理");
    expect(entries.find((e) => e.label === "商家档案")?.href).toBe("/merchants?tab=list");
  });

  it("⌘K 匹配：空格分词，词序无关；不匹配则落空", () => {
    expect(matchesQuery("商家治理 入驻与资质 商家档案", "商家 档案")).toBe(true);
    expect(matchesQuery("商家治理 入驻与资质 商家档案", "档案 商家")).toBe(true);
    expect(matchesQuery("Merchants Merchant profiles", "merchant PROFILE")).toBe(true);
    expect(matchesQuery("商家治理 商家档案", "订单")).toBe(false);
    expect(matchesQuery("任意串", "   ")).toBe(true); // 空查询 = 不过滤
  });

  it("section 首页默认高亮首个可点叶子", () => {
    const s = NAV.find((x) => x.key === "merchant")!;
    const leaves = visibleLeaves(s, backendPermsOf("SUPER_ADMIN"));
    expect(activeLeafIndex(leaves, "/merchants", null, null)).toBe(0);
  });

  it("sectionDefaultHref 永不落到待建/锁定叶子（点 Rail 不该进 404 或灰页）", () => {
    // 通用断言而不是钉死某个 section：叶子的 ready/soon 会随开发进度反复变，
    // 钉死具体 section 的话，每交付一个域就要来改一次测试，改着改着就把断言改软了。
    for (const s of NAV) {
      if (s.soon) continue; // 整域待建：Rail 上本就不可点
      for (const r of ALL_ROLES) {
        const href = sectionDefaultHref(s, backendPermsOf(r));
        const leaves = visibleLeaves(s, backendPermsOf(r));
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
