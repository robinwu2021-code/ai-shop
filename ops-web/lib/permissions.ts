import type { Role } from "./auth";

// 权限码 `<模块>:<资源>:<动作>`。模块前缀与 lib/nav.ts 的 NavSection.module 一一对应
// （nav.test.ts 断言这条对应关系，改一处必须改两处）。
// 通配：'*' 全部；'merchant:*' 该模块全部。
//
// ⚠️ 这里只是**前端展示裁剪**。后端是权威（矩阵 §2.3：数据可见性由归属键裁剪，不靠前端隐藏）。
// ⚠️ 高危动作（授权/封禁/打款/分账/环境切换）单独发码，不要并进模块通配 —— 矩阵 §2.3 的
//    「高危权限」列就是这批；混进通配后「谁能动钱」在代码里就看不出来了。

const ROLE_PERMS: Record<Role, string[]> = {
  // 超管：账号、角色、权限、系统配置。全量。
  SUPER_ADMIN: ["*"],

  // 商品/类目运营：类目树、商品池、上下架、多语言文案
  PRODUCT_OPS: [
    "dashboard:overview:read",
    "product:*",
    "order:order:read",
    "content:material:read",
    "system:param:read",
  ],

  // 活动运营：券、满减、限时、拼团、新人礼、皮肤下发
  CAMPAIGN_OPS: [
    "dashboard:overview:read",
    "marketing:*",
    "growth:fission:read", "growth:fission:update",
    "group:campaign:read", "group:campaign:audit",
    "product:sku:read",
    "content:*",
    "system:theme:update",
  ],

  // 社区运营：社区网格、自提点建档与启停、履约调度
  COMMUNITY_OPS: [
    "dashboard:overview:read",
    "community:*",
    "fulfillment:*",
    "order:order:read",
    "merchant:merchant:read",
  ],

  // 商家运营（BD）：商家拉新与入驻审核、资质、类目授权、求团人肉撮合
  MERCHANT_BD: [
    "dashboard:overview:read",
    "merchant:merchant:read", "merchant:apply:audit", "merchant:category:grant",
    "merchant:verify:grant", "merchant:merchant:ban",
    "store:page:read", "store:qrcode:export",
    "group:demand:read", "group:demand:assign",
    "community:community:read",
    "product:sku:read",
  ],

  // 审核员：商品、评价、内容、凭证、图片审核（只看待审队列，无封禁权）
  AUDITOR: [
    "dashboard:overview:read",
    "product:sku:read", "product:sku:audit",
    "review:review:read", "review:review:audit", "review:score:update",
    "content:material:read", "content:material:audit",
    "store:page:read", "store:page:audit",
    "merchant:merchant:read",
  ],

  // 客服：工单、争议介入、代客操作、退款审批（阈值内）
  CS: [
    "dashboard:overview:read",
    "order:order:read", "order:order:export", "order:order:modify", "order:order:proxy",
    "aftersale:ticket:read", "aftersale:ticket:handle", "aftersale:refund:approve",
    "message:ticket:read", "message:ticket:handle", "message:faq:update",
    "review:review:read",
    "merchant:merchant:read",
    "fulfillment:redeem:read",
  ],

  // 财务/结算：分账、结算单、提现、发票、个税代扣（唯一持有打款/分账码的角色）
  FINANCE: [
    "dashboard:overview:read",
    "finance:settle:read", "finance:settle:execute", "finance:withdraw:approve",
    "finance:invoice:read", "finance:rate:update",
    "order:order:read", "order:order:export",
    "aftersale:ticket:read",
    "merchant:merchant:read",
  ],

  // 风控：刷单、异常裂变、恶意退款、黑名单（可拦截、封禁）
  RISK: [
    "dashboard:overview:read",
    "risk:*",
    "growth:attribution:read",
    "order:order:read",
    "merchant:merchant:read", "merchant:merchant:ban",
    "community:pickup:read",
  ],

  // 数据分析：只读脱敏，无任何写权
  ANALYST: [
    "dashboard:*",
    "order:order:read",
    "merchant:merchant:read",
    "finance:settle:read",
    "growth:attribution:read",
    "product:sku:read",
    "community:community:read",
  ],

  // 技术运维：配置、灰度、日志、Mock 开关、发版
  TECH_OPS: [
    "dashboard:overview:read",
    "system:*",
    "message:template:read", "message:template:update",
    "order:pay:read", "order:pay:repair",
    "growth:attribution:read",
  ],
};

function match(pattern: string, code: string): boolean {
  if (pattern === code) return true;
  if (pattern.endsWith("*")) return code.startsWith(pattern.slice(0, -1));
  return false;
}

/** 按钮/操作级鉴权：角色是否拥有该权限码。 */
export function can(role: Role | undefined, code: string): boolean {
  if (!role) return false;
  return ROLE_PERMS[role]?.some((p) => match(p, code)) ?? false;
}

/** 模块级（导航/页面）：角色对某模块前缀是否有任一权限。 */
export function canModule(role: Role | undefined, module: string): boolean {
  if (!role) return false;
  return ROLE_PERMS[role]?.some((p) => p === "*" || p === module || p.startsWith(module + ":")) ?? false;
}

/** 测试与「角色-权限对照」页用；页面不要直接读它做鉴权，走 can()。 */
export function permsOf(role: Role): string[] {
  return ROLE_PERMS[role] ?? [];
}

export const ROLE_LABEL: Record<Role, string> = {
  SUPER_ADMIN: "超级管理员",
  PRODUCT_OPS: "商品运营",
  CAMPAIGN_OPS: "活动运营",
  COMMUNITY_OPS: "社区运营",
  MERCHANT_BD: "商家运营",
  AUDITOR: "审核员",
  CS: "客服",
  FINANCE: "财务",
  RISK: "风控",
  ANALYST: "数据分析",
  TECH_OPS: "技术运维",
};

/** 高危权限码：矩阵 §2.3「高危权限」列。发布前要能一眼数清谁持有它们。 */
export const CRITICAL_PERMS = [
  "iam:role:grant",
  "merchant:merchant:ban",
  "finance:settle:execute",
  "finance:withdraw:approve",
  "risk:blacklist:update",
  "system:env:switch",
] as const;
