// Business key ownership and semantic collisions shared by schema guards and ER generation.
export const KEY_OWNER_DEFINITIONS = {
  execution_no: { table: "ord_refund_execution" },
  config_no: { table: "ord_store_pricing_config" },
  agent_no: { table: "prn_agent" },
  printer_no: { table: "prn_printer" },
  route_no: { table: "prn_route" },
  route_version_no: { table: "prn_route_version" },
  policy_no: { table: "prn_scene_policy" },
  policy_version_no: { table: "prn_policy_version" },
  job_no: { table: "prn_job" },

  build_no: { table: "pts_statement_build" },
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

export const NAME_COLLISIONS = {
  voucher_no: "Points and stored-value vouchers are independent identities; select the ledger by domain.",
  trace_no: "Marketing attribution traces and print operation traces are independently allocated.",
  source_event_no: "Upstream event identity must include source_system and the source protocol.",
  attempt_no: "Logistics subscription attempts and print delivery attempts are independent identities.",
  entry_no: "Points ledger entries and entitlement ledger entries use independent numbering.",
  line_no: "Stock alignment batch row ordinal and stored-value posting line identity differ.",
  target_no: "Polymorphic entitlement and printing targets require target type and domain.",
  receipt_no: "Entitlement migration receipts and printer receipts are independent identities.",
  active_revision_no: "Pricing templates and store pricing configs reference their own revision tables.",
  previous_revision_no: "Pricing template and store config previous revisions belong to different revision families.",

  trade_no: "stl_payment 与 ord_payment_resolution 保存渠道交易号快照；该号仅在 pay_channel 范围内唯一，不能脱离渠道直接关联。",
  source_shipment_no:
    "lgs_waybill 与 lgs_channel_subscription 保存同一个上游发货身份的快照；更新订阅必须同时匹配 source_shipment_version，不能把历史订阅行当作当前运单主记录。",
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

export const KEY_OWNERS = Object.fromEntries(Object.entries(KEY_OWNER_DEFINITIONS).map(([key, value]) => [key, value.table]));
