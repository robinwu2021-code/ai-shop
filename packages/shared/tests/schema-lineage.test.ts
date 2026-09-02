// 对账血缘守卫：资金账与积分账的关联通路必须连得上。
//
// 为什么需要它：这条链路**断了不会有任何编译错误，也不会有测试失败**。
// 把 `pts_user_ledger.sub_order_no` 改个名或删掉，代码照样编译、单测照样绿，
// 要等到月底对账对不上、或者商家问「我的积分兑付去哪了」才会发现。
// 而那时数据已经错了一个月。
//
// 通路（8 跳，来自 docs/technical/积分域-ER图.md）：
//   资金账 stl_payment → ord_order/ord_after_sale（收款与退款）
//         ord_sub_order → stl_bill → stl_split_log（计提与分账）
//   积分账 ord_sub_order →(points_fee)→ stl_bill →(settle_no)→ stl_points_pool
//                        stl_payment(SUBSIDY) →(sub_order_no)→ ord_sub_order
//         商家侧的积分账在 V34 删除：他只感知发分服务费，抵扣与兑付不可见
//   勾稽点 ord_sub_order.points_deduct_minor ↔ pts_user_ledger.amount_minor
import { existsSync, readFileSync, readdirSync } from "node:fs";
// @ts-expect-error -- 生成器是 .mjs，没有类型声明；它是 DDL 解析的唯一真源
import { readColumnNames } from "../../../scripts/lib/ddl.mjs";
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
    from: "ord_sub_order",
    fromCol: "sub_order_no",
    to: "pts_user_ledger",
    toCol: "sub_order_no",
    why:
      "本单发了多少分、抵了多少钱。断了就退款时不知道该扣回多少 —— " +
      "扣回是**按订单**找发放行，不是按发放商家追溯（V28）",
  },
  {
    lane: "积分账",
    from: "ord_sub_order",
    fromCol: "points_fee_minor",
    to: "stl_bill",
    toCol: "points_fee_minor",
    why:
      "商家发分的服务费，按单计提到结算单。**这是商家唯一感知到的积分成本** —— " +
      "抵扣与兑付对他不可见（V34）。断了商家就白发分，平台成本失控",
  },
  {
    lane: "积分账",
    from: "stl_bill",
    fromCol: "settle_no",
    to: "stl_points_pool",
    toCol: "ref_no",
    why:
      "收到的发分服务费进池（MERCHANT_RECEIVE）。断了池子就只出不进，" +
      "「池子余额 == 流通积分对应资金」这条恒等式立刻失衡",
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
  {
    lane: "勾稽点",
    from: "ord_sub_order",
    fromCol: "points_deduct_minor",
    to: "pts_user_ledger",
    toCol: "amount_minor",
    why: "两本账唯一的连接点：资金账里商家少收的 == 积分账里商家收到的",
  },
];

/**
 * 业务键的**归属登记**：这个 `xxx_no` 是谁的主键、指向哪张表的哪一列。
 *
 * 为什么要显式登记而不是从表名推断：真实的表名与主键列名经常对不上 ——
 * `stl_bill` 的主键叫 `settle_no`、
 * `cmt_pickup_point` 的叫 `pickup_no`。用「表名去前缀 + _no」去猜，六个键会猜错。
 *
 * 角色化外键（同一张表被引用多次、列名各不相同）用 `col` 指明真正指向的列，
 * 例如「发放方商家」`issuer_merchant_no` 指向 `usr_merchant.merchant_no`。
 */
const KEY_OWNERS: Record<string, { table: string; col?: string }> = {
  /*
   * ── 2026-08-29 补齐的十三个 ──
   * 归属判据不是靠猜：**看哪张表把它建成了唯一键**（schema-test.sql 里逐个查过），
   * 只有一张表建唯一键的就是它的主；两张都建的进 NAME_COLLISIONS。
   */
  // 商品：标准品库是全平台共享的，prd_goods 引用它
  std_no: { table: "prd_spu_std" },
  // 规格：维度与取值两级。七张表引用 dim_no，四张引用 value_no ——
  // 类目规格、商家自定义规格、商家覆盖都挂在同一套维度上
  dim_no: { table: "prd_spec_dim" },
  value_no: { table: "prd_spec_value" },
  topic_no: { table: "prd_topic" },
  /*
   * 事件号：sys_outbox 是主（它建的唯一键），sys_event_consumed 引用它。
   * 两张表是 Outbox 的一体两面 —— 前者记「发了什么」，后者记「谁处理过」。
   * **不是同名不同义**：按 event_no join 这两张表是正确用法，
   * 也正是排查「这个事件到底有没有被消费」时要做的第一件事。
   */
  event_no: { table: "sys_outbox" },
  // 履约：运单与轨迹是一对多
  shipment_no: { table: "ful_shipment" },
  // 商家服务范围。mch_channel_area 引用它 —— 渠道覆盖哪些服务区
  area_no: { table: "mch_service_area" },
  // 人档与会员：**两级，别混**。person_no 是跨商家的人，member_no 是他在某一家的会员身份。
  // 按名字把这两个当一个用，会把「这个人在 A 家的消费」算到 B 家头上
  person_no: { table: "usr_person" },
  member_no: { table: "mbr_member" },
  tag_no: { table: "mbr_tag" },
  segment_no: { table: "mbr_segment" },
  // 营销（promotion 这一族）
  activity_no: { table: "pmt_activity" },
  fission_no: { table: "mkt_fission_campaign" },
  // 推送任务。mbr_reach_log 引用它 —— 一次群发对应一条 task，多条 log
  task_no: { table: "notify_push_task" },

  // 主体（全库重建后 merchant_no → entity_no；merchant 一词只留给支付语境）
  entity_no: { table: "mch_entity" },
  // 门店。**与 entity_no 是两级**：钱与信用挂主体，货与人挂门店 —— 按名字
  // 把 store_no 当成 entity_no 用（或反过来）会在多门店之后连错，且不报错
  store_no: { table: "mch_store" },
  // 商家账号（B 端账号池本身，不是"员工附属表"），与平台运营 sys_ops_staff 无关
  mch_account_no: { table: "mch_account" },
  // 收款商户号业务键 —— 全库唯一合法的 "merchant" 用法；mch_store 引用它选收款号
  pay_merchant_no: { table: "mch_payment_merchant" },
  user_no: { table: "usr_account" },
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
  staff_no: { table: "sys_ops_staff" },
  settle_no: { table: "stl_bill" },
  after_sale_no: { table: "ord_after_sale" },
  payment_no: { table: "stl_payment" },
  // 用户的 USE 流水。商家进账挂它 —— 一次使用一条进账，见 LINEAGE 里那一跳
  use_ledger_no: { table: "pts_user_ledger", col: "ledger_no" },

  // 角色化外键：列名带角色前缀，指向的仍是主表的主键
  inviter_no: { table: "usr_account", col: "user_no" },
  // ⚠️ 命名欠账：按基准应叫 issuer_entity_no，改它要动积分域 Java 映射，随积分域下次动工时改
  issuer_merchant_no: { table: "mch_entity", col: "entity_no" },

  /*
   * 保证金账户与流水都按商家挂，指向的是商家主体。
   *
   * ⚠️ 与上面 issuer_merchant_no 同一笔命名欠账：按基准该叫 entity_no。
   * 建表时沿用了 merchant_no，因为准入这条链上（AdmissionPort、
   * AdmissionService、两个 VO）从头到尾都叫 merchantNo，
   * 只在两张表上改名会让读代码的人以为是两个东西。
   * 要改就连着这条链一起改，单独动表名是净亏。
   */
  merchant_no: { table: "mch_entity", col: "entity_no" },
};

/**
 * 已登记的**同名不同义**列。
 *
 * 这些列名一样但语义完全不同，**不可以按名字 join**。
 * 登记在此是为了让下一个做 schema 分析的人（或脚本）不再连错 ——
 * 我们自己就连错过一次。
 */
const NAME_COLLISIONS: Record<string, string> = {
  slot_no:
    "mch_appointment_slot 是**预约时段**（到店服务：周三 14:00–15:00 这一格）；" +
    "mkt_content_slot 是**内容位**（运营：首页第一屏那个楼层）。\n" +
    "  两族毫不相干，只是中文都能叫「位」。按名字 join 会把某个商家的预约时段" +
    "连到一个首页楼层上，而两边都有 slot_no 且都非空 —— 连错了不报错。",
  sort_no:
    "两张表各自的**排序序号**，不是任何东西的键：sys_market 是国家/地区在选择器里的顺序，" +
    "mkt_content_slot 是同类内容位之间的先后。\n" +
    "  同名同义（都是「小的在前」）但**没有共同的定义域**，按它 join 只会得到笛卡尔积。",
  message_no:
    "stl_channel_message 是**渠道报文**（支付：通道推给我方的回调、我方发给通道的调用）；" +
    "notify_message 是**站内消息**（通知：发给用户看的那条）。\n" +
    "  两族毫不相干，只是都叫「消息」。按名字 join 会把一条支付回调连到某个用户的收件箱上，" +
    "而两边都有 message_no 且都非空 —— 连错了不会报错，只会安静地少几行或多几行。",
  batch_no:
    "ful_batch 是**到货批次**（履约：一车货到自提点）；" +
    "sys_media_purge_batch 是**媒体清理批次**（运维：一次对象存储回收）。\n" +
    "  两族毫不相干，只是都叫「批次」。按名字 join 会把一次清理任务连到一车货上。",
  user_coupon_no:
    "mkt_user_coupon 与 pmt_user_coupon 是**两套并行的券模型**：\n" +
    "  mkt_* 是 marketing 那一族（CouponServiceImpl 用它），" +
    "pmt_* 是 promotion 那一族（OpsPromotionServiceImpl 用它）。\n" +
    "  **只有 pmt_* 登记进了数据域**，mkt_* 没有 —— 所以按名字混用两族，" +
    "除了连错行，还会让一半查询悄悄不受数据域约束。",
  issue_no:
    "同 user_coupon_no：mkt_coupon_issue 与 pmt_coupon_issue 各自建了唯一键，是两族。\n" +
    "  pmt_user_coupon 里的 issue_no 指的是 pmt_coupon_issue —— 同族内是外键，跨族不是。",
  tax_no:
    "**它根本不是编号，是纳税人识别号这个业务值**（ord_invoice_request 开票抬头填的、" +
    "stl_settle_invoice 结算发票上印的）。两张表都没给它建唯一键。\n" +
    "  同一个纳税人识别号本来就该在多张表里重复出现，按它 join 得到的是笛卡尔积。",

  receiver_no:
    "notify_message 与 notify_push_token 都是「收件人编号」，但**都是多态的**：\n" +
    "  真正的含义由同表的 receiver_type 决定 —— USER 时是 usr_user.user_no，" +
    "STAFF 时是 mch_staff.staff_no（两族编号，取值空间不重叠但也不保证）。\n" +
    "  所以它不是任何一张表的外键，按名字 join 会把店员的设备连到买家身上：" +
    "**推送发错人**，而且不报错。要连必须带上 receiver_type 一起连。",
  request_no:
    "mkt_request 是求团需求单号；stl_split_log 是分账幂等号；" +
    "ord_invoice_request 是开票申请单号。**三族**，互不相干。\n" +
    "  建表时已发现约束名会撞车并加了 uk_split_request_no 前缀，但列名的撞车还在。",
  invoice_no:
    "**三个方向的票，这是最危险的一组同名**：\n" +
    "  stl_purchase_invoice 是**进项**（供应商开给平台，决定平台能不能列支成本）；\n" +
    "  ord_invoice_request 是**销项·对消费者**（平台开给买家，ADR-017 §3.4 条件 2，" +
    "决定归集资金模式成不成立）；\n" +
    "  stl_settle_invoice 是**销项·对商家**（平台开给商家的结算凭证，P-12.2.4）。\n" +
    "  义务人、方向、法律后果各不相同 —— 按名字 join 出来的「发票」会把进项当销项，" +
    "而三边都有值、都不报错。",
  express_no: "ord_sub_order 是发货快递单号；ord_after_sale 是用户退货的快递单号。方向相反。",
  apply_no:
    "mch_entity_apply 是商家入驻申请单；cmt_community_apply 是商家提报新社区的单子。\n" +
    "  两者都是「某人申请某事」，但申请的对象完全不同 —— 按名字 join 会把入驻单连到社区提报上。",
  ref_no:
    "stl_points_pool 是积分池指向的业务单据；mch_store_audit 是审核单指向的业务记录" +
    "（kind=SERVICE_AREA 时是 mch_service_area.area_no）。\n" +
    "  两者都是「指针列」，但**指向的表由同行的另一列决定**（前者看 pool 类型，后者看 kind）——" +
    "所以谁都不能登记成外键，按名字 join 必然连错，且不报错。",
  operator_no: "ord_status_log 与 ful_verify_log 各自记录操作人，不是同一张表的外键。",
  template_no:
    "**同一个列名下其实是两族**：notify_template 是消息模板，notify_message.template_no 引用它；" +
    "prd_spec_template.template_no 是商品规格模板（颜色/尺码那套），与消息毫无关系。\n" +
    "  按名字 join 会把「发过哪条推送」连到「用了哪个规格模板」上 —— 两边都有值，不报错。\n" +
    "  没改名是因为两族在各自的域里都叫得通；登记在这里，是为了让下一个人先看到这句话。\n" +
    "  注意 notify_message → notify_template 这一跳**没有守卫** —— LINEAGE 只管资金账与积分账，" +
    "触达链路断了的后果（推送发不出去）不在这个文件的职责里。",
  txn_no:
    "mch_deposit_txn.txn_no 是保证金流水号（平台代管商家资金，将来要退还）；\n" +
    "  mch_debt_txn.txn_no 是商家欠款流水号（平台垫付后向商家追偿）。\n" +
    "  两者都是各自表的唯一业务键（各自建了 uk），资金方向相反 ——" +
    "按名字 join 会把保证金扣款连到欠款追偿上，而两边都有值、不报错。",
  source_no:
    "mbr_member_source.source_no 是会员引荐来源码（记录「这个会员从哪个推广渠道来」，建了全局唯一键）；\n" +
    "  mch_debt_txn.source_no 是欠款来源单号（指向售后单号或结算单号，可为空，由 source_type 决定含义）。\n" +
    "  两者都是「来源」但完全不同义 —— 按名字 join 会把会员拉新来源连到商家欠款上。",
  log_no:
    "mch_staff_log.log_no 是员工操作日志的流水号；\n" +
    "  pay_risk_shadow_log.log_no 是支付风控影子模式拦截记录的流水号（只记录、不真拦）。\n" +
    "  两者都是各自表的唯一业务键，所属域完全不同 —— 按名字 join 会把员工操作连到风控影子记录上。",
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

  it("与 gen-erd.mjs 的键归属表一致 —— 两份分叉会让 ER 图连错线", async () => {
    // ER 图生成器要靠同一份归属画关系。两处各存一份必然漂移，
    // 而漂移的表现是「图上少了一条线」或「连到了错的表」—— 没人会发现。
    const gen = (await import("../../../scripts/gen-erd.mjs")) as {
      KEY_OWNERS: Record<string, string>;
    };
    const mine = Object.fromEntries(
      Object.entries(KEY_OWNERS).map(([k, v]) => [k, v.table]),
    );
    expect(gen.KEY_OWNERS, "gen-erd.mjs 与本文件的 KEY_OWNERS 不一致").toEqual(mine);
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
