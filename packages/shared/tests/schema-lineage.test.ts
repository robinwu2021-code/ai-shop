// 对账血缘守卫：资金账与当前积分账的关联通路必须连得上。
//
// 为什么需要它：这条链路**断了不会有任何编译错误，也不会有测试失败**。
// 把 `pts_ledger_entry.voucher_no` 改个名或删掉，代码照样编译、单测照样绿，
// 要等到月底对账对不上、或者商家问「我的积分兑付去哪了」才会发现。
// 而那时数据已经错了一个月。
//
// 通路来自 V425 积分账本与 V453+ 订单绑定：
//   资金账 stl_payment → ord_order/ord_after_sale（收款与退款）
//         ord_sub_order → stl_bill → stl_split_log（计提与分账）
//   积分账 pts_voucher → pts_ledger_entry / pts_pool_flow
//         ord_points_binding → ord_sub_order；售后决策 → ord_points_refund_binding
//   旧 pts_user_ledger / stl_points_pool 属于未上线旧方案，保留空表，不参与当前链路
import { existsSync, readFileSync, readdirSync } from "node:fs";
// @ts-expect-error -- 生成器是 .mjs，没有类型声明；它是 DDL 解析的唯一真源
import { readColumnNames } from "../../../scripts/lib/ddl.mjs";
import { join } from "node:path";
// @ts-expect-error -- Shared schema metadata is an .mjs module.
import { KEY_OWNER_DEFINITIONS as KEY_OWNERS, NAME_COLLISIONS } from "../../../scripts/lib/schema-keys.mjs";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");

/** 表 → 列集合。解析器只有一份（scripts/lib/ddl.mjs），这里只做视图转换。 */
function readSchema(): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  for (const [name, def] of readColumnNames(ROOT) as Map<string, { cols: string[] }>) {
    out.set(name, new Set(def.cols));
  }
  return out;
}

interface Hop {
  lane: string;
  from: string;
  fromCol: string;
  to: string;
  toCol: string;
  why: string;
}

const LINEAGE: Hop[] = [
  {
    lane: "资金账",
    from: "stl_payment",
    fromCol: "order_no",
    to: "ord_order",
    toCol: "order_no",
    why:
      "收款流水 → 订单。断了就**对不出账**：与通道账单核对是逐笔对应的，" +
      "而掉单（用户付了钱、我方没收到回调）只能靠对账发现，没有别的手段",
  },
  {
    lane: "资金账",
    from: "stl_payment",
    fromCol: "after_sale_no",
    to: "ord_after_sale",
    toCol: "after_sale_no",
    why:
      "退款流水 → 售后单。断了退款重试就没有幂等依据 —— 部分退款会有多条，" +
      "各自一次通道调用，认不出哪条退过就会退两次",
  },
  {
    lane: "资金账",
    from: "ord_sub_order",
    fromCol: "sub_order_no",
    to: "stl_bill",
    toCol: "sub_order_no",
    why: "一子单一张结算单；断了就不知道这单该结给谁",
  },
  {
    lane: "资金账",
    from: "stl_bill",
    fromCol: "settle_no",
    to: "stl_split_log",
    toCol: "settle_no",
    why: "分账指令与回执挂在结算单上；断了就查不到「发过但没回执」",
  },
  {
    lane: "积分账",
    from: "pts_ledger_entry",
    fromCol: "voucher_no",
    to: "pts_voucher",
    toCol: "voucher_no",
    why: "不可变积分分录必须回溯到唯一凭证；凭证保存幂等键和完整过账结果。",
  },
  {
    lane: "积分账",
    from: "pts_pool_flow",
    fromCol: "voucher_no",
    to: "pts_voucher",
    toCol: "voucher_no",
    why: "资金池 U/C/D/B 变动与用户积分分录共用凭证，保证复式过账原子可追溯。",
  },
  {
    lane: "订单接线",
    from: "ord_points_binding",
    fromCol: "sub_order_no",
    to: "ord_sub_order",
    toCol: "sub_order_no",
    why: "积分接线绑定冻结到原门店子单，便于单独接通或脱离而不改变普通订单行为。",
  },
  {
    lane: "售后接线",
    from: "ord_points_refund_binding",
    fromCol: "after_sale_no",
    to: "ord_after_sale",
    toCol: "after_sale_no",
    why: "售后积分决策必须绑定原售后单；重试沿用冻结决策，避免重复退款或反向过账。",
  },
  {
    lane: "积分账",
    from: "stl_payment",
    fromCol: "sub_order_no",
    to: "ord_sub_order",
    toCol: "sub_order_no",
    why:
      "补差流水 → 子单。断了就不知道这一单补没补上，而**补差失败必须阻断分账**：" +
      "认不出对应关系，要么漏拦（商家少收），要么全拦（好单也结不了）",
  },
];

describe("对账血缘", () => {
  const schema = readSchema();

  it("解析到了迁移 —— 解析失效时不能静默通过", () => {
    // 正则解析最坏的失败方式是「一张表都没抽到」却报绿
    if (!existsSync(MIGRATION_DIR)) return; // 只装前端的场景
    expect(schema.size, "一张表都没解析到，多半是建表写法变了").toBeGreaterThan(30);
  });

  it.each(LINEAGE)("[$lane] $from.$fromCol → $to.$toCol", (hop) => {
    if (!schema.size) return;
    const from = schema.get(hop.from);
    const to = schema.get(hop.to);
    expect(from, `表 ${hop.from} 不存在`).toBeDefined();
    expect(to, `表 ${hop.to} 不存在`).toBeDefined();

    const missing: string[] = [];
    if (!from?.has(hop.fromCol)) missing.push(`${hop.from}.${hop.fromCol}`);
    if (!to?.has(hop.toCol)) missing.push(`${hop.to}.${hop.toCol}`);

    expect(
      missing,
      `对账链路断了：${missing.join("、")} 不存在。\n  用途：${hop.why}\n` +
        "  这条断了**不会有编译错误也不会有别的测试失败**，" +
        "要到月底对账对不上才发现 —— 所以在这里拦住。",
    ).toEqual([]);
  });

  it("新出现的跨表同名业务键必须被分类", () => {
    if (!schema.size) return;

    const byKey = new Map<string, string[]>();
    for (const [table, cols] of schema) {
      for (const c of cols) {
        if (!c.endsWith("_no") || c === "tenant_no") continue;
        byKey.set(c, [...(byKey.get(c) ?? []), table]);
      }
    }

    const unclassified = [...byKey]
      .filter(([, ts]) => ts.length >= 2)
      .filter(([k]) => !(k in KEY_OWNERS) && !(k in NAME_COLLISIONS))
      .map(([k, ts]) => `${k}（出现在 ${ts.join("、")}）`);

    expect(
      unclassified,
      "这些列名出现在多张表里，但既没登记归属、也没登记为同名不同义。\n  " +
        unclassified.join("\n  ") +
        "\n→ 它要么是外键（登记进 KEY_OWNERS，写明指向哪张表的哪一列），" +
        "要么是同名不同义（登记进 NAME_COLLISIONS 并写清差别）。\n" +
        "  留着不分类的后果：下一个按名字 join 的人会连错，而且连错了不报错。",
    ).toEqual([]);
  });

  it("登记的归属真实存在 —— 指向不存在的表或列即失效", () => {
    if (!schema.size) return;
    const broken = Object.entries(KEY_OWNERS)
      .filter(([key, o]) => !schema.get(o.table)?.has(o.col ?? key))
      .map(([key, o]) => `${key} → ${o.table}.${o.col ?? key}`);
    expect(
      broken,
      `KEY_OWNERS 里这些归属指向了不存在的表或列：${broken.join("、")}`,
    ).toEqual([]);
  });

  it("ER generation uses the shared key ownership and collision metadata", () => {
    const generator = readFileSync(join(ROOT, "scripts/gen-erd.mjs"), "utf8");
    expect(generator).toMatch(/import\s*\{\s*KEY_OWNERS,\s*NAME_COLLISIONS\s+as\s+NAME_COLLISIONS_WHY\s*\}\s*from\s*["']\.\/lib\/schema-keys\.mjs["']/);
    expect(generator).not.toMatch(/(?:const|let)\s+KEY_OWNERS\s*=/);
  });

  it("登记的同名冲突仍然存在 —— 解决了要删登记", () => {
    if (!schema.size) return;
    const stale = Object.keys(NAME_COLLISIONS).filter((k) => {
      const n = [...schema.values()].filter((cols) => cols.has(k)).length;
      return n < 2;
    });
    expect(
      stale,
      `以下列名已不再跨表冲突，请从 NAME_COLLISIONS 删除：${stale.join("、")}`,
    ).toEqual([]);
  });

  it("python 侧解析出同一套表与列 —— 两种语言的解析器不能分叉", () => {
    // gen-test-schema.py 用 python 重放同一批迁移，生成 H2 建表脚本。
    // 语言不同没法共用 scripts/lib/ddl.mjs，只能靠这条断言把它拴住。
    //
    // 分叉的后果是**单测跑在一套 schema 上、线上跑在另一套**：
    // python 侧曾经字典序排文件（V15 在 V2 前），ALTER 整批被丢弃而没有任何报错。
    const p = join(ROOT, "backend/shop-app/src/test/resources/schema-test.sql");
    if (!existsSync(p) || !schema.size) return;
    const h2 = readFileSync(p, "utf8");

    const inH2 = new Set(
      [...h2.matchAll(/CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)/gi)].map((m) => m[1]!),
    );
    const onlyJs = [...schema.keys()].filter((t) => !inH2.has(t));
    const onlyPy = [...inH2].filter((t) => !schema.has(t));
    expect(
      { onlyJs, onlyPy },
      "JS 与 python 解析出的表集合不一致 —— 重跑 npm run gen:erd 与 gen-test-schema.py",
    ).toEqual({ onlyJs: [], onlyPy: [] });
  });
});
