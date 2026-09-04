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
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { useCopy, fill } from "@/lib/use-copy";
import { COMMUNITIES_COPY } from "./copy";
import { segmentedItemClass, segmentedTrackClass } from "@/components/ui/segmented";
import type { DistributionRow } from "@/lib/types";

/**
 * 供需缺口（T13）。**同一份数据的四种切法，不是另一张表** ——
 * 另开一屏各算一遍，两处的数字迟早会不一样，而没人会知道该信哪个。
 *
 * - `supply` 有人没商家：这一格是招商清单
 * - `demand` 有商家没人：**先别急着撤** —— 今天全平台只有两条能定位的地址，
 *   绝大多数聚落都会落进这一格，那说明的是分母太小，不是那儿没人
 * - `empty`  两边都没有：既没人也没货，多半是刚开还没运营
 */
type Gapkind = "all" | "supply" | "demand" | "empty";

function classify(r: DistributionRow): Exclude<Gapkind, "all"> | "ok" {
  if (r.buyerCount > 0 && r.merchantCount === 0) return "supply";
  if (r.merchantCount > 0 && r.buyerCount === 0) return "demand";
  if (r.buyerCount === 0 && r.merchantCount === 0) return "empty";
  return "ok";
}

type Copy = (typeof COMMUNITIES_COPY)["zh"];

/** 一格「算不了的」。**0 也要显示** —— 缺了这一格，读的人不知道它是 0 还是没算 */
function Gap({ label, n, hint, tone, to, toLabel }: {
  label: string; n: number; hint: string; tone: "danger" | "warn" | "info";
  /** 去补这份数据的地方。**给不出明细的那一格不给链接** —— 点进去什么也没有比没有链接更糟 */
  to?: string; toLabel?: string;
}) {
  const cls = n === 0 ? "text-muted-foreground"
    : tone === "danger" ? "text-destructive" : tone === "warn" ? "text-amber-600" : "text-primary";
  return (
    <div className="rounded-card border border-border bg-card p-4">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${cls}`}>{n}</div>
      <div className="mt-2 text-xs leading-relaxed text-muted-foreground">{hint}</div>
      {/* 数字是 0 时不给链接：那一格没有待办，点进去只会让人以为漏看了什么 */}
      {to && n > 0 && (
        <Link className="focus-ring mt-2 inline-block text-xs text-primary underline-offset-2 hover:underline"
              href={to}>
          {toLabel}
        </Link>
      )}
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

  const [gap, setGap] = useState<Gapkind>("all");

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

      {/*
        每一格都要能走到「具体缺什么数据」那一步，否则运营看到一个数字也无从做起。
        没坐标的地址是**唯一一格给不出明细的**：那要列出具体是谁家的地址，
        而这一屏要回答的是「哪儿有人」，不是「谁住哪儿」—— 所以那一格直说去哪儿看总量。
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

      {/*
        供需缺口：**同一份数据的四种切法**，点一格就把下面的表筛成那一格里的聚落 ——
        「每一格能下钻到具体聚落」这条判据的意思就是这个。
        另开一屏各算一遍的话，两处的数字迟早会不一样，而没人会知道该信哪个。
      */}
      <div className="flex flex-wrap items-center gap-2">
        <div className={segmentedTrackClass()}>
          {(["all", "supply", "demand", "empty"] as const).map((k) => (
            <button key={k} type="button" className={segmentedItemClass(gap === k)}
                    onClick={() => setGap(k)}>
              {c[`gapTab${k[0].toUpperCase()}${k.slice(1)}` as keyof Copy] as string}
              <span className="ml-1 tabular-nums">
                {k === "all" ? data.rows.length : data.rows.filter((r) => classify(r) === k).length}
              </span>
            </button>
          ))}
        </div>
        <span className="text-xs text-muted-foreground">
          {gap === "supply" ? c.gapTabSupplyHint
            : gap === "demand" ? c.gapTabDemandHint
            : gap === "empty" ? c.gapTabEmptyHint : c.gapTabAllHint}
        </span>
      </div>

      <DataTable
        rows={gap === "all" ? data.rows : data.rows.filter((r) => classify(r) === gap)}
        columns={cols} rowKey={(r) => r.communityNo} />
    </div>
  );
}
