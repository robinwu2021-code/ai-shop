"use client";

// 支付中心（TDD-支付中心运营页面 P1）：near-pay 运营页面的原生化。
// 一个路由、按 ?tab= 切页签；页签名字与权限码登记在 lib/pay-ops/center-tabs.ts（菜单由统一网关落库前的过渡）。
// 每个页签是一张「表格 + 行操作」，操作只走抽屉（单层）与弹框，编辑存本地草稿 —— 骨架见 components/ui/list-page.tsx。
import * as React from "react";
import { Suspense } from "react";
import { TabHeader } from "@/components/ui/tab-header";
import { EmptyState } from "@/components/ui/misc";
import { useCan } from "@/lib/use-can";
import { useI18n } from "@/lib/i18n";
import { toTraditional } from "@/lib/i18n/zh-tw";
import { useCopy } from "@/lib/use-copy";
import { usePageTab } from "@/lib/use-page-tab";
import { useRouter } from "next/navigation";
import { PAY_CENTER_GROUP_LABEL, PAY_CENTER_TABS, payCenterHref, type PayCenterTabKey } from "@/app/payment-center/module";
import { PAY_CENTER_COPY } from "./copy";
import { OrdersTab } from "./orders-tab";
import { ExceptionsTab } from "./exceptions-tab";
import { MerchantsTab } from "./merchants-tab";
import { StoresTab } from "./stores-tab";
import { OnboardingsTab } from "./onboardings-tab";
import { ApprovalsTab } from "./approvals-tab";
import { ChannelsTab } from "./channels-tab";
import { ProductsTab } from "./products-tab";
import { AccessTab } from "./access-tab";
import { DataScopesTab } from "./data-scopes-tab";
import { MerchantChangesTab } from "./merchant-changes-tab";
import { RefundsTab } from "./refunds-tab";
import { TerminalsTab } from "./terminals-tab";
import { AppsTab } from "./apps-tab";
import { MembersTab } from "./members-tab";
import { RepairsTab } from "./repairs-tab";
import { ChannelCallbacksTab, ChannelCallsTab, RequestLogsTab } from "./traces-tab";
import { AlertEventsTab, AlertRulesTab } from "./alerts-tab";
import { ApprovalFlowsTab, SettingsTab } from "./config-tabs";
import { AttemptsTab, ReconDiffsTab } from "./attempts-recon-tabs";
import { LedgerTab, PayoutsTab, SettlementsTab, WithdrawalsTab } from "./funds-tabs";
import { ChannelCostsTab, CommissionRulesTab, SvAccountsTab, SvTransactionsTab } from "./assets-rates-tabs";

export default function PaymentCenterPage() {
  return <Suspense fallback={null}><PaymentCenterInner /></Suspense>;
}

const TAB_COMPONENT: Record<PayCenterTabKey, React.ComponentType<{ header: React.ReactNode }>> = {
  approvals: ApprovalsTab,
  merchants: MerchantsTab,
  stores: StoresTab,
  onboardings: OnboardingsTab,
  orders: OrdersTab,
  exceptions: ExceptionsTab,
  channels: ChannelsTab,
  products: ProductsTab,
  access: AccessTab,
  "data-scopes": DataScopesTab,
  "merchant-changes": MerchantChangesTab,
  refunds: RefundsTab,
  terminals: TerminalsTab,
  apps: AppsTab,
  "merchant-members": MembersTab,
  "association-repairs": RepairsTab,
  "request-logs": RequestLogsTab,
  "channel-calls": ChannelCallsTab,
  "channel-callbacks": ChannelCallbacksTab,
  "alert-events": AlertEventsTab,
  "alert-rules": AlertRulesTab,
  "approval-flows": ApprovalFlowsTab,
  settings: SettingsTab,
  attempts: AttemptsTab,
  "ledger-entries": LedgerTab,
  settlements: SettlementsTab,
  withdrawals: WithdrawalsTab,
  payouts: PayoutsTab,
  "recon-diffs": ReconDiffsTab,
  "sv-accounts": SvAccountsTab,
  "sv-transactions": SvTransactionsTab,
  "channel-costs": ChannelCostsTab,
  "commission-rules": CommissionRulesTab,
};

function PaymentCenterInner() {
  const allow = useCan();
  const { locale } = useI18n();
  const c = useCopy(PAY_CENTER_COPY);
  const label = React.useCallback((v: { zh: string; en: string }) => (locale === "en" ? v.en : locale === "zh-TW" ? toTraditional(v.zh) : v.zh), [locale]);
  const router = useRouter();
  const permitted = PAY_CENTER_TABS.filter((t) => allow(t.perm)).map((t) => t.key).join(",");
  // 引用要稳定：usePageTab 以 tabs 为依赖做回落校正，每次渲染一个新数组会反复触发
  const tabs = React.useMemo(() => PAY_CENTER_TABS.filter((t) => permitted.split(",").includes(t.key))
    .map((t) => ({ key: t.key, label: label(t), group: label(PAY_CENTER_GROUP_LABEL[t.group]) })), [permitted, label]);
  // 读页签走 usePageTab（无权或缺省时回落到第一个有权的）；名字不回 nav.ts 取 —— 见 center-tabs.ts 的说明。
  // 切页签不用它的 setter：那会保留 ?action=&id=，把上一个页签的抽屉带进新页签。改为换到干净的地址。
  const [tab] = usePageTab(tabs);
  const active = tabs.find((t) => t.key === tab)?.key as PayCenterTabKey | undefined;

  if (!active) {
    return (
      <div>
        <TabHeader tabs={[{ key: "none", label: c.pageTitle }]} value="none" onChange={() => {}} />
        <EmptyState title={c.noTabAccess} />
      </div>
    );
  }
  const header = <TabHeader tabs={tabs} value={active} onChange={(k) => router.replace(payCenterHref(k as PayCenterTabKey))} />;
  const Tab = TAB_COMPONENT[active];
  return <Tab key={active} header={header} />;
}
