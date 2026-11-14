"use client";
import { Suspense } from "react";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { HelpNote } from "@/components/ui/help-note";
import { useReportingIdentity } from "@/lib/use-reporting-identity";
import { useCopy } from "@/lib/use-copy";
import DashboardPage from "@/app/page";
import { CUSTOMERS_COPY } from "./copy";
import { RegistrationsTab, BrandsTab, PlatformMembersTab } from "./read-tabs";
import { StoreWorkspace } from "./store-workspace";
const TAB_KEYS = ["overview", "users", "members", "stores", "chains"] as const;
export default function CustomersPage() { return <Suspense fallback={null}><CustomersInner /></Suspense>; }
function CustomersInner() {
  const identity=useReportingIdentity();
  const c=useCopy(CUSTOMERS_COPY), tabs=useNavTabs("/customers",TAB_KEYS);
  if(!tabs.length)return <p role="status">{c.denied}</p>;
  return <CustomerContent key={JSON.stringify(identity)} />;
}
function CustomerContent() {
  const c=useCopy(CUSTOMERS_COPY), tabs=useNavTabs("/customers",TAB_KEYS), [tab,setTab]=usePageTab(tabs);
  return <div className="space-y-4"><TabHeader tabs={tabs} value={tab} onChange={setTab}/>
    {tab==="overview"&&<><HelpNote>{c.overviewHint}</HelpNote><DashboardPage/></>}
    {tab==="users"&&<RegistrationsTab/>}{tab==="members"&&<PlatformMembersTab/>}
    {tab==="stores"&&<StoreWorkspace/>}{tab==="chains"&&<BrandsTab/>}
  </div>;
}
