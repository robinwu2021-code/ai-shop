"use client";

// 坐标健康度 —— **这一页是整个位置模块的分母。**
//
// 门店没标点时，后端那条自送半径的闸**直接放行**（缺数据不该拦正常订单，这是对的）。
// 代价是：商家在「送货方式」里填了三公里，实际多远的单都进得来，
// 等他准备送货那一刻才发现送不到 —— 而那时钱已经收了，只能退款并向买家解释。
//
// 这件事此前**在任何界面上都看不见**：商家看不见（他填了就以为生效了），
// 运营也看不见（没有任何一页统计过它）。
//
// 同理，没坐标的收货地址推不出任何聚落。它们既不算进任何一个片区的统计，
// 也不该被静默丢掉 —— 位置分布那张表必须把它们单列一格，
// 否则会把「缺数据」说成「缺需求」，而运营会据此去撤一个其实有人的片区的商家。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/misc";
import { useCopy } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

type MissingStore = {
  storeNo: string;
  storeName: string;
  merchantNo: string;
  deliveryRadiusM: number | null;
};

/** 一格：分子/分母 + 一句「缺了会怎样」。**光给数字没用，要说后果** */
function Stat({ label, done, total, consequence, allGood, tone }: {
  label: string; done: number; total: number; consequence: string; allGood: string;
  tone: "danger" | "warn" | "ok";
}) {
  const missing = total - done;
  const cls = missing === 0 ? "text-muted-foreground"
    : tone === "danger" ? "text-destructive" : "text-amber-600";
  return (
    <div className="rounded-card border border-border bg-card p-4">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-semibold tabular-nums">
        <span className={cls}>{done}</span>
        <span className="text-muted-foreground"> / {total}</span>
      </div>
      {/*
        缺口为 0 时也要说一句「这一格是齐的」。只显示一个孤零零的 23/23，
        与「后果那行还没写完」长得一样 —— 而这一页的读者正是来找缺口的，
        他分不清「没缺口」和「没做完」。
      */}
      <div className="mt-2 text-xs leading-relaxed text-muted-foreground">
        {missing > 0 ? consequence : allGood}
      </div>
    </div>
  );
}

export function HealthTab({ enabled }: { enabled: boolean }) {
  const c = useCopy<Copy>(COMMUNITIES_COPY);
  const { data, isPending } = useQuery({
    queryKey: ["coverage-health"],
    queryFn: () => api.coverageHealth(),
    enabled,
  });

  const cols: Column<MissingStore>[] = [
    { header: c.colStoreName, cell: (r) => r.storeName },
    { header: c.colStoreNo, cell: (r) => <span className="font-mono text-xs">{r.storeNo}</span> },
    {
      header: c.colMerchant,
      // 能跳过去才算「点名到户」；只列一串号，运营下一步还是无从做起
      cell: (r) => (
        <a className="focus-ring text-primary underline-offset-2 hover:underline"
           href={`/merchants?keyword=${encodeURIComponent(r.merchantNo)}`}>
          {r.merchantNo}
        </a>
      ),
    },
    {
      header: c.colSetRadius,
      // 他以为自己限了多少米 —— 这个数越大，「实际一米都没限」的落差越刺眼
      cell: (r) => (r.deliveryRadiusM
        ? <Badge tone="warning">{r.deliveryRadiusM}{c.radiusSuffix}</Badge>
        : <span className="text-muted-foreground">{c.radiusUnset}</span>),
    },
  ];

  if (!data && isPending) return <div className="p-6 text-sm text-muted-foreground">{c.loading}</div>;
  if (!data) return null;

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <Stat label={c.statStores} done={data.stores.withCoords} total={data.stores.total}
              consequence={c.storeConsequence} allGood={c.storeAllGood} tone="danger" />
        <Stat label={c.statAddresses} done={data.addresses.withCoords} total={data.addresses.total}
              consequence={c.addressConsequence} allGood={c.addressAllGood} tone="warn" />
        <Stat label={c.statCommunities} done={data.communities.withCoords} total={data.communities.total}
              consequence={c.communityConsequence} allGood={c.communityAllGood} tone="danger" />
      </div>

      <div>
        <div className="mb-2 text-sm font-medium">{c.missingStoresTitle}</div>
        {data.stores.missing.length === 0
          ? <EmptyState title={c.allStoresPinned} />
          : <DataTable rows={data.stores.missing} columns={cols} rowKey={(r) => r.storeNo} />}
      </div>

      {data.communities.missing.length > 0 && (
        <div>
          <div className="mb-2 text-sm font-medium">{c.missingCommunitiesTitle}</div>
          <ul className="space-y-1 text-sm">
            {data.communities.missing.map((m) => (
              <li key={m.communityNo}>
                {m.name} <span className="font-mono text-xs text-muted-foreground">{m.communityNo}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
