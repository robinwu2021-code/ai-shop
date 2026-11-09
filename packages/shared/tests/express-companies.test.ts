import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  EXPRESS_COMPANIES,
  EXPRESS_COMPANIES_BY_MARKET,
  expressCompaniesOf,
  expressCompanyName,
} from "../src/utils/express-companies";

const ROOT = resolve(__dirname, "../../..");
const JAVA = resolve(ROOT,
  "backend/shop-base/src/main/java/ai/neargo/shop/common/ExpressCompanies.java");

type Pair = { code: string; name: string };

const COMPANY = /new Company\("([^"]+)",\s*"([^"]+)"\)/g;

/** 去掉注释再抠 —— 类注释里举例写的 `new Company("…", "…")` 不是表里的一行 */
function javaSource(): string {
  return readFileSync(JAVA, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
}

/**
 * 从 Java 那份里按市场抠出 `new Company("SF", "顺丰速运")` 的序对。
 *
 * Java 的写法是每个市场一张 `private static final List<Company> CN = List.of(…);`，
 * 再由 `BY_MARKET` 的 `Map.of("CN", CN, "TW", TW)` 把码挂到表上。两步都要抠：
 * 只抠表不抠映射，Java 里加了一张表却没挂上（`forMarket` 永远给空）这里看不出来。
 */
function javaByMarket(): Record<string, Pair[]> {
  const src = javaSource();
  const lists: Record<string, Pair[]> = {};
  for (const m of src.matchAll(/List<Company>\s+(\w+)\s*=\s*List\.of\(([\s\S]*?)\);/g)) {
    lists[m[1]] = [...m[2].matchAll(COMPANY)].map((c) => ({ code: c[1], name: c[2] }));
  }
  const map = src.match(/BY_MARKET\s*=\s*Map\.of\(([^)]*)\)/);
  if (!map) throw new Error("ExpressCompanies.java 里找不到 BY_MARKET = Map.of(...) —— 结构变了，对账要跟着改");
  const out: Record<string, Pair[]> = {};
  for (const e of map[1].matchAll(/"([A-Z]{2})"\s*,\s*(\w+)/g)) {
    out[e[1]] = lists[e[2]] ?? [];
  }
  return out;
}

/** 整个文件里 `new Company(` 出现几次 —— 用来证明「按市场抠」没有漏掉任何一张表 */
function javaCompanyCount(): number {
  return [...javaSource().matchAll(COMPANY)].length;
}

const flat = (xs: readonly Pair[]) => xs.map((c) => `${c.code}|${c.name}`);

/**
 * 快递公司表**两份必须按市场逐项一致**。
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
  it("★★★ 按市场逐项一致（市场集合、顺序、编码、名字）", () => {
    const java = javaByMarket();
    expect(Object.keys(EXPRESS_COMPANIES_BY_MARKET).sort()).toEqual(Object.keys(java).sort());
    for (const [market, list] of Object.entries(java)) {
      expect(flat(EXPRESS_COMPANIES_BY_MARKET[market]), `${market} 市场`).toEqual(flat(list));
    }
  });

  it("★★★ 对照量：每个市场都真的抠到了东西，且没有一条 `new Company` 落在市场之外", () => {
    const java = javaByMarket();
    expect(java.CN?.length ?? 0).toBeGreaterThan(10);
    // 台湾首批五家（TDD-商户App快递公司 已拍板 X1）。正则失配时这里是 0，上一条会「空 === 空」恒绿
    expect(java.TW?.length ?? 0).toBe(5);
    const grouped = Object.values(java).reduce((n, l) => n + l.length, 0);
    expect(grouped, "Java 里有 Company 不属于任何 BY_MARKET 里的表").toBe(javaCompanyCount());
  });

  it("大陆那份就是 EXPRESS_COMPANIES（b-app / c-app 现有引用不动）", () => {
    expect(EXPRESS_COMPANIES).toBe(EXPRESS_COMPANIES_BY_MARKET.CN);
  });

  it("★★ 编码全局不重复 —— 订单上只存一个码，跨市场也要能反查名字", () => {
    const codes = Object.values(EXPRESS_COMPANIES_BY_MARKET).flat().map((c) => c.code);
    expect(new Set(codes).size).toBe(codes.length);
  });

  it("★★ 编码形态：全大写字母数字，不带中文", () => {
    for (const c of Object.values(EXPRESS_COMPANIES_BY_MARKET).flat()) {
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

  it("★★ 台湾黑貓排第一（App 默认选第一项，与改版前的默认值一致）", () => {
    expect(EXPRESS_COMPANIES_BY_MARKET.TW[0]).toEqual({ code: "TCAT", name: "黑貓宅急便" });
  });

  it("不认得的市场给空表，不回落大陆", () => {
    expect(expressCompaniesOf("AE")).toEqual([]);
    expect(expressCompaniesOf("tw")).toBe(EXPRESS_COMPANIES_BY_MARKET.TW);
  });

  it("名字跨市场反查；认不得的码给 undefined", () => {
    expect(expressCompanyName("TCAT")).toBe("黑貓宅急便");
    expect(expressCompanyName("SF")).toBe("顺丰速运");
    expect(expressCompanyName("NOPE")).toBeUndefined();
  });
});
