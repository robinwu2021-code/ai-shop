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
import { useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { DataTable, type Column } from "@/components/ui/data-table";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { HelpNote } from "@/components/ui/help-note";
import { useCopy, fill } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";
import type { DistributionRow, RegionRow } from "@/lib/types";

type Copy = (typeof COMMUNITIES_COPY)["zh"];

/** 一格「算不了的」。**0 也要显示** —— 缺了这一格，读的人不知道它是 0 还是没算 */
function Gap({ label, n, hint, tone, to, toLabel }: {
  label: string; n: number; hint: string; tone: "danger" | "warn" | "info";
  /** 去补这份数据的地方。**给不出明细的那一格不给链接** —— 点进去什么也没有比没有链接更糟 */
  to?: string; toLabel?: string;
}) {
  const cls = n === 0 ? "text-muted-foreground"
    : tone === "danger" ? "text-destructive" : tone === "warn" ? "text-warning-ink" : "text-primary-ink";
  return (
    <div className="rounded-card border border-border bg-card p-4">
      <div className="txt-body text-muted-foreground">{label}</div>
      <div className={`mt-1 txt-display tabular-nums ${cls}`}>{n}</div>
      <div className="mt-2 txt-caption leading-relaxed text-muted-foreground">{hint}</div>
      {/* 数字是 0 时不给链接：那一格没有待办，点进去只会让人以为漏看了什么 */}
      {to && n > 0 && (
        <Link className="focus-ring mt-2 inline-block txt-caption text-primary-ink underline-offset-2 hover:underline"
              href={to}>
          {toLabel}
        </Link>
      )}
    </div>
  );
}

export function DistributionTab({ enabled }: { enabled: boolean }) {
  const c = useCopy<Copy>(COMMUNITIES_COPY);
  const { data, isPending, error, refetch } = useQuery({
    queryKey: ["coverage-distribution"],
    queryFn: () => api.coverageDistribution(),
    enabled,
  });
  const [drill, setDrill] = useState<RegionRow | null>(null);
  const drillRows = useQuery({
    queryKey: ["coverage-distribution-communities", drill?.regionCode],
    queryFn: () => api.distributionCommunities(drill!.regionCode!),
    enabled: enabled && !!drill?.regionCode,
  });

  // 聚落明细列：招商清单与下钻共用（买家真搜得到口径的「在售商家/商品」）
  const communityCols: Column<DistributionRow>[] = [
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
      header: c.colMerchants, numeric: true,
      cell: (r) => (r.merchantCount === 0 && r.buyerCount > 0
        ? <span className="text-destructive tabular-nums">{r.merchantCount}</span>
        : <span className="tabular-nums">{r.merchantCount}</span>),
    },
    { header: c.colGoods, numeric: true, cell: (r) => r.goodsCount },
  ];

  const regionCols: Column<RegionRow>[] = [
    { header: c.colDistrict, cell: (r) => (
      <button type="button"
              className="focus-ring text-start text-primary-ink underline-offset-2 hover:underline"
              onClick={() => setDrill(r)}>
        {r.regionName ?? r.regionCode ?? "—"}
      </button>
    ) },
    { header: c.colCommunityCount, numeric: true, cell: (r) => r.communityCount },
    { header: c.colBuyers, numeric: true, cell: (r) => r.buyerCount },
    { header: c.colBuyerCommunities, numeric: true, cell: (r) => r.buyerCommunityCount },
    { header: c.colMerchantCommunities, numeric: true, cell: (r) => r.merchantCommunityCount },
    { header: c.distSupplyGap, numeric: true, cell: (r) => (r.supplyGapCount > 0
        ? <span className="text-destructive tabular-nums">{r.supplyGapCount}</span>
        : <span className="tabular-nums">{r.supplyGapCount}</span>) },
    { header: c.distDemandGap, numeric: true, cell: (r) => r.demandGapCount },
    { header: c.distEmpty, numeric: true, cell: (r) => r.emptyCount },
  ];

  // 出错时**必须出一块看得见的东西**：接口一挂整个面板空白，运营既不知道坏了、也没有重试入口。
  if (error) return <ErrorState error={error} onRetry={() => refetch()} />;
  if (!data && isPending) return <Skeleton className="h-40 w-full" />;
  if (!data) return null;

  const u = data.unattributable;
  const t = data.totals;
  const totalBuyers = t.buyers + u.addressesWithoutCoords + u.addressesOutsideFences;

  return (
    <div className="space-y-4">
      {/*
        **样本太小的时候要直说。** 分母是个位数时，任何一格的高低都不说明问题 ——
        而它长得和一张有统计意义的表一模一样，不说的话第一个看到的人就会拿它去做决定。
      */}
      {totalBuyers < 30 && (
        <Notice tone="warning">{fill(c.distSmallSample, { n: totalBuyers })}</Notice>
      )}

      {/*
        「算不了的」四格 —— 这屏最要紧的不是那几行。静默丢掉就会把「缺数据」说成「缺需求」。
        每一格能走到「具体缺什么数据」那一步。
      */}
      <div className="grid gap-3 sm:grid-cols-4">
        <Gap label={c.gapNoCoords} n={u.addressesWithoutCoords} hint={c.gapNoCoordsHint} tone="danger"
             to="/communities?tab=health" toLabel={c.gapGoHealth} />
        <Gap label={c.gapOutside} n={u.addressesOutsideFences} hint={c.gapOutsideHint} tone="info" />
        <Gap label={c.gapStores} n={u.storesWithoutCoords} hint={c.gapStoresHint} tone="danger"
             to="/communities?tab=health" toLabel={c.gapGoStores} />
        <Gap label={c.gapClosed} n={u.communitiesClosed} hint={c.gapClosedHint} tone="warn"
             to="/communities?tab=grid&opened=0" toLabel={c.gapGoClosed} />
      </div>

      {/* 供需四桶：全平台计数（服务端算好，不靠把两万多行发过来自己数） */}
      <div className="rounded-card border border-border bg-card p-4">
        <div className="mb-3 txt-body font-medium">{c.distTotalsTitle}</div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {([
            [c.distOk, t.okCount, ""],
            [c.distSupplyGap, t.supplyGapCount, t.supplyGapCount > 0 ? "text-destructive" : ""],
            [c.distDemandGap, t.demandGapCount, ""],
            [c.distEmpty, t.emptyCount, ""],
          ] as const).map(([label, n, cls]) => (
            <div key={label}>
              <div className="txt-caption text-muted-foreground">{label}</div>
              <div className={`txt-display tabular-nums ${cls}`}>{n}</div>
            </div>
          ))}
        </div>
      </div>

      {/* 招商清单：有人没商家。可行动到具体小区；全平台都有商家覆盖时为空（不渲染这一块） */}
      {data.supplyGaps.length > 0 && (
        <div className="space-y-2">
          <div className="txt-body font-medium">{c.distSupplyGapsTitle}</div>
          <HelpNote>{c.distSupplyGapsHint}</HelpNote>
          <DataTable rows={data.supplyGaps} columns={communityCols}
                     rowKey={(r) => r.communityNo} empty="" />
        </div>
      )}

      {/* 区县概览：一区县一行，点区县名下钻到它的小区明细 */}
      <div className="space-y-2">
        <div className="txt-body font-medium">{c.distRegionsTitle}</div>
        <HelpNote>{c.distRegionsHint}</HelpNote>
        <DataTable rows={data.regions} columns={regionCols}
                   rowKey={(r) => r.regionCode ?? "_"} empty={c.distNoRegions} />
      </div>

      {/* 下钻：某区县的小区明细（行数被区县框住） */}
      {drill && (
        <div className="space-y-2 rounded-card border border-border bg-card p-4">
          <div className="flex items-center justify-between">
            <div className="txt-body font-medium">{drill.regionName ?? drill.regionCode}</div>
            <button type="button"
                    className="focus-ring txt-caption text-primary-ink underline-offset-2 hover:underline"
                    onClick={() => setDrill(null)}>
              {c.distDrillClose}
            </button>
          </div>
          <DataTable rows={drillRows.data ?? []} columns={communityCols}
                     loading={drillRows.isPending} error={drillRows.error}
                     onRetry={() => drillRows.refetch()}
                     rowKey={(r) => r.communityNo} empty={c.distDrillEmpty} />
        </div>
      )}
    </div>
  );
}
