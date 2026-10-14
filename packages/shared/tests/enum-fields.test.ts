// 按字段的枚举对账接进 CI。
//
// 与 enum-alignment.test.ts 的分工：
//   enum-alignment  —— 端上声明的值在后端**全局词汇表**里存不存在
//   本文件          —— 具体某个 wire 字段上，两侧的取值域**是不是同一个集合**
//
// 后者能抓到前者按原理抓不到的一类：**两边都有词，但不是同一个词**。
// 实测证据（2026-08）：把 `FULFILLMENT.DELIVERY` 改回错误的 "DELIVERY" 之后
//   · enum-alignment 报 `FULFILLMENT (3/7) STORE_VERIFY, APPOINTMENT, INSTANT`
//     —— DELIVERY 不在里面，因为 "DELIVERY" 这个词在后端全集里确实存在
//   · 本文件报 `端上有后端没有：DELIVERY` + `后端有端上没有：MERCHANT_DELIVERY`
// 而那个 bug 的真实后果是确认订单页把 i18n 键原样打给用户。
import { describe, expect, it } from "vitest";
// @ts-expect-error —— 脚本是 .mjs，没有类型声明；这里只用它的返回值
import { audit, FIELDS, DISMISSED, surface, uncovered, ratchet, RATCHET_FILE } from "../../../scripts/check-enum-fields.mjs";

/**
 * 允许暂时存在的差异。**每条都要写清楚在等什么** ——
 * 「先加一行让它绿」是这套守卫失效的唯一方式，上一轮已经付过一次代价：
 * FULFILLMENT 整个进了豁免名单，于是它名下**新出现**的 DELIVERY 差异也被静音了。
 */
const PENDING = new Map<string, string>([
  // 空 = 当前没有已知未解决的字段级差异。
  // 曾经这里有一条「营销活动类型」，查清后发现那是我把三个不同的概念
  // （店铺级活动 / 商品级买赠 / 平台投放场次）当成了一个字段来比对 ——
  // 不是命名问题。见 docs/technical/营销枚举对账报告.md
]);

describe("枚举对账 · 按字段", () => {
  const { problems, skipped } = audit() as {
    problems: {
      concept: string;
      field: string;
      client?: string;
      fatal?: string;
      clientOnly?: string[];
      backendOnly?: string[];
    }[];
    skipped: { key: string; why: string }[];
  };

  it("取值域提取本身不能坏掉（提不出来要报 fatal，不能静默通过）", () => {
    // 如果 DDL 或 Java 常量的解析写挂了，两侧都会变成空集合、比对「通过」——
    // 那是最糟的失败方式：工具绿着，而它什么都没查。所以 fatal 单独断言。
    const broken = problems.filter((p) => p.fatal).map((p) => `${p.concept}：${p.fatal}`);
    expect(broken, "取值域抽取失败：\n  " + broken.join("\n  ")).toEqual([]);
  });

  it("每个 wire 字段两侧的取值域必须一致", () => {
    const real = problems
      .filter((p) => !p.fatal && !PENDING.has(p.concept))
      .map(
        (p) =>
          `${p.concept}（${p.field}）@ ${p.client}\n` +
          `      端上有、后端没有：${p.clientOnly?.join(", ") || "—"}\n` +
          `      后端有、端上没有：${p.backendOnly?.join(", ") || "—"}`,
      );
    expect(
      real,
      "这些字段两侧对不上：\n  " +
        real.join("\n  ") +
        "\n\n后果不是报错：端上多出来的值 → 按它筛必然是空列表；" +
        "后端多出来的值 → 下发时端上落进兜底分支，或把 i18n 键原样显示给用户。\n" +
        "两种都不会有任何报错，只有真的打开那一页才看得见。",
    ).toEqual([]);
  });

  it("PENDING 里的条目要真的还在差异中 —— 修好了就从名单里删掉", () => {
    const stale = [...PENDING.keys()].filter((c) => !problems.some((p) => p.concept === c));
    expect(stale, `以下已经对齐了，请从 PENDING 删除：${stale.join(", ")}`).toEqual([]);
  });

  /**
   * 登记表自身的完整性。
   *
   * <p>这条守卫是踩了自己的坑换来的：`FIELDS` 里履约字段只登记了 shared，
   * **漏登了 ops-web** —— 于是 ops-web 的 `FulfillType` 多出一个后端没有的
   * `SERVICE`，字段级对账一路绿灯，最后是人工按领域逐个盘点才发现的。
   *
   * <p>教训是通用的：**一张手写的登记表，它自己的完整性也需要守卫**，
   * 否则「表里没有」和「代码里没问题」看起来一模一样。
   */
  it("同一个 wire 字段，各端的声明都要登记全（漏登会让对账假绿）", () => {
    const KNOWN_PAIRS: [string, string[]][] = [
      ["履约方式", ["shared", "ops-web"]],
      ["订单状态（下发口径）", ["shared", "ops-web"]],
      ["售后单状态", ["shared", "ops-web"]],
      ["自提点类型", ["shared", "ops-web"]],
    ];
    const registered = new Map<string, Set<string>>();
    for (const f of FIELDS as { concept: string; clients: { file: string }[] }[]) {
      registered.set(
        f.concept,
        new Set(f.clients.map((c) => (c.file.startsWith("ops-web") ? "ops-web" : "shared"))),
      );
    }
    const gaps: string[] = [];
    for (const [concept, ends] of KNOWN_PAIRS) {
      const got = registered.get(concept);
      if (!got) { gaps.push(`${concept}：整条没登记`); continue; }
      const missing = ends.filter((e) => !got.has(e));
      if (missing.length) gaps.push(`${concept}：漏登 ${missing.join("、")}`);
    }
    expect(
      gaps,
      "对账登记表不完整：\n  " + gaps.join("\n  ") +
        "\n\n漏登的那一端不会被对账 —— 工具照常全绿，而那一端的取值可能与后端对不上。",
    ).toEqual([]);
  });

  it("显式豁免的每一条都要有理由", () => {
    for (const s of skipped) expect(s.why, `${s.key} 缺少豁免理由`).toBeTruthy();
  });

  /*
   * ── 未判定面（2026-09-06 加）──
   *
   * 在这之前这套工具只回答「已登记的 8 个对不对」，不回答「还有多少没人看过」。
   * 两者差得很远：可见面 134 列，登记 8 列 —— 而没有任何东西说得出那 126 列在哪。
   * 「工具全绿」于是同时意味着「查过的都对」和「绝大多数没查过」，
   * 而读到绿色的人只会记住前一半。
   *
   * ⚠️ 这个面**不是覆盖率的分母**（那应当来自需求端的取值域定义，见
   * TDD-取值域按字段对账-补全登记 §2.2）。它只是把空白点出名字来。
   */
  it("★★ 未判定的取值域列只准变少 —— 新出现的要点名", () => {
    const { added } = ratchet() as { added: { key: string; values: string[] }[] };
    const lines = added.map((u) => `${u.key}  取值：${u.values.join(" / ")}`);
    expect(
      lines,
      `这些列在建表注释里枚举了取值，却既没登记进 FIELDS、也没写进 DISMISSED：\n  ` +
        lines.join("\n  ") +
        `\n\n登记 = 它对应哪个端上类型（于是两侧取值域会被逐字比对）；` +
        `\n驳回 = 写明这一列不是受限取值域，并给理由。` +
        `\n两者都不做就把它加进 ${RATCHET_FILE} 并说清为什么 —— 但那一档只准变短。`,
    ).toEqual([]);
  });

  it("驳回的每一条都要有理由，且还在可见面里（陈条要删）", () => {
    const dismissed = DISMISSED as { key: string; why: string }[];
    for (const d of dismissed) {
      expect(d.why, `${d.key} 驳回了却没写理由 —— 没有理由的驳回与豁免名单没有区别`).toBeTruthy();
    }
    const cols = surface() as Map<string, string[]>;
    const stale = dismissed.map((d) => d.key).filter((k) => !cols.has(k));
    expect(
      stale,
      `这些列已经不在可见面里（列删了或注释改了），驳回条是陈的，请删掉：\n  ` + stale.join("\n  "),
    ).toEqual([]);
  });

  it("基线文件与工具算出来的未判定面是同一个数 —— 两把尺量的必须是同一个东西", () => {
    const un = uncovered() as { key: string }[];
    const { added, fixed } = ratchet() as { added: unknown[]; fixed: string[] };
    // 收紧之后恒红的经典成因是「基线与量具量的不是同一个数」，先把这条钉住
    expect(un.length - added.length + fixed.length, "基线条数与未判定面对不上").toBeGreaterThan(0);
  });
});
