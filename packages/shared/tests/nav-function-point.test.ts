// 菜单 ↔ 功能点：两边必须一一对应，且「后端有能力、前端没做页面」的缺口只降不升。
//
// **为什么需要它**：这个对应关系是 `gen-perm-seed.mjs` 生成出来的
// （`nav.ts × perm-map.ts × Perms.java`），生成那一刻是对的。之后：
//
//   · 改 `nav.ts` 加一个菜单项，不重跑生成器 + 不出迁移 → 库里没有这个功能点
//     → **那一项对所有非超管不可见，而没有任何东西报错**
//   · 反过来删一个菜单项 → 库里留下孤儿功能点，配角色时还能勾到，勾了没用
//
// 前者是这个仓库反复出现的形状里最难查的一种：页面在、路由在、代码在，
// 就是看不见 —— 而看不见的原因在另一个仓库层（数据库种子）。
//
// 守卫读**迁移文件**而不是数据库：CI 里没有库，而迁移就是库的真源。
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");

/** 从所有迁移里收 sys_function_point 的 INSERT（V62 建 78 个，V64 补 24 个） */
function seededPoints() {
  const out: { pointCode: string; group: string | null; href: string | null;
               ui: string | null; type: string }[] = [];
  for (const f of readdirSync(MIGRATION_DIR).sort()) {
    if (!f.endsWith(".sql")) continue;
    const sql = readFileSync(join(MIGRATION_DIR, f), "utf8");
    // VALUES 的列序见 V62：point_code, function_code, name, group_name, href,
    //                     ui_perm_code, perm_code, backend_status, ui_ready,
    //                     matrix_code, point_type, sort, ...
    for (const m of sql.matchAll(
      /INSERT INTO sys_function_point \([^)]*\) VALUES \(('[^']*'|NULL), ('[^']*'|NULL), ('[^']*'|NULL), ('[^']*'|NULL), ('[^']*'|NULL), ('[^']*'|NULL), ('[^']*'|NULL), ('[^']*'|NULL), (\d+), ('[^']*'|NULL), ('[^']*'|NULL)/g,
    )) {
      const lit = (v: string) => (v === "NULL" ? null : v.slice(1, -1));
      out.push({
        pointCode: lit(m[1])!,
        group: lit(m[4]),
        href: lit(m[5]),
        ui: lit(m[6]),
        type: lit(m[11])!,
      });
    }
  }
  return out;
}

/** nav.ts 里的叶子（有 href + label 的那些） */
function navLeaves() {
  const src = readFileSync(join(ROOT, "ops-web/lib/nav.ts"), "utf8");
  return [...src.matchAll(/\{\s*href:\s*"([^"]+)",\s*label:/g)].map((m) => m[1]);
}

describe("菜单 ↔ 功能点", () => {
  it("★★★ nav 的每个叶子在库里都有功能点，反之亦然", () => {
    const nav = new Set(navLeaves());
    const seeded = new Set(
      seededPoints().filter((p) => p.type === "MENU" && p.href).map((p) => p.href!),
    );
    expect(seeded.size, "一个功能点都没解析到，迁移里的 INSERT 列序变了？").toBeGreaterThan(50);

    const onlyNav = [...nav].filter((h) => !seeded.has(h)).sort();
    const onlyDb = [...seeded].filter((h) => !nav.has(h)).sort();

    expect(
      { 只在nav里: onlyNav, 只在库里: onlyDb },
      "菜单与库里的功能点对不上了。\n" +
        "  **只在 nav 里** = 加了菜单项但没重跑种子 → 那一项对所有非超管不可见，\n" +
        "     而页面在、路由在、代码在，看不出任何原因。这是最难查的一种。\n" +
        "  **只在库里** = 删了菜单项但库里留着孤儿 → 配角色时还能勾到，勾了没用。\n" +
        "  修：改完 nav.ts 要重跑 `npm run gen:perm-seed`（ops-web）并出一条迁移。",
    ).toEqual({ 只在nav里: [], 只在库里: [] });
  });

  it("★★ 「后端有能力、前端没做页面」的缺口只降不升", () => {
    /*
     * V64 补的 24 个 ACTION 点：后端有端点，而 ops-web 一个入口都没有
     * （保证金、进项发票、行业开关…）。它们不是设计，是缺口。
     *
     * 棘轮而不是断言为零：今天就是 24 个，一次补完不现实。
     * 但**只准变少** —— 否则后端每补一个域、前端不跟，它就悄悄涨一个。
     */
    const BASELINE = 24;
    /*
     * 判据是**分组名**，不是「有没有 href」——
     * 页面内按钮（10 个）与无界面入口（24 个）都没有 href，但性质相反：
     * 前者本来就不该有独立入口，后者是缺口。
     * 第一版拿权限码去比 nav 的 href 集合，两者根本不是一类东西，数出来 34。
     *
     * V67 起库里有 ui_kind 三态列，但它是 UPDATE 出来的、不在 INSERT 里，
     * 所以这里仍按 group_name 解析。**两处口径要一致** ——
     * V67 的 UPDATE 用的就是这个分组名。
     */
    const codes = seededPoints()
      .filter((p) => p.type === "ACTION" && p.group === "无界面入口")
      .map((p) => p.ui!)
      .sort();

    expect(
      codes.length,
      `「后端有能力、前端没入口」的功能点从 ${BASELINE} 变成了 ${codes.length}。\n` +
        `  变多 = 后端又补了一个域而前端没跟，那个能力只有超管能通过接口用。\n` +
        `  变少 = 好事，把 BASELINE 调下来。\n  当前清单：\n    ${codes.join("\n    ")}`,
    ).toBeLessThanOrEqual(BASELINE);
  });
});
