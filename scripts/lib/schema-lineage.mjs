// Logical schema lineage shared by the ER CLI and guards. No schema reads or writes.
import { AUDIT } from './ddl.mjs';

const tenant = [{ from: "tenant_no", to: "tenant_no" }];

export const KEY_OWNERS = {
  build_no: { table: "pts_statement_build" },
  evidence_no: { table: "pts_fund_evidence" },
  receivable_no: { table: "pts_receivable" },
  statement_no: { table: "pts_store_statement", col: "position_no" },
  cash_payment_no: { table: "stl_payment", col: "payment_no" },
  origin_payment_no: { table: "stl_payment", col: "payment_no" },
  pay_no: { table: "ord_create_intent" },
  instance_no: { table: "ent_instance", scope: tenant },
  usage_no: { table: "ent_usage", scope: tenant },
  recharge_no: { table: "sv_recharge", scope: tenant },
  lot_no: { table: "sv_lot", scope: tenant },
  exception_no: { table: "pay_ent_exception", scope: tenant },
  recon_no: { table: "pay_ent_recon_run", scope: tenant },
  stage_no: { table: "ent_migration_stage", scope: tenant },
  alias_no: { table: "ent_migration_alias", scope: tenant },
  checkout_no: { table: "ord_legacy_coupon_checkout", scope: tenant },
  import_no: { table: "ent_migration_import", scope: tenant },
  return_no: { table: "pmt_migration_return", scope: tenant },
  target_template_no: { table: "ent_template", col: "template_no", scope: tenant },
  template_version_no: { table: "ent_template_version", col: "version_no", scope: tenant },
  activity_version_no: { table: "pmt_activity_version", col: "version_no", scope: tenant },

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
  personnel_no: { table: "mch_personnel" },
  assignment_no: { table: "mch_personnel_store" },
  command_no: { table: "mch_resource_command" },
  bucket_no: { table: "mch_resource_bucket" },
  hold_no: { table: "mch_booking_hold" },
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
  buyer_no: { table: "usr_account", col: "user_no", scope: tenant },
  confirmation_no: { table: "ord_checkout_confirmation", scope: tenant },
};


export const NAME_COLLISIONS = {
  rule_no: "分账费率规则与资源班表规则属于不同域，不可按同名编号关联。",
  trade_no: "stl_payment 与 ord_payment_resolution 保存渠道交易号快照；该号仅在 pay_channel 范围内唯一，不能脱离渠道直接关联。",
  source_shipment_no:
    "lgs_waybill 与 lgs_channel_subscription 保存同一个上游发货身份的快照；更新订阅必须同时匹配 source_shipment_version，不能把历史订阅行当作当前运单主记录。",
  provider_no: "通道回执需结合通道、资金方向与原请求。",
  scope_no: "角色授权范围与积分开关统计范围，须按各自范围类型解释。",
  revision_no: "商品版本与积分账单版本，各自独立。",
  holder_no: "积分持有人多态编号，必须结合 holder_type。",
  position_no: "积分持仓与业务岗位分配的编号分别属于各自表，必须结合所属业务。",
  link_no: "会员来源关联号与业务人员账号关联号分别属于各自表，不可按列名关联。",
  ticket_no: "积分资金指令与客服工单的独立编号，不可直接关联。",
  migration_batch_no: "旧券迁移批次按tenant/sourceSystem/sourceTemplateNo限定，不是全局迁移主键；目标原子导入主键是import_no。",
  aggregate_no: "订单状态聚合与Pay Outbox的多态聚合号，各自携带聚合类型。",
  diff_no: "结算差异stl_recon_diff与权益差异pay_ent_recon_diff分别发号。",
  current_version_no: "pmt_activity指活动版本，ent_template指权益模板版本，须按所属表限定。",
  biz_no: "通道消息、储值凭证和权益异常各自按biz_type解释的多态业务号。",
  account_no: "mch_payout_account为银行出款账户；sv_account为储值账户。",
  version_no: "活动版本和权益模板版本各自发号；活动子表指pmt_activity_version，ent_template_version自持权益版本。",
  resource_no: "共用人员或物理资源在mch_resource；幂等结果与对账差异按各自资源类型解释，不可仅按同名字段关联。",
  owner_no: "通知渠道的owner_no按owner_type解释；资源归属头在mch_booking_owner，不能按同名字段跨域关联。",
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
  quote_no: "营销 mkt_quote 与结算 ord_checkout_quote 独立发号；仅营销 revision 与结算 confirmation 分别引用 tenant 内同族报价根。operation.current_quote_no 仅标识当前结算报价，不能代替业务 revision/hash/caller 校验。",
  voucher_no: "积分 pts_voucher 与储值 sv_voucher 独立发号；仅登记四条同族引用，禁止根互连或跨族关联。",
  plan_no: "pts_topup_plan 按 (plan_no,period_key) 定义独立计划；仅 pmt_migration_decision 与 ent_migration_import 引用 tenant 内 ent_migration_plan。",
  source_order_no: "来源协议决定根：ent_usage 的 SERVICE_USE 指向服务单，ORDER_USE/STORE_USE 指向 ord_sub_order.sub_order_no；不能无条件连接 ord_order。仅 ent_legacy_coupon_operation 引用 tenant 内 ord_order.order_no。",
  grant_no: "普通发放路径 ent_instance.grant_no → ent_grant.grant_no；MIGRATION_BASELINE 实例来自 ent_migration_asset.baseline_no，未创建 ent_grant，因此不画无条件实例边。仅售卡承诺和券投影可限定引用 ent_grant。",
  tier_no: "档位仅在 (tenant_no,version_no) 内唯一；pmt_activity_reservation 须同时匹配 tenant_no 与 version_no 才能引用 pmt_recharge_tier。",
  catalog_no: "目录仅在主体内唯一；prd_store_catalog_version 须同时匹配 tenant_no 与 entity_no 才能引用 prd_store_catalog。",
  actor_account_no: "支付 ord_checkout_payment_request 与 stl_payment_operation 保存解析后的 mch_account.mch_account_no（包括 USR 路径）；mch_staff_log 历史保存 login userNo，禁止全局连商家账号。",
  receipt_no: "stl_payment 为支付收据，ent_migration_return 为旧券权益恢复收据，两套独立发号；迁移恢复按 tenant+return_no 引用 pmt_migration_return。",
  source_event_no: "来源协议键：sys_outbox 需 (source_system,source_event_no)；pmt_effect_projection 还接收旧申请号，不能全局连接 outbox。",
  entry_no: "pts_ledger_entry 是积分账分录，ent_ledger 是权益账分录，各自独立发号，不可跨账关联。",
  line_no: "prd_stock_alignment_line 是库存批次局部行序；sv_posting_line 是 tenant+voucher 内局部行序，不是全局业务身份。",
  execution_no: "普通退款义务条件路径 ord_refund_obligation.execution_no → ord_refund_execution.execution_no；PRF- + after_sale_no 路径指 ord_points_refund_execution.after_sale_no，不能画无条件普通执行边。",
};

// Exact source-audited memberships; new tables require explicit review.
export const COLLISION_MEMBERS = {
  "quote_no": [
    "mkt_quote",
    "mkt_quote_revision",
    "ord_checkout_quote",
    "ord_checkout_confirmation"
  ],
  "receipt_no": [
    "stl_payment",
    "ent_migration_return"
  ],
  "source_event_no": [
    "sys_outbox",
    "pmt_effect_projection"
  ],
  "actor_account_no": [
    "mch_staff_log",
    "ord_checkout_payment_request",
    "stl_payment_operation"
  ],
  "execution_no": [
    "ord_refund_execution",
    "ord_refund_obligation"
  ],
  "entry_no": [
    "pts_ledger_entry",
    "ent_ledger"
  ],
  "line_no": [
    "prd_stock_alignment_line",
    "sv_posting_line"
  ],
  "catalog_no": [
    "prd_store_catalog",
    "prd_store_catalog_version"
  ],
  "voucher_no": [
    "pts_voucher",
    "pts_ledger_entry",
    "pts_pool_flow",
    "sv_voucher",
    "sv_entry",
    "sv_posting_line"
  ],
  "tier_no": [
    "pmt_recharge_tier",
    "pmt_activity_reservation"
  ],
  "plan_no": [
    "pts_topup_plan",
    "ent_migration_plan",
    "pmt_migration_decision",
    "ent_migration_import"
  ],
  "source_order_no": [
    "ent_usage",
    "ent_legacy_coupon_operation"
  ],
  "grant_no": [
    "ent_grant",
    "ent_instance",
    "ent_sale_reservation",
    "pmt_pay_coupon_projection"
  ]
};

// Source-table exceptions take precedence over global collision suppression.
export const TABLE_KEY_OWNERS = {
  "mkt_quote_revision": {
    "quote_no": {
      "table": "mkt_quote",
      "scope": tenant
    }
  },
  "ord_checkout_confirmation": {
    "quote_no": {
      "table": "ord_checkout_quote",
      "scope": tenant
    }
  },
  "ord_checkout_operation": {
    "current_quote_no": {
      "table": "ord_checkout_quote",
      "col": "quote_no",
      "scope": tenant
    }
  },
  "pts_ledger_entry": {
    "voucher_no": {
      "table": "pts_voucher"
    }
  },
  "pts_pool_flow": {
    "voucher_no": {
      "table": "pts_voucher"
    }
  },
  "sv_entry": {
    "voucher_no": {
      "table": "sv_voucher",
      "scope": tenant
    }
  },
  "sv_posting_line": {
    "voucher_no": {
      "table": "sv_voucher",
      "scope": tenant
    }
  },
  "pmt_migration_decision": {
    "plan_no": {
      "table": "ent_migration_plan",
      "scope": tenant
    }
  },
  "ent_migration_import": {
    "plan_no": {
      "table": "ent_migration_plan",
      "scope": tenant
    }
  },
  "ent_legacy_coupon_operation": {
    "source_order_no": {
      "table": "ord_order",
      "col": "order_no",
      "scope": tenant
    }
  },
  "ent_sale_reservation": {
    "grant_no": {
      "table": "ent_grant",
      "scope": tenant
    }
  },
  "pmt_pay_coupon_projection": {
    "grant_no": {
      "table": "ent_grant",
      "scope": tenant
    }
  },
  "ord_checkout_payment_request": {
    "actor_account_no": {
      "table": "mch_account",
      "col": "mch_account_no",
      "scope": tenant
    }
  },
  "stl_payment_operation": {
    "actor_account_no": {
      "table": "mch_account",
      "col": "mch_account_no",
      "scope": tenant
    }
  },
  "pmt_activity_reservation": {
    "tier_no": {
      "table": "pmt_recharge_tier",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "version_no",
          "to": "version_no"
        }
      ]
    }
  },
  "prd_store_catalog_version": {
    "catalog_no": {
      "table": "prd_store_catalog",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        }
      ]
    }
  }
};

const hasColumn = (def, name) => Boolean(def?.cols.some(c => c.name === name));

/** Resolve a logical reference only when every endpoint and scope column exists. */
export function resolveReference(tables, fromTable, columnName) {
  const source = tables.get(fromTable);
  if (AUDIT.has(columnName) || !hasColumn(source, columnName)) return null;
  const qualified = TABLE_KEY_OWNERS[fromTable]?.[columnName];
  const owner = qualified ?? (NAME_COLLISIONS[columnName] ? null : KEY_OWNERS[columnName]);
  if (!owner || owner.table === fromTable) return null;
  const target = tables.get(owner.table);
  const toCol = owner.col ?? columnName;
  const scope = owner.scope ?? [];
  if (!hasColumn(target, toCol) || scope.some(pair => !hasColumn(source, pair.from) || !hasColumn(target, pair.to))) return null;
  return { from: fromTable, to: owner.table, col: columnName, toCol, scope: scope.map(pair => ({ ...pair })) };
}

export function relations(tables) {
  const out = [];
  for (const [table, def] of tables) for (const column of def.cols) {
    const reference = resolveReference(tables, table, column.name);
    if (reference) out.push(reference);
  }
  return out;
}

/** Preserve the cross-table _no guard, including exact audited collision membership. */
export function unclassifiedBusinessKeys(tables) {
  const byKey = new Map();
  for (const [table, def] of tables) for (const column of def.cols) {
    const key = column.name;
    if (!key.endsWith('_no') || key === 'tenant_no') continue;
    byKey.set(key, [...(byKey.get(key) ?? []), table]);
  }
  return [...byKey]
    .filter(([, sources]) => sources.length >= 2)
    .filter(([key, sources]) => COLLISION_MEMBERS[key]
      ? sources.some(table => !COLLISION_MEMBERS[key].includes(table))
      : !(key in KEY_OWNERS) && !(key in NAME_COLLISIONS))
    .map(([key, sources]) => `${key}（出现在 ${sources.join('、')}）`);
}

export function columnBadge(tables, table, columnName) {
  const def = tables.get(table);
  if (!hasColumn(def, columnName)) return '';
  if (def.uniques.has(columnName)) return '<i class="k uk">UK</i>';
  return resolveReference(tables, table, columnName) ? '<i class="k fk">FK</i>' : '';
}

/** Scope is part of the relationship, never an optional bare-number join hint. */
export function describeRelation(reference) {
  const { from, to, col, toCol, scope } = reference;
  const equality = scope.map(pair => `${from}.${pair.from} = ${to}.${pair.to}`);
  return `${from}.${col} → ${to}.${toCol}${equality.length ? `（同时匹配 ${equality.join('；')}）` : ''}`;
}
