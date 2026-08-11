// 商家域（矩阵 P-11.1 商家治理 / B-11.1 入驻）。
import type { Archivable } from "./common";

/** 商家分层（矩阵 P-11.1.6，为引入大商家预留）。 */
/**
 * 商家**分层**（规模）。对应 `mch_entity.tier`，与 shared 的 `MerchantTier` 同名同值。
 *
 * ⚠️ 这里此前是 `PERSONAL | INDIVIDUAL | COMPANY` —— 那是**主体类型**的旧取值
 * （权威码见 shared 的 `MerchantSubject`：MICRO / INDIVIDUAL / ENTERPRISE）。
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
  /** 已上传并通过的资质名。授权需要资质的类目码时要对照它 */
  qualifications: string[];
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

/** 处置动作。SUSPEND 会真的把商家状态推到 SUSPENDED。 */
export type ViolationAction = "WARN" | "LIMIT" | "SUSPEND";

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
  /** 法律形态 MICRO / INDIVIDUAL / ENTERPRISE */
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
  /** 已传的资质图。个体户/企业必传，小微免 —— 缺它正是驳回的主因 */
  licenses?: string[];
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
  storeNo: string;
  storeName: string;
  merchantNo: string;
  businessMode: BusinessMode | null;
  /** 该店实际可用的收款号（本店专属号优先，回落到主体默认号）。**空 = 不能切第三方** */
  payMerchantNo: string | null;
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
 */
export type LegalForm = "MICRO" | "INDIVIDUAL" | "ENTERPRISE";

export interface AdmissionPolicy {
  legalForm: LegalForm;
  /** 应缴保证金（分）；0 = 免缴 */
  requiredDepositMinor: number;
  /** 单笔限额（分）；0 = 不限 */
  singleOrderLimitMinor: number;
  /** 日累计限额（分）；0 = 不限 */
  dailyAmountLimitMinor: number;
  /** 1 = 禁止经营任何「需资质」品类 */
  banQualifiedCategory: number;
  bannedCategoryCodes?: string | null;
  enabled: number;
  remark?: string | null;
}

/** 商家保证金账户。**可用余额 = 实缴 − 冻结**，判「够不够」用可用而非实缴。 */
export interface MerchantDeposit {
  merchantNo: string;
  paidMinor: number;
  frozenMinor: number;
  availableMinor: number;
  requiredMinor: number;
  sufficient: boolean;
  singleOrderLimitMinor: number;
  dailyAmountLimitMinor: number;
}

export type DepositTxnType = "PAY" | "REFUND" | "FREEZE" | "UNFREEZE" | "DEDUCT";

/** 保证金流水。**只有余额字段的账户是不可审计的** —— 说不清这笔钱什么时候少的、谁扣的。 */
export interface DepositTxn {
  txnNo: string;
  txnType: DepositTxnType;
  /** 有符号：扣划为负 */
  amountMinor: number;
  balanceAfterMinor: number;
  reason?: string | null;
  operator?: string | null;
  createdAt?: string | null;
}
