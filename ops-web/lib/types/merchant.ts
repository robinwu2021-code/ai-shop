// 商家域（矩阵 P-11.1 商家治理 / B-11.1 入驻）。
import type { Archivable } from "./common";

/** 商家分层（矩阵 P-11.1.6，为引入大商家预留）。 */
export type MerchantTier = "PERSONAL" | "INDIVIDUAL" | "COMPANY";

/** 入驻审核状态机：提交 → 审核中 →（通过 / 驳回补交）；封禁独立于审核。 */
export type MerchantStatus =
  | "DRAFT"
  | "SUBMITTED"
  | "REVIEWING"
  | "APPROVED"
  | "REJECTED"
  | "SUSPENDED";

/** 合法迁移（mock 层强制，非法迁移抛错）。 */
export const MERCHANT_TRANSITIONS: Record<MerchantStatus, MerchantStatus[]> = {
  DRAFT: ["SUBMITTED"],
  SUBMITTED: ["REVIEWING"],
  REVIEWING: ["APPROVED", "REJECTED"],
  REJECTED: ["SUBMITTED"],
  APPROVED: ["SUSPENDED"],
  SUSPENDED: ["APPROVED"],
};

export interface Merchant extends Archivable {
  /** 商家单号 */
  merchantNo: string;
  /** 店铺名 */
  name: string;
  /** 商家分层，为引入大商家预留 */
  tier: MerchantTier;
  /** 入驻审核状态。合法迁移见 `MERCHANT_TRANSITIONS`，非法迁移抛错 */
  status: MerchantStatus;
  /** 归属社区（数据域裁剪键之一） */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
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
