// 枚举对账守卫：端上声明的取值，后端真的会下发/接受吗。
//
// 这条守卫是被**三次同一形状的故障**换来的（2026-08）：路径通、200、字段也在，
// 唯独值对不上 —— 订单状态让买家的「待取货」和商家的三个页签全空；
// ops-web 的售后状态是另一套「处理阶段」的名字；登录方式端上发 WX_MINI 而后端只认 WECHAT_MP。
// 三次都不是打字错误，是两边各自定义了一套词汇，而**没有任何东西比对过**。
//
// `check:api` 只比路径不比取值，所以它三次都是绿的。
import { describe, expect, it } from "vitest";
// @ts-expect-error —— 脚本是 .mjs，没有类型声明；这里只用它的两个纯函数
import { findGaps } from "../../../scripts/check-enums.mjs";

interface Gap {
  client: "shared" | "ops-web";
  name: string;
  missing: string[];
  total: number;
}

/**
 * shared（c-app / b-app）已知且**有理由**的差异。
 *
 * 每一条都必须写清楚「为什么现在可以留着、什么时候该删掉」——
 * 否则这份名单会退化成「报错了就加一行」，而守卫就此失效。
 */
const KNOWN_SHARED: Record<string, string> = {
  GrantType:
    "WX_MINI/WX_PHONE/WX_OPEN 是端上按微信三种登录场景拆的，后端只有一个 WECHAT_MP。" +
    "微信登录本身还没接（code2Session 是 TODO），接的时候两边一起定名",
  MerchantTier:
    "MEDIUM/LARGE 是分层费率的预留档，后端目前只产出 SMALL —— 分层上线时后端补齐",
};

/**
 * ops-web 的差异数**只许降不许升**。
 *
 * 它有 171 条端点还没有后端实现，逐条对齐是接下来几周的事；
 * 但在那之前，不该再新增一个「后端永远不会给的值」。
 * 这个数字每降一点，就是一块真正对齐了的地方。
 */
const OPS_WEB_BASELINE = 33;

describe("枚举对账", () => {
  const gaps: Gap[] = findGaps();

  it("shared：端上声明的取值，后端都真的会给（例外必须在 KNOWN_SHARED 里写明理由）", () => {
    const unexplained = gaps
      .filter((g) => g.client === "shared" && !KNOWN_SHARED[g.name])
      .map((g) => `${g.name}: ${g.missing.join(", ")}`);

    expect(
      unexplained,
      "这些取值端上声明了、后端永远不会下发 —— 端上按自己的类型解析或筛选，\n" +
        "结果是「接口 200 但页面是空的」，而且不报错：\n  " +
        unexplained.join("\n  ") +
        "\n\n要么去后端实现，要么从契约里删掉。确实要暂留的，写进 KNOWN_SHARED 并说明理由。",
    ).toEqual([]);
  });

  it("ops-web：差异只许降不许升", () => {
    const count = gaps.filter((g) => g.client === "ops-web").length;
    expect(
      count,
      `ops-web 的枚举差异从 ${OPS_WEB_BASELINE} 涨到了 ${count}。\n` +
        "它有大量端点还没有后端实现，所以基线不为零；但**不该再新增**：\n" +
        "新增一条就是又写下一个后端永远不会给的值，而 ops-web 只跑 mock，自己发现不了。\n" +
        "对齐了几个就把 OPS_WEB_BASELINE 调低几个 —— 这个数字只能往下走。",
    ).toBeLessThanOrEqual(OPS_WEB_BASELINE);
  });

  it("KNOWN_SHARED 里不留已经修好的条目（名单本身也会过期）", () => {
    const stale = Object.keys(KNOWN_SHARED).filter(
      (name) => !gaps.some((g) => g.client === "shared" && g.name === name),
    );
    expect(
      stale,
      `这些已经不再有差异，从 KNOWN_SHARED 删掉：${stale.join(", ")}\n` +
        "留着的话，下次同一个类型再出问题就会被这条豁免掉。",
    ).toEqual([]);
  });
});
