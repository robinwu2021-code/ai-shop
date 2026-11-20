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
  | "data-scopes";

export interface PayCenterTab {
  key: PayCenterTabKey;
  perm: string;
  group: "review" | "merchant" | "trade" | "channel" | "settings";
  zh: string;
  en: string;
  /** 首期仍经 /oms 过渡入口的既有菜单项（PaymentOpsEntry 的 workspace 名），开关打开后改跳这里 */
  workspace?: "channels" | "merchants" | "onboarding" | "payments" | "payment-exceptions";
}

/** 顺序 = 菜单 TDD §2.2 的分组与组内顺序 */
export const PAY_CENTER_TABS: readonly PayCenterTab[] = [
  { key: "approvals", perm: "pay:approval:view", group: "review", zh: "审核待办", en: "Approvals" },
  { key: "merchants", perm: "pay:merchant:view", group: "merchant", zh: "商户档案", en: "Merchants", workspace: "merchants" },
  { key: "stores", perm: "pay:store:view", group: "merchant", zh: "支付门店", en: "Stores" },
  { key: "onboardings", perm: "pay:onboarding:view", group: "merchant", zh: "渠道进件", en: "Onboarding", workspace: "onboarding" },
  { key: "orders", perm: "pay:order:view", group: "trade", zh: "支付订单", en: "Payments", workspace: "payments" },
  { key: "exceptions", perm: "pay:order:view", group: "trade", zh: "异常交易", en: "Exceptions", workspace: "payment-exceptions" },
  { key: "channels", perm: "pay:channel:view", group: "channel", zh: "支付渠道", en: "Channels", workspace: "channels" },
  { key: "products", perm: "pay:product:view", group: "channel", zh: "支付产品", en: "Products" },
  // 收款接入的查看码：near-pay OpsCollectionEntryController 用 pay:collection-entry:view（2026-11-20 核对）
  { key: "access", perm: "pay:collection-entry:view", group: "channel", zh: "收款接入", en: "Collection entries" },
  { key: "data-scopes", perm: "pay:data-scope:view", group: "settings", zh: "数据范围", en: "Data scope" },
];

export const PAY_CENTER_GROUP_LABEL: Record<PayCenterTab["group"], { zh: string; en: string }> = {
  review: { zh: "审核", en: "Review" },
  merchant: { zh: "支付商户", en: "Merchants" },
  trade: { zh: "交易", en: "Transactions" },
  channel: { zh: "渠道接入", en: "Channels" },
  settings: { zh: "平台设置", en: "Settings" },
};

/** 还等统一网关登记菜单点位（菜单 TDD G1/G4）的页签。登记一条删一条。 */
export const PENDING_MENU: readonly PayCenterTabKey[] = [
  "approvals", "merchants", "stores", "onboardings", "orders", "exceptions", "channels", "products", "access", "data-scopes",
];

/**
 * 原生页开关：打开后，既有菜单项（/merchants?tab=payment 等）由跳 /oms 改为进 /payment-center。
 * 默认关 —— 原生页连真接口的鉴权（near-pay 是否认 shop 令牌）验证之前，不改变线上行为。
 */
export function payNativePagesEnabled(value = process.env.NEXT_PUBLIC_PAY_NATIVE_PAGES): boolean {
  return value === "1" || value === "true";
}

export function payCenterHref(key: PayCenterTabKey): string {
  return `/payment-center/?tab=${key}`;
}
