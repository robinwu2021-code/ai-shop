"use client";

import { Suspense } from "react";
import { useNavTabs, usePageTab } from "@/lib/use-page-tab";
import { usePointsText } from "@/lib/points-i18n";
import { useCopy } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { TabHeader } from "@/components/ui/tab-header";
import { FINANCE_COPY } from "../finance/copy";
import { PointsTab } from "./pool-tab";
import { PointsPolicyTab } from "./switches-tab";
import { PointsConfigurationTab } from "./types-tab";
import { PointsQueriesTab } from "./queries-tab";
import { PointsSettlementsTab } from "./settlements-tab";
import { PointsAdjustments } from "./adjustments";
import { PointsTreasuryTab } from "./requests-tab";

const TABS = ["overview", "configuration", "switches", "treasury", "settlements", "queries"] as const;
export default function PointsPage() { return <Suspense fallback={null}><PointsInner /></Suspense>; }
function PointsInner() {
  const tabs = useNavTabs("/points", TABS);
  const [tab, setTab] = usePageTab(tabs);
  const inheritedCopy = useCopy(FINANCE_COPY);
  const { locale } = usePointsText();
  const c = inheritedCopy;
  const can = useCan();
  return <div className="space-y-4" dir="ltr" lang={locale}>
    <TabHeader tabs={tabs} value={tab} onChange={setTab} />
    {tab === "overview" && <PointsTab c={c} />}
    {tab === "configuration" && <PointsConfigurationTab canEdit={can("finance:settle:execute")} />}
    {tab === "switches" && <PointsPolicyTab c={c} canEdit={can("finance:settle:execute")} />}
    {tab === "queries" && <PointsQueriesTab canEdit={can("finance:settle:execute")} canReconRead={can("finance:recon:read")} canReconRun={can("finance:recon:resolve")} />}
    {tab === "settlements" && <PointsSettlementsTab canMake={can("finance:settle:execute")} canExecute={can("finance:payout:execute")} />}
    {tab === "treasury" && <><PointsTreasuryTab canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /><PointsAdjustments canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /></>}
  </div>;
}
