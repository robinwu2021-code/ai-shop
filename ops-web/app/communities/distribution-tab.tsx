"use client";

// 位置分布：聚落 × 买家 × 商家 × 商品。
//
// **这一屏最要紧的不是那张表，是它上面那一排「算不了的」。**
//
// 没坐标的收货地址推不出任何聚落；有坐标却不落在任何围栏里的地址说明「那儿真的有人，
// 只是平台还没在那儿开聚落」；没标点的门店让自送半径形同虚设。把它们静默丢掉，
// 这张表就会把**「缺数据」说成「缺需求」**——而运营会据此去撤一个其实有人的片区的商家。
//
// 分母写错的分析比没有分析更危险：没有分析时人会去查，
// 有一张看起来完整的表时，人会直接照着做。所以那一排画得和表同样显眼，不是脚注。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { useCopy, fill } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";
import type { DistributionRow } from "@/lib/types";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

/** 一格「算不了的」。**0 也要显示** —— 缺了这一格，读的人不知道它是 0 还是没算 */
function Gap({ label, n, hint, tone }: {
  label: string; n: number; hint: string; tone: "danger" | "warn" | "info";
}) {
  const cls = n === 0 ? "text-muted-foreground"
    : tone === "danger" ? "text-destructive" : tone === "warn" ? "text-amber-600" : "text-primary";
  return (
    <div className="rounded-card border border-border bg-card p-4">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${cls}`}>{n}</div>
      <div className="mt-2 text-xs leading-relaxed text-muted-foreground">{hint}</div>
    </div>
  );
}

export function DistributionTab({ enabled }: { enabled: boolean }) {
  const c = useCopy<Copy>(COMMUNITIES_COPY);
  const { data, isPending } = useQuery({
    queryKey: ["coverage-distribution"],
    queryFn: () => api.coverageDistribution(),
    enabled,
  });

  const cols: Column<DistributionRow>[] = [
    { header: c.colCommunity, cell: (r) => (
      <span>
        {r.name}
        {r.kind === "BUILDING" && <Badge className="ml-2">{c.kindBuilding}</Badge>}
      </span>
    ) },
    { header: c.colRegion, cell: (r) => (
      r.regionPath ? <span className="txt-caption">{r.regionPath}</span>
        : <span className="txt-caption text-muted-foreground">{c.regionUnset}</span>
    ) },
    { header: c.colBuyers, numeric: true, cell: (r) => r.buyerCount },
    {
      // 供给侧取的是**社区池**（买家真搜得到的），不是「谁框了这儿」：
      // 一个商家框了整个区却一件货都没上，在「他框了什么」里是 1，在这儿是 0 ——
      // 而运营要据此决定去哪儿招商，看错一个就是白跑一趟。
      header: c.colMerchants, numeric: true,
      cell: (r) => (r.merchantCount === 0 && r.buyerCount > 0
        ? <span className="text-destructive tabular-nums">{r.merchantCount}</span>
        : <span className="tabular-nums">{r.merchantCount}</span>),
    },
    { header: c.colGoods, numeric: true, cell: (r) => r.goodsCount },
  ];

  if (!data && isPending) return <div className="p-6 text-sm text-muted-foreground">{c.loading}</div>;
  if (!data) return null;

  const u = data.unattributable;
  const attributed = data.rows.reduce((n, r) => n + r.buyerCount, 0);
  const totalBuyers = attributed + u.addressesWithoutCoords + u.addressesOutsideFences;

  return (
    <div className="space-y-4">
      {/*
        **样本太小的时候要直说。** 这张表在今天的库上分母是个位数，
        任何一行的高低都不说明任何事 —— 而它长得和一张有统计意义的表一模一样，
        不说的话，第一个看到它的人就会拿它去做决定。
      */}
      {totalBuyers < 30 && (
        <Notice tone="warning">{fill(c.distSmallSample, { n: totalBuyers })}</Notice>
      )}

      <div className="grid gap-3 sm:grid-cols-4">
        <Gap label={c.gapNoCoords} n={u.addressesWithoutCoords} hint={c.gapNoCoordsHint} tone="danger" />
        <Gap label={c.gapOutside} n={u.addressesOutsideFences} hint={c.gapOutsideHint} tone="info" />
        <Gap label={c.gapStores} n={u.storesWithoutCoords} hint={c.gapStoresHint} tone="danger" />
        <Gap label={c.gapClosed} n={u.communitiesClosed} hint={c.gapClosedHint} tone="warn" />
      </div>

      <DataTable rows={data.rows} columns={cols} rowKey={(r) => r.communityNo} />
    </div>
  );
}
