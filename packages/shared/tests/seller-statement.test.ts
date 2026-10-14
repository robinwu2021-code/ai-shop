// 销售主体的表述 —— **这条守的是资金模式的合法性，不是文案风格**。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么一句文案能推翻整套资金模式
// ─────────────────────────────────────────────────────────────────────────────
// 平台走「归集」路径（用户付给平台户、平台是销售主体、代销）的前提，是四流一致：
// 合同、发票、资金、货物指向同一套关系（ADR-017 §3.4）。
//
// 而**合同相对方是谁，看的是页面上怎么写的**。写了
// 「平台仅提供信息展示，交易由商家与您达成」——
// 那就是白纸黑字承认自己是平台模式（居间），此时再让钱进平台账户就是**二清**。
//
// 很多平台是在用户协议里栽的，不是在业务上。所以这条不能靠人记得，要机器判。
//
// ─────────────────────────────────────────────────────────────────────────────
// 可以说 vs 不能说（ADR-017 §3.5）
// ─────────────────────────────────────────────────────────────────────────────
//   ✅ 供货商：XX 果蔬     ❌ 本商品由 XX 果蔬**销售**
//   ✅ 由 XX 店配送        ❌ 售后请**联系 XX 商家**
//   ✅ 商家评分、门店主页   ❌ 「入驻商户」「第三方卖家」这类身份表述
//
// 分界线是**「谁在卖」与「谁在供货/配送」**：后者是事实描述，前者是法律关系。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/**
 * 禁用词。
 *
 * 每一条都要能回答「它为什么推翻资金模式」—— 只是「读着别扭」的不进这张表，
 * 那属于文案评审，混进来会让这条守卫变成一个谁都想绕过的东西。
 */
const FORBIDDEN: { pattern: RegExp; why: string }[] = [
  {
    pattern: /平台仅提供信息展示/,
    why: "直接声明自己是居间方 —— 写了它，钱再进平台账户就是二清",
  },
  {
    pattern: /交易由(商家|卖家).{0,4}(与您|和您|双方)达成/,
    why: "把合同相对方指给商家。合同相对方不是平台 → 钱不是平台的",
  },
  {
    pattern: /本商品由.{0,12}销售/,
    why: "「销售」是法律关系表述。归集路径下销售方必须是平台，只能写「供货商」",
  },
  {
    pattern: /(售后|退换|退款)请?联系(商家|卖家|商户)/,
    why: "ADR-017 §3.4 条件 3：平台对消费者承担售后责任。指给商家 = 实质是平台模式",
  },
  {
    pattern: /第三方卖家/,
    why: "身份表述，直接说明商家是销售方",
  },
];

/** 只扫 C 端 —— 它是给消费者看的那一面，法律关系由它确立 */
const SCAN_DIRS = ["c-app/src"];
const EXTS = [".ts", ".vue"];

function files(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(join(ROOT, dir))) {
    const p = `${dir}/${e}`;
    if (statSync(join(ROOT, p)).isDirectory()) files(p, out);
    else if (EXTS.some((x) => e.endsWith(x))) out.push(p);
  }
  return out;
}

describe("销售主体表述", () => {
  const scanned = SCAN_DIRS.flatMap((d) => files(d));

  it("扫到了文件 —— 扫不到的话下面那条断言恒真", () => {
    expect(scanned.length).toBeGreaterThan(20);
  });

  it("★★★ C 端不得出现把销售方指给商家的表述 —— 那会推翻归集资金模式", () => {
    const hits: string[] = [];
    for (const f of scanned) {
      const src = readFileSync(join(ROOT, f), "utf8");
      for (const { pattern, why } of FORBIDDEN) {
        // 注释里出现是允许的：本守卫自己的说明、以及代码里解释「为什么不能这么写」
        const body = src
          .replace(/\/\*[\s\S]*?\*\//g, "")
          .replace(/^\s*(\/\/|\*|<!--).*$/gm, "");
        if (pattern.test(body)) hits.push(`${f}\n    命中：${pattern}\n    为什么不行：${why}`);
      }
    }
    expect(
      hits,
      "这些表述会把合同相对方指给商家。\n" +
        "  归集路径（钱进平台户）要求平台是销售主体 —— 页面上这么写，" +
        "资金模式就不成立了（ADR-017 §3.4/§3.5）。\n\n  " +
        hits.join("\n  "),
    ).toEqual([]);
  });
});
