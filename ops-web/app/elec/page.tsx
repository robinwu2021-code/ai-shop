"use client";

// 电子元器件（P-19）。**独立服务 elec-svc 的运营面**：接口是 /elec/ops/**，判权在 elec-svc。
// 四个子页一个路由、?tab= 深链；询报价在第一个 —— 运营每天最常干的是照着「库里谁有货」报价。
import { Suspense } from "react";
import { useCopy } from "@/lib/use-copy";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { ELEC_COPY } from "./copy";
import { RfqTab } from "./rfq-tab";
import { SupplierTab } from "./supplier-tab";
import { PartTab } from "./part-tab";
import { BaseTab } from "./base-tab";

const TAB_KEYS = ["rfq", "supplier", "part", "base"] as const;   // 顺序与 lib/nav.ts 的叶子一致

export default function ElecPage() {
  return <Suspense fallback={null}><ElecInner /></Suspense>;
}

function ElecInner() {
  const c = useCopy(ELEC_COPY);
  const tabs = useNavTabs("/elec", TAB_KEYS);
  const [tab, setTab] = usePageTab(tabs);
  return (
    <div className="space-y-5">
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "rfq" && <RfqTab c={c} />}
      {tab === "supplier" && <SupplierTab c={c} />}
      {tab === "part" && <PartTab c={c} />}
      {tab === "base" && <BaseTab c={c} />}
    </div>
  );
}
