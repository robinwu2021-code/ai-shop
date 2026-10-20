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
  const pageTabs = TAB_KEYS.map((key) => ({ key, label: key }));
  const [tab] = usePageTab(pageTabs);
  const legacyConfiguration = search.get("tab") === "configuration-legacy";
  const redirect = pointsLegacyRedirect(search.get("tab"));
  const inheritedCopy = useCopy(FINANCE_COPY);
  const { locale, t } = usePointsText();
  const c = inheritedCopy;
  const can = useCan();
  useEffect(() => { if (redirect) router.replace(redirect); }, [redirect, router]);
  if (redirect) return null;
  if (legacyConfiguration) return <div className="space-y-4" dir="ltr" lang={locale}>
    <div className="flex flex-wrap gap-3 txt-caption">
      <Link href="/points/types" className="text-primary underline">{t("积分类型与规则", "Points types & rules")}</Link>
      <Link href="/points/store-access" className="text-primary underline">{t("门店协议与接入", "Store agreements & access")}</Link>
    </div>
    <PointsConfigurationTab canEdit={can("finance:settle:execute")} />
  </div>;
  return <div className="space-y-4" dir="ltr" lang={locale}>
    {(!tab || tab === "overview") && <PointsTab c={c} />}
    {tab === "switches" && <PointsPolicyTab c={c} canEdit={can("finance:settle:execute")} />}
    {tab === "queries" && <PointsQueriesTab canEdit={can("finance:settle:execute")} canReconRead={can("finance:recon:read")} canReconRun={can("finance:recon:resolve")} />}
    {tab === "settlements" && <PointsSettlementsTab canMake={can("finance:settle:execute")} canExecute={can("finance:payout:execute")} />}
    {tab === "treasury" && <><PointsTreasuryTab canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /><PointsAdjustments canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /></>}
  </div>;
}
