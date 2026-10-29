// Logical schema lineage shared by the ER CLI and guards. No schema reads or writes.
import { AUDIT } from './ddl.mjs';

const tenant = [{ from: "tenant_no", to: "tenant_no" }];

export const KEY_OWNERS = {
  "active_job_no": {
    "table": "prn_job",
    "col": "job_no"
  },
  "active_attempt_no": {
    "table": "prn_attempt",
    "col": "attempt_no"
  },
  "agent_no": {
    "table": "prn_agent"
  },
  "printer_no": {
    "table": "prn_printer"
  },
  "route_version_no": {
    "table": "prn_route_version"
  },
  "active_store_no": {
    "table": "mch_store",
    "col": "store_no"
  },
  "from_brand_no": {
    "table": "mch_brand",
    "col": "brand_no"
  },
  "to_brand_no": {
    "table": "mch_brand",
    "col": "brand_no"
  },
  "clearing_no": {
    "table": "sv_clearing_entry"
  },
  "payer_entity_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "payee_entity_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "bill_no": {
    "table": "sv_settlement_bill"
  },
  "amendment_no": {
    "table": "ord_industry_amendment"
  },
  "application_no": {
    "table": "ord_pricing_fee_application"
  },
  "first_store_no": {
    "table": "mch_store",
    "col": "store_no"
  },
  "recharge_store_no": {
    "table": "mch_store",
    "col": "store_no"
  },
  "execution_no": {
    "table": "ord_refund_execution"
  },
  "customer_no": {
    "table": "sv_customer_identity"
  },
  "issuer_no": {
    "table": "sv_issuer"
  },
  "scope_member_no": {
    "table": "mbr_scope_member"
  },
  "issuer_version_no": {
    "table": "sv_issuer_version"
  },
  "plan_version_no": {
    "table": "sv_recharge_plan_version",
    "col": "version_no"
  },
  "release_no": {
    "table": "sv_release"
  },
  "collection_entity_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "settlement_entity_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "operating_entity_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "management_no": {
    "table": "mch_management_space"
  },
  "brand_no": {
    "table": "mch_brand"
  },
  "change_no": {
    "table": "mch_brand_change"
  },
  "config_no": {
    "table": "ord_store_pricing_config"
  },
  "template_revision_no": {
    "table": "ord_pricing_template_revision",
    "col": "revision_no"
  },
  "quota_no": {
    "table": "sv_issue_quota"
  },
  "quote_line_no": {
    "table": "ord_price_quote_line"
  },
  "device_no": {
    "table": "prn_device"
  },
  "job_no": {
    "table": "prn_job"
  },
  "build_no": {
    "table": "pts_statement_build"
  },
  "voucher_no": {
    "table": "pts_voucher"
  },
  "evidence_no": {
    "table": "pts_fund_evidence"
  },
  "receivable_no": {
    "table": "pts_receivable"
  },
  "statement_no": {
    "table": "pts_store_statement",
    "col": "position_no"
  },
  "cash_payment_no": {
    "table": "stl_payment",
    "col": "payment_no"
  },
  "origin_payment_no": {
    "table": "stl_payment",
    "col": "payment_no"
  },
  "pay_no": {
    "table": "ord_create_intent"
  },
  "tier_no": {
    "table": "pmt_recharge_tier"
  },
  "grant_no": {
    "table": "ent_grant"
  },
  "instance_no": {
    "table": "ent_instance",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "usage_no": {
    "table": "ent_usage",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "source_order_no": {
    "table": "ord_order",
    "col": "order_no"
  },
  "recharge_no": {
    "table": "sv_recharge",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "lot_no": {
    "table": "sv_lot",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "exception_no": {
    "table": "pay_ent_exception",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "recon_no": {
    "table": "pay_ent_recon_run",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "stage_no": {
    "table": "ent_migration_stage",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "alias_no": {
    "table": "ent_migration_alias",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "checkout_no": {
    "table": "ord_legacy_coupon_checkout",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "plan_no": {
    "table": "ent_migration_plan"
  },
  "import_no": {
    "table": "ent_migration_import",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "return_no": {
    "table": "pmt_migration_return",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "target_template_no": {
    "table": "ent_template",
    "col": "template_no",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "template_version_no": {
    "table": "ent_template_version",
    "col": "version_no",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "activity_version_no": {
    "table": "pmt_activity_version",
    "col": "version_no",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "std_no": {
    "table": "prd_spu_std"
  },
  "dim_no": {
    "table": "prd_spec_dim"
  },
  "value_no": {
    "table": "prd_spec_value"
  },
  "topic_no": {
    "table": "prd_topic"
  },
  "event_no": {
    "table": "sys_outbox"
  },
  "shipment_no": {
    "table": "lgs_waybill"
  },
  "out_trade_no": {
    "table": "stl_payment"
  },
  "area_no": {
    "table": "mch_service_area"
  },
  "person_no": {
    "table": "usr_person"
  },
  "member_no": {
    "table": "mbr_member"
  },
  "tag_no": {
    "table": "mbr_tag"
  },
  "segment_no": {
    "table": "mbr_segment"
  },
  "activity_no": {
    "table": "pmt_activity"
  },
  "fission_no": {
    "table": "mkt_fission_campaign"
  },
  "task_no": {
    "table": "notify_push_task"
  },
  "entity_no": {
    "table": "mch_entity"
  },
  "store_no": {
    "table": "mch_store"
  },
  "mch_account_no": {
    "table": "mch_account"
  },
  "pay_merchant_no": {
    "table": "mch_payment_merchant"
  },
  "user_no": {
    "table": "usr_account"
  },
  "order_no": {
    "table": "ord_order"
  },
  "sub_order_no": {
    "table": "ord_sub_order"
  },
  "community_no": {
    "table": "cmt_community"
  },
  "pickup_no": {
    "table": "cmt_pickup_point"
  },
  "goods_no": {
    "table": "prd_goods"
  },
  "sku_no": {
    "table": "prd_sku"
  },
  "category_no": {
    "table": "prd_category"
  },
  "coupon_no": {
    "table": "mkt_coupon"
  },
  "quote_no": {
    "table": "mkt_quote"
  },
  "group_no": {
    "table": "mkt_group_buy"
  },
  "period_no": {
    "table": "pmt_period"
  },
  "enrollment_no": {
    "table": "pmt_enrollment"
  },
  "review_no": {
    "table": "rvw_review"
  },
  "staff_no": {
    "table": "sys_ops_staff"
  },
  "settle_no": {
    "table": "stl_bill"
  },
  "payout_no": {
    "table": "stl_payout"
  },
  "after_sale_no": {
    "table": "ord_after_sale"
  },
  "payment_no": {
    "table": "stl_payment"
  },
  "use_ledger_no": {
    "table": "pts_user_ledger",
    "col": "ledger_no"
  },
  "inviter_no": {
    "table": "usr_account",
    "col": "user_no"
  },
  "issuer_merchant_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "merchant_no": {
    "table": "mch_entity",
    "col": "entity_no"
  },
  "personnel_no": {
    "table": "mch_personnel"
  },
  "assignment_no": {
    "table": "mch_personnel_store"
  },
  "command_no": {
    "table": "mch_resource_command"
  },
  "bucket_no": {
    "table": "mch_resource_bucket"
  },
  "hold_no": {
    "table": "mch_booking_hold"
  },
  "buyer_no": {
    "table": "usr_account",
    "col": "user_no",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  },
  "confirmation_no": {
    "table": "ord_checkout_confirmation",
    "scope": [
      {
        "from": "tenant_no",
        "to": "tenant_no"
      }
    ]
  }
};


export const NAME_COLLISIONS = {
  "print_fact_no": "订单支付事实由ord_print_fact持有，退款和充值事实由biz_print_fact持有；完整打印引用按sourceType、租户、主体、门店、场景和版本选择来源，不能仅按同名编号跨表join。",
  "trace_no": "营销归因链路追踪与打印操作审计追踪各自由来源系统产生，不能按同号跨域连接。",
  "receipt_no": "stl_payment支付收据、ent_migration_return旧券恢复凭证、prn_receipt打印设备回执独立发号，按来源族及tenant限定；恢复按tenant+return_no引用pmt_migration_return，不能仅按receipt_no跨族关联。",
  "route_no": "打印管理路由目录与统一投递目标路由是不同层级；管理版本引用catalog，投递路由仍由统一服务持有。",
  "policy_version_no": "储值发行政策版本与打印场景政策版本各自发布，必须结合来源表选择所属版本族。",
  "source_event_no": "来源协议键：sys_outbox 需 (source_system,source_event_no)；pmt_effect_projection 还接收旧申请号，不能全局连接 outbox。；统一扩展：来源系统各自产生的事件号；必须结合source_system及原始来源，不能仅按同名跨系统关联。",
  "rule_no": "分账费率规则与资源班表规则属于不同域，不可按同名编号关联。；统一扩展：stl_fee_rule是结算费率规则；ord_price_quote_allocation保存本次试算规则标识，两套规则目录独立。",
  "subject_no": "角色成员与报价/人工改价都使用主体多态编号；需同时核验各自subject_type，不能当成单一外键。",
  "link_no": "会员来源关联号与业务人员账号关联号分别属于各自表，不可按列名关联。；统一扩展：会员来源、旧会员迁移关系与储值活动发布/审批各有独立关联记录；编号仅在对应关系族内有效。",
  "attempt_no": "物流订阅尝试与打印投递尝试独立生成，不能混为同一次执行。",
  "entry_no": "pts_ledger_entry 是积分账分录，ent_ledger 是权益账分录，各自独立发号，不可跨账关联。；统一扩展：pts_ledger_entry积分分录与ent_ledger权益分录是不同账本，不共用分录身份。",
  "binding_no": "订单积分接线与品牌门店有效期绑定是不同聚合，不能按同名连接。",
  "adjustment_no": "积分人工调整与订单人工改价分别有自己的命令/审计身份。",
  "line_no": "prd_stock_alignment_line 是库存批次局部行序；sv_posting_line 是 tenant+voucher 内局部行序，不是全局业务身份。；统一扩展：库存对齐行与储值过账行只在各自父单据内编号，不能作为全局行身份。",
  "allocation_no": "储值冻结批次分配与报价金额分摊有不同对象、数量和金额语义。",
  "target_no": "权益所有者注册与储值发行政策的目标都是多态范围，必须结合target_type及所属域。",
  "document_no": "Core与Pay分别分配业务展示单号；通知保存来源单号，必须结合来源系统/单据类型，不能假定单一主表。",
  "active_revision_no": "行业模板与门店配置各自引用自己的不可变发布版本，按源表指定目标。",
  "previous_revision_no": "行业模板版本与门店配置版本各自形成历史链，不能跨版本族关联。",
  "draft_revision_no": "储值发布和Core活动审批冻结对应活动的草稿标识，必须连同activity_no及来源版本解释，非全库通用版本键。",
  "policy_no": "SKU定价策略、储值发行管控、人工改价策略是独立政策族，不能按同名跨域关联。",
  "bridge_actor_no": "打印桥接认证主体标识，需结合可信桥接凭证与租户核验；不是用户或运营账号表的通用外键。",
  "voucher_no": "积分 pts_voucher 与储值 sv_voucher 独立发号；仅登记四条同族引用，禁止根互连或跨族关联。；统一扩展：积分凭证与储值凭证是独立账本；须按来源表选择pts_voucher或sv_voucher，禁止跨账本按号猜关联。",
  "quote_no": "营销 mkt_quote、订单定价 ord_price_quote、结算 ord_checkout_quote 三族独立发号；按源表选择同族根并匹配 tenant_no，禁止根互连或裸号跨族连接。operation.current_quote_no 仅标识结算报价，不能代替 revision/hash/caller 校验。",
  "plan_no": "pts_topup_plan 按 (plan_no,period_key) 定义独立计划；pmt_migration_decision 与 ent_migration_import 引用 tenant 内 ent_migration_plan；sv_recharge、sv_recharge_plan_version 与 sv_statement 引用 tenant 内 sv_recharge_plan。三族不可裸号互连。",
  "grant_no": "普通发放路径 ent_instance.grant_no → ent_grant.grant_no；MIGRATION_BASELINE 实例来自 ent_migration_asset.baseline_no，未创建 ent_grant，因此不画无条件实例边。仅售卡承诺和券投影可限定引用 ent_grant。；mch_management_grant 是独立经营管理授权族；服务执行及其库存retention保留服务grant上下文，不连接 ent_grant。",
  "trade_no": "stl_payment 与 ord_payment_resolution 保存渠道交易号快照；该号仅在 pay_channel 范围内唯一，不能脱离渠道直接关联。",
  "source_shipment_no": "lgs_waybill 与 lgs_channel_subscription 保存同一个上游发货身份的快照；更新订阅必须同时匹配 source_shipment_version，不能把历史订阅行当作当前运单主记录。",
  "parent_no": "cmt_community.parent_no是社区层级，prd_category.parent_no是商品类目层级；各自树内引用，不能按全局名字连线。",
  "provider_no": "通道回执需结合通道、资金方向与原请求。",
  "scope_no": "角色授权范围与积分开关统计范围，须按各自范围类型解释；mkt_counter_campaign_fence 按 tenant_no+scope_kind 判别：ENTITY 对应经营主体 mch_entity.entity_no，PLATFORM 为固定 PLATFORM 哨兵，无无条件 FK。；统一扩展：角色授权范围与积分开关统计范围，须按各自范围类型解释。",
  "revision_no": "商品版本与积分账单版本，各自独立。",
  "holder_no": "积分持有人多态编号，必须结合 holder_type。",
  "position_no": "积分持仓与业务岗位分配的编号分别属于各自表，必须结合所属业务。；统一扩展：不同种类积分持仓的多态编号，必须结合持仓类型。",
  "ticket_no": "积分资金指令与客服工单的独立编号，不可直接关联。",
  "migration_batch_no": "旧券迁移批次按tenant/sourceSystem/sourceTemplateNo限定，不是全局迁移主键；目标原子导入主键是import_no。",
  "aggregate_no": "订单状态聚合与Pay Outbox的多态聚合号，各自携带聚合类型。",
  "diff_no": "结算差异stl_recon_diff与权益差异pay_ent_recon_diff分别发号。",
  "current_version_no": "pmt_activity指活动版本，ent_template指权益模板版本，须按所属表限定。",
  "biz_no": "通道消息、储值凭证和权益异常各自按biz_type解释的多态业务号。",
  "account_no": "mch_payout_account为银行出款账户；sv_account为储值账户。",
  "version_no": "活动版本和权益模板版本各自发号；活动子表指pmt_activity_version，ent_template_version自持权益版本。",
  "resource_no": "共用人员或物理资源在mch_resource；幂等结果与对账差异按各自资源类型解释，不可仅按同名字段关联。；统一扩展：幂等结果和对账差异按对应资源类型解释，不是同一实体。",
  "actor_no": "审计操作人可为商家账号、运营或系统，必须结合actor_type解释。",
  "decision_no": "Core订单源决定与旧券迁移永久源决定分别发号。",
  "reservation_no": "活动资源占用与Pay售卡承诺分别发号，关联必须结合业务协议。",
  "source_decision_no": "Core订单源决定或迁移源决定，由source协议与purpose限定。",
  "operation_no": "统一使用意图或旧券原操作号，由protocol决定根表，不能仅按号合并。",
  "intent_no": "活动发权益与储值充值协调意图分别发号。",
  "source_template_no": "旧MKT或PMT根号，须同时校验source_system与tenant，禁止同号猜测。",
  "source_instance_no": "原旧券实例号，须同时校验source_system与tenant，禁止跨来源连接。",
  "source_usage_no": "原历史流水号，须同时校验source_system与source_instance_no。",
  "waybill_no": "两边是**同一个快递运单号**，但它**不是外键**：运单号只在承运商内唯一，lgs_waybill 按（承运商, 运单号）登记；ord_shipment（订单服务 2.0 发货单，V401）记的是商家录入的号。\n  只按 waybill_no join 会把两家快递撞号的单连到一起 —— 要连必须带上承运商（ord_shipment.carrier = lgs_waybill.carrier）。",
  "flow_no": "stl_points_pool 的是**积分池流水号**（平台自己发号，记池子的每一次增减）；stl_bank_flow 的是**银行给的流水号**（人工从网银导出，是出款对账的外部判据）。\n  两者的发号方不同、值域也不同，按名字 join 会把一笔积分变动连到一笔银行付款上。\n  与银行流水真正对勾的是 stl_bill.payment_ref —— 那一列才是同一个号。",
  "slot_no": "mch_appointment_slot 是**预约时段**（到店服务：周三 14:00–15:00 这一格）；mkt_content_slot 是**内容位**（运营：首页第一屏那个楼层）。\n  两族毫不相干，只是中文都能叫「位」。按名字 join 会把某个商家的预约时段连到一个首页楼层上，而两边都有 slot_no 且都非空 —— 连错了不报错。",
  "sort_no": "两张表各自的**排序序号**，不是任何东西的键：sys_market 是国家/地区在选择器里的顺序，mkt_content_slot 是同类内容位之间的先后。\n  同名同义（都是「小的在前」）但**没有共同的定义域**，按它 join 只会得到笛卡尔积。",
  "message_no": "stl_channel_message 是**渠道报文**（支付：通道推给我方的回调、我方发给通道的调用）；notify_message 是**站内消息**（通知：发给用户看的那条）。\n  两族毫不相干，只是都叫「消息」。按名字 join 会把一条支付回调连到某个用户的收件箱上，而两边都有 message_no 且都非空 —— 连错了不会报错，只会安静地少几行或多几行。",
  "batch_no": "ful_batch 是**到货批次**（履约：一车货到自提点）；sys_media_purge_batch 是**媒体清理批次**（运维：一次对象存储回收）。\n  两族毫不相干，只是都叫「批次」。按名字 join 会把一次清理任务连到一车货上。",
  "user_coupon_no": "mkt_user_coupon 与 pmt_user_coupon 是**两套并行的券模型**：\n  mkt_* 是 marketing 那一族（CouponServiceImpl 用它），pmt_* 是 promotion 那一族（OpsPromotionServiceImpl 用它）。\n  **只有 pmt_* 登记进了数据域**，mkt_* 没有 —— 所以按名字混用两族，除了连错行，还会让一半查询悄悄不受数据域约束。",
  "issue_no": "同 user_coupon_no：mkt_coupon_issue 与 pmt_coupon_issue 各自建了唯一键，是两族。\n  pmt_user_coupon 里的 issue_no 指的是 pmt_coupon_issue —— 同族内是外键，跨族不是。",
  "tax_no": "**它根本不是编号，是纳税人识别号这个业务值**（ord_invoice_request 开票抬头填的、stl_settle_invoice 结算发票上印的）。两张表都没给它建唯一键。\n  同一个纳税人识别号本来就该在多张表里重复出现，按它 join 得到的是笛卡尔积。",
  "receiver_no": "notify_message 与 notify_push_token 都是「收件人编号」，但**都是多态的**：\n  真正的含义由同表的 receiver_type 决定 —— USER 时是 usr_user.user_no，STAFF 时是 mch_staff.staff_no（两族编号，取值空间不重叠但也不保证）。\n  所以它不是任何一张表的外键，按名字 join 会把店员的设备连到买家身上：**推送发错人**，而且不报错。要连必须带上 receiver_type 一起连。",
  "request_no": "mkt_request 是求团需求单号；stl_split_log 是分账幂等号；ord_invoice_request 是开票申请单号。**三族**，互不相干。\n  建表时已发现约束名会撞车并加了 uk_split_request_no 前缀，但列名的撞车还在。",
  "invoice_no": "**三个方向的票，这是最危险的一组同名**：\n  stl_purchase_invoice 是**进项**（供应商开给平台，决定平台能不能列支成本）；\n  ord_invoice_request 是**销项·对消费者**（平台开给买家，ADR-017 §3.4 条件 2，决定归集资金模式成不成立）；\n  stl_settle_invoice 是**销项·对商家**（平台开给商家的结算凭证，P-12.2.4）。\n  义务人、方向、法律后果各不相同 —— 按名字 join 出来的「发票」会把进项当销项，而三边都有值、都不报错。",
  "express_no": "ord_sub_order 是发货快递单号；ord_after_sale 是用户退货的快递单号。方向相反。",
  "apply_no": "mch_entity_apply 是商家入驻申请单；cmt_community_apply 是商家提报新社区的单子。\n  两者都是「某人申请某事」，但申请的对象完全不同 —— 按名字 join 会把入驻单连到社区提报上。",
  "ref_no": "stl_points_pool 是积分池指向的业务单据；mch_store_audit 是审核单指向的业务记录（kind=SERVICE_AREA 时是 mch_service_area.area_no）。\n  两者都是「指针列」，但**指向的表由同行的另一列决定**（前者看 pool 类型，后者看 kind）——所以谁都不能登记成外键，按名字 join 必然连错，且不报错。",
  "operator_no": "ord_status_log 与 ful_verify_log 各自记录操作人，不是同一张表的外键。",
  "template_no": "**同一个列名下有独立的模板族**：notify_template 是消息模板，notify_message.template_no 引用它；prd_spec_template.template_no 是商品规格模板，ent_template/ent_template_version是支付权益模板；三族互不相干。\n  按名字 join 会把「发过哪条推送」连到「用了哪个规格模板」上 —— 两边都有值，不报错。\n  没改名是因为两族在各自的域里都叫得通；登记在这里，是为了让下一个人先看到这句话。\n  注意 notify_message → notify_template 这一跳**没有守卫** —— LINEAGE 只管资金账与积分账，触达链路断了的后果（推送发不出去）不在这个文件的职责里。",
  "txn_no": "mch_deposit_txn.txn_no 是保证金流水号（平台代管商家资金，将来要退还）；\n  mch_debt_txn.txn_no 是商家欠款流水号（平台垫付后向商家追偿）。\n  两者都是各自表的唯一业务键（各自建了 uk），资金方向相反 ——按名字 join 会把保证金扣款连到欠款追偿上，而两边都有值、不报错。",
  "source_no": "mbr_member_source.source_no 是会员引荐来源码（记录「这个会员从哪个推广渠道来」，建了全局唯一键）；\n  mch_debt_txn.source_no 是欠款来源单号（指向售后单号或结算单号，可为空，由 source_type 决定含义）。\n  两者都是「来源」但完全不同义 —— 按名字 join 会把会员拉新来源连到商家欠款上。",
  "log_no": "mch_staff_log.log_no 是员工操作日志的流水号；\n  pay_risk_shadow_log.log_no 是支付风控影子模式拦截记录的流水号（只记录、不真拦）。\n  两者都是各自表的唯一业务键，所属域完全不同 —— 按名字 join 会把员工操作连到风控影子记录上。",
  "unit_no": "资源持有单元与手工服务永久数量单元独立发号，仅 ord_service_execution 以 tenant/entity/store 引用本族数量单元。；prd_execution_retention 与 prd_execution_retention_route 保存服务数量单元原号及其完整原权威上下文，不能当作资源持有单元号。",
  "action_no": "手工收款声明动作与服务执行动作是两个不可变命名空间，不按同名号跨族关联。",
  "source_line_no": "结算原始行由 ord_checkout_line 持有，服务数量单元须同时核对 tenant/caller/operation/order/subOrder。；ord_price_quote_line.source_line_no 是报价来源行标识，须遵循来源协议，非结算行的无条件外键。",
  "source_operation_no": "原始结算操作别名须核对完整作用域；仅服务数量单元登记带 caller/entity/store 的精确引用。",
  "declaration_no": "收款声明与声明动作属于原收款族；执行尝试仅以 tenant/entity/store 绑定原声明，不建立跨族全局别名。",
  "owner_no": "通知渠道的owner_no按owner_type解释；资源归属头在mch_booking_owner，不能按同名字段跨域关联。",
  "source_order_no": "来源协议决定根：ent_usage 的 SERVICE_USE 指向服务单，ORDER_USE/STORE_USE 指向 ord_sub_order.sub_order_no；不能无条件连接 ord_order。仅 ent_legacy_coupon_operation 引用 tenant 内 ord_order.order_no。",
  "tier_no": "档位仅在 (tenant_no,version_no) 内唯一；pmt_activity_reservation 须同时匹配 tenant_no 与 version_no 才能引用 pmt_recharge_tier。；sv_recharge、sv_lot、sv_release_tier 保存各自活动/版本/发布上下文中的档位，不能只按tier_no连接全局根。",
  "catalog_no": "目录仅在主体内唯一；prd_store_catalog_version 须同时匹配 tenant_no 与 entity_no 才能引用 prd_store_catalog。",
  "actor_account_no": "支付 ord_checkout_payment_request 与 stl_payment_operation 保存解析后的 mch_account.mch_account_no（包括 USR 路径）；mch_staff_log 历史保存 login userNo，禁止全局连商家账号。",
  "execution_no": "普通退款义务条件路径 ord_refund_obligation.execution_no → ord_refund_execution.execution_no；PRF- + after_sale_no 路径指 ord_points_refund_execution.after_sale_no，不能画无条件普通执行边。"
};

// Exact source-audited memberships; new tables require explicit review.
export const COLLISION_MEMBERS = {
  "unit_no": [
    "mch_booking_hold_unit",
    "ord_service_execution_unit",
    "ord_service_execution",
    "prd_execution_retention",
    "prd_execution_retention_route"
  ],
  "action_no": [
    "ord_manual_service_admission_action",
    "ord_service_execution_action",
    "pay_ent_exception_action"
  ],
  "source_line_no": [
    "ord_checkout_line",
    "ord_service_execution_unit",
    "ord_price_quote_line"
  ],
  "source_operation_no": [
    "ord_sub_order",
    "ord_service_execution_unit"
  ],
  "declaration_no": [
    "ord_manual_service_admission",
    "ord_manual_service_admission_action",
    "ord_service_execution"
  ],
  "scope_no": [
    "sys_role_member",
    "pts_switch",
    "pts_switch_log",
    "pts_pause_interval",
    "pts_stat_daily",
    "mkt_counter_campaign_fence",
    "sv_statement",
    "ord_points_earn_rule",
    "ord_points_earn_rule_revision",
    "mbr_scope_member",
    "sv_issuer",
    "pmt_sv_release_link",
    "pmt_sv_approval",
    "ord_pricing_fee_scope",
    "ord_pricing_fee_application"
  ],
  "quote_no": [
    "mkt_quote",
    "mkt_quote_revision",
    "ord_checkout_quote",
    "ord_checkout_confirmation",
    "ord_price_quote",
    "ord_price_quote_line",
    "ord_price_quote_allocation",
    "ord_pricing_funding_source",
    "ord_industry_order_source",
    "ord_pricing_fee_application",
    "ord_pricing_fee_advice"
  ],
  "receipt_no": [
    "stl_payment",
    "ent_migration_return",
    "prn_receipt"
  ],
  "source_event_no": [
    "sys_outbox",
    "pmt_effect_projection",
    "prn_job",
    "prn_request"
  ],
  "actor_account_no": [
    "mch_staff_log",
    "ord_checkout_payment_request",
    "stl_payment_operation"
  ],
  "execution_no": [
    "ord_refund_execution",
    "ord_refund_obligation",
    "ord_service_execution",
    "ord_service_execution_action",
    "prd_execution_retention",
    "prd_execution_retention_route"
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
    "sv_posting_line",
    "sv_statement"
  ],
  "tier_no": [
    "pmt_recharge_tier",
    "pmt_activity_reservation",
    "sv_recharge",
    "sv_lot",
    "sv_release_tier"
  ],
  "plan_no": [
    "pts_topup_plan",
    "ent_migration_plan",
    "pmt_migration_decision",
    "ent_migration_import",
    "sv_recharge",
    "sv_recharge_plan",
    "sv_recharge_plan_version",
    "sv_statement"
  ],
  "source_order_no": [
    "ent_usage",
    "ent_legacy_coupon_operation"
  ],
  "grant_no": [
    "ent_grant",
    "ent_instance",
    "ent_sale_reservation",
    "pmt_pay_coupon_projection",
    "ord_service_execution",
    "mch_management_grant",
    "prd_execution_retention",
    "prd_execution_retention_route"
  ],
  "print_fact_no": [
    "ord_print_fact",
    "biz_print_fact"
  ],
  "trace_no": [
    "mkt_attribution_log",
    "prn_action_log"
  ],
  "route_no": [
    "prn_route",
    "prn_route_catalog",
    "prn_route_version",
    "prd_execution_retention_route"
  ],
  "policy_version_no": [
    "sv_issue_policy_version",
    "prn_policy_version",
    "prn_request"
  ],
  "rule_no": [
    "stl_fee_rule",
    "ord_price_quote_allocation",
    "ord_pricing_fee_rule",
    "ord_pricing_fee_revision",
    "ord_pricing_fee_scope",
    "ord_pricing_fee_application",
    "mch_schedule_rule"
  ],
  "subject_no": [
    "sys_role_member",
    "ord_price_quote",
    "ord_manual_price_adjustment"
  ],
  "link_no": [
    "mbr_member_source",
    "mbr_scope_legacy_link",
    "pmt_sv_release_link",
    "pmt_sv_approval",
    "mch_personnel_account"
  ],
  "attempt_no": [
    "lgs_kd100_sub_attempt",
    "prn_attempt",
    "prn_receipt",
    "ord_service_execution_unit",
    "ord_service_execution",
    "prd_execution_retention",
    "prd_execution_retention_route"
  ],
  "binding_no": [
    "ord_points_binding",
    "mch_brand_store_binding",
    "ord_service_execution_unit"
  ],
  "adjustment_no": [
    "pts_user_adjustment",
    "ord_manual_price_adjustment"
  ],
  "allocation_no": [
    "sv_hold_allocation",
    "ord_price_quote_allocation",
    "sv_clearing_entry"
  ],
  "target_no": [
    "ent_owner_registry",
    "sv_issue_policy",
    "prn_operation",
    "prn_incident",
    "prn_action_log"
  ],
  "document_no": [
    "core_document_number",
    "pay_document_number",
    "notify_stored_value_task"
  ],
  "active_revision_no": [
    "ord_pricing_template",
    "ord_store_pricing_config"
  ],
  "previous_revision_no": [
    "ord_pricing_template_revision",
    "ord_store_pricing_revision"
  ],
  "draft_revision_no": [
    "sv_release",
    "pmt_sv_release_link",
    "pmt_sv_approval"
  ],
  "policy_no": [
    "prd_sku_pricing_policy",
    "sv_issue_policy",
    "sv_issue_policy_version",
    "ord_manual_price_policy",
    "ord_manual_price_adjustment",
    "prn_scene_policy",
    "prn_policy_version"
  ],
  "bridge_actor_no": [
    "prn_device",
    "prn_attempt"
  ],
  "trade_no": [
    "stl_payment",
    "ord_payment_resolution"
  ],
  "source_shipment_no": [
    "lgs_channel_subscription",
    "lgs_waybill"
  ],
  "parent_no": [
    "cmt_community",
    "prd_category"
  ],
  "provider_no": [
    "stl_split_log",
    "stl_cash_refund_reverse"
  ],
  "revision_no": [
    "prd_goods_revision",
    "pts_statement_revision",
    "pts_statement_line",
    "ord_pricing_template_revision",
    "ord_store_pricing_revision",
    "ord_pricing_fee_rule",
    "ord_pricing_fee_revision",
    "ord_pricing_fee_application"
  ],
  "holder_no": [
    "pts_account",
    "pts_ledger_entry"
  ],
  "position_no": [
    "pts_refund_reserve",
    "pts_grant_position",
    "pts_redeem_position",
    "pts_refund_preparation",
    "pts_payout_ticket",
    "pts_store_statement",
    "pts_fund_transfer",
    "pts_grant_line",
    "pts_redeem_line",
    "pts_statement_line",
    "pts_statement_build_item",
    "pts_statement_source_history",
    "mch_personnel_position"
  ],
  "ticket_no": [
    "pts_redeem_position",
    "pts_receivable_allocation",
    "notify_ticket"
  ],
  "migration_batch_no": [
    "mkt_coupon",
    "pmt_coupon",
    "ent_owner_registry",
    "ent_migration_stage",
    "ent_migration_alias",
    "ent_migration_plan",
    "pmt_migration_decision",
    "pmt_pay_coupon_projection"
  ],
  "aggregate_no": [
    "ord_status_log",
    "pay_ent_outbox"
  ],
  "diff_no": [
    "stl_recon_diff",
    "pay_ent_recon_diff"
  ],
  "current_version_no": [
    "pmt_activity",
    "ent_template",
    "sv_recharge_plan",
    "sv_issue_policy",
    "prn_template_catalog",
    "prn_route_catalog",
    "prn_scene_policy",
    "prd_store_catalog"
  ],
  "biz_no": [
    "stl_channel_message",
    "sv_voucher",
    "pay_ent_exception",
    "prn_request"
  ],
  "account_no": [
    "mch_payout_account",
    "sv_account",
    "sv_lot",
    "sv_voucher",
    "sv_statement",
    "sv_customer_issuer_index",
    "ord_checkout_operation",
    "ord_checkout_confirmation",
    "ord_manual_service_admission"
  ],
  "version_no": [
    "ent_template_version",
    "pmt_activity_version",
    "pmt_activity_scope",
    "pmt_recharge_tier",
    "pmt_activity_reservation",
    "sv_recharge_plan_version"
  ],
  "resource_no": [
    "pay_ent_command_dedup",
    "pmt_command_dedup",
    "pay_ent_recon_diff",
    "mch_management_grant",
    "mch_resource",
    "mch_schedule_rule",
    "mch_schedule_change_log",
    "mch_resource_bucket"
  ],
  "actor_no": [
    "mbr_identity_ticket",
    "pay_ent_exception_action",
    "pmt_migration_decision",
    "ent_legacy_coupon_issue",
    "pmt_pay_coupon_projection",
    "mch_management_grant",
    "ord_industry_order_source",
    "ord_industry_fulfillment",
    "ord_manual_price_adjustment",
    "ord_industry_amendment",
    "ord_pricing_fee_revision",
    "prn_operation",
    "prn_action_log",
    "mbr_guest_registration",
    "ord_checkout_operation",
    "ord_checkout_confirmation",
    "ord_checkout_payment_request",
    "stl_payment_operation",
    "ord_manual_service_admission"
  ],
  "decision_no": [
    "ord_ent_source_decision",
    "pmt_migration_decision",
    "pmt_migration_source",
    "sv_clearing_entry"
  ],
  "reservation_no": [
    "pmt_activity_reservation",
    "ent_grant",
    "sv_recharge",
    "ent_sale_reservation",
    "sv_issue_quota_reservation"
  ],
  "source_decision_no": [
    "ent_grant",
    "ent_use_intent",
    "ent_use_reversal",
    "ent_sale_reservation",
    "ent_migration_import"
  ],
  "operation_no": [
    "ord_order",
    "ord_create_intent",
    "prd_stock_sync_operation",
    "prd_stock_alignment_batch",
    "ent_use_intent",
    "ent_use_allocation",
    "ent_usage",
    "pay_ent_source_fence",
    "ord_ent_order_use",
    "ord_ent_service_order",
    "sv_hold_allocation",
    "ent_legacy_coupon_operation",
    "ord_legacy_coupon_checkout",
    "prn_operation",
    "prn_action_log",
    "prn_simulation_run",
    "prn_export_task",
    "ord_checkout_operation",
    "ord_checkout_quote",
    "ord_checkout_confirmation",
    "ord_checkout_line",
    "ord_checkout_payment_request",
    "stl_payment_operation",
    "ord_manual_service_admission"
  ],
  "intent_no": [
    "pmt_benefit_intent",
    "pmt_recharge_intent"
  ],
  "source_template_no": [
    "ent_migration_stage",
    "ent_migration_plan",
    "pmt_migration_source",
    "ent_legacy_coupon_issue",
    "pmt_pay_coupon_projection"
  ],
  "source_instance_no": [
    "ent_migration_asset",
    "ent_migration_usage"
  ],
  "source_usage_no": [
    "ent_migration_usage",
    "pmt_migration_return",
    "ent_migration_return"
  ],
  "waybill_no": [
    "ord_shipment",
    "lgs_channel_subscription",
    "lgs_kd100_sub_attempt",
    "lgs_push_event",
    "lgs_precheck_cache",
    "lgs_update_log",
    "lgs_waybill"
  ],
  "flow_no": [
    "stl_points_pool",
    "stl_bank_flow",
    "pts_pool_flow"
  ],
  "slot_no": [
    "mch_appointment_slot",
    "mkt_content_slot"
  ],
  "sort_no": [
    "sys_market",
    "mkt_content_slot"
  ],
  "message_no": [
    "stl_channel_message",
    "notify_message"
  ],
  "batch_no": [
    "ful_batch",
    "stl_bill",
    "sys_media_purge_batch",
    "stl_settle_batch",
    "mch_debt_txn",
    "pay_risk_shadow_log",
    "stl_payout",
    "prd_stock_alignment_batch",
    "prd_stock_alignment_line",
    "ent_grant",
    "ent_issue_batch",
    "ent_legacy_coupon_issue",
    "prn_job",
    "prn_batch"
  ],
  "user_coupon_no": [
    "mkt_user_coupon",
    "pmt_user_coupon"
  ],
  "issue_no": [
    "mkt_coupon_issue",
    "pmt_user_coupon",
    "pmt_coupon_issue",
    "ent_legacy_coupon_issue",
    "pmt_pay_coupon_projection"
  ],
  "tax_no": [
    "ord_invoice_request",
    "stl_settle_invoice"
  ],
  "receiver_no": [
    "notify_message",
    "notify_push_token"
  ],
  "request_no": [
    "mkt_quote",
    "mkt_quote_revision",
    "mkt_request",
    "mkt_request_interest",
    "stl_split_log",
    "mch_deposit_txn",
    "ord_invoice_request",
    "mch_debt_txn",
    "pts_pool_request",
    "stl_split_intent",
    "stl_cash_refund_reverse",
    "prn_job",
    "prn_request",
    "prn_batch"
  ],
  "invoice_no": [
    "stl_purchase_invoice",
    "ord_invoice_request",
    "stl_settle_invoice"
  ],
  "express_no": [
    "ord_after_sale",
    "ord_sub_order"
  ],
  "apply_no": [
    "mch_entity_apply",
    "cmt_community_apply",
    "pmt_apply"
  ],
  "ref_no": [
    "stl_points_pool",
    "mch_store_audit",
    "mbr_member_source",
    "pmt_coupon_scope",
    "pmt_activity_goods",
    "pmt_activity_scope"
  ],
  "operator_no": [
    "ful_verify_log",
    "ord_status_log",
    "mch_violation",
    "mkt_coupon_issue",
    "sys_notify_log",
    "ord_invoice_request",
    "usr_person_merge_log",
    "mbr_member_source",
    "mbr_tag_merge_log",
    "pmt_coupon_issue",
    "pmt_apply",
    "mch_store_qrcode_print",
    "mbr_reach_task",
    "ord_points_refund_retry",
    "ent_issue_batch",
    "ent_usage",
    "ord_ent_service_order",
    "sv_recharge",
    "pmt_recharge_intent"
  ],
  "template_no": [
    "prd_spec_template",
    "sys_notify_log",
    "ful_freight_template",
    "ent_template",
    "ent_template_version",
    "ent_issue_policy",
    "ent_issue_recipient",
    "ent_sale_reservation",
    "ord_pricing_template",
    "ord_pricing_template_revision",
    "ord_store_pricing_config",
    "prn_template",
    "prn_route",
    "prn_job",
    "prn_template_catalog",
    "prn_template_version",
    "notify_message",
    "notify_template"
  ],
  "txn_no": [
    "mch_deposit_txn",
    "mch_debt_txn"
  ],
  "source_no": [
    "mbr_member_source",
    "mch_debt_txn",
    "ord_refund_obligation",
    "ent_owner_registry",
    "core_document_number",
    "pay_document_number",
    "ord_price_quote",
    "ord_industry_order_source",
    "ord_industry_fulfillment",
    "prn_export_task",
    "biz_print_fact",
    "mch_booking_owner"
  ],
  "log_no": [
    "mch_staff_log",
    "pay_risk_shadow_log",
    "prn_action_log"
  ],
  "owner_no": [
    "notify_channel",
    "mch_booking_owner",
    "mch_resource_command",
    "mch_booking_hold"
  ]
};

// Source-table exceptions take precedence over global collision suppression.
export const TABLE_KEY_OWNERS = {
  "mkt_quote_revision": {
    "quote_no": {
      "table": "mkt_quote",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_checkout_confirmation": {
    "quote_no": {
      "table": "ord_checkout_quote",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_checkout_operation": {
    "current_quote_no": {
      "table": "ord_checkout_quote",
      "col": "quote_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
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
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "sv_posting_line": {
    "voucher_no": {
      "table": "sv_voucher",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "pmt_migration_decision": {
    "plan_no": {
      "table": "ent_migration_plan",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ent_migration_import": {
    "plan_no": {
      "table": "ent_migration_plan",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ent_legacy_coupon_operation": {
    "source_order_no": {
      "table": "ord_order",
      "col": "order_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ent_sale_reservation": {
    "grant_no": {
      "table": "ent_grant",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "pmt_pay_coupon_projection": {
    "grant_no": {
      "table": "ent_grant",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_service_execution_unit": {
    "unit_no": {
      "table": "ord_service_execution_unit",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "source_operation_no": {
      "table": "ord_checkout_operation",
      "col": "operation_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "caller",
          "to": "caller"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        }
      ]
    },
    "source_line_no": {
      "table": "ord_checkout_line",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "caller",
          "to": "caller"
        },
        {
          "from": "source_operation_no",
          "to": "operation_no"
        },
        {
          "from": "sub_order_no",
          "to": "sub_order_no"
        }
      ]
    },
    "order_no": {
      "table": "ord_order",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "sub_order_no": {
      "table": "ord_sub_order",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "order_no",
          "to": "order_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        }
      ]
    },
    "task_no": {
      "table": "ord_service_execution_unit",
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
    },
    "current_execution_no": {
      "table": "ord_service_execution",
      "col": "execution_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        },
        {
          "from": "unit_no",
          "to": "unit_no"
        }
      ]
    }
  },
  "ord_service_execution": {
    "execution_no": {
      "table": "ord_service_execution",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "grant_no": {
      "table": "ord_service_execution",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "unit_no": {
      "table": "ord_service_execution_unit",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        }
      ]
    },
    "prepare_action_no": {
      "table": "ord_service_execution_action",
      "col": "action_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        },
        {
          "from": "execution_no",
          "to": "execution_no"
        }
      ]
    },
    "declaration_no": {
      "table": "ord_manual_service_admission",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        }
      ]
    },
    "confirmation_no": {
      "table": "ord_checkout_confirmation",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "resource_command_no": {
      "table": "mch_resource_command",
      "col": "command_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        }
      ]
    }
  },
  "ord_service_execution_action": {
    "action_no": {
      "table": "ord_service_execution_action",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "execution_no": {
      "table": "ord_service_execution",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        },
        {
          "from": "entity_no",
          "to": "entity_no"
        },
        {
          "from": "store_no",
          "to": "store_no"
        }
      ]
    }
  },
  "ord_checkout_payment_request": {
    "actor_account_no": {
      "table": "mch_account",
      "col": "mch_account_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "stl_payment_operation": {
    "actor_account_no": {
      "table": "mch_account",
      "col": "mch_account_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
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
  },
  "prn_route_version": {
    "route_no": {
      "table": "prn_route_catalog",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "prn_request": {
    "policy_version_no": {
      "table": "prn_policy_version",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "sv_issue_policy_version": {
    "policy_version_no": {
      "table": "sv_issue_policy_version",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "policy_no": {
      "table": "sv_issue_policy",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "prn_receipt": {
    "receipt_no": {
      "table": "prn_receipt",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ent_migration_return": {
    "receipt_no": {
      "table": "ent_migration_return",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "sv_statement": {
    "voucher_no": {
      "table": "sv_voucher",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    },
    "plan_no": {
      "table": "sv_recharge_plan",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_pricing_template": {
    "active_revision_no": {
      "table": "ord_pricing_template_revision",
      "col": "revision_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_store_pricing_config": {
    "active_revision_no": {
      "table": "ord_store_pricing_revision",
      "col": "revision_no",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_pricing_template_revision": {
    "previous_revision_no": {
      "table": "ord_pricing_template_revision",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_store_pricing_revision": {
    "previous_revision_no": {
      "table": "ord_store_pricing_revision",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_price_quote_line": {
    "quote_no": {
      "table": "ord_price_quote",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_price_quote_allocation": {
    "quote_no": {
      "table": "ord_price_quote",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "sv_recharge_plan_version": {
    "plan_no": {
      "table": "sv_recharge_plan",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "sv_recharge": {
    "plan_no": {
      "table": "sv_recharge_plan",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "mch_management_grant": {
    "grant_no": {
      "table": "mch_management_grant",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_pricing_funding_source": {
    "quote_no": {
      "table": "ord_price_quote",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_industry_order_source": {
    "quote_no": {
      "table": "ord_price_quote",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "pts_topup_plan": {
    "plan_no": {
      "table": "pts_topup_plan",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
        }
      ]
    }
  },
  "ord_manual_price_adjustment": {
    "policy_no": {
      "table": "ord_manual_price_policy",
      "scope": [
        {
          "from": "tenant_no",
          "to": "tenant_no"
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
