/**
 * 官网的硬约束。这些规则写在 TDD 里已经够久了，但注释拦不住第 8 个组件。
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";
import { site } from "./site.config";

const SITE = join(import.meta.dirname, "..");

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.tsx?$/.test(p) && !p.endsWith(".test.ts") && !p.endsWith(".test.tsx")) out.push(p);
  }
  return out;
}

const SOURCES = [...walk(join(SITE, "app")), ...walk(join(SITE, "components")), ...walk(join(SITE, "lib"))];

/**
 * 读源码，**先剥掉注释**。
 *
 * 注释里出现域名或色值是正当的 —— 解释「官网接管 / 之前 hxmall.top 上是什么」
 * 恰恰需要把它写出来，而那不是生效的代码。不剥的话这类注释会把守卫打成红的，
 * 逼着后来的人要么删注释、要么把真问题和假警报一起无视（后者更常见，守卫也就废了）。
 * packages/shared 的 design-tokens.test.ts 已经踩过一次，这里踩了第二次 —— 所以抽成一处。
 */
function readCode(file: string): string {
  return readFileSync(file, "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");
}

describe("客户端组件预算", () => {
  /**
   * 每多一个客户端组件，首屏 JS 就厚一层 —— 而官网除了换色演示没有任何交互。
   * 实测基线：零客户端组件时首屏 JS gzip 129.9 KB，预算 160 KB（TDD §3.7）。
   * 汉堡菜单这类开合一律用 CSS（checkbox + peer），不要为它开一个客户端组件。
   */
  it('只有 SkinShowcase 允许 "use client"', () => {
    const offenders = SOURCES.filter((f) => /^\s*["']use client["']/m.test(readFileSync(f, "utf8")))
      .map((f) => relative(SITE, f))
      .filter((f) => f !== "components/home/skin-showcase.tsx");
    expect(offenders, `这些文件加了 "use client"：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });
});

describe("零硬编码", () => {
  /** 上一轮有两处写死占位域名，物料生成得出来但指向不存在的地方（TDD-店铺码与分享）*/
  it("组件里不写死域名与邮箱，一律走 site.config", () => {
    const offenders: string[] = [];
    for (const f of SOURCES) {
      if (f.endsWith("site.config.ts")) continue;
      const src = readCode(f);
      const hits = [
        ...(src.match(/hxmall\.top|hxtech\.top/g) ?? []),
        ...(src.match(/[\w.]+@[\w.]+\.\w+/g) ?? []),
      ];
      if (hits.length) offenders.push(`${relative(SITE, f)}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `写死了域名/邮箱：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });

  it("组件里不写死品牌色，一律走 @theme 工具类", () => {
    const offenders: string[] = [];
    // 允许中性的界面灰（描边、缩略图底）与纯白纯黑；品牌色必须走 token
    const ALLOW = /^#(fff|ffffff|000|000000|cfd2d8|edeef1|f2f3f5|e3e5e9)$/i;
    for (const f of SOURCES) {
      if (f.endsWith("logo.tsx")) continue; // 标识是 SVG 路径，色值随 brand/ 产物，见文件内说明
      const src = readCode(f);
      const hits = (src.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []).filter((c) => !ALLOW.test(c));
      if (hits.length) offenders.push(`${relative(SITE, f)}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `写死了色值：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });
});

describe("费率不手抄", () => {
  /**
   * 官网上的费率与 App 里算出来的钱对不上，是商家最不能接受的一种不一致 ——
   * 而手抄的那份永远不会跟着 `SETTLE` 改。所以：凡是谈钱的页面，数字必须 import。
   *
   * 只扫「谈钱的文件」是有意的：`2%`、`16%` 这类在别处是布局坐标（customers.tsx 的环形示意图），
   * 全站一刀切会把守卫打成一堆假警报，然后它就废了。
   */
  const MONEY_TALK = /佣金|履约服务费|结算周期/;

  /**
   * Tailwind 的**任意值**都写在方括号里：`w-[38%]`、`inset-[16%]`、`min-w-[640px]`。
   * 它们是布局坐标，不是费率 —— 不剥掉的话，一个谈佣金的页面只要有一处 `w-[38%]`
   * 就会被判成「写死了费率」。假警报会让守卫在第三次之后被整体无视，
   * 和 readCode 剥注释是同一个道理。
   */
  const stripArbitrary = (src: string) => src.replace(/\[[^\]\n]*\]/g, " ");

  /**
   * 只查「谈钱的文件里有没有费率**数字**」这一件事。
   *
   * 不查「谈钱就必须 import SETTLE」：`佣金`「费率」这些词也出现在纯定性的句子里
   * （档位对照表的「费率不做成档位差异」），那些文件本来就不需要接真源，
   * 逼它们 import 一个用不上的常量只会让人加 eslint-disable 式的绕过。
   * 真正的失效模式是**数字**被手抄一份，而它跑不掉这一条。
   */
  it("谈费率的文件里不出现写死的百分比与金额", () => {
    const offenders: string[] = [];
    for (const f of SOURCES) {
      const src = stripArbitrary(readCode(f));
      if (!MONEY_TALK.test(src)) continue;
      const hits = [
        ...(src.match(/\d+(\.\d+)?\s*%/g) ?? []),
        ...(src.match(/[¥￥]\s*\d/g) ?? []),
        // 万分比/小数形式的费率：0.02、0.3 —— 抄成小数一样是抄
        ...(src.match(/\b0\.\d+\b/g) ?? []),
      ];
      if (hits.length) offenders.push(`${relative(SITE, f)}: ${[...new Set(hits)].join(", ")}`);
    }
    expect(offenders, `写死了费率：\n  ${offenders.join("\n  ")}`).toEqual([]);
  });
});

describe("site.config 的空占位", () => {
  /**
   * 空占位本身是合法的（备案号还没下来），但**必须是已知的那几个**。
   * 新加一个字段忘了填，这里会红 —— 而不是等到线上出现一个空白页脚。
   */
  const KNOWN_EMPTY = new Set([
    "legal.icp",
    "contact.salesWechatQr",
    "download.consumerAppStore",
    "download.consumerAndroid",
    "download.consumerMiniProgram",
    "download.merchantAndroid",
    "download.merchantAndroidVersion",
  ]);

  it("空字段全部在已知清单里", () => {
    const empties: string[] = [];
    const walkCfg = (o: Record<string, unknown>, prefix = "") => {
      for (const [k, v] of Object.entries(o)) {
        const path = prefix ? `${prefix}.${k}` : k;
        if (typeof v === "object" && v) walkCfg(v as Record<string, unknown>, path);
        else if (v === "") empties.push(path);
      }
    };
    walkCfg(site as unknown as Record<string, unknown>);
    const unexpected = empties.filter((e) => !KNOWN_EMPTY.has(e));
    expect(unexpected, `这些字段是空的但不在已知清单里：${unexpected.join(", ")}`).toEqual([]);
  });
});
