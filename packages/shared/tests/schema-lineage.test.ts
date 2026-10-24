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
  // Current merchant, stored-value, pricing and print migration ownership.
  first_store_no: { table: "mch_store", col: "store_no" },
  execution_no: { table: "ord_refund_execution" },
  customer_no: { table: "sv_customer_identity" },
  issuer_no: { table: "sv_issuer" },
  scope_member_no: { table: "mbr_scope_member" },
  issuer_version_no: { table: "sv_issuer_version" },
  plan_version_no: { table: "sv_recharge_plan_version", col: "version_no" },
  release_no: { table: "sv_release" },
  collection_entity_no: { table: "mch_entity", col: "entity_no" },
  settlement_entity_no: { table: "mch_entity", col: "entity_no" },
  operating_entity_no: { table: "mch_entity", col: "entity_no" },
  management_no: { table: "mch_management_space" },
  brand_no: { table: "mch_brand" },
  change_no: { table: "mch_brand_change" },
  config_no: { table: "ord_store_pricing_config" },
  template_revision_no: { table: "ord_pricing_template_revision", col: "revision_no" },
  quota_no: { table: "sv_issue_quota" },
  quote_line_no: { table: "ord_price_quote_line" },
  device_no: { table: "prn_device" },
  job_no: { table: "prn_job" },

  build_no: { table: "pts_statement_build" },
  voucher_no: { table: "pts_voucher" },
  evidence_no: { table: "pts_fund_evidence" },
  receivable_no: { table: "pts_receivable" },
  statement_no: { table: "pts_store_statement", col: "position_no" },
  cash_payment_no: { table: "stl_payment", col: "payment_no" },
  origin_payment_no: { table: "stl_payment", col: "payment_no" },
  pay_no: { table: "ord_create_intent" },
  tier_no: { table: "pmt_recharge_tier" },
  grant_no: { table: "ent_grant" },
  instance_no: { table: "ent_instance" },
  usage_no: { table: "ent_usage" },
  source_order_no: { table: "ord_order", col: "order_no" },
  recharge_no: { table: "sv_recharge" },
  lot_no: { table: "sv_lot" },
  exception_no: { table: "pay_ent_exception" },
  recon_no: { table: "pay_ent_recon_run" },
  stage_no: { table: "ent_migration_stage" },
  alias_no: { table: "ent_migration_alias" },
  checkout_no: { table: "ord_legacy_coupon_checkout" },
  plan_no: { table: "ent_migration_plan" },
  import_no: { table: "ent_migration_import" },
  return_no: { table: "pmt_migration_return" },
  target_template_no: { table: "ent_template", col: "template_no" },
  template_version_no: { table: "ent_template_version", col: "version_no" },
  activity_version_no: { table: "pmt_activity_version", col: "version_no" },

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
  shipment_no: { table: "lgs_waybill" },
  /*
   * 商户订单号：stl_payment 是主（它建的唯一键），trd_shipping_upload 引用它。
   * 后者是微信「发货信息录入」的上报台账（V323），一次上报对应一笔支付 ——
   * **按 out_trade_no join 这两张表是正确用法**，排查「这笔付了但微信说没发货」
   * 时要做的第一件事就是它。不是同名不同义：两边都是同一个商户订单号，
   * 传给微信的也是这一个值。
   */
  out_trade_no: { table: "stl_payment" },
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
  // 社区集单的期（V335）：ord_sub_order.period_no 指向 pmt_period
  period_no: { table: "pmt_period" },
  // 平台活动报名单（V337）：pmt_enrollment_goods.enrollment_no 指向它
  enrollment_no: { table: "pmt_enrollment" },
  review_no: { table: "rvw_review" },
  staff_no: { table: "sys_ops_staff" },
  settle_no: { table: "stl_bill" },
  // 放款单（V391）：stl_payout 的业务键，stl_bill.payout_no 引用它（一笔网银转账一条）
  payout_no: { table: "stl_payout" },
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
  source_event_no: "来源系统各自产生的事件号；必须结合source_system及原始来源，不能仅按同名跨系统关联。",
  rule_no: "stl_fee_rule是结算费率规则；ord_price_quote_allocation保存本次试算规则标识，两套规则目录独立。",
  subject_no: "角色成员与报价/人工改价都使用主体多态编号；需同时核验各自subject_type，不能当成单一外键。",
  link_no: "会员来源、旧会员迁移关系与储值活动发布/审批各有独立关联记录；编号仅在对应关系族内有效。",
  attempt_no: "物流订阅尝试与打印投递尝试独立生成，不能混为同一次执行。",
  entry_no: "pts_ledger_entry积分分录与ent_ledger权益分录是不同账本，不共用分录身份。",
  binding_no: "订单积分接线与品牌门店有效期绑定是不同聚合，不能按同名连接。",
  adjustment_no: "积分人工调整与订单人工改价分别有自己的命令/审计身份。",
  line_no: "库存对齐行与储值过账行只在各自父单据内编号，不能作为全局行身份。",
  allocation_no: "储值冻结批次分配与报价金额分摊有不同对象、数量和金额语义。",
  target_no: "权益所有者注册与储值发行政策的目标都是多态范围，必须结合target_type及所属域。",
  document_no: "Core与Pay分别分配业务展示单号；通知保存来源单号，必须结合来源系统/单据类型，不能假定单一主表。",
  active_revision_no: "行业模板与门店配置各自引用自己的不可变发布版本，按源表指定目标。",
  previous_revision_no: "行业模板版本与门店配置版本各自形成历史链，不能跨版本族关联。",
  draft_revision_no: "储值发布和Core活动审批冻结对应活动的草稿标识，必须连同activity_no及来源版本解释，非全库通用版本键。",
  policy_no: "SKU定价策略、储值发行管控、人工改价策略是独立政策族，不能按同名跨域关联。",
  bridge_actor_no: "打印桥接认证主体标识，需结合可信桥接凭证与租户核验；不是用户或运营账号表的通用外键。",
  voucher_no: "积分凭证与储值凭证是独立账本；须按来源表选择pts_voucher或sv_voucher，禁止跨账本按号猜关联。",
  quote_no: "营销报价与订单试算快照是独立报价族，须按来源表选择权威报价。",
  plan_no: "权益迁移计划与储值充值方案是独立计划族；同名不表示相同方案。",
  grant_no: "权益发放记录与经营管理授权是独立授权族，须按来源表解释。",

  trade_no: "stl_payment 与 ord_payment_resolution 保存渠道交易号快照；该号仅在 pay_channel 范围内唯一，不能脱离渠道直接关联。",
  source_shipment_no:
    "lgs_waybill 与 lgs_channel_subscription 保存同一个上游发货身份的快照；更新订阅必须同时匹配 source_shipment_version，不能把历史订阅行当作当前运单主记录。",
  parent_no: "不同层级树的父节点，必须结合所属树。",
  provider_no: "通道回执需结合通道、资金方向与原请求。",
  scope_no: "角色授权范围与积分开关统计范围，须按各自范围类型解释。",
  revision_no: "商品版本与积分账单版本，各自独立。",
  holder_no: "积分持有人多态编号，必须结合 holder_type。",
  position_no: "不同种类积分持仓的多态编号，必须结合持仓类型。",
  ticket_no: "积分资金指令与客服工单的独立编号，不可直接关联。",
  migration_batch_no: "旧券迁移批次按tenant/sourceSystem/sourceTemplateNo限定，不是全局迁移主键；目标原子导入主键是import_no。",
  aggregate_no: "订单状态聚合与Pay Outbox的多态聚合号，各自携带聚合类型。",
  diff_no: "结算差异stl_recon_diff与权益差异pay_ent_recon_diff分别发号。",
  current_version_no: "pmt_activity指活动版本，ent_template指权益模板版本，须按所属表限定。",
  biz_no: "通道消息、储值凭证和权益异常各自按biz_type解释的多态业务号。",
  account_no: "mch_payout_account为银行出款账户；sv_account为储值账户。",
  version_no: "活动版本和权益模板版本各自发号；活动子表指pmt_activity_version，ent_template_version自持权益版本。",
  resource_no: "幂等结果和对账差异按对应资源类型解释，不是同一实体。",
  actor_no: "审计操作人可为商家账号、运营或系统，必须结合actor_type解释。",
  decision_no: "Core订单源决定与旧券迁移永久源决定分别发号。",
  reservation_no: "活动资源占用与Pay售卡承诺分别发号，关联必须结合业务协议。",
  source_decision_no: "Core订单源决定或迁移源决定，由source协议与purpose限定。",
  operation_no: "统一使用意图或旧券原操作号，由protocol决定根表，不能仅按号合并。",
  intent_no: "活动发权益与储值充值协调意图分别发号。",
  source_template_no: "旧MKT或PMT根号，须同时校验source_system与tenant，禁止同号猜测。",
  source_instance_no: "原旧券实例号，须同时校验source_system与tenant，禁止跨来源连接。",
  source_usage_no: "原历史流水号，须同时校验source_system与source_instance_no。",

  parent_no: "cmt_community.parent_no是社区层级，prd_category.parent_no是商品类目层级；各自树内引用，不能按全局名字连线。",
  waybill_no:
    "两边是**同一个快递运单号**，但它**不是外键**：运单号只在承运商内唯一，lgs_waybill 按（承运商, 运单号）登记；" +
    "ord_shipment（订单服务 2.0 发货单，V401）记的是商家录入的号。\n" +
    "  只按 waybill_no join 会把两家快递撞号的单连到一起 —— 要连必须带上承运商（ord_shipment.carrier = lgs_waybill.carrier）。",
  flow_no:
    "stl_points_pool 的是**积分池流水号**（平台自己发号，记池子的每一次增减）；" +
    "stl_bank_flow 的是**银行给的流水号**（人工从网银导出，是出款对账的外部判据）。\n" +
    "  两者的发号方不同、值域也不同，按名字 join 会把一笔积分变动连到一笔银行付款上。\n" +
    "  与银行流水真正对勾的是 stl_bill.payment_ref —— 那一列才是同一个号。",
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
    "**同一个列名下有独立的模板族**：notify_template 是消息模板，notify_message.template_no 引用它；" +
    "prd_spec_template.template_no 是商品规格模板，ent_template/ent_template_version是支付权益模板；三族互不相干。\n" +
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

const SCOPED_KEY_OWNERS = {
  "pts_ledger_entry.voucher_no": "pts_voucher",
  "pts_pool_flow.voucher_no": "pts_voucher",
  "sv_entry.voucher_no": "sv_voucher",
  "sv_posting_line.voucher_no": "sv_voucher",
  "sv_statement.voucher_no": "sv_voucher",
  "ord_pricing_template.active_revision_no": "ord_pricing_template_revision",
  "ord_store_pricing_config.active_revision_no": "ord_store_pricing_revision",
  "ord_pricing_template_revision.previous_revision_no": "ord_pricing_template_revision",
  "ord_store_pricing_revision.previous_revision_no": "ord_store_pricing_revision",
  "ord_price_quote_line.quote_no": "ord_price_quote",
  "ord_price_quote_allocation.quote_no": "ord_price_quote",
  "sv_recharge_plan_version.plan_no": "sv_recharge_plan",
  "sv_recharge.plan_no": "sv_recharge_plan",
  "mch_management_grant.grant_no": "mch_management_grant",
  "mkt_quote_revision.quote_no": "mkt_quote",
  "ord_pricing_funding_source.quote_no": "ord_price_quote",
  "ord_industry_order_source.quote_no": "ord_price_quote",
  "pts_topup_plan.plan_no": "pts_topup_plan",
  "pmt_migration_decision.plan_no": "ent_migration_plan",
  "ent_migration_import.plan_no": "ent_migration_plan",
  "sv_statement.plan_no": "sv_recharge_plan",
  "ent_instance.grant_no": "ent_grant",
  "ent_sale_reservation.grant_no": "ent_grant",
  "pmt_pay_coupon_projection.grant_no": "ent_grant",
  "sv_issue_policy_version.policy_no": "sv_issue_policy",
  "ord_manual_price_adjustment.policy_no": "ord_manual_price_policy"
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
