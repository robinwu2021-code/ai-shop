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
import { existsSync, readFileSync } from "node:fs";
// @ts-expect-error -- 生成器是 .mjs，没有类型声明；它是 DDL 解析的唯一真源
import { readColumnNames, readSchema as readDdlSchema } from "../../../scripts/lib/ddl.mjs";
// @ts-expect-error -- shared pure JavaScript lineage API
import { KEY_OWNERS, TABLE_KEY_OWNERS, NAME_COLLISIONS, relations, resolveReference, columnBadge, unclassifiedBusinessKeys, describeRelation } from "../../../scripts/lib/schema-lineage.mjs";
import { join } from "node:path";
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
  const tables = readDdlSchema(ROOT);

  it("共用人员键归属明确且不把账号关联或岗位编号误连到其他业务", () => {
    expect(KEY_OWNERS.personnel_no).toEqual({ table: "mch_personnel" });
    expect(KEY_OWNERS.assignment_no).toEqual({ table: "mch_personnel_store" });
    expect(NAME_COLLISIONS.link_no).toContain("人员");
    expect(NAME_COLLISIONS.position_no).toContain("岗位");
    expect(schema.get("mch_personnel_store")?.has("personnel_no")).toBe(true);
    expect(schema.get("mch_personnel_position")?.has("assignment_no")).toBe(true);
  });

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

    const unclassified = unclassifiedBusinessKeys(tables);

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
  const tenant = [{ from: "tenant_no", to: "tenant_no" }];
  const ref = (from: string, col: string, to: string, toCol = col, scope = tenant) => ({ from, col, to, toCol, scope });
  const byColumn = (col: string) => relations(tables).filter((r: any) => r.col === col);

  // A global marketing owner would fabricate checkout edges and lose tenant scope.
  it("quote_no 精确两条 tenant 内同族关系", () => {
    expect(byColumn("quote_no")).toEqual([
      ref("mkt_quote_revision", "quote_no", "mkt_quote"),
      ref("ord_checkout_confirmation", "quote_no", "ord_checkout_quote"),
    ]);
  });

  it("quote_no 两根不连自身或跨族，结算确认不连营销报价", () => {
    for (const root of ["mkt_quote", "ord_checkout_quote"]) {
      expect(schema.get(root)?.has("quote_no")).toBe(true);
      expect(resolveReference(tables, root, "quote_no"), root).toBeNull();
      expect(byColumn("quote_no").filter((r: any) => r.from === root)).toEqual([]);
      expect(columnBadge(tables, root, "quote_no")).not.toContain('fk');
    }
    expect(byColumn("quote_no").filter((r: any) =>
      (r.from.startsWith("ord_checkout_") && r.to === "mkt_quote") ||
      (r.from.startsWith("mkt_") && r.to === "ord_checkout_quote")
    )).toEqual([]);
  });

  it("仅 operation.current_quote_no 引用 tenant 内结算报价", () => {
    const expected = ref("ord_checkout_operation", "current_quote_no", "ord_checkout_quote", "quote_no");
    expect(byColumn("current_quote_no")).toEqual([expected]);
    expect(resolveReference(tables, "ord_checkout_operation", "current_quote_no")).toEqual(expected);
    expect(columnBadge(tables, "ord_checkout_operation", "current_quote_no")).toBe('<i class="k fk">FK</i>');
    const extended = new Map(tables);
    extended.set("unreviewed_operation", { cols: [{ name: "current_quote_no" }, { name: "tenant_no" }], uniques: new Set() });
    expect(resolveReference(extended, "unreviewed_operation", "current_quote_no")).toBeNull();
    expect(columnBadge(extended, "unreviewed_operation", "current_quote_no")).toBe("");
  });

  it("quote_no 未知第五表拒绝分类和 FK，保留 UK 优先", () => {
    const extended = new Map(tables);
    extended.set("unreviewed_quote", { cols: [{ name: "quote_no" }, { name: "tenant_no" }], uniques: new Set() });
    expect(resolveReference(extended, "unreviewed_quote", "quote_no")).toBeNull();
    expect(relations(extended).filter((r: any) => r.from === "unreviewed_quote")).toEqual([]);
    expect(columnBadge(extended, "unreviewed_quote", "quote_no")).toBe("");
    expect(unclassifiedBusinessKeys(extended).filter((x: string) => x.startsWith("quote_no（"))).toEqual([
      "quote_no（出现在 mkt_quote、mkt_quote_revision、ord_checkout_quote、ord_checkout_confirmation、unreviewed_quote）",
    ]);
    extended.get("unreviewed_quote").uniques.add("quote_no");
    expect(columnBadge(extended, "unreviewed_quote", "quote_no")).toBe('<i class="k uk">UK</i>');
    expect(resolveReference(extended, "unreviewed_quote", "quote_no")).toBeNull();
  });

  it("voucher_no 精确四条同族关系且不跨族、不连根、不 self", () => {
    expect(byColumn("voucher_no").some((r: any) => r.from.startsWith("sv_") && r.to === "pts_voucher"), "stored-value must never reference points voucher").toBe(false);
    expect(byColumn("voucher_no").some((r: any) => r.from.startsWith("pts_") && r.to === "sv_voucher"), "points must never reference stored-value voucher").toBe(false);
    expect(byColumn("voucher_no")).toEqual([
      ref("pts_ledger_entry", "voucher_no", "pts_voucher", "voucher_no", []),
      ref("pts_pool_flow", "voucher_no", "pts_voucher", "voucher_no", []),
      ref("sv_entry", "voucher_no", "sv_voucher"),
      ref("sv_posting_line", "voucher_no", "sv_voucher"),
    ]);
    for (const t of ["pts_voucher", "sv_voucher"]) {
      expect(resolveReference(tables, t, "voucher_no")).toBeNull();
    }
    expect(relations(tables).some((r: any) => r.from === r.to)).toBe(false);
  });

  it.each([
    ["pmt_migration_decision", "plan_no", "ent_migration_plan", "plan_no", tenant],
    ["ent_migration_import", "plan_no", "ent_migration_plan", "plan_no", tenant],
    ["ent_legacy_coupon_operation", "source_order_no", "ord_order", "order_no", tenant],
    ["ent_sale_reservation", "grant_no", "ent_grant", "grant_no", tenant],
    ["pmt_pay_coupon_projection", "grant_no", "ent_grant", "grant_no", tenant],
    ["ord_checkout_payment_request", "actor_account_no", "mch_account", "mch_account_no", tenant],
    ["stl_payment_operation", "actor_account_no", "mch_account", "mch_account_no", tenant],
    ["pmt_activity_reservation", "tier_no", "pmt_recharge_tier", "tier_no", [...tenant, { from: "version_no", to: "version_no" }]],
    ["prd_store_catalog_version", "catalog_no", "prd_store_catalog", "catalog_no", [...tenant, { from: "entity_no", to: "entity_no" }]],
  ])("限定正向关系 %s.%s 保留目标列和全部 scope", (from, col, to, toCol, scope) => {
    const expected = ref(from as string, col as string, to as string, toCol as string, scope as typeof tenant);
    expect(resolveReference(tables, from, col)).toEqual(expected);
    expect(relations(tables)).toContainEqual(expected);
    const uniqueSource = ["pmt_migration_decision", "ent_migration_import", "prd_store_catalog_version"].includes(from as string);
    expect(columnBadge(tables, from, col)).toBe(uniqueSource ? '<i class="k uk">UK</i>' : '<i class="k fk">FK</i>');
    const description = describeRelation(expected);
    expect(description).toContain(`${from}.${col} → ${to}.${toCol}`);
    for (const pair of scope as typeof tenant) expect(description).toContain(`${from}.${pair.from} = ${to}.${pair.to}`);
  });

  it("buyer 角色四表指 user_no；confirmation 与迁移 return 保留 tenant", () => {
    expect(byColumn("buyer_no")).toEqual([
      ref("ord_checkout_operation", "buyer_no", "usr_account", "user_no"),
      ref("ord_checkout_confirmation", "buyer_no", "usr_account", "user_no"),
      ref("ord_checkout_payment_request", "buyer_no", "usr_account", "user_no"),
      ref("stl_payment_operation", "buyer_no", "usr_account", "user_no"),
    ]);
    expect(byColumn("confirmation_no")).toEqual([ref("ord_checkout_operation", "confirmation_no", "ord_checkout_confirmation")]);
    expect(byColumn("return_no")).toEqual([ref("ent_migration_return", "return_no", "pmt_migration_return")]);
  });

  it.each([
    ["ent_ledger", "instance_no", "ent_instance", "instance_no"],
    ["ent_use_reversal", "usage_no", "ent_usage", "usage_no"],
    ["sv_lot", "recharge_no", "sv_recharge", "recharge_no"],
    ["sv_entry", "lot_no", "sv_lot", "lot_no"],
    ["pay_ent_exception_action", "exception_no", "pay_ent_exception", "exception_no"],
    ["pay_ent_recon_diff", "recon_no", "pay_ent_recon_run", "recon_no"],
    ["ent_migration_plan", "stage_no", "ent_migration_stage", "stage_no"],
    ["ent_migration_plan", "alias_no", "ent_migration_alias", "alias_no"],
    ["ent_legacy_coupon_operation", "checkout_no", "ord_legacy_coupon_checkout", "checkout_no"],
    ["ent_migration_asset", "import_no", "ent_migration_import", "import_no"],
    ["ent_migration_return", "return_no", "pmt_migration_return", "return_no"],
    ["ent_legacy_coupon_issue", "target_template_no", "ent_template", "template_no"],
    ["ent_grant", "template_version_no", "ent_template_version", "version_no"],
    ["sv_recharge", "activity_version_no", "pmt_activity_version", "version_no"],
  ])("真实普通/角色引用 %s.%s → %s.%s", (from, col, to, toCol) => {
    expect(resolveReference(tables, from, col)).toEqual(ref(from, col, to, toCol));
    expect(relations(tables)).toContainEqual(ref(from, col, to, toCol));
  });

  it("协议/条件/局部键的每个实际源表均无无条件 FK", () => {
    const negatives: Record<string, string[]> = {
      receipt_no: ["stl_payment", "ent_migration_return"],
      source_event_no: ["sys_outbox", "pmt_effect_projection"],
      entry_no: ["pts_ledger_entry", "ent_ledger"],
      line_no: ["prd_stock_alignment_line", "sv_posting_line"],
      execution_no: ["ord_refund_execution", "ord_refund_obligation"],
      actor_account_no: ["mch_staff_log"],
      plan_no: ["pts_topup_plan", "ent_migration_plan"],
      source_order_no: ["ent_usage"],
      grant_no: ["ent_instance", "ent_grant"],
      tier_no: ["pmt_recharge_tier"],
      catalog_no: ["prd_store_catalog"],
    };
    for (const [col, sources] of Object.entries(negatives)) for (const from of sources) {
      expect(schema.get(from)?.has(col), `${from}.${col} really exists`).toBe(true);
      expect(resolveReference(tables, from, col), `${from}.${col}`).toBeNull();
      expect(byColumn(col).filter((r: any) => r.from === from), `${from}.${col}`).toEqual([]);
      expect(columnBadge(tables, from, col), `${from}.${col}`).not.toContain('fk');
    }
  });

  const fixture = (source = ["buyer_no", "tenant_no"], target = ["user_no", "tenant_no"], uniques: string[] = []) => new Map([
    ["fixture_source", { cols: source.map(name => ({ name })), uniques: new Set(uniques) }],
    ["usr_account", { cols: target.map(name => ({ name })), uniques: new Set() }],
  ]);
  it("目标角色列及 scope 双方列必须存在；badge 使用同一真实 resolver", () => {
    for (const f of [fixture(undefined, ["tenant_no"]), fixture(["buyer_no"]), fixture(undefined, ["user_no"]), fixture(["tenant_no"])]) {
      expect(relations(f)).toEqual([]);
      expect(columnBadge(f, "fixture_source", "buyer_no")).toBe("");
    }
    expect(relations(fixture())).toEqual([ref("fixture_source", "buyer_no", "usr_account", "user_no")]);
    expect(columnBadge(fixture(), "fixture_source", "buyer_no")).toBe('<i class="k fk">FK</i>');
    expect(columnBadge(fixture(undefined, undefined, ["buyer_no"]), "fixture_source", "buyer_no")).toBe('<i class="k uk">UK</i>');
    expect(columnBadge(fixture(undefined, ["tenant_no"], ["buyer_no"]), "fixture_source", "buyer_no")).toBe('<i class="k uk">UK</i>');
  });

  it.each(["receipt_no", "source_event_no", "actor_account_no", "execution_no", "entry_no", "line_no", "catalog_no", "voucher_no", "tier_no", "plan_no", "source_order_no", "grant_no"])("审计冲突 %s 未知新成员必须拒绝分类及 edge", col => {
    const extended = new Map(tables);
    extended.set("unreviewed_table", { cols: [{ name: col }, { name: "tenant_no" }], uniques: new Set() });
    expect(resolveReference(extended, "unreviewed_table", col)).toBeNull();
    expect(columnBadge(extended, "unreviewed_table", col)).toBe("");
    expect(unclassifiedBusinessKeys(extended).some((x: string) => x.startsWith(`${col}（`) && x.includes("unreviewed_table"))).toBe(true);
  });

  it("全登记 owner 和限定引用目标及 scope 列真实存在", () => {
    for (const [from, owners] of Object.entries(TABLE_KEY_OWNERS) as [string, Record<string, any>][]) for (const [col, owner] of Object.entries(owners)) {
      expect(schema.get(from)?.has(col), `${from}.${col}`).toBe(true);
      expect(schema.get(owner.table)?.has(owner.col ?? col)).toBe(true);
      for (const pair of owner.scope ?? []) {
        expect(schema.get(from)?.has(pair.from)).toBe(true);
        expect(schema.get(owner.table)?.has(pair.to)).toBe(true);
      }
    }
  });

  it("原两条 points LINEAGE 是实际生产关系", () => {
    for (const from of ["pts_ledger_entry", "pts_pool_flow"]) expect(relations(tables)).toContainEqual(ref(from, "voucher_no", "pts_voucher", "voucher_no", []));
  });

  it("条件说明保留真实链路及限定语义", () => {
    expect(NAME_COLLISIONS.source_order_no).toContain("SERVICE_USE");
    expect(NAME_COLLISIONS.source_order_no).toContain("ord_sub_order.sub_order_no");
    expect(NAME_COLLISIONS.grant_no).toContain("MIGRATION_BASELINE");
    expect(NAME_COLLISIONS.grant_no).toContain("ent_migration_asset.baseline_no");
    expect(NAME_COLLISIONS.execution_no).toContain("PRF-");
    expect(NAME_COLLISIONS.execution_no).toContain("ord_points_refund_execution");
    expect(NAME_COLLISIONS.execution_no).toContain("ord_refund_execution");
  });

});
