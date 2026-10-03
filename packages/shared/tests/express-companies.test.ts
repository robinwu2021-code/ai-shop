import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { EXPRESS_COMPANIES } from "../src/utils/express-companies";

const ROOT = resolve(__dirname, "../../..");
const JAVA = resolve(ROOT,
  "backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java");

/** 从 Java 那份里抠出 `new Company("SF", "顺丰速运")` 的序对 */
function javaList(): { code: string; name: string }[] {
  const src = readFileSync(JAVA, "utf8");
  const out: { code: string; name: string }[] = [];
  for (const m of src.matchAll(/new Company\("([^"]+)",\s*"([^"]+)"\)/g)) {
    out.push({ code: m[1], name: m[2] });
  }
  return out;
}

/**
 * 快递公司表**两份必须逐项一致**。
 *
 * 端上那份决定商家能选什么，后端那份决定 `ship()` 收不收。
 * 对不上的后果分两种，都不会有任何地方报出来：
 *
 * - 端上有、后端没有 → 商家选了，收到一个莫名其妙的「参数错误」；
 * - 后端有、端上没有 → 那家快递永远选不到，而代码看起来支持它。
 *
 * 名字也要一致：商家在我们这儿选「顺丰速运」，微信那边显示别的名字，
 * 买家会以为发错了快递。
 */
describe("快递公司表：端上与后端对账", () => {
  it("★★★ 两份逐项一致（顺序、编码、名字）", () => {
    expect(EXPRESS_COMPANIES.map((c) => `${c.code}|${c.name}`))
      .toEqual(javaList().map((c) => `${c.code}|${c.name}`));
  });

  it("★★ 对照量：真的抠到了东西 —— 正则失配时上一条会「空 === 空」恒绿", () => {
    expect(javaList().length).toBeGreaterThan(10);
    expect(EXPRESS_COMPANIES.length).toBeGreaterThan(10);
  });

  it("编码不重复", () => {
    const codes = EXPRESS_COMPANIES.map((c) => c.code);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it("★★ 编码是微信的 delivery_id 形态：全大写字母数字，不带中文", () => {
    for (const c of EXPRESS_COMPANIES) {
      expect(c.code, `${c.name} 的编码`).toMatch(/^[A-Z0-9_]+$/);
    }
  });

  it("★★★ 几个曾经写错过的码，钉住", () => {
    const by = new Map(EXPRESS_COMPANIES.map((c) => [c.name, c.code]));
    expect(by.get("韵达速递"), "不是 YUNDA —— 微信全量表里没有这个码").toBe("YD");
    expect(by.get("邮政快递包裹"), "不是 POSTB —— 不存在").toBe("YZPY");
    expect(by.get("顺丰速运"), "不是 NSF（那是新顺丰）").toBe("SF");
    expect(by.get("百世快递"), "不是 BTWL（那是百世快运）").toBe("HTKY");
  });
});
