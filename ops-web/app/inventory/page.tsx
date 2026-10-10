"use client";

// 进销存（矩阵 P-18）。**独立模块，不挂在「商品与类目」下面。**
//
// 进销存与电商侧的商品/类目是两件事：那边管的是「这件货长什么样、能不能卖」，
// 这边管的是「这件货现在有多少、是怎么变成这么多的」。它有独立的库、独立的
// Java 模块，将来要能单独交付 —— 在运营端把它塞进商品页的 tab 条里，
// 等于在界面上先把这条边界抹掉，而抹掉之后没有人会记得它曾经存在。
//
// **库存本身全只读**。运营改了商家的库存，「这个数是谁改的」就多了一个答案，
// 而商家不会知道。
//
// 第四页「开放对接」是唯一的写口，而它改的不是货、是**谁能读这些货** ——
// 对方换了对接商、密钥泄露，处置只能在平台这边。所以它挂的是
// `inventory:credential:grant`（发钥匙），而看列表判 `inventory:credential:read`
// —— 只读那一档给审计，签发那一档只给超管。
import { Suspense } from "react";
import { useCopy } from "@/lib/use-copy";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { TabHeader } from "@/components/ui/tab-header";
import { INVENTORY_COPY } from "./copy";
import { HealthTab } from "./health-tab";
import { LedgerTab } from "./ledger-tab";
import { ReconTab } from "./recon-tab";
import { LinkHealthTab } from "./link-health-tab";
import { CredentialsTab } from "./credentials-tab";

const TAB_KEYS = ["health", "ledger", "recon", "link-health", "credentials"] as const;   // 顺序与 lib/nav.ts 的叶子一致

export default function InventoryPage() {
  return <Suspense fallback={null}><InventoryInner /></Suspense>;
}

function InventoryInner() {
  const c = useCopy(INVENTORY_COPY);
  const tabs = useNavTabs("/inventory", TAB_KEYS);
  const [tab, setTab] = usePageTab(tabs);

  return (
    <div className="space-y-5">
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab === "health" && <HealthTab c={c} />}
      {tab === "ledger" && <LedgerTab c={c} />}
      {tab === "recon" && <ReconTab c={c} />}

      {tab === "link-health" && <LinkHealthTab c={c} />}
      {tab === "credentials" && <CredentialsTab c={c} />}
    </div>
  );
}
