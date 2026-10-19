"use client";

import { Suspense } from "react";
import { useNavTabs, usePageTab } from "@/lib/use-page-tab";
import { useQueryClient } from "@tanstack/react-query";
import { Select } from "@/components/ui/input";
import { useLocaleStore } from "@/lib/stores/locale";
import { tNav } from "@/lib/i18n/nav-labels";
import { usePointsLanguage } from "@/lib/points-language";
import { usePointsText, pointsText } from "@/lib/points-i18n";
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
  const qc = useQueryClient();
  const setArabic = usePointsLanguage((s) => s.setArabic);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const c = locale === "ar" ? Object.fromEntries(Object.entries(FINANCE_COPY.en).map(([key,value]) => [key,pointsText(value,value,"ar")])) as typeof inheritedCopy : inheritedCopy;
  const localTabs = tabs.map((tab) => ({ ...tab, label: locale === "ar" ? pointsText(tab.label,tNav(tab.label,"en"),"ar") : tab.label }));
  const can = useCan();
  return <div className="space-y-4" dir={locale === "ar" ? "rtl" : "ltr"} lang={locale}>
    <label className="flex items-center gap-2">语言 / Language / اللغة <Select aria-label="Points workspace language" value={locale} onChange={(e) => {
      const value = e.target.value; setArabic(value === "ar");
      if (value === "zh" || value === "en") setLocale(value);
      void qc.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).startsWith("points-") });
    }}><option value="zh">中文</option><option value="en">English</option><option value="ar">العربية · مساحة النقاط</option></Select></label>
    <TabHeader tabs={localTabs} value={tab} onChange={setTab} />
    {tab === "overview" && <PointsTab c={c} />}
    {tab === "configuration" && <PointsConfigurationTab canEdit={can("finance:settle:execute")} />}
    {tab === "switches" && <PointsPolicyTab c={c} canEdit={can("finance:settle:execute")} />}
    {tab === "queries" && <PointsQueriesTab canEdit={can("finance:settle:execute")} canReconRead={can("finance:recon:read")} canReconRun={can("finance:recon:resolve")} />}
    {tab === "settlements" && <PointsSettlementsTab canMake={can("finance:settle:execute")} canExecute={can("finance:payout:execute")} />}
    {tab === "treasury" && <><PointsTreasuryTab canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /><PointsAdjustments canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} /></>}
  </div>;
}
