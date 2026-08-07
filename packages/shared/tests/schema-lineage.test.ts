// 对账血缘守卫：资金账与积分账的关联通路必须连得上。
//
// 为什么需要它：这条链路**断了不会有任何编译错误，也不会有测试失败**。
// 把 `pts_redeem_alloc.sub_order_no` 改个名或删掉，代码照样编译、单测照样绿，
// 要等到月底对账对不上、或者商家问「我的积分兑付去哪了」才会发现。
// 而那时数据已经错了一个月。
//
// 通路（8 跳，来自 docs/technical/积分域-ER图.md）：
//   资金账 ord_sub_order → stl_bill → stl_split_log
//   积分账 ord_sub_order → pts_redeem_alloc → pts_merchant_ledger
//                        → stl_points_bill → stl_points_pool
//   勾稽点 ord_sub_order.points_deduct_minor ↔ pts_redeem_alloc.amount_minor
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");

/**
 * 解析迁移得到「表 → 列集合」。
 *
 * 两处是踩过坑才这么写的，改动前先读：
 *   ① **按版本号数字排序**，不是字典序 —— 字典序把 V15 排在 V2 前面，
 *      于是 ALTER 在建表之前重放，结果全错（gen-test-schema.py 真出过这个 bug）。
 *   ② **必须应用 ALTER** —— 只看 CREATE TABLE 得到的是已被后续迁移改过的旧结构。
 */
function readSchema(): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  if (!existsSync(MIGRATION_DIR)) return out;

  const sql = readdirSync(MIGRATION_DIR)
    .filter((f) => f.endsWith(".sql"))
    .sort((a, b) => (parseInt(a.slice(1), 10) || 0) - (parseInt(b.slice(1), 10) || 0))
    .map((f) => readFileSync(join(MIGRATION_DIR, f), "utf8"))
    .join("\n");

  for (const m of sql.matchAll(
    /CREATE TABLE(?: IF NOT EXISTS)? (\w+)\s*\(([\s\S]*?)\n\)\s*ENGINE/g,
  )) {
    const cols = new Set<string>();
    for (const raw of m[2]!.split("\n")) {
      const c = raw
        .trim()
        .match(/^(\w+)\s+(BIGINT|VARCHAR|INT|TINYINT|SMALLINT|DATETIME|TEXT|JSON|DECIMAL|CHAR)/i);
      if (c && !/^(KEY|UNIQUE|PRIMARY|INDEX|CONSTRAINT|FULLTEXT)$/i.test(c[1]!)) cols.add(c[1]!);
    }
    out.set(m[1]!, cols);
  }

  for (const m of sql.matchAll(
    /ALTER TABLE\s+(\w+)\s+(ADD COLUMN|RENAME COLUMN|DROP COLUMN)\s+(\w+)(?:\s+TO\s+(\w+))?/gi,
  )) {
    const t = out.get(m[1]!);
    if (!t) continue;
    const op = m[2]!.toUpperCase();
    if (op === "ADD COLUMN") t.add(m[3]!);
    else if (op === "DROP COLUMN") t.delete(m[3]!);
    else if (op === "RENAME COLUMN" && m[4]) {
      t.delete(m[3]!);
      t.add(m[4]);
    }
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
    from: "ord_sub_order",
    fromCol: "sub_order_no",
    to: "pts_redeem_alloc",
    toCol: "sub_order_no",
    why: "本单的积分抵扣拆到了哪几个批次；断了就退款时不知道该返还谁的分",
  },
  {
    lane: "积分账",
    from: "pts_redeem_alloc",
    fromCol: "alloc_no",
    to: "pts_merchant_ledger",
    toCol: "alloc_no",
    why: "一条兑付产生商家侧收/付两条流水；断了就算不出谁欠谁",
  },
  {
    lane: "积分账",
    from: "pts_merchant_ledger",
    fromCol: "merchant_no",
    to: "stl_points_bill",
    toCol: "merchant_no",
    why: "账期单按商家聚合流水",
  },
  {
    lane: "积分账",
    from: "pts_merchant_ledger",
    fromCol: "period",
    to: "stl_points_bill",
    toCol: "period",
    why: "账期单按 (merchant_no, period) 唯一；少了 period 就聚不出期",
  },
  {
    lane: "积分账",
    from: "stl_points_bill",
    fromCol: "bill_no",
    to: "stl_points_pool",
    toCol: "ref_no",
    why: "账期结算走平台备付池；断了就对不出池子的钱花在哪",
  },
  {
    lane: "勾稽点",
    from: "ord_sub_order",
    fromCol: "points_deduct_minor",
    to: "pts_redeem_alloc",
    toCol: "amount_minor",
    why: "两本账唯一的连接点：资金账里商家少收的 == 积分账里商家收到的",
  },
];

/**
 * 业务键的**归属登记**：这个 `xxx_no` 是谁的主键、指向哪张表的哪一列。
 *
 * 为什么要显式登记而不是从表名推断：真实的表名与主键列名经常对不上 ——
 * `stl_bill` 的主键叫 `settle_no`、`pts_redeem_alloc` 的叫 `alloc_no`、
 * `cmt_pickup_point` 的叫 `pickup_no`。用「表名去前缀 + _no」去猜，六个键会猜错。
 *
 * 角色化外键（同一张表被引用多次、列名各不相同）用 `col` 指明真正指向的列，
 * 例如「发放方商家」`issuer_merchant_no` 指向 `usr_merchant.merchant_no`。
 */
const KEY_OWNERS: Record<string, { table: string; col?: string }> = {
  merchant_no: { table: "usr_merchant" },
  user_no: { table: "usr_user" },
  order_no: { table: "ord_order" },
  sub_order_no: { table: "ord_sub_order" },
  community_no: { table: "cmt_community" },
  pickup_no: { table: "cmt_pickup_point" },
  goods_no: { table: "prd_goods" },
  sku_no: { table: "prd_sku" },
  category_no: { table: "prd_category" },
  coupon_no: { table: "mkt_coupon" },
  quote_no: { table: "mkt_quote" },
  group_no: { table: "mkt_group_buy" },
  review_no: { table: "rvw_review" },
  staff_no: { table: "sys_staff" },
  settle_no: { table: "stl_bill" },
  alloc_no: { table: "pts_redeem_alloc" },

  // 角色化外键：列名带角色前缀，指向的仍是主表的主键
  inviter_no: { table: "usr_user", col: "user_no" },
  issuer_merchant_no: { table: "usr_merchant", col: "merchant_no" },
};

/**
 * 已登记的**同名不同义**列。
 *
 * 这些列名一样但语义完全不同，**不可以按名字 join**。
 * 登记在此是为了让下一个做 schema 分析的人（或脚本）不再连错 ——
 * 我们自己就连错过一次。
 */
const NAME_COLLISIONS: Record<string, string> = {
  request_no:
    "mkt_request 是求团需求单号；stl_split_log 是分账幂等号。" +
    "建表时已发现约束名会撞车并加了 uk_split_request_no 前缀，但列名的撞车还在。",
  ledger_no: "pts_user_ledger 与 pts_merchant_ledger 各自的主业务键，互不引用。",
  express_no: "ord_sub_order 是发货快递单号；ord_after_sale 是用户退货的快递单号。方向相反。",
  operator_no: "ord_status_log 与 ful_verify_log 各自记录操作人，不是同一张表的外键。",
};

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
});
