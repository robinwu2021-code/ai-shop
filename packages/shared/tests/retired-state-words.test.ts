import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * 需求文档里的**退役状态词**。
 *
 * <h2>为什么不是「文档里的大写词都得在代码里」</h2>
 *
 * 那条判据量过：95% 是噪声（需求编号、迁移号、术语缩写），
 * `notify-scene-doc.test.ts` 的头部记着为什么否掉它 —— 误报率那么高的闸门
 * 只会被加一张豁免名单，然后退化成谁都不看的东西。
 *
 * 这里换一条**窄而准**的：维护一张「确认已退役」的词表，
 * 断言它们在需求文档里的出现次数**只降不升**。
 * 更正记述（「此前写的是 X」）本来就要引用这些词，所以不能一刀切禁掉；
 * 而**新写的活断言会让计数变大**，那正是要挡的。
 *
 * <h2>它记录的那次事故</h2>
 *
 * 2026-09-09 清扫时，同一个任务里扫描面划窄了三次：
 *   ① 只 grep `DISPUTED`/`PREPARING` → 漏了 `AGREED`/`RETURNING`（同一套废弃词）
 *   ② 补齐四个词后仍漏 `ARRIVED`/`SHIPPED` —— 它们不是「废弃」而是被**合并**进
 *      `FULFILLING`，我按「废弃」找就找不到按「合并」消失的
 *   ③ 是量迁移箭头时偶然撞见 `B-5.5` 的 `SHIPPED → COMPLETED` 才发现的
 *
 * 前两次每次都以为扫干净了。**所以这张表本身要能被证伪** ——
 * 第一条断言查的就是「这些词是不是真的还死着」。
 */
const ROOT = join(import.meta.dirname, "../../..");
const REQ = join(ROOT, "docs/requirements");
const SRC = join(ROOT, "packages/shared/src");

/**
 * 退役词 → 今天该用什么。
 * 值只用于报错时给出方向，不参与判定。
 */
const RETIRED: Record<string, string> = {
  AGREED: "AfterSaleStatus 的 REFUNDING（同意后只有一段，含等寄回）",
  RETURNING: "同上 —— 后端没有把「寄回中」做成独立状态",
  DISPUTED: "AfterSaleStatus 的 ARBITRATING",
  PREPARING: "OrderStatus 的 PAID —— 后端付款后没有独立备货态",
  ARRIVED: "OrderStatus 的 FULFILLING —— 送法移到 fulfillment 表达",
  SHIPPED: "同上，ARRIVED/SHIPPED 已合并为 FULFILLING",
};

/** 今天的出现次数。**只降不升** —— 修一处就把对应数字改小。 */
const BASELINE: Record<string, number> = {
  AGREED: 5,
  RETURNING: 3,
  DISPUTED: 4,
  PREPARING: 2,
  ARRIVED: 2,
  SHIPPED: 2,
};

function reqDocs(): string[] {
  return readdirSync(REQ)
    .filter((f) => f.endsWith(".md"))
    .map((f) => join(REQ, f));
}

function countIn(files: string[], word: string): number {
  const re = new RegExp(`\\b${word}\\b`, "g");
  return files.reduce((n, f) => n + (readFileSync(f, "utf8").match(re)?.length ?? 0), 0);
}

/** 去掉注释 —— 解释这些词为什么退役的那段话，本身就得引用它们。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
}

/**
 * 代码里把它当**合法取值**写着的次数：类型联合体的成员 `| "WORD"`。
 *
 * ⚠️ 两个坑，都在消融时才暴露：
 * ① 别拿 {@link countIn} 套 `"PREPARING"` —— 那会拼出 `\b"PREPARING"\b`，
 *    而 `"` 不是词字符，`\b"` 只在前一个字符是词字符时才成立。
 *    `= "PREPARING"` 前面是空格，于是**永远匹配不上**，断言恒绿。
 * ② 匹配任意引号形式也不行 —— `trade.ts` 的 JSDoc 里正写着
 *    「此前是完全另一套：`PENDING`/`AGREED`/…」，六个词全在注释里命中，
 *    于是断言恒红。**守卫会扫到解释规则的那句话本身。**
 * 所以：先剥注释，再只认联合体成员那一种写法。
 */
function countAsUnionMember(files: string[], word: string): number {
  const re = new RegExp(`\\|\\s*"${word}"`, "g");
  return files.reduce(
    (n, f) => n + (stripComments(readFileSync(f, "utf8")).match(re)?.length ?? 0),
    0,
  );
}

describe("退役状态词", () => {
  const docs = reqDocs();

  it("需求目录至少扫到 30 篇 .md —— 少扫等于全绿", () => {
    // 目录读空、或后缀过滤写错，下面两条都会通过而一个字都没查。今天 40+ 篇。
    expect(docs.length, `只扫到 ${docs.length} 篇`).toBeGreaterThanOrEqual(30);
  });

  it("★★★ 这些词确实还死着 —— 表本身要能被证伪", () => {
    /*
     * 如果哪天某个词被正式启用了（比如后端真加了备货态），
     * 这条会红，提醒把它从 RETIRED 里删掉 —— 而不是让下面那条继续拦着合法的新写法。
     * 词表不验证，就会变成一张越来越不准、却越来越有权威感的名单。
     */
    const alive: string[] = [];
    for (const w of Object.keys(RETIRED)) {
      const inTypes = countAsUnionMember(
        [join(SRC, "types/trade.ts"), join(SRC, "types/merchant.ts")],
        w,
      );
      if (inTypes > 0) alive.push(w);
    }
    expect(
      alive,
      `这些词已经在 shared 类型里作为合法取值出现了：${alive.join(" ")}\n` +
        "→ 把它从 RETIRED 里删掉，别让它继续被当成退役词拦着。",
    ).toEqual([]);
  });

  it("★★ 出现次数只降不升 —— 更正记述要引用它们，新写的活断言会让计数变大", () => {
    const grown: string[] = [];
    for (const [w, base] of Object.entries(BASELINE)) {
      const n = countIn(docs, w);
      if (n > base) grown.push(`${w}: ${n} > 基线 ${base} —— 改用 ${RETIRED[w]}`);
    }
    expect(
      grown,
      "需求文档里退役词变多了：\n  " +
        grown.join("\n  ") +
        "\n（修好一处就把 BASELINE 里对应的数字改小；只有更正记述才该引用这些词）",
    ).toEqual([]);
  });
});
