"use client";

import { Suspense, useEffect } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { usePageTab } from "@/lib/use-page-tab";
import { usePointsText } from "@/lib/points-i18n";
import { useCopy } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { pointsLegacyRedirect } from "@/lib/points-ops-routes";
import { FINANCE_COPY } from "../finance/copy";
import { TabHeader } from "@/components/ui/tab-header";
import { PointsTab } from "./pool-tab";
import { PointsPolicyTab } from "./switches-tab";
import { PointsConfigurationTab } from "./legacy-configuration-tab";
import { PointsQueriesTab } from "./queries-tab";
import { PointsSettlementsTab } from "./settlements-tab";
import { PointsAdjustments } from "./adjustments";
import { PointsTreasuryTab } from "./requests-tab";

const TAB_KEYS = ["overview", "switches", "treasury", "settlements", "queries"] as const;

export default function PointsPage() { return <Suspense fallback={null}><PointsInner /></Suspense>; }
function PointsInner() {
  const search = useSearchParams();
  const router = useRouter();
  const { locale, t } = usePointsText();
  const pageTabs = [
    { key: "overview", label: t("积分总览", "Points overview") },
    { key: "switches", label: t("积分规则", "Points rules") },
    { key: "treasury", label: t("积分资金", "Points funds") },
    { key: "settlements", label: t("积分结算", "Points settlement") },
    { key: "queries", label: t("积分查询", "Points queries") },
  ].filter((item) => TAB_KEYS.includes(item.key as typeof TAB_KEYS[number]));
  const [tab, setTab] = usePageTab(pageTabs);
  const legacyConfiguration = search.get("tab") === "configuration-legacy";
  const redirect = pointsLegacyRedirect(search.get("tab"));
  const inheritedCopy = useCopy(FINANCE_COPY);
  const c = inheritedCopy;
  const can = useCan();
  useEffect(() => { if (redirect) router.replace(redirect); }, [redirect, router]);
  if (redirect) return null;
  if (legacyConfiguration) return <div className="space-y-4" dir="ltr" lang={locale}>
    <TabHeader tabs={[{ key: "legacy", label: t("积分资金与日常（旧版）", "Points funds & daily (legacy)") }]} value="legacy" onChange={() => {}} desc={t("临时兼容页面", "Temporary compatibility page")} />
    <div className="flex flex-wrap gap-3 txt-caption">
      <Link href="/points/types" className="text-primary-ink underline">{t("积分类型与规则", "Points types & rules")}</Link>
      <Link href="/points/store-access" className="text-primary-ink underline">{t("门店协议与接入", "Store agreements & access")}</Link>
    </div>
    <PointsConfigurationTab canEdit={can("finance:settle:execute")} />
  </div>;
  return <div className="space-y-4" dir="ltr" lang={locale}>
    <TabHeader tabs={pageTabs} value={tab} onChange={setTab} />
    {(!tab || tab === "overview") && <PointsTab c={c} />}
    {tab === "switches" && <PointsPolicyTab c={c} canEdit={can("finance:settle:execute")} />}
    {tab === "queries" && <PointsQueriesTab canEdit={can("finance:settle:execute")} canReconRead={can("finance:recon:read")} canReconRun={can("finance:recon:resolve")} />}
    {tab === "settlements" && <PointsSettlementsTab canMake={can("finance:settle:execute")} canExecute={can("finance:payout:execute")} />}
    {tab === "treasury" && <><PointsTreasuryTab canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /><PointsAdjustments canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /></>}
  </div>;
}
