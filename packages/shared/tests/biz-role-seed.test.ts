// B 端「角色 → 权限码」有**两个源**，这条守卫钉住它们逐条相等。
//
//   · 运行时读**库**：`mch_role.perms`（`BizIdentityResolverImpl` 每请求解析）
//   · 兜底与产物读**代码**：`BizPerms.ROLE_PERMS`
//     —— `BizContext.can()` 在解析不到门店权限时回落它；
//        《B端功能点-权限码-页面》那份矩阵也是从它生成的
//
// 两处不一致的后果分两个方向，都不报错：
//
//   · 库改了、代码没改 → 生成的矩阵文档在说谎（「店长能改商品」而实际不能），
//     而文档正是客服与运营回答「他看得到什么」的依据；
//   · 代码改了、库没改 → 平时无感（走的是库那份），直到解析器回落的那一刻
//     权限**换了一套**。回落路径本就是为「别让所有人一起失权」准备的，
//     它在那一刻给错权限，比直接失权更难查。
//
// 运营端早就有对称的一条（`ops-web/lib/perm-map.test.ts` +《TDD-权限种子一致性守卫》：
// 「库里的角色→后端权限码，必须与 Perms.ROLE_PERMS 逐条相等」）—— B 端一直缺。
//
// **比的是种子 SQL，不是活库**：守卫要能在没有数据库的机器上跑，
// 而且新环境的库正是由这段 SQL 建起来的 —— 它才是「库里应该是什么」的定义。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BIZ_PERMS = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/auth/BizPerms.java");
const SEED = join(ROOT, "backend/shop-app/src/main/resources/db/migration/V71__mch_role.sql");

/** `BizPerms.ROLE_PERMS`：角色码 → 权限码集合 */
function fromJava(): Record<string, Set<string>> {
  const src = readFileSync(BIZ_PERMS, "utf8");
  const at = src.indexOf("ROLE_PERMS = Map.");
  /*
   * 找不到就等于这条守卫静默失效 —— 后面的 matchAll 扫不出任何角色，
   * 漂移恒为空，测试永远绿。写法从 `Map.of` 换成 `Map.ofEntries` 时就会这样。
   */
  expect(at, "在 BizPerms.java 里找不到 ROLE_PERMS —— 写法变了，这条守卫已经查不到东西")
    .toBeGreaterThan(0);
  const block = src.slice(at, src.indexOf("\n\n", at));

  const out: Record<string, Set<string>> = {};
  for (const m of block.matchAll(/(\w+),\s*List\.of\(([^)]*)\)/g)) {
    const role = m[1]!;
    const codes = new Set<string>();
    /*
     * `List.of` 里既有字面量（`"*"`）也有常量引用（`ORDER_VIEW`），都要归一成码本身。
     * `*` 不符合「小写开头」那条，单独认 —— 漏了它会给 OWNER 报一条永远修不掉的漂移。
     */
    for (const lit of m[2]!.matchAll(/"(\*|[a-z][a-z:_-]*)"|\b([A-Z][A-Z_]+)\b/g)) {
      if (lit[1]) {
        codes.add(lit[1]);
      } else if (lit[2]) {
        const c = src.match(new RegExp(`String\\s+${lit[2]}\\s*=\\s*"([^"]+)"`));
        if (c) codes.add(c[1]!);
      }
    }
    out[role] = codes;
  }
  return out;
}

/** V71 种子里 `entity_no='*'` 的那几行：角色码 → 权限码集合 */
function fromSeed(): Record<string, Set<string>> {
  const sql = readFileSync(SEED, "utf8");
  const out: Record<string, Set<string>> = {};
  // 形如 ('*', 'MANAGER', '店长', '["biz:receive",…]', 1, …)
  for (const m of sql.matchAll(/\(\s*'\*'\s*,\s*'(\w+)'\s*,\s*'[^']*'\s*,\s*'(\[[^']*\])'/g)) {
    out[m[1]!] = new Set(JSON.parse(m[2]!) as string[]);
  }
  return out;
}

describe("B 端角色种子", () => {
  const java = fromJava();
  const seed = fromSeed();

  it("两个源都解析得到（正则失效时不要静默通过）", () => {
    expect(Object.keys(java).length, "BizPerms.ROLE_PERMS 解析为空").toBeGreaterThanOrEqual(6);
    expect(Object.keys(seed).length, "V71 种子解析为空").toBeGreaterThanOrEqual(6);
  });

  it("★★★ 预置角色的权限码：代码与种子必须逐条相等", () => {
    const drift: string[] = [];
    for (const role of new Set([...Object.keys(java), ...Object.keys(seed)])) {
      const inJava = java[role];
      const inSeed = seed[role];
      if (!inJava) {
        drift.push(`${role}：只在种子里有，BizPerms 里没有`);
        continue;
      }
      if (!inSeed) {
        drift.push(`${role}：只在 BizPerms 里有，种子里没有 —— 新环境建库后这个角色是零权限`);
        continue;
      }
      const onlyJava = [...inJava].filter((c) => !inSeed.has(c));
      const onlySeed = [...inSeed].filter((c) => !inJava.has(c));
      if (onlyJava.length || onlySeed.length) {
        drift.push(`${role}：代码多 [${onlyJava}]，种子多 [${onlySeed}]`);
      }
    }

    expect(
      drift.sort(),
      "角色→权限码的两个源对不上 ——\n"
        + "  运行时读库（mch_role），而回落路径与生成的矩阵文档读代码（BizPerms.ROLE_PERMS）。\n"
        + "  库改了代码没改：矩阵文档在说谎；代码改了库没改：回落那一刻权限换了一套。\n"
        + "  两个方向都不报错。改一处就要改两处，或者干脆把其中一个源去掉。\n  "
        + drift.join("\n  "),
    ).toEqual([]);
  });

  it("★★ 预置角色都不许带 biz:store:admin —— 授权权不能授出去", () => {
    /*
     * 老板之外任何角色拿到它，就等于能把自己提成老板（改门店、挂收款号、给别人授权）。
     * 自定义角色那一侧后端已经硬拒（`MerchantRoleServiceImpl` 抛 70006），
     * 但**预置角色是直接写进库的，绕过了那道校验** —— 所以这里单独钉一条。
     */
    const offenders = Object.entries(seed)
      .filter(([role, codes]) => role !== "OWNER" && codes.has("biz:store:admin"))
      .map(([role]) => role);
    expect(offenders, "这些预置角色带了 biz:store:admin：" + offenders.join("、")).toEqual([]);
  });
});
