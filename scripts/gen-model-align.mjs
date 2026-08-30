#!/usr/bin/env node
/**
 * 领域模型 × 数据库 对齐清单生成器。
 *
 * 前两份文档回答的是「接口长什么样」，这份回答的是**「后端拿什么把它填出来」**：
 * 契约里承诺返回的每个字段，库里得有列存它，否则接口实现到一半才会发现填不出来 ——
 * 而那时前端页面已经按契约写完了。
 *
 * 三个来源：
 *   · 契约   docs/api/openapi{,-b,-ops}.yaml 的 components.schemas（前端生成，形状真源）
 *   · 库     backend/shop-app/src/main/resources/db/migration/*.sql（Flyway 迁移，DDL 真源）
 *   · 映射   本文件的 ENTITY_MAP（唯一需要人维护的部分 —— 表名与类型名不同源，机器猜不出来）
 *
 * 判定口径：
 *   契约有、表里没有  → **阻塞**：这个字段后端返回不了
 *   表有、契约没有    → 内部字段，正常（审计列、锁、租户位…），只统计不报警
 *   类型没有表        → 分两种：聚合视图（正常，注明由哪些表拼）／缺持久化（阻塞）
 *
 * 用法：npm run gen:model-align
 */
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { readColumnNames, MIGRATION_DIR, INVENTORY_MIGRATION_DIR } from "./lib/ddl.mjs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

const OUT = join(ROOT, "docs/api/领域模型对齐清单.md");

// ---------------------------------------------------------------- 库
// DDL 解析在 scripts/lib/ddl.mjs —— 这里曾经自己写过一份，于是
// DROP TABLE 与 MODIFY COLUMN 两个缺陷只在 gen-erd 里修好，本脚本一直错着。

// ---------------------------------------------------------------- 契约
function readSchemas(file) {
  const p = join(ROOT, file);
  if (!existsSync(p)) return {};
  return YAML.parse(readFileSync(p, "utf8")).components?.schemas ?? {};
}

/** camelCase → snake_case。`payableMinor` → `payable_minor` */
/** 表格里的说明：竖线会被当成列分隔 */
const cell = (t) => String(t ?? "").replace(/\|/g, "\\|");

const snake = (s) => s.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();

/**
 * 已知列名别名：契约字段 → 实际列名。
 *
 * 每一条都是一处**真实的命名不一致**，不是翻译规则 —— 所以要在报告里点名，
 * 而不是在这里悄悄抹平。抹平之后，下一个人会以为两边本来就一致。
 */
const COLUMN_ALIAS = {
  // 积分账户：库里省了过去分词（total_earn/total_use），契约用 totalEarned/totalUsed。
  // 点名而不抹平 —— 抹平之后下一个人会以为两边本来就一致
  totalEarned: "total_earn",
  totalUsed: "total_use",
  // 金额：ord_* 用 `xxx_amount`，mkt_*/stl_* 用 `xxx_minor`，契约统一 `xxxMinor`
  goodsMinor: "goods_amount",
  freightMinor: "freight_amount",
  discountMinor: "discount_amount",
  payableMinor: "pay_amount",
  paidMinor: "pay_amount",
  groupPrice: "group_price_minor",
  basePrice: "origin_price_minor",
  lockedPriceMinor: "locked_price",
  priceMinor: "unit_price_minor",
  // 语义同物异名
  verifyCode: "pickup_code",
  desc: "description",
  expectQty: "expect_count",
  interestedCount: "interest_count",
  minCount: "min_count",
  expireAt: "end_at",
  validUntil: "valid_until",
  merchantReply: "merchant_remark",
  returnExpressNo: "express_no",
  arrivalDesc: "arrival_desc",
  cUserNo: "user_no",
  billNo: "settle_no",
  read: "is_read",
  addressId: "address_id",
  /*
   * 商家主体那次域重命名的下游：72 张表用 `entity_no`，契约仍叫 `merchantNo`。
   * **不是抹平差异**，是记下同一件事的两个名字 —— 此前这 9 个字段被逐条报成
   * 「契约有、库里没有列（阻塞）」，而它们一个都不缺，只是名字换过。
   * 真正的阻塞项因此被淹在里面没人看见。
   *
   * <p>放全局是安全的：匹配顺序是**直接列名优先、别名兜底**，
   * 所以仍在用旧列名 `merchant_no` 的那 4 张表（sys_ops_staff / mch_deposit /
   * mch_deposit_txn / prd_merchant_spec_override）照旧走直接匹配，不会被覆盖。
   */
  merchantNo: "entity_no",
  merchantName: "entity_name",
};

/**
 * **按类型**的列名别名。全局表达不了的放这里 —— 同一个字段名在不同实体上映射到不同列
 * （`type` 在 Message 是 `msg_type`、在 OrderItem 是 `category_type`、在 Category 是 `attr_template`），
 * 全局别名会把它们互相覆盖，且覆盖是静默的。
 */
const TYPE_ALIAS = {
  Message: { type: "msg_type" },
  OrderItem: { type: "category_type" },
  Category: { type: "attr_template" },
  Coupon: { name: "title", discountMinor: "face_minor", expireAt: "end_at" },
  Goods: { desc: "description" },
  MerchantApplyReq: { subject: "merchant_type", phone: "contact_phone" },
  // 入驻申请单的两条：subject 是主体档位（库里叫 legal_form），
  // licenses 是结构化资质项（V79 起用 qualification_items，旧的 qualifications 是纯文本）
  MerchantApplyStatus: { subject: "legal_form", licenses: "qualification_items" },
  // 「商家类型」= 主体档位。契约的 MerchantType 就是 MerchantSubject 的别名
  Merchant: { type: "legal_form" },
  MerchantBrief: { type: "legal_form" },
  Quote: { minCount: "min_qty", desc: "note", priceMinor: "unit_price_minor" },
  // 进货/出库单在库里是两张表，单号列各叫各的；契约收成一个 StockDocument
  StockDocument: { docNo: "inbound_no" },
  MemberSegment: { rule: "rule_json" },
  CouponIssueBatch: { planned: "planned_count", issued: "issued_count", skipped: "skipped_count" },
  StoreActivity: { maxExposureMinor: "budget_minor" },
  QuoteRevision: { priceMinor: "to_price_minor" },
};

/**
 * 关联字段：契约里的嵌套对象/数组，数据来自**另一张表**，不是本表的列。
 * 不登记的话它们会全部报成「缺列」，把真正的缺口淹掉。
 */
const RELATION = {
  Community: { pickups: "cmt_pickup_point" },
  // ── 2026-08-30 第二轮定性 ──
  CartItem: {
    merchantNo: "经 goods_no join prd_goods.entity_no —— 购物车行只存 sku，"
      + "归属靠商品带出来。**这是有意的**：商品换了主体，历史购物车行不该跟着变",
    merchantName: "同上，join prd_goods.store_name",
  },
  Sku: {
    storePrice: "prd_store_price.price —— 门店价单独一张表。"
      + "没设过价的店按主体价卖（与门店库存的回退方向相反：没设库存按 0 卖）",
  },
  AfterSale: { timeline: "ord_status_log —— 售后的状态流转与订单共用一张日志表" },
  SpecTemplate: {
    primary: "prd_category_spec.is_primary —— **主维度是「类目 × 模板」这条绑定的属性**，"
      + "不是模板自身的属性：同一个模板绑到不同类目上，是不是主维度可以不一样",
  },
  Category: { children: "prd_category 自关联（parent_no）" },
  // ── 2026-08-30 第三批带出来的 join ──
  UserCoupon: { coupon: "join mkt_coupon —— 券模板快照，一张券和它的模板是两个对象" },
  StoreFulfillmentChannel: {
    pickups: "mch_channel_pickup —— 这条渠道挂了哪些自提点",
    areaNos: "mch_channel_area —— 覆盖到哪些区划",
  },
  StoreFulfillment: { channels: "mch_fulfillment_channel 的多行（一个门店多条渠道）" },
  MerchantStaff: { roles: "mch_store_role —— 一人可在多店多角色，权限取并集" },
  MerchantPlan: {
    planName: "join sys_merchant_plan_def.name —— **订阅行只存 plan_code**："
      + "运营改了档位显示名，已卖出去的订阅要跟着变",
    tiers: "sys_merchant_plan_def 全表 —— 升级页要摆出所有档位",
    trialTier: "join sys_merchant_plan_def（试用档）",
    trialDays: "join sys_merchant_plan_def.trial_days",
  },
  StoreActivity: { audiences: "pmt_activity_audience", goodsNos: "pmt_activity_goods" },
  MerchantSpecDim: { values: "prd_spec_value 的多行" },
  StoreCategory: {
    platformName: "join prd_category.name —— 平台类目的原名。"
      + "**与 display_name 分开**：商家改了叫法之后，运营仍要认得出这是哪个平台类目",
  },
  StoreRole: { storeName: "join mch_store.announcement/name" },
  StaffLog: {
    actor: "join mch_account（actor_account_no）—— 日志只存账号，名字会改",
    targetName: "join mch_account（target_account_no）",
    storeName: "join mch_store（store_no）",
  },
  ServiceArea: { name: "join sys_region.name（ref_code）—— 区划名不落列，地名会变" },
  CommunityApply: {
    merchantName: "join mch_entity.name",
    regionPath: "由 sys_region 逐级上溯拼出来",
  },
  Member: {
    phoneTail: "join usr_person.phone_tail —— **会员挂人档**，号码不在会员表里。"
      + "永远只有后四位：需要完整号的只有平台申诉处置，那条路要理由与审计",
  },
  // ── 进销存：名字与明细都在别的表 ──
  StockBalance: {
    name: "join inv_item.name —— 余额表只存 item_id。**这是有意的**：货品改名不该重写余额行",
    specText: "join inv_item.spec_text",
    baseUom: "join inv_item.base_uom",
  },
  StockItemDetail: {
    barcode: "inv_item_ref 里 ref_system=BARCODE 的那条 —— **一个物料可以有多个条码**"
      + "（换包装还是同一件货），所以它是引用表的一行而不是 inv_item 上的一列。"
      + "商家货号（ERP）走同一张表的另一个 ref_system",
    onHand: "join inv_stock_balance.on_hand（按 location 汇总）",
    reserved: "join inv_stock_balance.reserved",
    byLocation: "inv_stock_balance 按库位的多行",
  },
  StockLedgerRow: { itemName: "join inv_item.name" },
  StockCount: { lines: "inv_stock_count_line" },
  StockCountLine: {
    name: "join inv_item.name",
    specText: "join inv_item.spec_text",
    baseUom: "join inv_item.base_uom",
  },
  StockTransfer: {
    fromLocationName: "join inv_location.name（from_location_id）",
    toLocationName: "join inv_location.name（to_location_id）",
    lines: "调拨明细 —— 调拨走的是「发货出库单 + 收货进货单」两张单的行，不另存一份",
  },
  Goods: {
    merchant: "mch_entity",
    skus: "prd_sku",
    groupBuy: "mkt_group_buy",
    promotions: "营销活动在商品上的投影",
    slots: "服务类容量配置（未建表）",
    card: "prd_goods 上卡券商品的属性（未拆列）",
    virtual: "prd_goods 上虚拟商品的属性（未拆列）",
  },
  OrderAmount: { currency: "ord_order.currency —— 一次支付一个币种，落在主订单上" },
  Order: {
    items: "ord_item",
    amount: "本表的金额列聚成对象",
    timeline: "ord_status_log",
    afterSale: "ord_after_sale",
    payGroupNo: "ord_order.order_no（主订单即支付组）",
    payDeadlineAt: "ord_order.pay_deadline_at —— 支付截止属于「一次支付」，落在主订单上",
    idempotencyKey: "sys_idempotent（幂等键是基础设施表，不挂业务单）",
    currency: "ord_order.currency —— 一次支付一个币种，落在主订单上",
  },
  GroupBuy: { merchant: "mch_entity", members: "mkt_group_member", neighborPickup: "cmt_pickup_point" },
  GroupRequest: { quotes: "mkt_quote", neighbours: "mkt_request_interest" },
  Quote: { merchant: "mch_entity", revisions: "mkt_quote_revision" },
  Review: { appeal: "rvw_appeal", scores: "本表的 score_* 三列" },
  Merchant: {
    serviceCommunityNos: "mch_entity_community",
    address: "mch_store.address —— **主体没有地址，门店才有**。一个主体可以有多家店，"
      + "契约上这一格给的是「主营门店」的地址",
    openHours: "mch_store.open_hours —— 同上，营业时间挂门店",
  },
};

/**
 * 派生字段：**故意不存**，查询时算出来。
 * 存了反而是 bug —— `distance` 依赖当前用户位置，`reached` 依赖 joined_count 与 min_count，
 * 落成列就必然有过期的那一刻。
 */
const DERIVED = {
  PointAccount: {
    // 库里是 (user_no, market) 一行的余额缓存；过期与待生效时点都要查批次
    expiringSoon: "扫 pts_user_ledger 的 EARN 行按 expire_at 算，不落列",
    expiringAt: "同上，取最近一批的到期时间",
    pendingActivateAt: "取最近一批未生效 EARN 行的 available_at，不落列",
  },
  AppointmentSlot: { remaining: "capacity - booked —— 不落列，落了就要和每一次下单占位保持一致" },
  Community: {
    distance: "按用户当前位置实时算",
    originName: "origin_code 经区划字典取名（masterDataPort.regionNames）——"
      + "**只存码不存名**：地名会变，存了名字就会有两份说法",
    rural: "kind === VILLAGE。端上要的是个布尔（走不走农村那套文案与类目），"
      + "库里存的是聚落类型，多一种聚落时布尔就不够用了",
  },
  MerchantBrief: { selfOperated: "同 Merchant.selfOperated" },
  Sku: {
    priceByMarket: "prd_sku 按 (goods_no, market) 是**多行**，聚成 map 下发。"
      + "只在商家侧下发：编辑页按市场逐格填而保存是整份覆盖，"
      + "拿不到整张表就只能回填当前市场那一格，于是改一次标题其余市场的价就被删了",
  },
  Category: {
    qualifications: "requiredCode 经资质字典取名。**展示用，不是校验依据**"
      + "（判据是 required_code）—— 但商家要看的恰恰是这一句人话",
  },
  Pickup: {
    distance: "按用户当前位置实时算",
    hostMerchantNo: "由 owner_ref 解析（type=STORE 时指向 merchant_no）",
    hostName: "join mch_entity",
    hostAvatar: "join mch_entity",
  },
  Goods: {
    price: "SKU 最低价（表注释已写明价格不在本表）",
    originPrice: "同上",
    status: "on_sale + audit_status + pending_on_sale 三列合出来的展示态 ——"
      + "**库里不能合**：审核与上架是两条线，合了「驳回」和「下架」就共用取值",
    hasDraft: "prd_goods_draft 有没有行（不比内容 —— 保存时内容相同即删行）",
  },
  CartItem: {
    title: "join prd_goods（购物车只存 goods_no/sku_no —— 加购到结算之间商品会改，存快照反而给用户看的是旧价）",
    cover: "join prd_goods",
    spec: "join prd_sku",
    price: "join prd_sku（实时价，与快照价的差异在结算页提示）",
    type: "join prd_goods",
    fulfillment: "join prd_goods.fulfillments，下单时才定",
    invalidReason: "由下架/库存实时判",
    giftQty: "由买赠活动实时算",
    giftLabel: "由买赠活动实时算",
  },
  Order: {
    receiver: "receiver_name / receiver_phone / receiver_address 三列聚成对象",
    subOrders: "本表自身。契约的 Order 是子单，主单视角下这一格是同主单的兄弟行",
    pickupName: "join cmt_pickup_point",
    redeemCode:
      "与 verifyCode 同列（verify_code）—— V6 注释写明「自提码/核销码/兑换码三态共用一个字段」。" +
      "契约侧两个名字指向同一个值，需收敛（待办 T-16）",
  },
  Review: { liked: "按当前用户查 rvw_review_like" },
  OrderItem: { merchantNo: "由所属子订单带出" },
  GroupBuy: {
    pickupName: "join cmt_pickup_point",
    reached: "joined_count >= min_count",
    need: "min_count - joined_count",
    joined: "按当前用户查 mkt_group_member",
    isOwner: "按当前用户与发起人比对",
    initiatorNickname: "join 发起人",
    initiatorAvatar: "join 发起人",
  },
  GroupRequest: {
    pickupName: "join cmt_pickup_point",
    interested: "按当前用户查 mkt_request_interest",
    initiatorNickname: "join usr_account（表存 owner_id）",
    initiatorAvatar: "join usr_account",
    confirmedCount: "按 mkt_request_interest 的确认态计数",
    confirmed: "按当前用户算",
  },
  Coupon: {
    received: "按当前用户查 mkt_user_coupon",
    remain: "total_count - received_count，不落列 —— 落了就要和领取动作保持一致",
  },
  Quote: { locked: "由 chosen 推导 —— 选定即锁价（ADR-003），不需要独立列" },
  PickupPoint: {
    ownerType: "由 owner_ref 前缀解析（表把「谁承接」压成一列）",
    ownerNo: "由 owner_ref 解析",
  },
  Merchant: {
    distance: "按用户当前社区实时算",
    selfOperated: "mch_store.business_mode === SELF_OPERATED（MerchantQueryPort.MODE_SELF_OPERATED）"
      + "—— 销售主体是谁挂在**门店**上，不在主体上",
    scores: "表已拆成 score_goods / score_service / score_speed 三列，契约收成一个对象",
  },
  Supplier: {
    fromPlatform: "platform_supplier_no 非空 —— 平台带下来的供应商与自己录的，"
      + "商家能改的字段不一样",
  },
  StockBalance: {
    available: "on_hand - reserved。**不落列**：落了就要和每一次预占保持一致，"
      + "而预占是高频写，多一列就多一处会对不上的地方",
    flags: "按健康规则实时判（负库存 / 零库存仍在架 / 长期未动销）",
  },
  StockItemDetail: { available: "同 StockBalance.available" },
  StockDocument: {
    kind: "由来源表决定：inv_inbound_order → IN，inv_outbound_order → OUT。"
      + "**库里不存这一列** —— 存了就会出现「在进货单表里 kind=OUT」这种自相矛盾的行",
    subtitle: "展示用的一句话（供应商名 / 用途 / 来源单号），按单据类型拼",
  },
  StockTransfer: { totalQty: "按明细行汇总" },
  StoreFulfillmentChannel: {
    denied: "由准入矩阵实时判（S 轴 × T 轴）—— 不是配置，是「这个主体准不准用这条渠道」",
    locked: "由运营处置状态判 —— 锁着时商家侧置灰不可自行打开",
    templateNo: "运费模板在 ful_freight_template，渠道行只存引用（此处未落列，走另一条查询）",
  },
  MasterDataIndustry: { microAllowed: "由准入矩阵判：小微在这个行业准不准做" },
  PaymentApplyment: {
    channelName: "pay_channel 的显示名，字典取",
    canReceiveMoney: "apply_status=ACTIVE 且有 sub_mchid —— **两个条件缺一不可**，"
      + "进件过了但没拿到子商户号照样收不了钱",
    subMchidMasked: "sub_mchid 打码后下发 —— 完整值只在服务端用",
    missing: "按当前主体档位倒推还差哪几份材料",
    submitted: "channel_apply_no 非空",
  },
  MerchantRole: {
    permLabels: "权限码的人话名，字典取 —— **别拿 code 给店主看**",
    usedBy: "按 mch_store_role 计数：这个角色有几个人在用。删角色前要知道影响面",
  },
  MerchantPlan: {
    storeUsed: "按 mch_store 计数",
    staffUsed: "按 mch_account 计数",
    suspendedStores: "超配额被压下的门店 —— 降档时按规则算出来，不落列",
  },
  PlanTier: { current: "与当前订阅的 plan_code 比 —— **随会话变**，不是档位定义的属性" },
  StoreActivity: {
    quotaLeft: "quota - quota_used",
    liveNow: "按 status 与时间窗实时判 —— 落列就要有人定时刷，刷不动时页面会说谎",
  },
  CouponIssueBatch: { skipReasons: "skip_detail 里的明细聚成分类计数" },
  MerchantSpecDim: {
    valueCount: "按 prd_spec_value 计数",
    usedCount: "**按规格组名统计**用在几件商品上 —— 存量商品的快照里只有名字没有维度编号",
    dimUsed: "已建维度数",
    dimQuota: "配额上限，按档位取",
    valueQuota: "同上",
  },
  SpuStd: { categoryName: "join prd_category.name" },
  UserCoupon: {
    usableNow: "按券模板的时间窗与门槛实时判 —— **不落列**：落了就要有人定时刷，"
      + "而刷不动的那一刻用户看到的是一张「可用」的过期券",
  },
  StoreCategory: {
    name: "display_name（商家自己的叫法）—— 没设过时回落平台类目名",
    goodsCount: "按 prd_goods 计数",
    onSaleCount: "按 prd_goods.on_sale 计数",
    pendingCount: "按 prd_goods.audit_status 计数",
  },
  Entity: {
    storeCount: "按 mch_store 计数",
    isPrimary: "当前登录人在这个主体下是不是主账号 —— **随会话变**，不是主体的属性",
    canManage: "由当前会话的角色判，同上",
  },
  Store: {
    payReady: "由 mch_payment_merchant 的进件状态判 —— 门店能不能收钱不是门店表的事",
    staffCount: "按 mch_account 计数",
  },
  StaffLog: { at: "created_at —— 日志的「发生时刻」就是落库时刻，不另存一列" },
  Region: {
    hasChild: "按 parent_code 反查有没有下级 —— 落列的话每次增删下级都要回写父级",
    pending: "audit_status 是待审 —— 提报上来的村/小区要审，字典里的不用",
  },
  MemberTag: { count: "按 mbr_member_tag 计数 —— 标签定义表上不落用量，打标是高频写" },
  SpecOverride: {
    label: "label_override，没设过时回落平台维度名",
    values: "prd_merchant_spec_override 按 (category_no, dim_no) 的多行聚成数组",
  },
  Member: { daysSinceLast: "按 last_order_at 与今天实时算" },
  Address: { region: "表已拆成 province / city / district 三列，契约拼成一个字符串" },
};

/**
 * 契约里有、库里整域没建表的实体。
 * 与「缺几个字段」不同：这些是**整块功能没有持久化**，前端却已经实现并在跑 mock。
 */
const NO_TABLE = {
  PointRecord: {
    impact: "积分流水。见「积分域整体未落库」—— 积分是平台负债，流水是对账依据，不能只存余额。",
    action: "新建 `usr_point_record`（含 balance_after 便于对账）",
  },
};

/**
 * 整体结构不匹配的类型：**逐字段报没有意义**，因为问题不在某个字段，
 * 而在两边把同一件事切成了不同的粒度。这类要产品/后端一起重新对，不是补几列能解决的。
 */
const STRUCTURAL = {
  UserCard: {
    mapped: "mkt_user_coupon",
    problem:
      "映射不成立。`mkt_user_coupon` 是**优惠券领取记录**（coupon_no / 领取时间 / 核销时间），" +
      "而契约的 `UserCard` 是**卡包**：储值卡余额、次卡剩余次数、卡面、有效期 —— 一列都没有。",
    action: "卡包需要独立的表（如 `mkt_user_card`），或明确一期不做卡包并从契约删除该类型",
  },
  SettleBill: {
    mapped: "stl_bill",
    problem:
      "粒度不同。库里 `stl_bill` 是**一个子订单一条**（sub_order_no + gross/commission/net），" +
      "契约的 `SettleBill` 是**一个商家一个周期一张**（periodStart/periodEnd/orderCount）。" +
      "两者不是同一个东西：前者是分账明细，后者是账单。",
    action:
      "要么新增周期账单表（`stl_bill` 降为明细，另建 `stl_settlement`），" +
      "要么改契约让 B 端直接读明细并在服务端按周期聚合",
  },
};

const ALIASED_COLS = new Set(Object.values(COLUMN_ALIAS));

/** 每张表都有的基础设施列。逐表重复列出它们只会淹没真正的内部字段 */
const AUDIT_COLS = new Set([
  "id", "tenant_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted",
]);

/**
 * 契约类型 → 表。表名与类型名不同源（`Order`→`ord_sub_order`），机器猜不出来，
 * 这是本文件唯一需要人维护的部分。
 *
 * `note` 写的是**这条映射为什么不是显然的** —— 一一对应的不用写。
 */
const ENTITY_MAP = {
  MerchantApplyStatus: {
    table: "mch_entity_apply",
    note: "入驻**审核**生命周期。与 `mch_entity.status`（**经营**状态：ACTIVE/SUSPENDED）是两条线 —— 审核发生在商家还不存在时，封禁发生在商家已存在后，混成一个枚举两件事迟早互相踩",
  },
  PointAccount: {
    table: "pts_user_account",
    note: "用户积分账户。`balance` 只放**能花的**分，未过售后期的在 `pending_balance`（V25）—— 合成一个数的话用户看到 500 却只能用 400，无法解释",
  },
  // ── 交易
  Order: {
    table: "ord_sub_order",
    note: "契约的 Order 是**子订单**（一单一商家）。库里另有 `ord_order` 主订单承载「一次支付」，对应契约的 `payGroupNo`",
  },
  OrderItem: { table: "ord_item" },
  OrderAmount: { table: "ord_sub_order", note: "金额是子订单的列，不是独立表 —— 契约把它收进一个对象只为读起来清楚" },
  AfterSale: { table: "ord_after_sale" },
  CartItem: { table: "trd_cart_item" },
  // ── 商品
  Goods: { table: "prd_goods", note: "价格不在这张表（见表注释），`Goods.price` 由 SKU 最低价推导" },
  Sku: { table: "prd_sku" },
  Category: { table: "prd_category" },
  // ── 用户与商家
  User: { table: "usr_account" },
  Address: { table: "usr_address" },
  Merchant: { table: "mch_entity" },
  MerchantBrief: { table: "mch_entity", note: "同表的投影，商品卡上只带这几个字段" },
  MerchantApplyReq: { table: "mch_entity_apply" },
  // ── 社区与自提
  Community: { table: "cmt_community" },
  Pickup: { table: "cmt_pickup_point" },
  PickupPoint: { table: "cmt_pickup_point" },
  // ── 团购与求团
  GroupBuy: { table: "mkt_group_buy" },
  GroupRequest: { table: "mkt_request" },
  Quote: { table: "mkt_quote" },
  QuoteRevision: { table: "mkt_quote_revision" },
  // ── 评价
  Review: { table: "rvw_review" },
  ReviewAppeal: { table: "rvw_appeal" },
  // ── 营销
  Coupon: { table: "mkt_coupon" },
  MarketingCampaign: { table: "mkt_campaign", note: "四类活动统一一张表：它们只差「触发条件 + 优惠方式」" },
  SpecTemplate: { table: "prd_spec_template" },

  // ── 2026-08-30 补映射：表一直都在，只是这里没写 ──
  //
  // 没写的后果不是「少一条记录」，是被归进「契约有类型、库里无承载」那一类，
  // **字段级比对根本不启动** —— 清单看着在管，实际一个字段都没比。
  MerchantStaff: {
    table: "mch_account",
    note: "商家账号。**契约里叫 mchAccountNo 不叫 staffNo** —— staffNo 被平台运营占着，"
      + "两者是不同的人（sys_ops_staff 才是运营）",
  },
  MerchantRole: { table: "mch_role", note: "商家自定义角色。mch_store_role 是「谁在哪家店是什么角色」的授权行" },
  MerchantPlan: {
    table: "mch_entity_plan",
    note: "**订阅行**（这家店买了哪一档、什么时候到期）。档位的定义在 sys_merchant_plan_def"
      + " —— 两者分开是因为改档位定义不该改已卖出去的订阅",
  },
  PlanTier: { table: "sys_merchant_plan_def", note: "档位定义（配额与能力开关），见 MerchantPlan" },
  MemberSetting: { table: "mbr_setting" },
  AppointmentSlot: {
    table: "mch_appointment_slot",
    note: "**闸 C 第一次跑就抓到的存量错**：它此前登记在 VIEW_TYPES 里写着"
      + "「由容量配置实时算」—— 那句话在建表之前是对的，V 表建起来之后没人回来改，"
      + "于是这个实体从报告里消失了，字段比对一次没跑过。列与契约几乎一一对应",
  },
  MemberSegment: { table: "mbr_segment" },
  StoreActivity: { table: "pmt_activity" },
  MerchantSpecDim: { table: "prd_spec_dim" },
  MasterDataIndustry: { table: "sys_industry" },
  StoreFulfillment: { table: "mch_fulfillment_channel" },
  StoreFulfillmentChannel: { table: "mch_fulfillment_channel", note: "同表的单行投影" },
  PaymentApplyment: {
    table: "mch_payment_merchant",
    note: "收款进件。**第一版我映到了 `pmt_apply`** —— 名字像，其实是促销域的核销记录"
      + "（pmt_ = promotion），12 个字段全对不上。字段级比对当场把这个错映射抓了出来，"
      + "而在它之前这个实体根本没被比过",
  },
  CouponIssueBatch: { table: "pmt_coupon_issue" },

  SpuStd: { table: "prd_spu_std" },
  InvoiceRequest: { table: "ord_invoice_request" },
  UserCoupon: {
    table: "mkt_user_coupon",
    note: "**映射到老模型是有意的**：契约描述的就是老模型（`Coupon` 的字段别名"
      + "指向 mkt_coupon 的 face_minor/discount_rate）。后端 P4 已搬到 pmt_*"
      + "（V232 回填，pmt 用量 72 处 vs mkt 42 处），**契约还没跟上** —— "
      + "这是一条真欠账，但改映射会凭空造出一批假缺口，要连契约一起改，属独立一批",
  },
  Qualification: { table: "mch_qualification" },
  StoreCategory: { table: "mch_store_category" },
  Entity: { table: "mch_entity", note: "运营端叫 Entity，C/B 端契约叫 Merchant —— 同一张表两个名字" },
  Store: { table: "mch_store" },
  StoreRole: { table: "mch_store_role" },
  StaffLog: { table: "mch_staff_log" },
  ServiceArea: { table: "mch_service_area" },
  CommunityApply: { table: "cmt_community_apply" },
  Region: { table: "sys_region" },
  Member: { table: "mbr_member", note: "会员挂**人档**不挂账号（person_no）—— 换手机号不换会员" },
  MemberTag: {
    table: "mbr_tag",
    note: "**标签的定义表**。`mbr_member_tag` 是「谁被打了哪个标」的关联表 —— "
      + "第一版我映到了后者，于是 name/status 全被报成缺列",
  },
  SpecOverride: {
    table: "prd_merchant_spec_override",
    note: "**仍在用旧列名 merchant_no** —— 全局别名 merchantNo→entity_no 不影响它，"
      + "匹配是直接列名优先",
  },

  // ── 2026-08-30 进销存这一批 ──
  //
  // 这些实体的表在**第二个库**（backend/shop-inventory/.../db/inventory），
  // 而本文件此前只读平台那一条 Flyway 历史 —— 于是整个域被报成
  // 「契约有类型、库里无承载（阻塞）」，**结论正好反了**：表都建好了。
  // 20 条假阻塞把真的那几条淹掉，这正是这份清单最怕的形状。
  StockBalance: { table: "inv_stock_balance" },
  StockItemDetail: { table: "inv_item", note: "货品档 + 当前余额的投影" },
  StockLedgerRow: { table: "inv_ledger" },
  StockDocument: {
    table: "inv_inbound_order",
    note: "进货/出库单在库里是**两张表**（inv_inbound_order / inv_outbound_order），"
      + "契约收成一个 StockDocument 靠 docKind 区分 —— 单据字段两边一致，分表是为了各自的行表",
  },
  StockCount: { table: "inv_stock_count" },
  StockCountLine: { table: "inv_stock_count_line" },
  StockTransfer: { table: "inv_transfer_order" },
  StockLocation: { table: "inv_location" },
  Supplier: { table: "inv_supplier" },
  Carrier: {
    table: "ful_carrier",
    note: "**承运方归履约域维护，进销存只读** —— 跨库不能外键，"
      + "所以调拨单存的是业务键 carrier，名字由端上回传快照",
  },
  UserCard: { table: "mkt_user_coupon", note: "卡包与券共表：储值卡/次卡在 mkt_user_coupon 上用类型区分" },
  // ── 结算
  SettleBill: { table: "stl_bill" },
  // ── 消息
  Message: { table: "notify_message" },
};

/**
 * 聚合视图：**本来就不该有表**，由多张表拼出来。
 * 列在这里是为了把它们从「缺持久化」里摘出去 —— 否则报告会被一堆假问题淹没，
 * 而真正缺表的那几个就没人看见了。
 */
const VIEW_TYPES = {
  StoreHome: "mch_entity + prd_goods + usr_store_favorite",
  MerchantTodo: "ord_sub_order + ord_after_sale + mkt_request 的计数",
  MerchantStats: "ord_sub_order 的聚合",
  FrequentItem: "ord_item 按 (user_no, sku_no) 的频次聚合",
  ReorderResult: "加购动作的返回值，非实体",
  PickingRow: "ord_item 按自提点 + SKU 的汇总",
  PickupOverview: "ord_sub_order + ful_verify_log 的计数",
  VerifyBatchResult: "批量核销的返回值，非实体",
  RateCard: "费率配置，当前在 stl_bill.commission_rate 落快照",
  PointsDeductible:
    "结算页试算的**返回值，非实体**：由 pts_user_account.balance + 抵扣上限 + 四级开关实时算出",
  MerchantPointsRecord:
    "stl_bill 中 points_fee_minor > 0 的行的投影。**不是表** —— " +
    "商家的发分服务费按单计提在结算单上，没有单独的积分账（V34 删了 pts_merchant_ledger）",
  MerchantPointAccount:
    "pts_merchant_ledger 按 (merchant_no, period) 聚合 + 四级开关判定。" +
    "**不是表**：商家侧看的是钱与开关，不是余额（预付费模型，V22/V28）",
  StoreProfile: "mch_entity 的店主可编辑子集",
  MerchantProfile:
    "B 端登录态，跨四张表：mch_entity（主体）+ usr_account（手机号，经 owner_user_no）" +
    " + mch_entity_apply（驳回原因）+ cmt_pickup_point（是否承接自提点）",
  StoreQrcode: "由 merchant_no 实时生成，不存",
  ShareKit: "由服务端按语言/市场实时生成，不存",
  MerchantCustomer: "ord_sub_order 按 (merchant_no, user_no) 的聚合",
  VisitedMerchant: "mch_entity + ord_sub_order 的聚合",
  SpecGroup: "prd_goods.spec_groups JSON 列内的结构",
  SpecOption: "prd_goods.spec_groups JSON 列内的结构",
  Promotion: "营销活动在商品上的投影",
  CardSpec: "prd_goods 上卡券类商品的属性",
  VirtualSpec: "prd_goods 上虚拟商品的属性",
  OrderTimelineNode: "ord_status_log 的行",
  ReviewScores: "评价的三维分，随评价表存",
  LoginResp: "登录响应（token + 用户档案），非实体",
  MerchantLoginResp: "商家登录响应，非实体",
  Result: "响应信封，非实体",
  PageResult: "分页信封，非实体",
};

/**
 * 缺口的主题归类。
 *
 * 33 条平铺出来，读的人看不出**哪几条其实是同一个洞** —— 积分那 6 条不是 6 个疏漏，
 * 是整个积分域没落库；自提点那 4 条不是字段没加，是「成团单位是自提点」这条核心约束
 * 在库里没有承载。按洞分组才知道要开几张单。
 */
const THEMES = [
  {
    title: "积分域整体未落库",
    match: /points|pointsGranted/i,
    why:
      "积分能被商家接收并向平台兑付 —— 它是**平台的负债**（ADR-006），要按资金标准建模。" +
      "但库里没有积分账户表、没有积分流水表，订单上也没有任何积分列。" +
      "C 端已实现下单抵扣与完成返分，接后端即刻失效。",
    action: "新建 `usr_point_account` + `usr_point_record`，并在 ord_sub_order 上补 points_* 列",
  },
  {
    title: "称重差价（生鲜核心）无处落",
    match: /nominalGram|weighed|weighAdjust/i,
    why:
      "生鲜按重计价：下单按标称重量收，实际称重后产生差价（补款或退款）。" +
      "`prd_goods` 上有 `weighed` 标记，但**订单行没有标称重量、没有实称结果、订单没有差价金额** —— " +
      "差价算不出来，也无处记账。",
    action: "ord_item 补 nominal_gram / weighed，ord_sub_order 补 weigh_adjust_amount",
  },
  {
    title: "「成团单位是自提点」在库里没有承载",
    match: /^(GroupBuy|GroupRequest)\.pickup/,
    why:
      "团购拼的是**一车送到一个点**的成本，自提点是成团范围（见 GroupBuy.pickupNo 的契约注释）；" +
      "求团的范围同样是自提点/小区。但 `mkt_group_buy` 与 `mkt_request` 都没有 pickup_no —— " +
      "团按什么范围成、货送到哪个点，库里表达不了。",
    action: "两张表各补 pickup_no（求团单另需 budget_minor、成团后回填 group_no）",
  },
  {
    title: "商家服务范围（可见性核心约束）缺列",
    match: /^Merchant\.service/,
    why:
      "邻里购物最硬的约束是**商家有服务半径**：隔壁区的生鲜店送不到我的自提点。" +
      "`serviceScope` 决定这家店的货在 C 端能被谁看到，选错不是展示问题而是下单后提不了货。" +
      "库里 `mch_entity` 没有任何范围字段 —— 可见性过滤没有依据。",
    action: "mch_entity 补 service_scope / service_city_code，另建 mch_entity_community 关联表",
  },
  {
    title: "订单履约字段缺失（与已知契约漂移互为佐证）",
    match: /^Order\.(appointmentAt|groupNo|redeemCode|expressNo)/,
    why:
      "`appointmentAt`（预约时段）与 `groupNo`（参团下单）此前已在**请求体层**发现后端不认" +
      "（见契约漂移清单）—— 现在库侧确认：列也没有。同一个洞的第二处证据。" +
      "`redeemCode`（虚拟商品兑换码/卡号）与 `expressNo`（快递单号）同样无处存。",
    action: "ord_sub_order 补 appointment_at / group_no / redeem_code / express_no",
  },
];

/**
 * 对象字面量里写重复键，JS **后者静默胜出**，前面那条的登记就凭空消失了 ——
 * 报告随之多出几条假的「缺列」。这个坑在本文件里踩过两次（DERIVED.Merchant、DERIVED.GroupBuy），
 * 两次都是靠肉眼比对报告才发现的。让它当场炸。
 */
function assertNoDupKeys(name, src) {
  const seen = new Set();
  for (const m of src.matchAll(/^  (\w+):/gm)) {
    if (seen.has(m[1])) throw new Error(`${name} 里有重复键 \`${m[1]}\` —— 后写的会静默覆盖先写的`);
    seen.add(m[1]);
  }
}
{
  const self = readFileSync(new URL(import.meta.url), "utf8");
  for (const name of ["RELATION", "DERIVED", "ENTITY_MAP", "VIEW_TYPES", "COLUMN_ALIAS", "TYPE_ALIAS"]) {
    const block = self.match(new RegExp(`const ${name} = \\{([\\s\\S]*?)\\n\\};`));
    if (block) assertNoDupKeys(name, block[1]);
  }
}

// ---------------------------------------------------------------- 比对
/*
 * **两条 Flyway 历史都要读**。`INVENTORY_MIGRATION_DIR` 默认不进 `readSchema`，
 * 那个默认对平台侧生成器（ER 图、表清单）是对的 —— 混进去会让它们凭空多出十几张表。
 *
 * 但本文件映射的是**契约实体**，而契约里有整整一批进销存实体
 * （StockBalance / StockLedgerRow / Supplier / Carrier …）。不读第二个目录，
 * 它们会被报成「契约有类型、库里无承载（阻塞）」——**结论正好反了**：
 * 表都建好了，是这个生成器看不见。20 条假阻塞会把真的那几条淹掉。
 */
const tables = readColumnNames(ROOT, [MIGRATION_DIR, INVENTORY_MIGRATION_DIR]);
const cSchemas = { ...readSchemas("docs/api/openapi.yaml"), ...readSchemas("docs/api/openapi-b.yaml") };
const opsSchemas = readSchemas("docs/api/openapi-ops.yaml");

/** 请求/查询 DTO 不参与持久化比对：它们是入参形状，不是实体 */
const isDto = (n) => /(Req|ReqBody|Query|Draft|Config|Rule|Texts)$/.test(n);
const isMapped = (n) => /^(Partial[_<])?Record[_<]/.test(n);

const findings = { missingCol: [], noTable: [], aliasUsed: [], internalOnly: [], suspectMap: [] };

function compare(typeName, schema) {
  if (STRUCTURAL[typeName]) return null; // 单独成节，见「结构不匹配」
  const map = ENTITY_MAP[typeName];
  if (!map) return null;
  const t = tables.get(map.table);
  if (!t) {
    /*
     * ── 闸 A：**当场炸，不写进报告** ──
     *
     * 上一版这里是往 noTable 里推一行，于是它混进「契约有类型、库里无承载」那一节，
     * 看着像一条已知欠账。四个表名（usr_user / usr_merchant / usr_merchant_apply /
     * msg_message）就是这么活下来的，而且**带着五个类型一起空转** ——
     * 指向不存在的表时字段级比对根本不启动，清单却看着有在管。
     *
     * 一条指向不存在的表的映射不是欠账，是这张表本身错了。表被改名/删掉是常事，
     * 改的人不会想到来更新这里 —— 所以要让下一次生成当场失败，而不是安静降级。
     */
    throw new Error(
      `ENTITY_MAP.${typeName} 指向 \`${map.table}\`，但库里没有这张表。\n`
      + "  表被改名或删掉了？去迁移里找 RENAME TO / DROP TABLE，把映射改到现名。\n"
      + "  **别把它删掉了事** —— 删了这个类型就落进「库里无承载」，字段比对同样不跑。",
    );
  }
  const cols = new Set(t.cols);
  const rows = [];
  for (const field of Object.keys(schema.properties ?? {})) {
    const alias = TYPE_ALIAS[typeName]?.[field] ?? COLUMN_ALIAS[field];
    const direct = snake(field);
    let col = null;
    if (cols.has(direct)) col = direct;
    else if (alias && cols.has(alias)) col = alias;

    if (col) {
      rows.push({ field, col, kind: col === direct ? "ok" : "alias" });
      if (col !== direct) findings.aliasUsed.push({ type: typeName, field, col });
      continue;
    }
    const rel = RELATION[typeName]?.[field];
    if (rel) {
      rows.push({ field, col: null, kind: "relation", note: rel });
      continue;
    }
    const der = DERIVED[typeName]?.[field];
    if (der) {
      rows.push({ field, col: null, kind: "derived", note: der });
      continue;
    }
    rows.push({ field, col: null, kind: "missing" });
    findings.missingCol.push({ type: typeName, table: map.table, field });
  }
  /*
   * ── 闸 B：**命中率过低 = 这条映射多半错了，而不是这张表缺列** ──
   *
   * 两种形状在上一版里被渲染成同一句「缺这些列」：
   *   · 这张表确实少一两列        → 真欠账，该补列或改契约
   *   · 映射挂到了另一张表        → 一串假缺口，照着做会去给不相干的表加列
   *
   * 今天两次都是后者，而且都是**按名字猜**的：
   *   PaymentApplyment → pmt_apply（名字像收款进件，其实 pmt_ 是促销域的核销记录），12 个字段全不匹配
   *   MemberTag        → mbr_member_tag（打标关联行，标签定义在 mbr_tag），3 个字段全不匹配
   * 两次都是靠肉眼看出「怎么一个都对不上」才发现的。判据其实很粗：
   * 一张表不会同时少掉大半个类型的字段。
   *
   * 阈值取「匹配（含别名/关联/推导）不足一半，且缺失多于 3 条」——
   * 字段少的类型不触发，因为 2/3 不匹配也可能是真的。
   *
   * <p><b>它有个边界，别指望它兜底</b>：字段一旦被登记进 RELATION/DERIVED 就算命中，
   * 于是**先映错、再把一堆字段登记成 join**，这道闸就不响了。
   * 验过：把 PaymentApplyment 改回错的 `pmt_apply`，因为那些字段事后已经定性，它一声不吭。
   *
   * <p>所以它守的是「**新加一条映射、字段还没定性**」那一刻 —— 恰好是需要它的时刻，
   * 也是今天两次映错发生的时刻。事后再改映射得靠人自己重新看一遍字段。
   */
  const matched = rows.filter((r) => r.kind !== "missing").length;
  const missing = rows.length - matched;
  if (missing > 3 && matched * 2 < rows.length) {
    findings.suspectMap.push({
      type: typeName, table: map.table, matched, total: rows.length,
      missing: rows.filter((r) => r.kind === "missing").map((r) => r.field),
    });
  }
  return { map, table: t, rows };
}

// ---------------------------------------------------------------- 渲染
const md = [
  "# 领域模型 × 数据库 对齐清单",
  "",
  "> 由 `npm run gen:model-align` 生成，**请勿手改**。",
  "> 契约（三份 OpenAPI）× 库（Flyway 迁移 DDL）× 映射表（`scripts/gen-model-align.mjs` 的 `ENTITY_MAP`）。",
  "",
  "回答的问题：**契约承诺返回的字段，库里有列存它吗**。",
  "契约有而库里没有 = 后端返回不了，属阻塞；库里有而契约没有 = 内部字段，正常。",
  "",
  "对照：[API 清单](API清单.md) ｜ [响应格式规范](响应格式规范.md) ｜ [契约漂移清单](契约漂移清单.md)",
  "",
  "---",
  "",
];

// 先算，后写摘要
const details = [];
for (const [name, schema] of Object.entries(cSchemas)) {
  if (isDto(name) || isMapped(name) || schema.enum || !schema.properties) continue;
  const r = compare(name, schema);
  if (r) details.push([name, r]);
  else if (!ENTITY_MAP[name] && !VIEW_TYPES[name] && !STRUCTURAL[name]) {
    findings.noTable.push({ type: name, reason: "既没有映射到表，也未登记为聚合视图" });
  }
}

// 平台端：几乎整域未落库，逐个报会淹没报告，按域汇总
const opsEntities = Object.entries(opsSchemas).filter(
  ([n, s]) => !isDto(n) && !isMapped(n) && !s.enum && s.properties,
);
const opsWithTable = opsEntities.filter(([n]) => ENTITY_MAP[n] && tables.has(ENTITY_MAP[n].table));

// 表侧：没有任何契约类型映射过来的
const mappedTables = new Set(Object.values(ENTITY_MAP).map((m) => m.table));
const unmappedTables = [...tables.keys()].filter((t) => !mappedTables.has(t));

md.push(
  "## 摘要",
  "",
  "| 项 | 数 |",
  "|---|---|",
  `| 库表 | ${tables.size} |`,
  `| **契约有实体、库里整域无表** | **${findings.noTable.length}** |`,
  `| **结构不匹配（粒度对不上）** | **${Object.keys(STRUCTURAL).length}** |`,
  `| C/B 端契约实体（已映射到表） | ${details.length} |`,
  `| **契约有、库里没有列的字段** | **${findings.missingCol.length}** |`,
  `| 命名不一致 | ${new Set(findings.aliasUsed.map((a) => `${a.field}→${a.col}`)).size} 组映射（${findings.aliasUsed.length} 处字段） |`,
  `| 平台端契约实体 | ${opsEntities.length}，其中有表的 ${opsWithTable.length} |`,
  `| 未被契约映射的表（内部表） | ${unmappedTables.length} |`,
  "",
);

// ── 阻塞项
md.push("## 一、契约有、库里没有列（阻塞）", "");
if (!findings.missingCol.length) {
  md.push("无。", "");
} else {
  md.push(
    "后端实现这些接口时会发现**这个字段填不出来**，而前端页面已经按契约写完了。",
    "每条要么补列，要么改契约删字段 —— 不能留着。",
    "",
  );

  const taken = new Set();
  for (const th of THEMES) {
    const hit = findings.missingCol.filter((f) => {
      const key = `${f.type}.${f.field}`;
      return !taken.has(key) && (th.match.test(key) || th.match.test(f.field));
    });
    if (!hit.length) continue;
    hit.forEach((f) => taken.add(`${f.type}.${f.field}`));
    md.push(
      `### ${th.title}（${hit.length} 处）`,
      "",
      th.why,
      "",
      "| 契约字段 | 目标表 |",
      "|---|---|",
      ...hit.map((f) => `| \`${f.type}.${f.field}\` | \`${f.table}\` |`),
      "",
      `**处置**：${th.action}`,
      "",
    );
  }

  const rest = findings.missingCol.filter((f) => !taken.has(`${f.type}.${f.field}`));
  if (rest.length) {
    md.push(
      `### 其余（${rest.length} 处）`,
      "",
      "彼此无关，逐条补列或逐条从契约删字段。",
      "",
      "| 契约字段 | 目标表 |",
      "|---|---|",
      ...rest.map((f) => `| \`${f.type}.${f.field}\` | \`${f.table}\` |`),
      "",
    );
  }
}

// ── 无持久化
md.push("## 二、契约有类型、库里无承载（阻塞）", "");
if (!findings.noTable.length) {
  md.push("无。", "");
} else {
  md.push(
    "既没有映射到表，也未登记为聚合视图 —— 要么该建表，要么该在生成器的 `VIEW_TYPES` 里",
    "写明它由哪些表拼出来。**留白就是没人想过这件事**。",
    "",
  );
  for (const f of findings.noTable) {
    const x = NO_TABLE[f.type];
    md.push(`### \`${f.type}\``, "");
    if (x) md.push(x.impact, "", `**处置**：${x.action}`, "");
    else md.push(`${f.reason} —— 未定性，需要人看一眼。`, "");
  }
}

// ── 结构不匹配
md.push("## 三、结构不匹配（要重新对齐，不是补列）", "");
for (const [name, x] of Object.entries(STRUCTURAL)) {
  md.push(`### \`${name}\` ↔ \`${x.mapped}\``, "", x.problem, "", `**处置**：${x.action}`, "");
}

// ── 命名不一致
md.push("## 四、命名不一致（非阻塞，但要有人拍板）", "");
if (!findings.aliasUsed.length) {
  md.push("无。", "");
} else {
  md.push(
    "字段能对上，但两边叫法不同。**不改也能跑**（ORM 映射一次即可），",
    "代价是此后每个人都要记住两套名字，且映射表本身会成为新的漂移点。",
    "",
    "| 契约字段 | 库列 | 出现在 |",
    "|---|---|---|",
  );
  const seen = new Set();
  for (const a of findings.aliasUsed) {
    const key = `${a.field}→${a.col}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const types = findings.aliasUsed
      .filter((x) => x.field === a.field && x.col === a.col)
      .map((x) => x.type);
    md.push(`| \`${a.field}\` | \`${a.col}\` | ${[...new Set(types)].map((t) => `\`${t}\``).join("、")} |`);
  }
  md.push("");
}

// ── 逐实体对照
md.push("## 五、实体逐字段对照", "");
for (const [name, r] of details.sort((a, b) => a[0].localeCompare(b[0]))) {
  const miss = r.rows.filter((x) => x.kind === "missing").length;
  md.push(
    `### \`${name}\` → \`${r.map.table}\`${miss ? `　⚠️ 缺 ${miss} 列` : "　✅"}`,
    "",
  );
  if (r.table.comment) md.push(`> 表注释：${r.table.comment}`, "");
  if (r.map.note) md.push(`> ${r.map.note}`, "");
  md.push("| 契约字段 | 库列 / 来源 | |", "|---|---|:---:|");
  const MARK = { ok: "✅", alias: "≈", relation: "↗", derived: "ƒ", missing: "❌" };
  for (const row of r.rows) {
    const src = row.col ? `\`${row.col}\`` : row.note ? cell(row.note) : "—";
    md.push(`| \`${row.field}\` | ${src} | ${MARK[row.kind]} |`);
  }
  const contractFields = new Set(Object.keys(cSchemas[name].properties ?? {}).map(snake));
  const extra = r.table.cols.filter(
    (c) => !contractFields.has(c) && !ALIASED_COLS.has(c) && !AUDIT_COLS.has(c),
  );
  if (extra.length) {
    md.push("", `库里另有（内部字段，不下发）：${extra.map((c) => `\`${c}\``).join("、")}`);
  }
  md.push("");
}

// ── 平台端
md.push(
  "## 六、平台端：契约与库的整体缺口",
  "",
  `平台端契约有 **${opsEntities.length}** 个实体类型，库里有对应表的只有 **${opsWithTable.length}** 个。`,
  "",
  "这不是漂移，是**后端尚未开工**（API 清单里平台端后端覆盖率 0.6%）。",
  "列在这里是为了让规模可见：平台端落地时要新建的表大致就是这个数量级，",
  "而其中相当一部分（风控、内容、消息、财务）与现有 39 张表没有交集，是全新的域。",
  "",
  "已有表的：" + (opsWithTable.length ? opsWithTable.map(([n]) => `\`${n}\``).join("、") : "无") + "。",
  "",
);

// ── 内部表
md.push(
  "## 七、未被契约映射的表",
  "",
  "以下表没有契约类型直接对应 —— 多数是**内部机制表**（幂等、发件箱、审计、库存锁、状态流水），",
  "不下发到端上是对的。逐个确认一遍：如果某张表本该有接口暴露却没有，那是接口缺口。",
  "",
  "| 表 | 注释 |",
  "|---|---|",
);
for (const t of unmappedTables.sort()) {
  md.push(`| \`${t}\` | ${tables.get(t).comment || "—"} |`);
}
md.push("");

/*
 * ── 闸 B（续）与闸 C：**写文件之前拦下来** ──
 *
 * 放在渲染之后是有意的：报告已经拼好，但不落盘 —— 否则一份带着错映射的清单
 * 会先被提交，再被人拿去对齐。
 */
if (findings.suspectMap.length) {
  const lines = findings.suspectMap.map(
    (x) => `  ${x.type} → \`${x.table}\`：${x.total} 个字段只对上 ${x.matched} 个\n`
      + `      对不上的：${x.missing.join(" ")}`,
  );
  throw new Error(
    "这些映射**多半是错的**（不是这张表缺列）：\n" + lines.join("\n") + "\n\n"
    + "  一张表不会同时少掉大半个类型的字段。先确认这个类型真的落在这张表上 ——\n"
    + "  判据要来自**类型自己的注释或后端代码**，不是表名像。\n"
    + "  确实是真缺口（整块功能没建列）的，把它挪进 NO_TABLE 并写清影响与处置。",
  );
}

/*
 * ── 闸 C：登记成聚合视图/无表的，若存在同名候选表，必须说明为什么不是它 ──
 *
 * 这一条挡的是最隐蔽的一种错：**登记成视图之后，那个类型就从报告里彻底消失** ——
 * 不红、不列、没人复核。相比之下「没映射」至少还在阻塞清单里红着。
 * 把一个有表的实体登记成视图，等于给它发了一张永久免检条子。
 *
 * 判据只用最保守的一条：类型名转 snake 之后，有表以它结尾（`MerchantCoupon`
 * → `pmt_coupon`）。命中就要求 note 里提到那张表名 —— 写一句「不是它，因为…」即可，
 * 不是禁止登记为视图。
 */
{
  const tableNames = [...tables.keys()];
  const bad = [];
  for (const [name, desc] of Object.entries(VIEW_TYPES)) {
    const s2 = snake(name);
    const cand = tableNames.filter((t) => t.endsWith("_" + s2) || t === s2);
    const text = String(desc);
    const unexplained = cand.filter((t) => !text.includes(t));
    if (unexplained.length) bad.push(`  ${name}：库里有 ${unexplained.join(" / ")}，而登记为聚合视图`);
  }
  if (bad.length) {
    throw new Error(
      "这些类型登记成了聚合视图，但库里存在同名表：\n" + bad.join("\n") + "\n\n"
      + "  **登记成视图之后它会从报告里消失** —— 不红、不列、没人再复核。\n"
      + "  要么改成 ENTITY_MAP 映射，要么在说明里点名那张表并写清为什么不是它。",
    );
  }
}

writeFileSync(OUT, md.join("\n"));
console.log(`✅ ${OUT}`);
console.log(
  `   库表 ${tables.size} · 已映射实体 ${details.length} · 缺列 ${findings.missingCol.length} · 命名不一致 ${findings.aliasUsed.length}`,
);
if (findings.missingCol.length) {
  console.log(`   ⚠️ 契约有而库里没有列的字段：`);
  for (const f of findings.missingCol) console.log(`      ${f.type}.${f.field} → ${f.table}`);
}
