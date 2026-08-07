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
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import YAML from "yaml";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");
const OUT = join(ROOT, "docs/api/领域模型对齐清单.md");

// ---------------------------------------------------------------- 库
/** 解析 Flyway 迁移的 DDL。产物是自家写的，形状可控，不引 SQL parser。 */
function readTables() {
  const out = new Map();
  if (!existsSync(MIGRATION_DIR)) return out;
  // **按版本号数字排序**，不是字典序 —— 字典序会把 V10 排在 V2 前面，
  // 而 ALTER 是有先后的：先 RENAME 再按旧名找列，结果就全错了。
  const files = readdirSync(MIGRATION_DIR)
    .filter((f) => f.endsWith(".sql"))
    .sort((a, b) => (parseInt(a.slice(1), 10) || 0) - (parseInt(b.slice(1), 10) || 0));
  const sql = files.map((f) => readFileSync(join(MIGRATION_DIR, f), "utf8")).join("\n");

  for (const m of sql.matchAll(
    /CREATE TABLE(?: IF NOT EXISTS)? (\w+)\s*\(([\s\S]*?)\n\)\s*ENGINE([^;]*);/g,
  )) {
    const [, name, body, tail] = m;
    const cols = [];
    for (const raw of body.split("\n")) {
      const line = raw.trim();
      const c = line.match(
        /^(\w+)\s+(BIGINT|VARCHAR|INT|TINYINT|SMALLINT|DATETIME|TIMESTAMP|TEXT|DECIMAL|JSON|CHAR|DOUBLE)/i,
      );
      // KEY / UNIQUE KEY / PRIMARY KEY 也能匹配上首个 \w+，排掉
      if (c && !/^(KEY|UNIQUE|PRIMARY|INDEX|CONSTRAINT|FULLTEXT)$/i.test(c[1])) cols.push(c[1]);
    }
    out.set(name, { cols, comment: tail.match(/COMMENT\s*=?\s*'([^']*)'/)?.[1] ?? "" });
  }

  // ---- ALTER：只看 CREATE TABLE 会得出**已被后续迁移修正过的**旧结构。
  // 真实踩到过：`ord_sub_order.pickup_code` 在 V6 改名成 `verify_code`，
  // 而报告照旧说「契约的 verifyCode 与库列 pickup_code 命名不一致」—— 早就一致了。
  // 反向更危险：漏看 ADD COLUMN 会把已有的列报成缺失，照着补一遍就是重复加列。
  for (const m of sql.matchAll(
    /ALTER TABLE\s+(\w+)\s+(ADD COLUMN|RENAME COLUMN|DROP COLUMN)\s+(\w+)(?:\s+TO\s+(\w+))?/gi,
  )) {
    const [, table, opRaw, col, newName] = m;
    const t = out.get(table);
    if (!t) continue;
    const op = opRaw.toUpperCase();
    if (op === "ADD COLUMN") {
      if (!t.cols.includes(col)) t.cols.push(col);
    } else if (op === "DROP COLUMN") {
      t.cols = t.cols.filter((c) => c !== col);
    } else if (op === "RENAME COLUMN" && newName) {
      t.cols = t.cols.map((c) => (c === col ? newName : c));
    }
  }
  return out;
}

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
  Quote: { minCount: "min_qty", desc: "note", priceMinor: "unit_price_minor" },
  QuoteRevision: { priceMinor: "to_price_minor" },
};

/**
 * 关联字段：契约里的嵌套对象/数组，数据来自**另一张表**，不是本表的列。
 * 不登记的话它们会全部报成「缺列」，把真正的缺口淹掉。
 */
const RELATION = {
  Community: { pickups: "cmt_pickup_point" },
  Category: { children: "prd_category 自关联（parent_no）" },
  Goods: {
    merchant: "usr_merchant",
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
  GroupBuy: { merchant: "usr_merchant", members: "mkt_group_member", neighborPickup: "cmt_pickup_point" },
  GroupRequest: { quotes: "mkt_quote", neighbours: "mkt_request_interest" },
  Quote: { merchant: "usr_merchant", revisions: "mkt_quote_revision" },
  Review: { appeal: "rvw_appeal", scores: "本表的 score_* 三列" },
  Merchant: { serviceCommunityNos: "usr_merchant_community" },
};

/**
 * 派生字段：**故意不存**，查询时算出来。
 * 存了反而是 bug —— `distance` 依赖当前用户位置，`reached` 依赖 joined_count 与 min_count，
 * 落成列就必然有过期的那一刻。
 */
const DERIVED = {
  Community: { distance: "按用户当前位置实时算" },
  Pickup: {
    distance: "按用户当前位置实时算",
    hostMerchantNo: "由 owner_ref 解析（type=STORE 时指向 merchant_no）",
    hostName: "join usr_merchant",
    hostAvatar: "join usr_merchant",
  },
  Goods: { price: "SKU 最低价（表注释已写明价格不在本表）", originPrice: "同上" },
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
    initiatorNickname: "join usr_user（表存 owner_id）",
    initiatorAvatar: "join usr_user",
    confirmedCount: "按 mkt_request_interest 的确认态计数",
    confirmed: "按当前用户算",
  },
  Coupon: { received: "按当前用户查 mkt_user_coupon" },
  Quote: { locked: "由 chosen 推导 —— 选定即锁价（ADR-003），不需要独立列" },
  PickupPoint: {
    ownerType: "由 owner_ref 前缀解析（表把「谁承接」压成一列）",
    ownerNo: "由 owner_ref 解析",
  },
  Merchant: {
    distance: "按用户当前社区实时算",
    scores: "表已拆成 score_goods / score_service / score_speed 三列，契约收成一个对象",
  },
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
  User: { table: "usr_user" },
  Address: { table: "usr_address" },
  Merchant: { table: "usr_merchant" },
  MerchantBrief: { table: "usr_merchant", note: "同表的投影，商品卡上只带这几个字段" },
  MerchantApplyReq: { table: "usr_merchant_apply" },
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
  UserCard: { table: "mkt_user_coupon", note: "卡包与券共表：储值卡/次卡在 mkt_user_coupon 上用类型区分" },
  // ── 结算
  SettleBill: { table: "stl_bill" },
  // ── 消息
  Message: { table: "msg_message" },
};

/**
 * 聚合视图：**本来就不该有表**，由多张表拼出来。
 * 列在这里是为了把它们从「缺持久化」里摘出去 —— 否则报告会被一堆假问题淹没，
 * 而真正缺表的那几个就没人看见了。
 */
const VIEW_TYPES = {
  StoreHome: "usr_merchant + prd_goods + usr_store_favorite",
  MerchantTodo: "ord_sub_order + ord_after_sale + mkt_request 的计数",
  MerchantStats: "ord_sub_order 的聚合",
  FrequentItem: "ord_item 按 (user_no, sku_no) 的频次聚合",
  ReorderResult: "加购动作的返回值，非实体",
  PickingRow: "ord_item 按自提点 + SKU 的汇总",
  PickupOverview: "ord_sub_order + ful_verify_log 的计数",
  VerifyBatchResult: "批量核销的返回值，非实体",
  RateCard: "费率配置，当前在 stl_bill.commission_rate 落快照",
  PointAccount: "积分账户（一期未建表，见下方缺口）",
  StoreProfile: "usr_merchant 的店主可编辑子集",
  MerchantProfile:
    "B 端登录态，跨四张表：usr_merchant（主体）+ usr_user（手机号，经 owner_user_no）" +
    " + usr_merchant_apply（驳回原因）+ cmt_pickup_point（是否承接自提点）",
  StoreQrcode: "由 merchant_no 实时生成，不存",
  ShareKit: "由服务端按语言/市场实时生成，不存",
  MerchantCustomer: "ord_sub_order 按 (merchant_no, user_no) 的聚合",
  VisitedMerchant: "usr_merchant + ord_sub_order 的聚合",
  AppointmentSlot: "服务类商品的可约时段，由容量配置实时算",
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
      "库里 `usr_merchant` 没有任何范围字段 —— 可见性过滤没有依据。",
    action: "usr_merchant 补 service_scope / service_city_code，另建 usr_merchant_community 关联表",
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
const tables = readTables();
const cSchemas = { ...readSchemas("docs/api/openapi.yaml"), ...readSchemas("docs/api/openapi-b.yaml") };
const opsSchemas = readSchemas("docs/api/openapi-ops.yaml");

/** 请求/查询 DTO 不参与持久化比对：它们是入参形状，不是实体 */
const isDto = (n) => /(Req|ReqBody|Query|Draft|Config|Rule|Texts)$/.test(n);
const isMapped = (n) => /^(Partial[_<])?Record[_<]/.test(n);

const findings = { missingCol: [], noTable: [], aliasUsed: [], internalOnly: [] };

function compare(typeName, schema) {
  if (STRUCTURAL[typeName]) return null; // 单独成节，见「结构不匹配」
  const map = ENTITY_MAP[typeName];
  if (!map) return null;
  const t = tables.get(map.table);
  if (!t) {
    findings.noTable.push({ type: typeName, reason: `映射到 \`${map.table}\`，但库里没有这张表` });
    return null;
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

writeFileSync(OUT, md.join("\n"));
console.log(`✅ ${OUT}`);
console.log(
  `   库表 ${tables.size} · 已映射实体 ${details.length} · 缺列 ${findings.missingCol.length} · 命名不一致 ${findings.aliasUsed.length}`,
);
if (findings.missingCol.length) {
  console.log(`   ⚠️ 契约有而库里没有列的字段：`);
  for (const f of findings.missingCol) console.log(`      ${f.type}.${f.field} → ${f.table}`);
}
