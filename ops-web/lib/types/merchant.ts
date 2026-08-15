// 商家域（矩阵 P-11.1 商家治理 / B-11.1 入驻）。
import type { Archivable } from "./common";

/** 商家分层（矩阵 P-11.1.6，为引入大商家预留）。 */
/**
 * 商家**分层**（规模）。对应 `mch_entity.tier`，与 shared 的 `MerchantTier` 同名同值。
 *
 * ⚠️ 这里此前是 `PERSONAL | INDIVIDUAL | COMPANY` —— 那是**主体类型**的旧取值
 * （权威码见 shared 的 `MerchantSubject`：NATURAL_PERSON / INDIVIDUAL / ENTERPRISE）。
 * 名字对得上后端字段，取值却来自另一个概念，于是这一列**永远显示不出东西**：
 * 后端下发的 tier 要么是 null（一期没启用分层），要么是 SMALL，
 * 而 SMALL 不在这个联合类型里，i18n 也查不到词条。
 *
 * <p>如果运营界面真正想看的是**主体类型**（小微/个体户/企业），那要后端在
 * ops 商家 VO 里补一个字段 —— `mch_entity` 上没有 subject 列，
 * 它在申请单与进件表上。这是另一件事，不能靠改端上的类型变出来。
 */
export type MerchantTier = "SMALL" | "MEDIUM" | "LARGE";

/**
 * 商家的**经营状态**。取值与后端 `mch_entity.status` 一致。
 *
 * ⚠️ 它**不是入驻审核状态** —— 审核状态在申请单上（{@link ApplyStatus}，
 * PENDING/REVIEWING/APPROVED/REJECTED），后端刻意分成两张表：
 * `mch_entity_apply` 记「这次申请审到哪了」，`mch_entity` 记「这家店现在能不能做生意」。
 *
 * 这里曾经把两者揉成一个字段（DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED/SUSPENDED），
 * 于是一家已经在正常经营、又提交了第二张执照的商家，status 该填什么无解 ——
 * 而这正是「一人多主体」的常见情形。
 */
export type MerchantStatus = "ACTIVE" | "SUSPENDED" | "FROZEN";

/**
 * 经营状态的合法迁移。
 *
 * <p>封禁（SUSPENDED）与冻结（FROZEN）的区别：封禁是**处罚**，可以解除；
 * 冻结是**风控**，要等风险排除。两者都不可逆地回到 ACTIVE 之外的态。
 */
export const MERCHANT_TRANSITIONS: Record<MerchantStatus, MerchantStatus[]> = {
  ACTIVE: ["SUSPENDED", "FROZEN"],
  SUSPENDED: ["ACTIVE"],
  FROZEN: ["ACTIVE"],
};

export interface Merchant extends Archivable {
  /** 商家单号 */
  merchantNo: string;
  /** 店铺名 */
  name: string;
  /** 商家分层，为引入大商家预留 */
  tier: MerchantTier;
  /** **经营状态**（不是审核状态 —— 审核在申请单上）。合法迁移见 `MERCHANT_TRANSITIONS` */
  status: MerchantStatus;
  /**
   * 服务的社区。**是列表不是单个** —— 一家店可以服务多个社区
   * （后端 `mch_entity_community`，服务范围三档见 ADR-009）。
   * 此前这里是单个 `communityNo`，多社区商家只会显示其中一个。
   */
  communityNos: string[];
  /** 联系人姓名 */
  contactName: string;
  /** 展示一律脱敏（中间四位掩码），完整号码不下发前端 */
  contactPhone: string;
  /** 经营类目编码，审核通过后即类目授权范围（P-11.1.3） */
  categoryCodes: string[];
  /** 认证标（P-11.1.2） */
  verified: boolean;
  /**
   * 已登记的结构化资质名。授权需要资质的类目码时要对照它。
   *
   * **必须是可选的。** 后端 `MerchantProfileVO` 曾经完全没有这个字段，
   * 而这里声明成必填 `string[]` —— 类型检查过得去，真接口下 `m.qualifications.length`
   * 直接抛 TypeError。只有 mock 有这个字段，所以一直没暴露。
   * 「契约有、后端不发」是字段问题，不是类型问题：**别把 `?` 去掉**。
   */
  qualifications?: string[];
  /** 信用档案：毁约次数（P-11.1.5 / ADR-003） */
  breachCount: number;
  /** 分账接收方报备状态（P-12.1.1，ADR-002） */
  settleAccountReady: boolean;
  /** 入驻申请提交时间 */
  createdAt: string;
  /** 最近一次审核意见（驳回原因/补交项） */
  auditRemark?: string;
  /**
   * 申请人是否愿意承接自提点（ADR-005）。
   *
   * **只是意愿，通过审核不会自动建点** —— 自提点的服务费口径是逐点线下谈的，
   * 没有一个默认值能覆盖。放在审核页上是为了让运营**看见有人在等**：
   * 不显示的话，申请人勾了这一项、通过后什么也没发生，而中间没有任何一处会报错。
   */
  asPickupPoint?: boolean;

  /**
   * 主体档位。**准入档位完全由它决定** —— 保证金、限额、禁售品类都按它取策略。
   *
   * 此前档案里没有它：运营看得到「这家被限额 500」，看不到「因为它是无照自然人」，
   * 于是只会来问为什么。
   */
  legalForm?: LegalForm | null;

  /**
   * 资金路径（轴②）：钱先进谁的账户。
   *
   * **与经营模式（`StoreMode.businessMode`，轴③）是两件事** ——
   * 这个说钱先进谁的账户，那个说谁是销售主体。两者正交：
   * 「直连 + 自营」（钱进商家户却说平台是卖方）是非法组合，要拦。
   *
   * 而「要不要给积分补差」判的是**这一列** —— 钱在商家账户才需要补进去。
   */
  fundsMode?: FundsMode;
  /**
   * 农业生产者。**无照主体走归集的唯一例外** ——
   * 平台可自开农产品收购发票，成本有合法凭证。
   */
  agriProducer?: boolean;
}

/**
 * 资金路径。**能走哪条由商户类型决定，不是平台自选**（ADR-017 §3.2）：
 * 自然人开不出票 → 平台按全额确认收入而成本不可税前扣除 → 禁归集；
 * 自产农产品例外。
 */
export type FundsMode = "AGGREGATED" | "DIRECT";

/** 资质证件类型。与后端 MchQualification 的四个常量逐字一致 */
export type QualificationType = "BUSINESS_LICENSE" | "FOOD_PERMIT" | "FOOD_WORKSHOP" | "OTHER";

/** 申请单上提交的一条结构化资质。`expireAt` 为 null = 长期有效 */
export interface QualificationItem {
  type: QualificationType;
  code: string;
  imageUrl: string;
  expireAt: number | null;
  issuer?: string;
}

/** 主体档案上**已登记**的一条资质（mch_qualification）。上架闸门读的就是它 */
export interface Qualification {
  qualNo: string;
  entityNo: string;
  qualType: string;
  /** 证件名。**要与 sys_auth_code.required_qualification 同一套字面量** —— 类目授权按名字比对 */
  qualName: string;
  qualNumber?: string;
  imageUrl?: string;
  /** null = 长期有效。与「已过期」是两回事，扫描任务不碰它 */
  expireAt?: number | null;
  /** VALID / EXPIRED / REVOKED */
  status: string;
}

// ── 类目授权 / 信用与处置（P-11.1.3 / 11.1.4 / 11.1.5）────────────────

/**
 * 类目授权码。
 *
 * 它与类目树是**多对一**：`CAT111 叶菜`、`CAT112 根茎菜` 都要 `FRESH_VEG`。
 * 按码授权而不是按类目节点授权，是因为类目树会重构，而"能不能卖菜"这件事不会。
 */
export interface AuthCode {
  /** 授权码，如 `FRESH_VEG`。**按码授权而不是按类目节点** —— 类目树会重构，能不能卖菜不会 */
  code: string;
  /** 授权码展示名 */
  name: string;
  /** 需要的资质名。为空表示无门槛类目 */
  requiredQualification?: string;
}

/** 违规类型。分开是因为**处置尺度不同**，笼统记成"违规"之后没法按类型统计。 */
export type ViolationType =
  /** 售假 */
  | "FAKE_GOODS"
  /** 毁约（不发货、成团后跑单）—— 只有这一类计入 breachCount */
  | "BREACH"
  /** 价格欺诈 */
  | "PRICE_FRAUD"
  /** 服务问题 */
  | "SERVICE";

/**
 * 处置动作。**两个动作有真副作用**，不是记一笔就完：
 * `SUSPEND` 把整个商家推到 SUSPENDED，`STORE_OFFLINE` 把**指定的那一家门店**
 * 压到 SUSPENDED 并撤下它的货架行。
 *
 * 门店级独立成一个 action 而不是「SUSPEND + 可选 storeNo」：后者的语义
 * 由一个可空字段决定，读处置记录的人分不出「封了整个商家」和「只压了一家店」。
 */
export type ViolationAction = "WARN" | "LIMIT" | "SUSPEND" | "STORE_OFFLINE";

export interface Violation {
  /** 违规记录单号 */
  violationNo: string;
  /** 涉事商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 违规类型。**只有 `BREACH` 计入 breachCount** */
  type: ViolationType;
  /** 处置动作。`SUSPEND` 会真的把商家状态推到 SUSPENDED */
  action: ViolationAction;
  /**
   * 门店级处置的对象门店。**`STORE_OFFLINE` 必有、其余动作必空** ——
   * 主体级处置带上门店号会让人以为只压了那一家。
   */
  storeNo?: string | null;
  /** 事实描述与证据出处。必填 —— 没有事实的处置在申诉时站不住 */
  detail: string;
  /** 处置人（STAFF 账号） */
  operator: string;
  /** 处置时间 */
  at: string;
}

// ================================================================ 入驻申请

/**
 * 入驻申请状态。
 *
 * **它不是商家的状态** —— 申请与主体是两个东西：通过之前商家<b>还不存在</b>。
 * 曾经把审核建模成「商家的一个状态」，于是驳回的申请会在商家表里留下一行
 * 从没开过张的「僵尸商家」，出现在每一处按主体聚合的地方（结算、积分、报表）。
 */
export type ApplyStatus = "PENDING" | "REVIEWING" | "APPROVED" | "REJECTED";

/** 入驻申请单（后端 `mch_entity_apply`）。 */
export interface MerchantApply {
  /** 申请单号。审核动作都打在它上面，不是商家号 */
  applyNo: string;
  /** 通过后生成的主体号。**未通过时为空** —— 商家在通过之前根本不存在 */
  merchantNo?: string;
  /** 拟用店铺名 */
  /** 拟用店铺名。**存快照** —— 后来改名不该让历史申请跟着变 */
  name: string;
  /** 法律形态 NATURAL_PERSON / INDIVIDUAL / ENTERPRISE */
  subject: string;
  /** 联系人姓名。审核要打电话找人 */
  contactName: string;
  /** 联系手机号（申请人自己填的，不一定是登录号）。**通过后它就是商家账号的登录号** */
  contactPhone: string;
  /** 主营类目 */
  category: string;
  /** 店铺简介。通过后会写进主体档案，C 端门店页读的就是它 */
  desc: string;
  /**
   * 行业。**决定这家店能不能以小微进件** —— 审核页要看得到它，
   * 否则运营批了一个行业不允许小微的小微商家，通道那边才会拒。
   */
  industry?: string;
  /**
   * 期望服务范围。**商家可以留空，但通过时必须确定** ——
   * 空的后果是商家上着架却对谁都不可见，且没有任何报错。
   */
  serviceScope?: string;
  /** 覆盖的小区。scope=COMMUNITY 时**空 = 通过之后对谁都不可见** */
  communityNos?: string[];
  /** 已传的资质图。个体户/企业必传，自然人免 —— 缺它正是驳回的主因 */
  licenses?: string[];
  /**
   * 结构化资质（V79）。**审核台看的是这一份** ——
   * 上面的 licenses 只有图片 URL，审核员看不出「这是执照还是食品证」「什么时候过期」。
   * 而通过之后转存进 mch_qualification 的正是它。
   */
  qualificationItems?: QualificationItem[];
  /** 是否愿意承接自提点（ADR-005）。**只是意愿，不代表点已建立** */
  asPickupPoint: boolean;
  /** 审核状态 */
  status: ApplyStatus;
  /** 驳回原因。**驳回必写** —— 不写对方只能猜着改 */
  rejectReason?: string;
  /** 提交时间 */
  createdAt: number;
  /** 审核完成时间。待审期间为空 */
  auditedAt?: number;
}


// ── 弱主体准入与门店经营模式（后端 mch_admission_policy / mch_deposit / mch_store）──

import type { BusinessMode } from "./finance";

/**
 * 门店经营模式。
 *
 * 自营 = 平台是法律上的销售主体，承担全部产品责任。
 * **这个身份不能由商家自己勾选**，所以只有运营端能改。
 */
export interface StoreMode {
  /** 门店号 */
  storeNo: string;
  /** 门店名，展示用 */
  storeName: string;
  /** 所属商家主体 */
  merchantNo: string;
  /** 自营 / 第三方；空 = 尚未设置 */
  businessMode: BusinessMode | null;
  /** 该店实际可用的收款号（本店专属号优先，回落到主体默认号）。**空 = 不能切第三方** */
  payMerchantNo: string | null;
}

/**
 * 无营业执照的主体 × 自营门店 —— **税务敞口清单**。
 *
 * 自营下平台是销售主体，列支成本要进项发票，而无照主体开不出票 ——
 * 这笔支出**不得在企业所得税前扣除**，不是「多交一点税」，
 * 是账面上凭空多出等额利润。
 *
 * 而这个组合**是默认会发生的**：`mch_store.business_mode` 默认就是自营，
 * 且后端没有任何一处校验「无照不得自营」。所以这份清单不是异常报表，
 * 是**现状盘点**。
 */
export interface ModeRisk {
  merchantNo: string;
  merchantName: string;
  /** 主体档位（免执照的那一档） */
  legalForm: string;
  storeNo: string;
  storeName: string;
  businessMode: string;
  /** 已产生的自营结算单数。**0 表示「查过了，没有」** —— 与「还没查」在界面上要分开 */
  settledBills: number;
  /** 累计商家实得（分）。**这就是不可税前扣除的成本规模** */
  settledMinor: number;
}

/**
 * 准入策略：按 `legalForm` 档位配置，**不按商户配置**。
 *
 * 挂档位是三档三行，改规则改一行；挂商户要逐个配、改一次规则要批量刷数据。
 * 三档就是 MICRO / INDIVIDUAL / ENTERPRISE，**不再增删**。
 */
/**
 * 主体档位 —— 与后端 `mch_entity.legal_form` 同一套取值。
 *
 * **三档锁定，不再增删**：S1/S2/S3 是对这三个值的读法，不是新枚举。
 *
 * V87 起 `MICRO` 改名 `NATURAL_PERSON` —— 那是通道发明的收款档位，不是法律形态，
 * 且与法规「小微企业（有照）」重名含义相反。通道档留在 `mch_payment_merchant` 上。
 */
export type LegalForm = "NATURAL_PERSON" | "INDIVIDUAL" | "ENTERPRISE";

export interface AdmissionPolicy {
  /** 主体档位，三档锁定 */
  legalForm: LegalForm;
  /** 应缴保证金（分）；0 = 免缴 */
  requiredDepositMinor: number;
  /** 单笔限额（分）；0 = 不限 */
  singleOrderLimitMinor: number;
  /** 日累计限额（分）；0 = 不限 */
  dailyAmountLimitMinor: number;
  /** 1 = 禁止经营任何「需资质」品类 */
  banQualifiedCategory: number;
  /** 额外禁售类目编码，JSON 数组字符串；空 = 无额外禁售 */
  bannedCategoryCodes?: string | null;
  /** 1 = 该档位的限制生效；0 = 该档位不做任何限制 */
  enabled: number;
  /** 为什么这么定 —— 回查时这句话比数字更有用 */
  remark?: string | null;
}

/** 商家保证金账户。**可用余额 = 实缴 − 冻结**，判「够不够」用可用而非实缴。 */
export interface MerchantDeposit {
  /** 商家主体 */
  merchantNo: string;
  /** 实缴（分） */
  paidMinor: number;
  /** 理赔冻结中（分） */
  frozenMinor: number;
  /** 可用（分）= 实缴 − 冻结。**判够不够用它，不用实缴** */
  availableMinor: number;
  /** 本档位应缴（分）；0 = 免缴 */
  requiredMinor: number;
  /** 可用是否已达应缴。不足则该商家不能上架 */
  sufficient: boolean;
  /** 单笔限额（分）；0 = 不限 */
  singleOrderLimitMinor: number;
  /** 日累计限额（分）；0 = 不限 */
  dailyAmountLimitMinor: number;
}

export type DepositTxnType = "PAY" | "REFUND" | "FREEZE" | "UNFREEZE" | "DEDUCT";

/** 保证金流水。**只有余额字段的账户是不可审计的** —— 说不清这笔钱什么时候少的、谁扣的。 */
export interface DepositTxn {
  /** 流水号 */
  txnNo: string;
  /** 变动类型 */
  txnType: DepositTxnType;
  /** 有符号：扣划为负 */
  amountMinor: number;
  /** 变动后实缴余额（分），对账用 */
  balanceAfterMinor: number;
  /** 变动原因 */
  reason?: string | null;
  /** 操作人 */
  operator?: string | null;
  /** 发生时间 */
  createdAt?: string | null;
}

/**
 * 商家的一个员工，以及他在各门店的角色（**运营端只读**）。
 *
 * 为什么运营要看得到：客服接到「我们店的配送员看不到订单」时，
 * 在此之前只能让老板自己截图 —— 而问题往往正是「他以为授了、其实没授」，
 * 截图里看不出这一点。
 *
 * 平台**不能改**这些授权：谁能进这家店是商家的雇佣关系。
 */
export interface MerchantStaffRow {
  /** 商家账号号 */
  mchAccountNo: string;
  /** 姓名（老板自己写的）。认人靠它；可能为空 */
  displayName?: string | null;
  /** 登录手机号。**它就是这个员工的登录用户名**（手机号 + 验证码，没有密码） */
  loginPhone: string;
  /** 老板。**不受门店授权限制**，所以 roles 为空不代表他没权限 */
  isOwner: boolean;
  /** ACTIVE / DISABLED */
  status: string;
  /** 他在各门店的角色。一人一店可多角色，权限取并集 */
  roles: { storeNo: string; storeName: string; role: string }[];
}

// ── 增值包与门店额度（P-11.2.2~11.2.6，V150）──────────────────────────────

/**
 * 订阅状态。
 *
 * `GRACE`（宽限期，7 天）**能力全保留** —— 到期当天就压店的话，
 * 一次忘记续费等于让他的店在客户面前消失，而他往往正在门店里忙。
 * 宽限期是给「人」的缓冲，不是给系统的。
 */
export type PlanStatus = "ACTIVE" | "GRACE" | "EXPIRED";

/**
 * 生效额度的来源。运营必须看得出这个数是哪来的 ——
 * 否则「这家怎么是 5 家？」只能靠翻审计日志回答。
 */
export type PlanQuotaSource =
  /** 档位快照：订阅那一刻从档位定义抄下来的 */
  | "PLAN"
  /** 单独谈的覆盖值，优先于快照 */
  | "OVERRIDE"
  /** 还没有订阅行，走配置兜底 */
  | "CONFIG";

/** 到期看板的一行（`GET /ops/merchant-plans`）。 */
export interface MerchantPlanRow {
  merchantNo: string;
  merchantName: string;
  planCode: string;
  /** 生效额度（覆盖值优先于快照）。与 storeUsed 一起显示成 2/3 */
  storeQuota: number;
  staffQuota: number;
  /** 已用门店数。**只数 ACTIVE**，与建店时那道额度闸同一口径 */
  storeUsed: number;
  staffUsed: number;
  crossStoreStats: boolean;
  status: PlanStatus;
  startAt?: number | null;
  expireAt?: number | null;
  /** PLATFORM（运营授予）/ SELF（一期没有这条路） */
  grantedBy?: string | null;
  trialUsed: boolean;
  /** 降级发生的时间。非空 = 已经压过店了（扫描靠它保证幂等） */
  downgradedAt?: number | null;
  quotaSource: PlanQuotaSource;
}

/** 档位定义（`GET /ops/plan-defs`）。 */
export interface PlanDef {
  planCode: string;
  name: string;
  storeQuota: number;
  staffQuota: number;
  crossStoreStats: boolean;
  trialDays: number;
  enabled: boolean;
  /**
   * 当前有几家在用这一档。
   *
   * **改定义的人必须看得到这个数** —— 它是「只影响之后新订阅的人」那句话的具体量。
   * 不给这个数，改档位的人只能凭感觉判断影响面。
   */
  subscriberCount: number;
}

/**
 * 升档信号的一行（`GET /ops/merchant-plans/upgrade-signals`）。
 *
 * **按 owner 分组而不是按主体**：「同一个人开了两个主体」正是要找的人 ——
 * 他已经在多店经营，只是绕过了额度。主体表上没有联系电话（那在申请单上），
 * 所以这里只给 owner 号，销售拿它去后台查人。
 */
export interface PlanUpgradeSignal {
  ownerUserNo: string;
  entityNos: string[];
  entityNames: string[];
  entityCount: number;
}
