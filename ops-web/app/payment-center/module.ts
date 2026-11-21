"use client";

import { useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { MenuFunction } from "@/lib/types";

// 支付中心模块（/payment-center）对外唯一的入口：页签登记与路由（TDD-支付中心运营页面 §模块边界）。
//
// 支付中心是独立模块：全部页面、接口客户端（_api/）、组件（_kit/）都在 app/payment-center/ 下；
// 模块外只许 import 这个文件，模块内不 import 其它业务页（payment-center-module.test.ts 守着）。
// 菜单点位全部在库里（V671 的 5 个既有入口由待执行的 ops_payment_center_own_module.sql 改到 /payment-center，其余 V684/V685），
// 权限码都是 pay:*，网关按前缀归到 pay 模块。PENDING_MENU 列「还没登记菜单点位」的页签，nav.test.ts 守着。

export type PayCenterTabKey =
  | "approvals" | "merchants" | "stores" | "onboardings"
  | "orders" | "exceptions"
  | "channels" | "products" | "access"
  | "data-scopes"
  // P2
  | "merchant-changes" | "refunds" | "terminals" | "apps"
  | "request-logs" | "channel-calls" | "channel-callbacks" | "alert-events" | "alert-rules"
  | "approval-flows" | "settings"
  // P3a
  | "attempts" | "ledger-entries" | "settlements" | "withdrawals" | "payouts" | "recon-diffs"
  | "sv-accounts" | "sv-transactions" | "channel-costs" | "commission-rules"
  // P8
  | "merchant-members" | "association-repairs";

export interface PayCenterTab {
  key: PayCenterTabKey;
  perm: string;
  group: "review" | "merchant" | "trade" | "channel" | "settings" | "ops" | "config" | "funds" | "recon" | "rates" | "assets";
  zh: string;
  en: string;
}

/** 顺序 = 菜单 TDD §2.2 的分组与组内顺序 */
export const PAY_CENTER_TABS: readonly PayCenterTab[] = [
  { key: "approvals", perm: "pay:approval:view", group: "review", zh: "审核待办", en: "Approvals" },
  { key: "merchants", perm: "pay:merchant:view", group: "merchant", zh: "商户档案", en: "Merchants" },
  { key: "stores", perm: "pay:store:view", group: "merchant", zh: "支付门店", en: "Stores" },
  { key: "merchant-changes", perm: "pay:merchant:view", group: "merchant", zh: "商户变更", en: "Merchant changes" },
  // P8（TDD-支付中心运营页面 §2.15）：商户成员（BM03）、来源关联修复
  { key: "merchant-members", perm: "pay:member:view", group: "merchant", zh: "商户成员", en: "Merchant members" },
  { key: "association-repairs", perm: "pay:merchant:view", group: "merchant", zh: "关联修复", en: "Association repairs" },
  { key: "onboardings", perm: "pay:onboarding:view", group: "merchant", zh: "渠道进件", en: "Onboarding" },
  { key: "orders", perm: "pay:order:view", group: "trade", zh: "支付订单", en: "Payments" },
  { key: "exceptions", perm: "pay:order:view", group: "trade", zh: "异常交易", en: "Exceptions" },
  { key: "refunds", perm: "pay:refund:view", group: "trade", zh: "退款订单", en: "Refunds" },
  { key: "attempts", perm: "pay:order:view", group: "trade", zh: "交易流水", en: "Transaction log" },
  { key: "channels", perm: "pay:channel:view", group: "channel", zh: "支付渠道", en: "Channels" },
  { key: "products", perm: "pay:product:view", group: "channel", zh: "支付产品", en: "Products" },
  // 收款接入的查看码：near-pay OpsCollectionEntryController 用 pay:collection-entry:view（2026-11-20 核对）
  { key: "access", perm: "pay:collection-entry:view", group: "channel", zh: "收款接入", en: "Collection entries" },
  { key: "terminals", perm: "pay:terminal:view", group: "channel", zh: "支付终端", en: "Terminals" },
  { key: "apps", perm: "pay:app:view", group: "channel", zh: "接入应用", en: "Open apps" },
  { key: "channel-costs", perm: "pay:channel-cost:view", group: "rates", zh: "渠道成本", en: "Channel costs" },
  { key: "commission-rules", perm: "pay:fee-plan:view", group: "rates", zh: "佣金规则", en: "Commission rules" },
  { key: "ledger-entries", perm: "pay:fund-account:view", group: "funds", zh: "资金流水", en: "Ledger" },
  { key: "settlements", perm: "pay:settlement:view", group: "funds", zh: "结算记录", en: "Settlements" },
  { key: "withdrawals", perm: "pay:withdrawal:view", group: "funds", zh: "提现申请", en: "Withdrawals" },
  { key: "payouts", perm: "pay:payout:view", group: "funds", zh: "出款记录", en: "Payouts" },
  { key: "recon-diffs", perm: "pay:recon-diff:view", group: "recon", zh: "对账差异", en: "Recon differences" },
  { key: "sv-accounts", perm: "pay:stored-value:view", group: "assets", zh: "储值账户", en: "Stored-value accounts" },
  { key: "sv-transactions", perm: "pay:stored-value:view", group: "assets", zh: "储值交易", en: "Stored-value transactions" },
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
  funds: { zh: "资金结算", en: "Funds" },
  recon: { zh: "对账", en: "Reconciliation" },
  rates: { zh: "费率", en: "Rates" },
  assets: { zh: "会员资产", en: "Member assets" },
};

/** 还等登记菜单点位的页签。2026-11-20 起菜单在本分支落地（V684 + platform-nav），已清空；以后新增页签先进这里。 */
export const PENDING_MENU: readonly PayCenterTabKey[] = [];

/**
 * 旧书签：商户、订单模块原来挂支付页签的地址（V671 的 href），打开时转到支付中心对应页签。
 * 菜单已不再指向它们（ops_payment_center_own_module.sql 执行后）。
 */
export const LEGACY_TAB_REDIRECT: Readonly<Record<"/merchants" | "/orders", Readonly<Record<string, PayCenterTabKey>>>> = {
  "/merchants": { payment: "merchants", "payment-onboarding": "onboardings", "payment-channels": "channels" },
  "/orders": { payments: "orders", "payment-exceptions": "exceptions" },
};

/**
 * 库里还没改址的旧菜单地址 → 支付中心页签。改 href 的脚本（docs/technical/pending-sql/ops_payment_center_own_module.sql，原定 V686）暂不在正式库执行：v1 运营端与 v2 共用菜单表，
 * v1 前端没有支付中心页，改了 v1 这 5 项会失效（2026-11-20 用户定：先兼容，v1 下线后再执行）。
 * 服务端菜单进前端时按这张表换成新地址；该脚本执行后库里就是新地址，这张表自然不再命中。
 */
export const LEGACY_MENU_HREFS: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(LEGACY_TAB_REDIRECT).flatMap(([path, tabs]) => Object.entries(tabs).map(([tab, key]) => [`${path}?tab=${tab}`, `/payment-center?tab=${key}`])),
);

/** 服务端菜单（/ops/menu）里的旧支付地址换成支付中心页签；不改入参。 */
export function normalizeLegacyPaymentMenu(functions: MenuFunction[]): MenuFunction[] {
  return functions.map((fn) => (fn.points?.some((p) => p.href && LEGACY_MENU_HREFS[p.href])
    ? { ...fn, points: fn.points.map((p) => (p.href && LEGACY_MENU_HREFS[p.href] ? { ...p, href: LEGACY_MENU_HREFS[p.href] } : p)) }
    : fn));
}

/** 商户、订单页调用：旧书签带着支付页签进来时转到支付中心。 */
export function useLegacyPayTabRedirect(path: keyof typeof LEGACY_TAB_REDIRECT): void {
  const router = useRouter();
  const search = useSearchParams();
  useEffect(() => {
    const moved = LEGACY_TAB_REDIRECT[path][search.get("tab") ?? ""];
    if (moved) router.replace(payCenterHref(moved));
  }, [path, router, search]);
}

export function payCenterHref(key: PayCenterTabKey, params?: Record<string, string>): string {
  const extra = params ? Object.entries(params).map(([k, v]) => `&${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join("") : "";
  return `/payment-center/?tab=${key}${extra}`;
}

/** 角色页里的「支付中心数据范围」只读栏（TDD-支付运营端经网关转发与权限下沉 §10 B2）。 */
export { RolePayScope } from "./role-pay-scope";
