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
  | "PLANNED" // 含后端未实现的取值，等实现
  /**
   * 种子脚本填的，**还没人看过**。
   *
   * <p>为什么要有这个值：本表建起来时 96 条标着 `OK`，其中只有 4 条带 note ——
   * 约 68 条是生成器默认填的。`OK` 因此同时表示「看过了没问题」和「没人看过」，
   * 两者在数据里分不开。**一份把「没人看过」显示成「没问题」的登记表，
   * 比没有登记表更危险** —— 它会让下一个人以为已经盘过了。
   * 这正是词典曾经犯的病：默认值伪装成结论。
   *
   * <p>证据不用推测：P1.2 那 24 处内联枚举同样从没被看过，
   * 里面藏着两对异名同义、一处重复声明、一个自我引用的 interface。
   */
  | "UNREVIEWED";

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
  { decl: "shared:Scene", dom: "payment", shape: "CLASS", verdict: "OK",
    note: "MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5 与库 ord_order.pay_scene 的注释逐字一致" },
  { decl: "shared:PayChannel", dom: "payment", shape: "CLASS", verdict: "OK",
    note: "WECHAT/ALIPAY 与后端 SysPayChannel 的两个常量一致" },
  { decl: "shared:PayMethod", dom: "payment", shape: "CLASS", verdict: "OK",
    note: "JSAPI/APP/H5/NATIVE 与库 pay_method / sys_pay_channel.pay_methods 一致" },
  { decl: "shared:Industry", dom: "payment", shape: "CLASS", verdict: "TO_DICT",
    note: "sys_industry 是字典表，配 4 个 ops 接口。待确认行业会不会新增" },
  { decl: "shared:PaymentApplyStatus", dom: "payment", shape: "STATUS", verdict: "OK",
    words: ["NONE", "APPLYING"] },
  { decl: "shared:SkinId", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "端上皮肤主题 id，纯展示层。后端零出现，也不该有 —— 换肤是端上的事" },
  { decl: "shared:ModeId", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "端上明暗模式，纯展示层。后端零出现" },
  { decl: "shared:SurfaceTone", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "端上表面色阶（卡片/背景层级），纯样式令牌。后端零出现" },
  { decl: "shared:SkinGroup", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "皮肤分组，纯展示层。后端零出现" },
  { decl: "shared:ImageSource", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "取图来源（相机/相册），端上能力选择。后端零出现" },
  { decl: "shared:PushPlatform", dom: "message", shape: "CLASS", verdict: "OK",
    note: "APP_ANDROID/APP_IOS，与后端 msg_push_token.platform 及 MsgPushToken 的常量逐字一致。**不含 WEB** —— Web Push 是另一条通道（触达能力矩阵 G6），混进来会让「有 token 就能推」不再成立" },
  { decl: "shared:PushProvider", dom: "message", shape: "CLASS", verdict: "OK",
    note: "GETUI/FCM/APNS，与后端 PushProvider 及 msg_push_token.provider 逐字一致（设计：多渠道推送与运营端触达配置 · 需求 2）。决定一台设备的推送交给哪家 gateway：uni-push 底座即个推(GETUI)，海外 Android 走 FCM、iOS 可直连 APNS。**GETUI 是默认** —— 存量与 uni-push 打包上报的都是个推 cid" },
  { decl: "ops-web:NotifyFailReason", dom: "message", shape: "CLASS", verdict: "OK",
    note: "CRED/QUOTA/TARGET/NETWORK。**不是 wire 契约**：后端只回自由文本 error，这四类是端上对它的归因分桶（lib/notify-reason.ts），用来把「下一步该做什么」显示给运营。归不出来时返回 null，不硬塞一个兜底类" },
  { decl: "ops-web:InboxMessageType", dom: "message", shape: "CLASS", verdict: "OK",
    note: "运营收件箱的消息类型，与后端 MsgMessage 的三个常量逐字一致。与 shared:MessageType 同值不同端 —— 端上各有一份是既有惯例" },
  { decl: "shared:MessageType", dom: "core", shape: "CLASS", verdict: "OK",
    note: "TRADE/MARKETING/SYSTEM 与后端 MsgMessage 的三个常量逐字一致" },
  { decl: "shared:AfterSaleReason", dom: "core", shape: "CLASS", verdict: "TO_DICT",
    note: "后端 /mp/after-sale/reasons 已下发，端上这份应只作兜底" },
  { decl: "shared:BizQualification", dom: "core", shape: "CLASS", verdict: "OK",
    note: "经营资格（轴①，法定）。与 mch_entity.biz_qualification 同值。**决定能不能交易** —— UNREGISTERED 是违法经营，平台不得供交易能力；EXEMPT 是电商法 §10 明文豁免的合法经营者，不是「无资质」" },
  { decl: "shared:ExemptType", dom: "core", shape: "CLASS", verdict: "OK",
    note: "电商法 §10 免登记四类情形。**只有 PETTY 受 10 万元/年 约束**，其余三类无金额上限 —— 混起来监控会误伤农户/手工业者" },
  { decl: "shared:MerchantSubject", dom: "core", shape: "CLASS", verdict: "OK",
    note: "权威码，V87 起三档为 NATURAL_PERSON/INDIVIDUAL/ENTERPRISE —— 正是税务系统对经营者的标准三分。**旧值 MICRO 已改名**：那是支付通道发明的收款档位，不是法律形态，且与法规「小微企业（有照）」重名含义相反；它保留在 mch_payment_merchant 上作为通道档。规则（needLicense/settleAccountType/行业白名单）由 /common/master-data 下发，端上不硬编码" },
  { decl: "shared:FundsMode", dom: "settle", shape: "CLASS", verdict: "OK",
    note: "资金路径（轴②：钱先进谁的账户）。与 ops-web:FundsMode、mch_entity.funds_mode 同值。**canPoints 判的就是它** —— 补差只在 DIRECT 下发生，用 BusinessMode 判会在两者不一致时判错" },
  { decl: "ops-web:FundsMode", dom: "settle", shape: "CLASS", verdict: "OK",
    note: "资金路径（轴②：钱先进谁的账户）。与 mch_entity.funds_mode 同值。**与 BusinessMode（轴③：谁是销售主体）正交，不要合并** —— 合成一个枚举后「直连+自营」这种非法组合在类型上就可表达（同 ADR-013 教训）。结算侧「要不要给积分补差」判的是这一个，不是 BusinessMode" },
  { decl: "ops-web:NotifyChannel", dom: "message", shape: "CLASS", verdict: "OK",
    note: "SMS/MAIL/WXSUB/PUSH 与后端 SysNotifyLog 的四个常量、sys_notify_log.channel 三处一致。**站内信 INAPP 刻意不在此枚举**：它不进 sys_notify_log，自己就是 msg_message 那张表（TDD-运营端触达中心 §1.1）" },
  { decl: "ops-web:NotifyStatus", dom: "message", shape: "STATUS", verdict: "OK",
    // SENT 不在 L1 词表里，申报为领域特有词：这条链路的终态就是「发出去了」，
    // 而 L1 的 COMPLETED/SUCCESS 指的是业务完成 —— 短信发出去不等于用户看到了，
    // 两者语义不同，套用会让人以为这张表能证明「用户收到了」。
    words: ["SENT"],
    note: "SENT/FAILED 与 sys_notify_log.status 一致。**只有两态是有意的** —— "
      + "没有 PENDING：发送是同步的，装饰器在通道返回之后才落库，不存在「发送中」" },
  { decl: "ops-web:QualificationType", dom: "core", shape: "CLASS", verdict: "OK",
    note: "与 shared:QualificationType 同值同义（运营端有自己的一套类型文件）。两端都列是现状，不是漂移 —— 取值由后端 MchQualification 的四个常量定" },
  { decl: "shared:QualificationType", dom: "core", shape: "CLASS", verdict: "OK",
    note: "资质证件类型，与后端 MchQualification 的四个常量逐字一致（V79 结构化资质）。**BUSINESS_LICENSE 是入驻校验的判据** —— 需要执照的档位必须含它，改名会让那条校验静默失效：找不到就当没传，然后放行" },
  { decl: "shared:ReviewAppealStatus", dom: "core", shape: "STATUS", verdict: "OK" },
  { decl: "shared:OrderStatus", dom: "core", shape: "STATUS", verdict: "OK",
    words: ["WAIT_PAY", "PAID", "ARRIVED", "SHIPPED", "COMPLETED", "REFUNDED"] },
  { decl: "shared:GrantType", dom: "core", shape: "CLASS", verdict: "PLANNED",
    note: "端上按微信三种登录场景拆成 WX_MINI/WX_PHONE/WX_OPEN，后端 AuthService 只有一个 GRANT_WECHAT_MP；PHONE_OTP 与 APPLE 两端一致。微信登录本身还没接（code2Session 是 TODO），接的时候两边一起定名" },
  { decl: "shared:MerchantStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "→ MerchantWorkability。它是 B 端「我现在能不能干活」的合并视图，不是主体状态。P4 待确认（影响 B 端首页判断）",
    words: ["NONE", "APPLYING", "REVIEWING"] },
  { decl: "shared:MerchantTier", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SubOrderStatus", dom: "trade", shape: "STATUS", verdict: "OK",
    note: "子单履约状态，与主单的 OrderStatus 分开：主单管钱、子单管货。"
      + "此前端上没有这个具名类型，履约台把子单当主单用，"
      + "按主单的 ARRIVED 过滤 —— 真实后端发的是 WAIT_FULFILL，列表因此恒空",
    words: ["WAIT_PAY", "WAIT_FULFILL", "FULFILLING", "COMPLETED", "REFUNDED"] },
  { decl: "shared:GoodsStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "与 ops-web SkuStatus 重叠，P4 待确认状态挂 SPU 还是 SKU。"
      + "AUDITING→PENDING **2026-08-12 真正归一**：此前只归在端上，"
      + "后端 /biz/goods 一直下发库列原值 AUDITING（联调实测），现已改发 PENDING；"
      + "列表筛选两个词都收，老客户端不至于筛出空列表",
    words: ["ON_SALE", "OFF_SALE"] },
  { decl: "shared:CampaignType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:CampaignStatus", dom: "core", shape: "STATUS", verdict: "RENAME",
    note: "RUNNING 与 ops-web CouponStatus/MemberCardStatus 的 ACTIVE 同为「生效中」，二选一" },
  { decl: "shared:PickupPointType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:AfterSaleType", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:AfterSaleStatus", dom: "core", shape: "STATUS", verdict: "OK",
    words: ["APPLIED", "REFUNDING", "REFUNDED", "ARBITRATING"] },
  { decl: "shared:InvoiceRequestStatus", dom: "core", shape: "STATUS", verdict: "OK",
    words: ["REQUESTED", "ISSUED"],
    note: "销项票（平台开给消费者）。与结算侧的 InvoiceStatus（进项，供应商开给平台）**同域不同向** —— 义务人、方向、法律后果都相反，不可合并。手工开票版；接票据系统时在 ISSUED 之后延长" },
  { decl: "shared:InvoiceTitleType", dom: "core", shape: "CLASS", verdict: "OK",
    words: ["PERSONAL", "COMPANY"] },
  { decl: "shared:CATEGORY_TYPE", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SERVICE_SCOPE", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:FULFILLMENT", dom: "core", shape: "CLASS", verdict: "OK" },
  { decl: "shared:FULFILLMENT_REACH", dom: "core", shape: "CLASS", verdict: "OK",
    note: "履约**能力**（这家店有什么送法，落在主体上），与 FULFILLMENT（某一单怎么送，落在订单上）是两个轴 —— 同名相近但不可合并。它与 SERVICE_SCOPE 的关系是取代：ADR-013 把三档拆成「能力 × 覆盖」，SERVICE_SCOPE 冻结为回滚锚点" },
  { decl: "shared:AREA_STATUS", dom: "core", shape: "STATUS", verdict: "OK",
    note: "覆盖项的生效状态。与 MchStoreAudit 的审核单状态是两回事：这个是「这一条覆盖算不算数」，那个是「这张审核单走到哪了」" },
  { decl: "ops-web:CommunityApplyStatus", dom: "core", shape: "STATUS", verdict: "OK",
    note: "与 shared:COMMUNITY_APPLY_STATUS 同一套取值（三端同一张提报单）。与 ops-web:StoreAuditStatus 的 PENDING/REJECTED 同名不同物：那个是门面内容审核单，通过叫 PASSED；这个通过叫 APPROVED，因为它的通过**建出了一个社区**" },
  { decl: "shared:COMMUNITY_APPLY_STATUS", dom: "core", shape: "STATUS", verdict: "OK",
    note: "商家提报新社区的单据状态。与 AREA_STATUS 的 PENDING 同名不同物：那个是「这条覆盖算不算数」，这个是「这张提报单走到哪了」——  APPROVED 意味着平台已经建出了社区" },
  { decl: "shared:AREA_LEVEL", dom: "core", shape: "CLASS", verdict: "OK",
    note: "覆盖项粒度。取值与 sys_region.level 同源（COMMUNITY 除外——那是社区不是区划），后端不写字面量，值从库里带出来" },
  { decl: "ops-web:Role", dom: "auth", shape: "CLASS", verdict: "OK",
    note: "**已对齐（2026-08-11）**。判权改读后端下发的 staff.perms，前端 UI 码经 lib/perm-map.ts 的 UI_PERM_MAP 翻译成后端码 —— 两套码的粒度不同不是错（前端 45 个要控按钮，后端 14 个只管端点），错的是此前根本没连接。三条守卫在 ops-web/lib/perm-map.test.ts：页面用的码必须登记、映射到的后端码必须真存在于 Perms.java、前端的角色镜像必须与 Java 源码一致。角色码异名同义仍在 http 层翻译（BD↔MERCHANT_BD 等）。**ops-web 保留 11 个角色**：它们与需求矩阵 §2.3 逐条对应，后端只配了 4 个 —— 那是后端的缺口，不是前端多造，砍前端等于砍需求" },
  { decl: "ops-web:FieldType", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "运营端表单控件类型，纯渲染层。后端零出现" },
  { decl: "ops-web:BusinessMode", dom: "settle", shape: "CLASS", verdict: "OK",
    note: "经营模式，与后端 MchStore.SELF_OPERATED / THIRD_PARTY 同一套取值。**它与「分账时机」是两个正交的轴，不要合并** —— 合并之后「自营 + 直连分账」这种非法组合在类型上就是可表达的" },
  { decl: "ops-web:FeeTrafficSource", dom: "settle", shape: "CLASS", verdict: "OK",
    note: "费率适用的流量来源，**比订单上的 TrafficSource 窄**（没有 INVITE / CHANNEL）—— 后端 stl_fee_rule 只认这两档。窄是事实不是遗漏：那两档后端从未实现过费率" },
  { decl: "ops-web:LegalForm", dom: "merchant", shape: "CLASS", verdict: "OK",
    note: "主体档位，与后端 mch_entity.legal_form 同一套取值。**三档锁定不再增删**：准入矩阵里的 S1/S2/S3 是对这三个值的读法，不是另一个枚举" },
  { decl: "ops-web:DepositTxnType", dom: "merchant", shape: "CLASS", verdict: "OK",
    note: "保证金流水类型，与后端 MchDepositTxn 常量一致。冻结/解冻动的是冻结额、其余动实缴 —— 两组语义不同但同属一个枚举，因为它们记在同一张流水表上" },
  { decl: "ops-web:ToastType", dom: "ui", shape: "CLASS", verdict: "OK",
    note: "运营端提示条样式，纯展示层。后端零出现" },
  { decl: "ops-web:Locale", dom: "ui", shape: "CLASS", verdict: "RENAME",
    note: "与 shared:Lang 异名同义，且取值不一致：Lang 是 zh-CN/en/ar，Locale 是 zh/en。后端 i18n 已补齐 ar（此前阿语用户收到中文报错）；ops-web 的 zh 与 zh-CN 靠 AcceptHeaderLocaleResolver 兜住没炸，但两套写法要归一" },
  { decl: "ops-web:AfterSaleType", dom: "aftersale", shape: "CLASS", verdict: "PLANNED",
    note: "多一个 EXCHANGE，一期不做换货" },
  { decl: "ops-web:AfterSaleStatus", dom: "aftersale", shape: "STATUS", verdict: "OK",
    words: ["APPLIED", "REFUNDING", "ARBITRATING", "REFUNDED"] },
  { decl: "ops-web:Liability", dom: "aftersale", shape: "CLASS", verdict: "PLANNED",
    note: "PLATFORM/MERCHANT/PICKUP 三方定责，后端 ord_after_sale 有 liability 列但没有取值常量 —— 后端补常量时按这三个定" },
  { decl: "ops-web:PickupPointType", dom: "community", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:PickupFeeMode", dom: "community", shape: "CLASS", verdict: "OK",
    note: "与后端 PickupQueryPortImpl.feeModeOf 的 NONE/PER_ITEM/RATE 一致" },
  { decl: "ops-web:PickupStatus", dom: "community", shape: "STATUS", verdict: "OK",
    note: "与后端 CommunityAdminServiceImpl 的 ACTIVE/SUSPENDED/MIGRATING 逐字一致，迁移规则两端也同构",
    words: ["MIGRATING"] },
  { decl: "ops-web:MaterialKind", dom: "content", shape: "CLASS", verdict: "PLANNED",
    note: "运营素材类型，后端无素材库" },
  { decl: "ops-web:MaterialScope", dom: "content", shape: "CLASS", verdict: "PLANNED",
    note: "素材可见范围，同上" },
  { decl: "ops-web:PostAuthorType", dom: "content", shape: "CLASS", verdict: "PLANNED",
    note: "社区帖子作者类型，后端无内容模块" },
  { decl: "ops-web:PostStatus", dom: "content", shape: "STATUS", verdict: "OK",
    words: ["OFFLINE"] },
  { decl: "ops-web:RankingKind", dom: "content", shape: "CLASS", verdict: "PLANNED",
    note: "榜单类型，后端无榜单" },
  { decl: "ops-web:QuestionStatus", dom: "content", shape: "STATUS", verdict: "OK",
    words: ["ANSWERED", "OFFLINE"] },
  { decl: "ops-web:SettleStatus", dom: "finance", shape: "STATUS", verdict: "OK",
    note: "**已收敛到后端口径（2026-08-11）**。此前 ops-web 这份是按「周期结算单」设计的，而后端从来是「订单成交即生成一张」的即时模型 —— 页面按周期汇总的样子做了很久，对不上任何真实数据。随 /ops/settlements 落地一并对齐：FAILED 拆成 RETRYING（自动重试中）与 MANUAL（重试用尽转人工），FROZEN_BACK 去掉（后端没有超时解冻这件事），补上自营轨道的 PENDING_RECON/CONFIRMED/PAID。**两条轨道互不相通**：第三方的单不会走到 PAID，自营的单不会走到 SPLIT",
    words: ["SPLITTING", "SPLIT", "RETRYING", "MANUAL", "REVERSED", "PENDING_RECON", "CONFIRMED", "PAID"] },
  { decl: "ops-web:WithdrawStatus", dom: "finance", shape: "STATUS", verdict: "PLANNED",
    note: "【只读对账】提现整块后端未实现（库里没有 stl_withdraw）。PAID 不在 L1 表内，实现时一并定名",
    words: ["PAID"] },
  { decl: "ops-web:InvoiceTitleType", dom: "finance", shape: "CLASS", verdict: "PLANNED",
    note: "【只读对账】开票整块后端未实现（库里没有 stl_invoice）" },
  { decl: "ops-web:InvoiceStatus", dom: "finance", shape: "STATUS", verdict: "PLANNED",
    note: "【只读对账】同上，开票未实现",
    words: ["ISSUED"] },
  { decl: "ops-web:BatchStatus", dom: "fulfillment", shape: "STATUS", verdict: "PLANNED",
    note: "配送批次状态，后端 ful_batch 表在但没有状态流转实现",
    words: ["PLANNED", "DISPATCHED", "ARRIVED", "SIGNED"] },
  { decl: "ops-web:OverdueAction", dom: "fulfillment", shape: "CLASS", verdict: "PLANNED",
    note: "超时未取的处置动作，后端无超时策略" },
  { decl: "ops-web:Carrier", dom: "fulfillment", shape: "CLASS", verdict: "PLANNED",
    note: "快递承运商，后端 ord_sub_order 只存 express_no 不存承运商" },
  { decl: "ops-web:ShipmentStatus", dom: "fulfillment", shape: "STATUS", verdict: "PLANNED",
    note: "运单状态，后端无物流轨迹对接",
    words: ["CREATED", "PICKED_UP", "IN_TRANSIT", "DELIVERED", "EXCEPTION"] },
  { decl: "ops-web:OutOfRangeAction", dom: "fulfillment", shape: "CLASS", verdict: "PLANNED",
    note: "超区处置动作，后端只判超区拒单（err.trade.out_of_delivery_range），没有可配策略" },
  { decl: "ops-web:GroupStatus", dom: "group", shape: "STATUS", verdict: "OK",
    words: ["SUCCESS"] },
  { decl: "ops-web:DemandStatus", dom: "group", shape: "STATUS", verdict: "MERGE",
    note: "见 shared:GroupRequestStatus —— 同一件事两套说法",
    words: ["OPEN", "QUOTING", "CHOSEN"] },
  { decl: "ops-web:AttrSource", dom: "growth", shape: "CLASS", verdict: "MERGE",
    note: "见 TrafficSource" },
  { decl: "ops-web:ConflictPolicy", dom: "growth", shape: "CLASS", verdict: "PLANNED",
    note: "增长活动冲突策略，后端无增长模块" },
  { decl: "ops-web:NewUserFactor", dom: "growth", shape: "CLASS", verdict: "PLANNED",
    note: "新客判定因子，同上" },
  { decl: "shared:CouponFunder", dom: "marketing", shape: "CLASS", verdict: "OK",
    note: "券的出资方，与后端 MktCoupon.funder 一致。平台券走平台预算，商家券从结算里扣 —— 这个字段决定钱从谁账上出，不是展示用" },
  { decl: "shared:CouponType", dom: "marketing", shape: "CLASS", verdict: "OK",
    note: "与后端 MktCoupon 的两个常量逐字一致。与 ops-web:CouponType 是同一件事的两套声明（那边多了两个未接的取值）" },
  { decl: "shared:CouponStatus", dom: "marketing", shape: "STATUS", verdict: "OK",
    note: "与 ops-web:CouponStatus 同值同义（ACTIVE/PAUSED/ENDED），两端各有一份声明是现状" },
  { decl: "ops-web:CouponBuildableType", dom: "marketing", shape: "CLASS", verdict: "OK",
    note: "建券表单能建的类型子集（POST /ops/coupons），是 CouponType 的两个真实取值" },
  { decl: "ops-web:CouponType", dom: "marketing", shape: "CLASS", verdict: "PLANNED",
    note: "FULL_CUT/DISCOUNT 已接 POST /ops/coupons（TDD-营销预算前置，2026-08-13）；"
      + "NEWCOMER/TARGETED 仍未接——discountFor 从未处理过这两种，没有算法撑着不给建" },
  { decl: "ops-web:CouponStatus", dom: "marketing", shape: "STATUS", verdict: "OK",
    note: "平台券（FULL_CUT/DISCOUNT）已实现，ACTIVE/PAUSED/ENDED 与后端 MktCoupon 状态一致。"
      + "ACTIVE 与 shared:CampaignStatus 的 RUNNING 同义，是两个不同领域对象各自的说法，不合并" },
  { decl: "ops-web:IssueTarget", dom: "marketing", shape: "CLASS", verdict: "PLANNED",
    note: "券的发放对象（全员/新客/指定人群），后端无人群圈选能力" },
  { decl: "ops-web:MerchantCampaignType", dom: "marketing", shape: "CLASS", verdict: "OK",
    note: "商家自建活动的四种，与后端 mkt_campaign.type 逐字一致（/ops/campaigns 真正返回的取值）。"
      + "与 PlatformSlotType 刻意分成两套：共用一套时 FLASH 恰好两边都有所以译得出来，"
      + "FULL_CUT/COUPON/BUY_GIFT 译不出来，原始枚举码直接打给用户" },
  { decl: "ops-web:PlatformSlotType", dom: "marketing", shape: "CLASS", verdict: "PLANNED",
    note: "平台投放场次，后端尚无对应表。与店铺级 mkt_campaign 是两个领域对象，已按规范加限定词改名（原名 CampaignType 与 shared 同名不同义）" },
  { decl: "ops-web:PlatformSlotStatus", dom: "marketing", shape: "STATUS", verdict: "PLANNED",
    note: "随 PlatformSlotType 一起改名；后端尚无对应表",
    words: ["SCHEDULED"] },
  { decl: "ops-web:SlotKind", dom: "marketing", shape: "CLASS", verdict: "PLANNED",
    note: "会员权益位类型，后端无会员体系" },
  { decl: "ops-web:BenefitKind", dom: "marketing", shape: "CLASS", verdict: "PLANNED",
    note: "会员权益类型，同上" },
  { decl: "ops-web:MemberCardStatus", dom: "marketing", shape: "STATUS", verdict: "PLANNED",
    note: "会员卡状态，后端无会员体系。ACTIVE 同上要与 RUNNING 二选一" },
  { decl: "ops-web:MerchantTier", dom: "merchant", shape: "CLASS", verdict: "OK",
    note: "取值已对齐分层（SMALL/MEDIUM/LARGE），与 shared 及 mch_entity.tier 一致。⚠️ 上一轮我把它判成「装的是主体类型，应改名 MerchantSubject」—— 那是错的：它绑定的后端字段 tier 确实是分层，错的是取值（写成了主体类型的旧取值 PERSONAL/INDIVIDUAL/COMPANY）。运营若要看主体类型，需后端在 ops 商家 VO 里补字段：mch_entity 上没有 subject 列" },
  { decl: "ops-web:MerchantStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    note: "库 mch_entity.status 口径" },
  { decl: "ops-web:ViolationAction", dom: "merchant", shape: "CLASS", verdict: "PLANNED",
    note: "商家违规处置动作，后端 mch_violation 表在但没有动作枚举" },
  { decl: "ops-web:ApplyStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    note: "入驻审核，与库 mch_entity_apply.status 逐字一致",
    words: ["REVIEWING"] },
  { decl: "ops-web:MsgChannel", dom: "message", shape: "CLASS", verdict: "PLANNED",
    note: "消息通道（站内/短信/推送），后端只有站内消息" },
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
  { decl: "ops-web:ReconDiffType", dom: "payment", shape: "CLASS", verdict: "PLANNED",
    note: "【只读对账】通道对账整块后端未实现（库里没有对账表），三种差异类型是运营端先画的" },
  { decl: "ops-web:ReconStatus", dom: "payment", shape: "STATUS", verdict: "OK",
    words: ["RESOLVED", "IGNORED"] },
  { decl: "ops-web:RecoverAction", dom: "payment", shape: "CLASS", verdict: "PLANNED",
    note: "【只读对账】对账差异的处置动作，同上未实现" },
  { decl: "ops-web:CategoryTemplate", dom: "product", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:Market", dom: "product", shape: "CLASS", verdict: "OK",
    note: "CN/SG 与后端 MARKET_CN/HOME_MARKET 的取值一致；SG 后端尚未启用，但它是市场码不是状态，留着不构成缺陷" },
  { decl: "ops-web:SkuStatus", dom: "product", shape: "STATUS", verdict: "OK",
    note: "P4 待确认与 GoodsStatus 的分层",
    words: ["ON_SALE", "OFF_SALE"] },
  { decl: "ops-web:ReviewStatus", dom: "review", shape: "STATUS", verdict: "OK",
    note: "PENDING/PASSED/REJECTED —— 三个词都在 L1 状态词表内，与评价审核的后端处理一致" },
  { decl: "ops-web:RiskFlag", dom: "review", shape: "CLASS", verdict: "PLANNED",
    note: "刷评信号，后端无风控信号计算（acceptCount30d 恒 0 是同一块缺口）" },
  { decl: "ops-web:AppealStatus", dom: "review", shape: "STATUS", verdict: "OK" },
  { decl: "ops-web:RiskType", dom: "risk", shape: "CLASS", verdict: "PLANNED",
    note: "风控事件类型，同上" },
  { decl: "ops-web:RiskStatus", dom: "risk", shape: "STATUS", verdict: "OK",
    words: ["CONFIRMED", "DISMISSED"] },
  { decl: "ops-web:SubjectType", dom: "risk", shape: "CLASS", verdict: "OK",
    note: "风控主体（USER/MERCHANT/DEVICE），与商家主体无关。同名冲突由 shared 侧删除解决" },
  { decl: "ops-web:BlacklistAppealStatus", dom: "risk", shape: "STATUS", verdict: "OK",
    words: ["NONE"] },
  { decl: "ops-web:StoreAuditKind", dom: "store", shape: "CLASS", verdict: "PLANNED",
    note: "门店装修审核项，后端 mch_store_audit 表在但只有整单状态没有分项" },
  { decl: "ops-web:StoreAuditStatus", dom: "store", shape: "STATUS", verdict: "PLANNED",
    note: "门店装修审核状态，后端有表待接" },
  { decl: "ops-web:StoreGovernStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    note: "门店经营状态，与库 mch_store.status 逐字一致（ACTIVE/READONLY/SUSPENDED）。⚠️ 与 ops-web:StoreAuditStatus 不同域：那个审的是门面内容，这个是「这家店还开不开门」。READONLY 与 SUSPENDED 必须分开——前者商家自己关的、自己开得回来，后者只有平台解得开（restoreStore）；压成一个 enabled 布尔的话运营看不出该找谁",
    // READONLY 不用 L1 的 DISABLED/CLOSED：那两个词说的是「关了」，
    // 而这家店**还在，只是不接单** —— 商品页照常可看、历史单照常售后，
    // 只有下单入口关着。用 CLOSED 的话运营会以为它已经不存在了
    words: ["READONLY"] },
  { decl: "ops-web:SectionKey", dom: "store", shape: "CLASS", verdict: "PLANNED",
    note: "门店装修版块键，后端 featured 只存商品列表，没有版块概念" },
  { decl: "c-app:HttpMethod", dom: "infra", shape: "CLASS", verdict: "OK",
    note: "HTTP 动词，端内契约描述用。后端零出现 —— 它是协议不是业务枚举" },
  { decl: "b-app:HttpMethod", dom: "infra", shape: "CLASS", verdict: "OK",
    note: "HTTP 动词，端内契约描述用。后端零出现 —— 它是协议不是业务枚举" },

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
  { decl: "shared:GroupBuyStatus", dom: "group", shape: "STATUS", verdict: "OK",
    note: "与库 mkt_group_buy.status 逐字一致。此前契约里没有这个字段，端上只能拿 reached 推 —— "
      + "而被平台中止的团人数可能已经够了，推出来是「已成团」，页面把作废的团当正常团显示",
    words: ["OPEN", "FORMED"] },   // FAILED 是 L1 状态词，不必申报
  { decl: "shared:GroupRequestStatus", dom: "group", shape: "STATUS", verdict: "MERGE",
    note: "取值已改为库里存的那套（mkt_request.status）—— 原先的 OPEN/QUOTING/MATCHED/EXPIRED 与后端一个都对不上，"
      + "页面上 status==='MATCHED' 恒 false。与 ops-web 的 DemandStatus 仍是同一件事的两套说法（CHOSEN↔LOCKED），待合并。"
      + "COLLECTING 是「开放接受报价」这个状态本身，不是「等某人处理」，与 L1 的 PENDING 语义不同，已申报",
    words: ["COLLECTING", "QUOTED", "LOCKED", "CONFIRMED"] },
  { decl: "ops-web:ViolationType", dom: "merchant", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:ExceptionKind", dom: "order", shape: "CLASS", verdict: "OK" },

  /*
   * 以下 15 条来自 P1.2「内联无主具名化」—— 它们此前内联在 interface 的字面量里，
   * **对所有工具不可见**：登记表登记不到、对账工具比对不到、改名时必漏一处。
   *
   * 具名化的过程本身就挖出四个问题（内联时一个都看不见）：
   *   · shared 的 feeMode 与 ops-web 的 PickupFeeMode 同值 —— 异名同义
   *   · 风控的 subjectType 与 ops-web 已有的 SubjectType 同值 —— 重复声明
   *   · c-app 的 type: "REFUND_ONLY" | "RETURN_REFUND" 就是已有的 AfterSaleType
   *   · ops-web 有一个 `interface FunnelStep { step: FunnelStep }` —— **自我引用**，
   *     字段类型指向了它自己所在的 interface。编译器不报错，因为它是合法的递归引用。
   */
  { decl: "shared:SettleAccountType", dom: "settle", shape: "CLASS", verdict: "OK" },
  { decl: "shared:PaymentApplyStatus", dom: "payment", shape: "STATUS", verdict: "OK",
    words: ["NONE", "APPLYING"] },
  { decl: "shared:StoreStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    words: ["READONLY"] },
  { decl: "shared:StaffStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    words: ["DISABLED"] },
  { decl: "shared:StaffRole", dom: "merchant", shape: "CLASS", verdict: "OK" },
  { decl: "shared:SettleBillStatus", dom: "settle", shape: "STATUS", verdict: "OK",
    note: "2026-08-11 已收敛为后端 StlBill 的取值。此前是 PENDING/PARTIAL/DONE/EXPIRED —— 一套后端从来没有过的词，配的是同样虚构的「周期账单」结构；b-app 结算页整片字段连真后端都是 undefined，靠 mock 才看起来是好的。做 P4 结算分店时撞出来",
    words: ["SPLITTING", "SPLIT", "RETRYING", "MANUAL", "REVERSED"] },
  { decl: "shared:MerchantApplyReviewStatus", dom: "merchant", shape: "STATUS", verdict: "OK",
    words: ["REVIEWING"] },
  { decl: "shared:PickupOwnerType", dom: "community", shape: "CLASS", verdict: "OK" },
  { decl: "shared:PickupScope", dom: "community", shape: "CLASS", verdict: "OK" },
  { decl: "shared:PickupFeeMode", dom: "community", shape: "CLASS", verdict: "MERGE",
    note: "与 ops-web 的 PickupFeeMode 同名同值 —— 两处声明，取值必须一起改。内联时看不见，具名化才暴露出来" },
  { decl: "shared:SpecTemplateScope", dom: "product", shape: "CLASS", verdict: "OK" },
  { decl: "shared:TrafficSource", dom: "growth", shape: "CLASS", verdict: "MERGE",
    note: "与 ops-web 的 TrafficSource 同名，那边多 INVITE/CHANNEL 两个值；且与 growth 的 AttrSource 也是同义" },
  { decl: "shared:ArrivalIssueKind", dom: "fulfillment", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:FunnelStep", dom: "dashboard", shape: "CLASS", verdict: "OK" },
  { decl: "ops-web:SectionLayout", dom: "store", shape: "CLASS", verdict: "OK" },
];
