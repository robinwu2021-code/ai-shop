// i18n 词条键必须等于枚举取值。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这一条守卫是同一个故障重复四次换来的
// ─────────────────────────────────────────────────────────────────────────────
// 症状每次都一样：**页面上出现 `xxx.YYY` 这样的 i18n 键原样**。
// 成因也每次都一样：词条按端上的叫法建，而渲染时拿到的是另一个值，查不到就回退成键名。
//
//   1. `FULFILLMENT.PICKUP = "PICKUP"` 而库里是 STORE_PICKUP
//      → 确认订单页显示 `fulfillment.STORE_PICKUP`
//   2. `FULFILLMENT.DELIVERY = "DELIVERY"` 而库里是 MERCHANT_DELIVERY
//      → 同一页，同样的显示
//   3. shared 的 AfterSaleStatus 整套与后端不同名
//      → 售后详情页状态永远落进兜底分支
//   4. 状态词归一时把取值 OPEN 改成了 PENDING，而 `riskStatus` 那条词条是单行对象、
//      被批量替换漏掉 → 风控页筛选下拉显示 `riskStatus.PENDING`
//
// 第 4 次是**改的人（我）自己制造的**，而且四层测试全绿 —— 因为测试断言的是
// 类型与逻辑，没有任何一层会去比对「词条键」和「枚举取值」这两个不同文件里的字符串。
// 只有在浏览器里点开那一页才看得见。
//
// 所以这里比对它们。判据：i18n 里的命名空间名 ≈ 枚举类型名（首字母小写），
// 命中的就要求键集合与取值集合完全一致。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/** ops-web 的枚举：类型名 → 取值 */
function opsEnums(): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const dir = join(ROOT, "ops-web/lib/types");
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts") && !x.endsWith(".test.ts"))) {
    // 剥块注释：成员之间夹 JSDoc 的多行联合类型否则匹配不上（这个盲区已经吃过一次）
    const src = readFileSync(join(dir, f), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
    for (const m of src.matchAll(
      /export type (\w+)\s*=\s*((?:\s*\|?\s*"[^"]+"\s*(?:\/\/[^\n]*)?\n?)+);/g,
    )) {
      const values = [...m[2]!.matchAll(/"([^"]+)"/g)].map((x) => x[1]!);
      if (values.length >= 2 && values.every((v) => /^[A-Z][A-Z0-9_]*$/.test(v)))
        out.set(m[1]!, values);
    }
  }
  return out;
}

/** i18n 命名空间 → 键集合。单行与多行对象都要认（漏掉单行的正是第 4 次故障的成因） */
function i18nNamespaces(file: string): Map<string, string[]> {
  const src = readFileSync(join(ROOT, file), "utf8");
  const out = new Map<string, string[]>();
  for (const m of src.matchAll(/^ {2}(\w+): \{([^}]*)\},?$/gms)) {
    const keys = [...m[2]!.matchAll(/(?:^|[{,\s])([A-Z][A-Z0-9_]*)\s*:/g)].map((x) => x[1]!);
    if (keys.length >= 2) out.set(m[1]!, keys);
  }
  return out;
}

describe("i18n 词条键与枚举取值", () => {
  const enums = opsEnums();
  const ns = i18nNamespaces("ops-web/lib/i18n/messages/zh.ts");

  /** 命名空间名 → 枚举类型名：首字母大写。`riskStatus` → `RiskStatus` */
  function typeOf(nsName: string): string {
    return nsName[0]!.toUpperCase() + nsName.slice(1);
  }

  it("同名命名空间的键集合必须与枚举取值完全一致", () => {
    const offenders: string[] = [];
    for (const [name, keys] of ns) {
      const values = enums.get(typeOf(name));
      if (!values) continue; // 不是枚举的词条（页面文案）跳过
      const missing = values.filter((v) => !keys.includes(v));
      const extra = keys.filter((k) => !values.includes(k));
      if (missing.length || extra.length) {
        offenders.push(
          `${name} ↔ ${typeOf(name)}\n` +
            `      枚举有、词条没有：${missing.join(", ") || "—"}  ← **这些会以 ${name}.XXX 的形式打给用户**\n` +
            `      词条有、枚举没有：${extra.join(", ") || "—"}  ← 改名后忘了删的死词条`,
        );
      }
    }
    expect(
      offenders,
      "词条键与枚举取值对不上：\n  " +
        offenders.join("\n  ") +
        "\n\n后果不是报错 —— 是**页面上出现 i18n 键原样**（如 `riskStatus.PENDING`），" +
        "而控制台一条错误都没有。\n" +
        "这个形状本轮已经出现四次，其中一次是改状态词时批量替换漏了单行对象。",
    ).toEqual([]);
  });

  it("中英两份词条的键集合一致（漏译会退回键名，与上面同一个症状）", () => {
    const en = i18nNamespaces("ops-web/lib/i18n/messages/en.ts");
    const gaps: string[] = [];
    for (const [name, keys] of ns) {
      if (!enums.get(typeOf(name))) continue;
      const eKeys = en.get(name) ?? [];
      const missing = keys.filter((k) => !eKeys.includes(k));
      if (missing.length) gaps.push(`${name}: en 缺 ${missing.join(", ")}`);
    }
    expect(gaps, `中英词条不齐：\n  ${gaps.join("\n  ")}`).toEqual([]);
  });
});
