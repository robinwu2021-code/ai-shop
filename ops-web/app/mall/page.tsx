"use client";

// 套餐商城（commerce Task 5）：平台向门店卖 FREE/PRO/CHAIN 套餐与业务端。
//
// 七个页签对应七个菜单叶子（lib/nav.ts「套餐商城」组）。接口逐字对应
// docs/api/commerce-mall-openapi.json（task8-v1）；mock 下规则全在 lib/mock/db/commerce.ts 强制。
//
// 与「商家与门店 › 增值包」是两件事：那里是旧的主体额度 Plan（授予/档位定义），保留不动；
// 这里是可购买的商业目录、商城订单与线下核款。
import { Suspense } from "react";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { useCan } from "@/lib/use-can";
import { useCopy } from "@/lib/use-copy";
import { TabHeader } from "@/components/ui/tab-header";
import { MALL_COPY } from "./copy";
import { CatalogTab } from "./catalog-tab";
import { ProductsTab } from "./products-tab";
import { OrdersTab } from "./orders-tab";
import { ClaimsTab } from "./claims-tab";
import { CollectionTab } from "./collection-tab";
import { AssistedTab } from "./assisted-tab";
import { SourcesTab } from "./sources-tab";

const TAB_KEYS = ["catalog", "products", "orders", "claims", "collection", "assisted", "sources"] as const;

export default function MallPage() {
  return <Suspense fallback={null}><MallInner /></Suspense>;
}

function MallInner() {
  const c = useCopy(MALL_COPY);
  const tabs = useNavTabs("/mall", TAB_KEYS);
  const [tab, setTab] = usePageTab(tabs);
  const allow = useCan();

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />
      {tab === "catalog" && (
        <CatalogTab c={c} canEdit={allow("commerce:catalog:edit")} canPublish={allow("commerce:catalog:publish")} />
      )}
      {tab === "products" && (
        <ProductsTab c={c} canEdit={allow("commerce:catalog:edit")} canPublish={allow("commerce:catalog:publish")} />
      )}
      {tab === "orders" && <OrdersTab c={c} />}
      {tab === "claims" && <ClaimsTab c={c} />}
      {tab === "collection" && <CollectionTab c={c} canWrite={allow("commerce:collection:profile:write")} />}
      {tab === "assisted" && <AssistedTab c={c} />}
      {tab === "sources" && (
        <SourcesTab c={c} canGrant={allow("commerce:subscription:grant")} canRevoke={allow("commerce:subscription:revoke")} />
      )}
    </div>
  );
}
