// 后端枚举值当 i18n key 用时，词条里必须真的有那一条。
//
// **与同目录的 `i18n-enum-keys.test.ts` 是两件事**：那一份比的是 ops-web 的
// TS 联合类型 ↔ ops-web 词条；这一份比的是 b 端词条 ↔ 后端 Java 枚举。
// 两份是同一天各自独立写出来的 —— 同一个症状（页面上出现 `xxx.YYY` 原样）
// 在两个端各咬了一次。
//
// **来历（2026-08-28）**：库存明细上每一行变动都显示成 `stock.reason.INIT` ——
// 一个程序标识符。后端的 `InvEnums.InboundSource.INIT` 是搬运落的期初单，
// 而端上那组 `stock.reason.*` 有 13 个键，独独没有 INIT（也没有 CHECK）。
//
// **为什么前面几道都管不到**：
// - `i18n-parity`：三种语言里都缺，缺得整整齐齐，所以它一致、它绿
// - `i18n-keys-exist`：它扫的是**写死的** `t("x.y")`；这里是
//   ``$t(`stock.reason.${r.reasonCode}`)`` —— 拼出来的，静态扫描看不见
// - `check-enums.mjs`：它比的是**反方向**（端上的字面量后端认不认），
//   而这里是后端有、端上没有
//
// 这是「守卫在、但这一类恰好从它眼皮底下过」的第八种：**动态拼的 key**。
//
// ⚠️ **覆盖范围是有限的，而且这份有限是明写出来的**：全仓有 20 处动态 key
// 前缀（`fulfillment.` / `members.level` / `store.ttl` …），本文件只登记了
// 取值域在 `InvEnums.java` 里的那几处。其余的取值域散在各端的 TS 联合类型里，
// 要另做一份登记 —— 没做之前它们仍然是裸的，别把这道闸当成全覆盖。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const INV_ENUMS = join(
  ROOT,
  "backend/shop-inventory/src/main/java/ai/neargo/shop/inventory/support/InvEnums.java",
);
const LOCALES = ["zh-CN", "en", "ar"] as const;

/** 从 InvEnums.java 里取某个内部类的全部常量值。 */
function valuesOf(java: string, className: string): string[] {
  const cls = new RegExp(
    `class\\s+${className}\\s*\\{([\\s\\S]*?)\\n\\s{4}\\}`,
  ).exec(java);
  if (!cls) throw new Error(`InvEnums.java 里找不到 ${className} —— 类改名了？`);
  return [...cls[1]!.matchAll(/String\s+\w+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1]!);
}

/**
 * i18n 前缀 → 它的取值域来自哪几个后端枚举。
 *
 * 新加一处 ``$t(`前缀.${变量}`)`` 时要在这里登记，否则那一处又是裸的。
 */
const REGISTRY: Array<[string, string[]]> = [
  ["stock.reason", ["InboundSource", "OutboundPurpose", "Reason"]],
  ["stockDocs.status", ["DocStatus", "TransferStatus"]],
  ["transfer.status", ["TransferStatus"]],
];

function localeKeys(locale: string): Set<string> {
  const src = readFileSync(join(ROOT, "b-app/src/i18n/locale", `${locale}.ts`), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");
  const out = new Set<string>();
  const stack: Array<[number, string]> = [];
  let depth = 0;
  for (const line of src.split("\n")) {
    for (const m of line.matchAll(/([A-Za-z_]\w*)\s*:\s*\{/g)) stack.push([depth, m[1]!]);
    for (const m of line.matchAll(/([A-Za-z_]\w*)\s*:\s*(?!\{)(\S|$)/g)) {
      const nxt = m[2] ?? "";
      if (nxt === "" || `"'\``.includes(nxt) || line.trimEnd().endsWith(":")) {
        out.add([...stack.map(([, n]) => n), m[1]!].join("."));
      }
    }
    depth += (line.match(/\{/g) ?? []).length - (line.match(/\}/g) ?? []).length;
    while (stack.length && stack[stack.length - 1]![0] >= depth) stack.pop();
  }
  return out;
}

describe("后端枚举当词条 key 用", () => {
  const java = readFileSync(INV_ENUMS, "utf8");

  for (const locale of LOCALES) {
    it(`${locale}：每个枚举值都要有词条 —— 缺一个就是把程序标识符显示给商家`, () => {
      const keys = localeKeys(locale);
      const missing: string[] = [];
      for (const [prefix, classes] of REGISTRY) {
        for (const cls of classes) {
          for (const v of valuesOf(java, cls)) {
            if (!keys.has(`${prefix}.${v}`)) missing.push(`${prefix}.${v}  ←  InvEnums.${cls}`);
          }
        }
      }
      expect(missing, `${locale} 缺这些词条，界面会原样显示 key：\n  ${missing.join("\n  ")}`)
        .toEqual([]);
    });
  }
});
