// 支付中心（/payment-center）的页签登记（TDD-支付中心运营页面 P1；菜单口径见 TDD-支付中心菜单整合 §2.2）。
//
// 页签名字本该回 nav.ts 取（useNavTabs）。但支付中心的菜单点位由统一网关落库（菜单 TDD G1/G4），
// 在那之前 nav.ts 里还没有这些叶子 —— 所以这里先自带三语名字，并在 PENDING_MENU 里**显式列出**
// 「还等网关登记」的页签。nav.test.ts 守着：每个页签要么已有菜单叶子，要么在这张清单上；
// 网关登记完一条，就从清单删一条，清空为止。

export type PayCenterTabKey =
  | "approvals" | "merchants" | "stores" | "onboardings"
  | "orders" | "exceptions"
  | "channels" | "products" | "access"
  | "data-scopes"
  // P2
  | "merchant-changes" | "refunds" | "terminals" | "apps"
  | "request-logs" | "channel-calls" | "channel-callbacks" | "alert-events" | "alert-rules"
  | "approval-flows" | "settings";

export interface PayCenterTab {
  key: PayCenterTabKey;
  perm: string;
  group: "review" | "merchant" | "trade" | "channel" | "settings" | "ops" | "config";
  zh: string;
  en: string;
  /**
   * 首期仍经 /oms 过渡入口的既有菜单项（PaymentOpsEntry 的 workspace 名），开关打开后改跳这里。
   * 这 5 个页签在菜单上用的是 V671 的原 href（point_code 与授权不变），见 legacyMenuHref。
   */
  workspace?: "channels" | "merchants" | "onboarding" | "payments" | "payment-exceptions";
}

/** 顺序 = 菜单 TDD §2.2 的分组与组内顺序 */
export const PAY_CENTER_TABS: readonly PayCenterTab[] = [
  { key: "approvals", perm: "pay:approval:view", group: "review", zh: "审核待办", en: "Approvals" },
  { key: "merchants", perm: "pay:merchant:view", group: "merchant", zh: "商户档案", en: "Merchants", workspace: "merchants" },
  { key: "stores", perm: "pay:store:view", group: "merchant", zh: "支付门店", en: "Stores" },
  { key: "merchant-changes", perm: "pay:merchant:view", group: "merchant", zh: "商户变更", en: "Merchant changes" },
  { key: "onboardings", perm: "pay:onboarding:view", group: "merchant", zh: "渠道进件", en: "Onboarding", workspace: "onboarding" },
  { key: "orders", perm: "pay:order:view", group: "trade", zh: "支付订单", en: "Payments", workspace: "payments" },
  { key: "exceptions", perm: "pay:order:view", group: "trade", zh: "异常交易", en: "Exceptions", workspace: "payment-exceptions" },
  { key: "refunds", perm: "pay:refund:view", group: "trade", zh: "退款订单", en: "Refunds" },
  { key: "channels", perm: "pay:channel:view", group: "channel", zh: "支付渠道", en: "Channels", workspace: "channels" },
  { key: "products", perm: "pay:product:view", group: "channel", zh: "支付产品", en: "Products" },
  // 收款接入的查看码：near-pay OpsCollectionEntryController 用 pay:collection-entry:view（2026-11-20 核对）
  { key: "access", perm: "pay:collection-entry:view", group: "channel", zh: "收款接入", en: "Collection entries" },
  { key: "terminals", perm: "pay:terminal:view", group: "channel", zh: "支付终端", en: "Terminals" },
  { key: "apps", perm: "pay:app:view", group: "channel", zh: "接入应用", en: "Open apps" },
  { key: "data-scopes", perm: "pay:data-scope:view", group: "settings", zh: "数据范围", en: "Data scope" },
  { key: "approval-flows", perm: "pay:approval-flow:view", group: "config", zh: "审批流程", en: "Approval flows" },
  { key: "settings", perm: "pay:channel:view", group: "config", zh: "经营设置", en: "Operating settings" },
  // 支付运维：渠道调用与监控告警各两个页签（菜单 TDD §2.2：一个菜单项最多 2 个页签）
  { key: "alert-events", perm: "pay:alert-event:view", group: "ops", zh: "告警事件", en: "Alert events" },
  { key: "alert-rules", perm: "pay:alert-rule:view", group: "ops", zh: "告警规则", en: "Alert rules" },
  { key: "channel-calls", perm: "pay:trace:view", group: "ops", zh: "渠道调用", en: "Channel calls" },
  { key: "channel-callbacks", perm: "pay:trace:view", group: "ops", zh: "渠道回调", en: "Channel callbacks" },
  { key: "request-logs", perm: "pay:trace:view", group: "ops", zh: "请求日志", en: "Request logs" },
];

export const PAY_CENTER_GROUP_LABEL: Record<PayCenterTab["group"], { zh: string; en: string }> = {
  review: { zh: "审核", en: "Review" },
  merchant: { zh: "支付商户", en: "Merchants" },
  trade: { zh: "交易", en: "Transactions" },
  channel: { zh: "渠道接入", en: "Channels" },
  settings: { zh: "平台设置 · 权限管理", en: "Settings · Access" },
  config: { zh: "平台设置 · 支付配置", en: "Settings · Payment config" },
  ops: { zh: "平台设置 · 支付运维", en: "Settings · Payment ops" },
};

/** 还等登记菜单点位的页签。2026-11-20 起菜单在本分支落地（V684 + platform-nav），已清空；以后新增页签先进这里。 */
export const PENDING_MENU: readonly PayCenterTabKey[] = [];

/**
 * 原生页开关：打开后，既有菜单项（/merchants?tab=payment 等）由跳 /oms 改为进 /payment-center。
 * 默认关 —— 原生页连真接口的鉴权（near-pay 是否认 shop 令牌）验证之前，不改变线上行为。
 */
/** 既有 5 个入口在菜单上的原 href（V671），其余页签的菜单 href 就是 /payment-center?tab= */
export const LEGACY_MENU_HREF: Partial<Record<PayCenterTabKey, string>> = {
  merchants: "/merchants?tab=payment",
  onboardings: "/merchants?tab=payment-onboarding",
  orders: "/orders?tab=payments",
  exceptions: "/orders?tab=payment-exceptions",
  channels: "/merchants?tab=payment-channels",
};

export function payNativePagesEnabled(value = process.env.NEXT_PUBLIC_PAY_NATIVE_PAGES): boolean {
  return value === "1" || value === "true";
}

export function payCenterHref(key: PayCenterTabKey): string {
  return `/payment-center/?tab=${key}`;
}
