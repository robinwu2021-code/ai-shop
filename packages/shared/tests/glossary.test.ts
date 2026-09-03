// 项目词典与代码的一致性。
//
// 词典的价值全在「说的是真话」。一份写着 `PENDING_PAY` 而代码里是 `WAIT_PAY` 的词典，
// 比没有词典更糟 —— 它会让读的人理直气壮地写错。
//
// 所以这里守两件事：
//   1. 词典里出现的枚举值，代码里必须真的有（防止词典编造/过期）
//   2. 核心状态机的每个值，词典里必须都收录（防止代码加了值而词典漏收）
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const GLOSSARY = readFileSync(join(ROOT, "docs/requirements/项目词典.md"), "utf8");
const TYPES = readFileSync(join(ROOT, "packages/shared/src/types/index.ts"), "utf8");
const CONSTS = readFileSync(
  join(ROOT, "packages/shared/src/utils/constants/index.ts"),
  "utf8",
);

/** 取某个字面量联合类型的值集合 */
function enumValues(name: string): string[] {
  const m = TYPES.match(new RegExp(`export type ${name}\\s*=([^;]+);`, "s"));
  if (!m) throw new Error(`types 里找不到 ${name} —— 类型改名了就同步改本测试与词典`);
  return [...m[1]!.matchAll(/"([^"]+)"/g)].map((x) => x[1]!);
}

/** 取某个常量对象的键集合 */
function constKeys(name: string): string[] {
  const m = CONSTS.match(new RegExp(`export const ${name} = \\{(.*?)\\} as const;`, "s"));
  if (!m) throw new Error(`constants 里找不到 ${name}`);
  return [...m[1]!.matchAll(/^\s*(\w+):/gm)].map((x) => x[1]!);
}

/**
 * 词典里所有反引号包起来的标识符 —— **不含 §11 状态词表**。
 *
 * <p>下面那一检的前提是「词典描述现状，所以词典里的标识符代码里必须有」。
 * 这个前提对 §11 不成立：那张表是**规定**，不是记录 ——
 * `PROCESSING` 是「该用但还没用到」，`DENIED` 是「明确不要用」。
 * 把规定性的内容拿去和现状对账，只会逼着规范去追代码，
 * 而这正是词典改为规定性要终结的那个方向（见词典头部）。
 */
const PRESCRIPTIVE = /## 11\. Status words[\s\S]*?\n(?=## 12\.)/;
const quoted = new Set(
  [...GLOSSARY.replace(PRESCRIPTIVE, "").matchAll(/`([^`]+)`/g)].flatMap((m) =>
    m[1]!.split(/[\s/|]+/),
  ),
);

describe("项目词典", () => {
  // 这几个是**跨端沟通最频繁**的词汇表：状态值说错一个，两端就对不上账
  const MUST_COVER: [string, string[]][] = [
    ["OrderStatus", enumValues("OrderStatus")],
    ["AfterSaleStatus", enumValues("AfterSaleStatus")],
    ["AfterSaleType", enumValues("AfterSaleType")],
    ["MerchantSubject", enumValues("MerchantSubject")],
    ["MerchantTier", enumValues("MerchantTier")],
    ["FULFILLMENT", constKeys("FULFILLMENT")],
    ["CATEGORY_TYPE", constKeys("CATEGORY_TYPE")],
    ["SERVICE_SCOPE", constKeys("SERVICE_SCOPE")],
  ];

  for (const [name, values] of MUST_COVER) {
    it(`${name} 的每个值都在词典里`, () => {
      const missing = values.filter((v) => !GLOSSARY.includes(v));
      expect(
        missing,
        `${name} 新增了值但词典没收录：${missing.join(", ")}\n` +
          "跨端沟通全靠这张表，漏一个就会有人自己造一个名字出来。",
      ).toEqual([]);
    });
  }

  /**
   * 平台端（ops-web）自己那套词汇。词典的「易混淆词」一节会**有意引用**它们
   * （如 `APPLIED` / `MERCHANT_HANDLING`），用来说明两套口径的差别 ——
   * 那不是编造，但也得是真的：对方改了名，词典的对照就失效了。
   */
  function opsVocabulary(): Set<string> {
    const dir = join(ROOT, "ops-web/lib/types");
    const out = new Set<string>();
    if (!existsSync(dir)) return out;
    for (const f of readdirSync(dir)) {
      if (!f.endsWith(".ts") || f.endsWith(".test.ts")) continue;
      for (const m of readFileSync(join(dir, f), "utf8").matchAll(/"([A-Z][A-Z_]+)"/g)) {
        out.add(m[1]!);
      }
    }
    return out;
  }

  it("词典里的状态值都不是编造的", () => {
    // 只查形似枚举值的 token（全大写 + 下划线），避免误伤字段名与路径
    const all = new Set([
      ...MUST_COVER.flatMap(([, v]) => v),
      ...enumValues("ReviewAppealStatus"),
      ...enumValues("MerchantStatus"),
      ...enumValues("MerchantType"),
      "MERCHANT",
      "USER", // PickupPoint.ownerType
      "MERCHANT_OWNED",
      "PLATFORM", // trafficSource
    ]);
    // 常量**组名**（FULFILLMENT / CATEGORY_TYPE…）本身就是词典要解释的对象，不是值
    const groupNames = new Set(
      [...CONSTS.matchAll(/^export const ([A-Z_]+) = \{/gm)].map((m) => m[1]!),
    );
    const ops = opsVocabulary();
    const invented = [...quoted].filter(
      (t) => /^[A-Z][A-Z_]{3,}$/.test(t) && !all.has(t) && !groupNames.has(t) && !ops.has(t),
    );
    expect(
      invented,
      "词典里这些大写标识符在**任何一端**的代码里都不存在（写错了或已改名）：" +
        invented.join(", "),
    ).toEqual([]);
  });
});

describe("后端验收清单与 mock 同步", () => {
  // 这份清单是后端的实现依据（`docs/api/后端验收清单.md`，由 npm run gen:spec 生成）。
  // 它一旦过期就是**有害的**：后端照着实现，结果 mock 早就改了规则 —— 联调时两边都觉得自己对。
  const SPEC = readFileSync(join(ROOT, "docs/api/后端验收清单.md"), "utf8");

  it("订单状态机与代码一致", () => {
    const db = readFileSync(join(ROOT, "packages/shared/src/mock/db.ts"), "utf8");
    const block = db.match(/const TRANSITIONS: Record<OrderStatus, OrderStatus\[\]> = \{(.*?)\n\};/s);
    expect(block, "TRANSITIONS 改名了，生成器也要跟着改").toBeTruthy();
    for (const m of block![1].matchAll(/^\s*(\w+):\s*\[([^\]]*)\]/gm)) {
      const from = m[1]!;
      const tos = [...m[2]!.matchAll(/"(\w+)"/g)].map((x) => x[1]!);
      const row = SPEC.split("\n").find((l) => l.startsWith(`| \`${from}\` |`));
      expect(row, `验收清单缺状态 ${from} —— 跑 npm run gen:spec`).toBeTruthy();
      for (const to of tos) {
        expect(row, `${from} → ${to} 没写进验收清单`).toContain(`\`${to}\``);
      }
    }
  });

  it("拒绝规则条数与 mock 一致", () => {
    const count = (p: string) =>
      [...readFileSync(join(ROOT, p), "utf8").matchAll(/throw new Error\(\s*(`[^`]*`|"[^"]*")\s*\)/g)]
        .map((m) => m[1]!.slice(1, -1).replace(/\$\{[^}]*\}/g, "…"));
    /*
     * B 端替身 2026-09-03 按域拆到了 `b-app/src/api/mocks/`。
     * **整个目录都要数**：只数原路径的话这一端一条都数不到，
     * 而这条断言会「变绿」—— 少数等于没有规则，正是它最不该有的失败方式。
     */
    const countDir = (dir: string) =>
      readdirSync(join(ROOT, dir))
        .filter((f) => f.endsWith(".ts"))
        .flatMap((f) => count(`${dir}/${f}`));
    const all = new Set([
      ...count("packages/shared/src/mock/db.ts"),
      ...countDir("c-app/src/api/mocks"),
      ...countDir("b-app/src/api/mocks"),
    ]);
    const declared = SPEC.match(/mock 里共强制 \*\*(\d+)\*\* 条拒绝规则/);
    expect(declared, "验收清单里找不到条数声明").toBeTruthy();
    expect(
      Number(declared![1]),
      `mock 现有 ${all.size} 条拒绝规则，清单写的是 ${declared![1]} —— 跑 npm run gen:spec`,
    ).toBe(all.size);
  });
});
