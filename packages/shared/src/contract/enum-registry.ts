// 枚举登记表 —— **端上每一个枚举都必须在这里有一条**。
//
// ─────────────────────────────────────────────────────────────────────────────
// 它解决什么
// ─────────────────────────────────────────────────────────────────────────────
// 此前「新增一个枚举」的成本是零：在自己那一端写一个联合类型就行。
// 于是三端积累了 121 个，其中 8 组同名不同义、8 组异名同义、24 个连具名声明都没有。
//
// 而当时的守卫（glossary.test.ts 的 MUST_COVER）只覆盖 8 个 —— 6.6% ——
// 且那 8 个**全部是出过事之后补进来的**。守卫守住的永远是上一次的事故。
//
// 这张表把「新增枚举」从零成本变成**必须登记**。G1 守卫扫端上所有具名枚举，
// 少登一个就报错。这是唯一能阻止 121 变成 150 的机制。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么 ops-web 的条目也在 shared 包里
// ─────────────────────────────────────────────────────────────────────────────
// 因为「同名不同义」只有把三端放在一起看才能发现 —— ops-web 的 68 个枚举
// 此前从不被当成需要符合规范的对象，只在「值是不是编造的」那一检里当白名单用。
// 登记表是跨端契约，跟其它跨端契约一样放在 shared。运行期没有任何一端 import 它，
// 只有守卫读。
//
// ─────────────────────────────────────────────────────────────────────────────
// 三个字段怎么填
// ─────────────────────────────────────────────────────────────────────────────
// shape   STATUS（名字以 Status 结尾）受 L1 通用状态词表约束；CLASS 不受。
//         分类型枚举（Scene / Industry / Carrier）的取值本就是领域词汇，
//         拿状态词表去套它们只会产生噪音。
// words   STATUS 型里**不在 L1 表内**的取值，必须逐个列在这里 = 显式申报。
//         申报不是豁免：它逼着写的人回答「这个词为什么不能用 L1 里的」。
// verdict 这一条现在是什么状态。OK 之外的都是待办 —— 缺陷因此从散在文档里
//         变成代码里可统计、CI 盯着收敛的清单。
//
// L1 状态词表在 docs/requirements/项目词典.md，**那份 markdown 是权威**：
// 改它等同于改规范，需要评审；改代码去符合它不需要。这个不对称是规范能生效的前提。

/** 这一条现在是什么状态 */
export type Verdict =
  | "OK" // 合规
  | "RENAME" // 名字要改（同名不同义，或异名同义里被合并的一方）
  | "MERGE" // 与另一个枚举合并
  | "DELETE" // 重复或废弃，删掉
  | "TO_DICT" // 本该是运营字典，要改走接口下发
  | "PLANNED"; // 含后端未实现的取值，等实现

export interface EnumEntry {
  /** `端:名字`。端 = shared / ops-web / c-app / b-app */
  decl: string;
  /** 领域对象 */
  dom: string;
  /** STATUS 受 L1 状态词表约束，CLASS 不受 */
  shape: "STATUS" | "CLASS";
  /** 现状判定 */
  verdict: Verdict;
  /** STATUS 型里不在 L1 表内的取值 —— 显式申报 */
  words?: string[];
  /** verdict 不是 OK 时**必填**：要改成什么、并到哪、在等什么 */
  note?: string;
}

export const ENUM_REGISTRY: EnumEntry[] = [
  { decl: "shared:Scene", dom: "payment", shape: "CLASS", verdict: "OK" },
  { decl: "shared:PayChannel", dom: "payment", shape: "CLASS", verdict: "OK" },
  { decl: "shared:PayMethod", dom: "payment", shape: "CLASS", verdict: "OK" },
  { decl: "shared:Industry", dom: "payment", shape: "CLASS", verdict: "TO_DICT",
    note: "sys_industry 是字典表，配 4 个 ops 接口。待确认行业会不会新增" },
  { decl: "shared:ApplyStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "→ PaymentApplyStatus。它是**支付进件**状态（MerchantPayment.applyStatus），与入驻审核无关 —— ACTIVE/FROZEN 两个值就是证据，审核不会有这两个态",
    words: ["NONE", "APPLYING"] },
  { decl: "shared:SkinId", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "shared:ModeId", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SurfaceTone", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SkinGroup", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "shared:ImageSource", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SUBSCRIBE_TMPL", dom: "message", shape: "CLASS", verdict: "OK" },
  { decl: "shared:MessageType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:AfterSaleReason", dom: "core", shape: "CLASS", verdict: "TO_DICT",
    note: "后端 /mp/after-sale/reasons 已下发，端上这份应只作兜底" },
  { decl: "shared:MerchantSubject", dom: "core", shape: "CLASS", verdict: "TO_DICT",
    note: "权威名。但 sys_legal_form 是字典表（带 enabled/sort/wechat_code/need_license），取值域会随通道政策变 —— 待确认后改走下发" },
  { decl: "shared:ReviewAppealStatus", dom: "core", shape: "STATUS", verdict: "OK" },
  { decl: "shared:OrderStatus", dom: "core", shape: "STATUS", verdict: "OK",
    words: ["WAIT_PAY", "PAID", "ARRIVED", "SHIPPED", "COMPLETED", "REFUNDED"] },
  { decl: "shared:GrantType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:MerchantStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "→ MerchantWorkability。它是 B 端「我现在能不能干活」的合并视图，不是主体状态。P4 待确认（影响 B 端首页判断）",
    words: ["NONE", "APPLYING", "REVIEWING"] },
  { decl: "shared:MerchantTier", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:GoodsStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "与 ops-web SkuStatus 重叠，P4 待确认状态挂 SPU 还是 SKU（AUDITING→PENDING 已归一）",
    words: ["ON_SALE", "OFF_SALE"] },
  { decl: "shared:CampaignType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:CampaignStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "RUNNING 与 ops-web CouponStatus/MemberCardStatus 的 ACTIVE 同为「生效中」，二选一" },
  { decl: "shared:PickupPointType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:AfterSaleType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:AfterSaleStatus", dom: "core", shape: "STATUS", verdict: "OK",
    words: ["APPLIED", "REFUNDING", "REFUNDED", "ARBITRATING"] },
  { decl: "shared:CATEGORY_TYPE", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SERVICE_SCOPE", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:FULFILLMENT", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:Role", dom: "auth", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:FieldType", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:ToastType", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:Locale", dom: "ui", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:AfterSaleType", dom: "aftersale", shape: "CLASS", verdict: "PLANNED",
    note: "多一个 EXCHANGE，一期不做换货" },
  { decl: "ops-web:AfterSaleStatus", dom: "aftersale", shape: "STATUS", verdict: "OK",
    words: ["APPLIED", "REFUNDING", "ARBITRATING", "REFUNDED"] },
  { decl: "ops-web:Liability", dom: "aftersale", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PickupPointType", dom: "community", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PickupFeeMode", dom: "community", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PickupStatus", dom: "community", shape: "STATUS", verdict: "OK",
    words: ["MIGRATING"] },
  { decl: "ops-web:MaterialKind", dom: "content", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:MaterialScope", dom: "content", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PostAuthorType", dom: "content", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PostStatus", dom: "content", shape: "STATUS", verdict: "OK",
    words: ["OFFLINE"] },
  { decl: "ops-web:RankingKind", dom: "content", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:QuestionStatus", dom: "content", shape: "STATUS", verdict: "OK",
    words: ["ANSWERED", "OFFLINE"] },
  { decl: "ops-web:SettleStatus", dom: "finance", shape: "STATUS", verdict: "OK",
    words: ["SPLITTING", "SPLIT", "FROZEN_BACK"] },
  { decl: "ops-web:WithdrawStatus", dom: "finance", shape: "STATUS", verdict: "OK",
    words: ["PAID"] },
  { decl: "ops-web:InvoiceTitleType", dom: "finance", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:InvoiceStatus", dom: "finance", shape: "STATUS", verdict: "OK",
    words: ["ISSUED"] },
  { decl: "ops-web:BatchStatus", dom: "fulfillment", shape: "STATUS", verdict: "OK",
    words: ["PLANNED", "DISPATCHED", "ARRIVED", "SIGNED"] },
  { decl: "ops-web:OverdueAction", dom: "fulfillment", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:Carrier", dom: "fulfillment", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:ShipmentStatus", dom: "fulfillment", shape: "STATUS", verdict: "OK",
    words: ["CREATED", "PICKED_UP", "IN_TRANSIT", "DELIVERED", "EXCEPTION"] },
  { decl: "ops-web:OutOfRangeAction", dom: "fulfillment", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:GroupStatus", dom: "group", shape: "STATUS", verdict: "OK",
    words: ["SUCCESS"] },
  { decl: "ops-web:DemandStatus", dom: "group", shape: "STATUS", verdict: "MERGE",
    note: "见 shared:GroupRequestStatus —— 同一件事两套说法",
    words: ["OPEN", "QUOTING", "CHOSEN"] },
  { decl: "ops-web:AttrSource", dom: "growth", shape: "CLASS", verdict: "MERGE",
    note: "见 TrafficSource" },
  { decl: "ops-web:ConflictPolicy", dom: "growth", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:NewUserFactor", dom: "growth", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:CouponType", dom: "marketing", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:CouponStatus", dom: "marketing", shape: "STATUS", verdict: "OK" },
  { decl: "ops-web:IssueTarget", dom: "marketing", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:CampaignType", dom: "marketing", shape: "CLASS", verdict: "RENAME",
    note: "→ PlatformSlotType。平台投放场次，与店铺级 mkt_campaign 是两个领域对象。P4 待确认是否合并成一张表" },
  { decl: "ops-web:CampaignStatus", dom: "marketing", shape: "STATUS", verdict: "RENAME",
    note: "随 CampaignType 一起改名",
    words: ["SCHEDULED"] },
  { decl: "ops-web:SlotKind", dom: "marketing", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:BenefitKind", dom: "marketing", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:MemberCardStatus", dom: "marketing", shape: "STATUS", verdict: "OK" },
  { decl: "ops-web:MerchantTier", dom: "merchant", shape: "CLASS", verdict: "RENAME",
    note: "→ MerchantSubject。它装的是主体类型不是规模；且取值 PERSONAL/INDIVIDUAL/COMPANY 正是 sys_legal_form.legacy_subject 注明的**旧取值** —— 错误的名字装着过时的值" },
  { decl: "ops-web:MerchantStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    note: "库 mch_entity.status 口径" },
  { decl: "ops-web:ViolationAction", dom: "merchant", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:ApplyStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    note: "入驻审核，与库 mch_entity_apply.status 逐字一致",
    words: ["REVIEWING"] },
  { decl: "ops-web:MsgChannel", dom: "message", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PushStatus", dom: "message", shape: "STATUS", verdict: "OK",
    words: ["SCHEDULED", "SENT"] },
  { decl: "ops-web:TicketStatus", dom: "message", shape: "STATUS", verdict: "OK",
    words: ["ASSIGNED", "RESOLVED"] },
  { decl: "ops-web:FulfillmentType", dom: "order", shape: "CLASS", verdict: "PLANNED",
    note: "STORE_VERIFY 后端未实现（与 shared 的 FULFILLMENT.STORE_VERIFY 同值）。此前叫 FulfillType 且那个值叫 SERVICE —— 名字与取值都是同一概念的第二套说法" },
  { decl: "ops-web:TrafficSource", dom: "order", shape: "CLASS", verdict: "MERGE",
    note: "与 growth 的 AttrSource 同义：STORE_CODE↔MERCHANT_OWNED、INVITER↔INVITE" },
  { decl: "ops-web:OrderStatus", dom: "order", shape: "STATUS", verdict: "OK",
    words: ["WAIT_PAY", "PAID", "SHIPPED", "ARRIVED", "COMPLETED", "REFUNDED"] },
  { decl: "ops-web:PayChannel", dom: "payment", shape: "CLASS", verdict: "PLANNED",
    note: "多一个 BALANCE，后端未实现" },
  { decl: "ops-web:ReconDiffType", dom: "payment", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:ReconStatus", dom: "payment", shape: "STATUS", verdict: "OK",
    words: ["RESOLVED", "IGNORED"] },
  { decl: "ops-web:RecoverAction", dom: "payment", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:CategoryTemplate", dom: "product", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:Market", dom: "product", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:SkuStatus", dom: "product", shape: "STATUS", verdict: "OK",
    note: "P4 待确认与 GoodsStatus 的分层",
    words: ["ON_SALE", "OFF_SALE"] },
  { decl: "ops-web:ReviewStatus", dom: "review", shape: "STATUS", verdict: "OK" },
  { decl: "ops-web:RiskFlag", dom: "review", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:AppealStatus", dom: "review", shape: "STATUS", verdict: "OK" },
  { decl: "ops-web:RiskType", dom: "risk", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:RiskStatus", dom: "risk", shape: "STATUS", verdict: "OK",
    words: ["CONFIRMED", "DISMISSED"] },
  { decl: "ops-web:SubjectType", dom: "risk", shape: "CLASS", verdict: "OK",
    note: "风控主体（USER/MERCHANT/DEVICE），与商家主体无关。同名冲突由 shared 侧删除解决" },
  { decl: "ops-web:BlacklistAppealStatus", dom: "risk", shape: "STATUS", verdict: "OK",
    words: ["NONE"] },
  { decl: "ops-web:StoreAuditKind", dom: "store", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:StoreAuditStatus", dom: "store", shape: "STATUS", verdict: "OK" },
  { decl: "ops-web:SectionKey", dom: "store", shape: "CLASS", verdict: "OK" },
  { decl: "c-app:HttpMethod", dom: "infra", shape: "CLASS", verdict: "OK" },
  { decl: "c-app:MerchantApplyType", dom: "infra", shape: "CLASS", verdict: "OK" },
  { decl: "b-app:HttpMethod", dom: "infra", shape: "CLASS", verdict: "OK" },

  /*
   * 以下四条是**修好扫描盲区之后才浮现的**。
   *
   * 此前的扫描正则遇到「成员之间夹 JSDoc 的多行联合类型」会整条匹配不上 ——
   * 而这个仓库里给每个取值挂一段说明是常见写法。也就是说：注释写得越认真的枚举，
   * 越容易从雷达上消失。全量清点报的 97 个因此是**低估**，真实是 101 个。
   *
   * 这个盲区不是被 G1 抓到的（G1 也用那个正则），是被「登记表里不能有代码中
   * 不存在的条目」那一检抓到的 —— 改名后新名字扫不出来，被报成已删除。
   * **单向的守卫会漏，双向的不会。**
   */
  { decl: "shared:PointRecordType", dom: "growth", shape: "CLASS", verdict: "OK" },
  { decl: "shared:GroupRequestStatus", dom: "group", shape: "STATUS", verdict: "MERGE",
    note: "与 ops-web 的 DemandStatus 是同一件事（求团需求）的两套说法：类型名不同，且 MATCHED↔CHOSEN 同义异名。OPEN 在这里保留 —— 它是「开放接受报价」这个状态本身，不是「等某人处理」，与 L1 的 PENDING 语义不同，已申报",
    words: ["OPEN", "QUOTING", "MATCHED", "EXPIRED"] },
  { decl: "ops-web:ViolationType", dom: "merchant", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:ExceptionKind", dom: "order", shape: "CLASS", verdict: "OK" },
];
