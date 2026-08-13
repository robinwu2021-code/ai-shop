"use client";

// 积分资金看板。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这一页要回答的唯一问题：池子对不对得上
// ─────────────────────────────────────────────────────────────────────────────
// 恒等式是「流通中的积分 == 池子里的钱」。两个数分开看的话，
// 失衡要等到有人主动比对才会发现 —— 所以它们必须摆在同一屏，并且**直接算出差额**。
//
// 按通道分的账本同样不能省：账面是一个池子，**钱实际分散在两个通道账户**。
// 只看总数的话，一个溢一个空的时候总数仍然是平的。
//
// **只读。** 池子的钱是靠流水推出来的，不是靠人改的 ——
// 开一个「手工调整余额」的入口，等于允许在没有业务事件的情况下改账，
// 而那之后恒等式失衡就再也说不清是哪一笔了。要调整就补一笔有类型的流水。
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import type { PoolByChannel } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { ConfigCard } from "@/components/ui/config-card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";
import type { FINANCE_COPY } from "./copy";

type Copy = (typeof FINANCE_COPY)["zh"];

export function PointsTab({ c }: { c: Copy }) {
  const q = useQuery({ queryKey: ["points-overview"], queryFn: () => api.pointsOverview() });
  const d = q.data;

  // 差额：流通积分（1 分 = 1 分钱）与池子余额的差。
  // **不四舍五入、不取绝对值** —— 正负方向本身是信息：
  // 池子多了是收了钱没发分，池子少了是发了分没收钱，两种要查的地方不同
  const gap = d ? d.poolBalanceMinor - d.circulatingPoints : 0;

  const cols: Column<PoolByChannel>[] = [
    { header: c.ptColChannel, cell: (r) => r.payChannel },
    { header: c.ptColMarket, cell: (r) => r.market },
    {
      header: c.ptColBalance,
      cell: (r) => <span className="tabular-nums">{money(r.balanceMinor)}</span>,
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="muted">{c.ptHint}</Notice>

      <div className="grid gap-3 sm:grid-cols-3">
        <Stat label={c.ptCirculating} value={money(d?.circulatingPoints ?? 0)} />
        <Stat label={c.ptPoolBalance} value={money(d?.poolBalanceMinor ?? 0)} />
        <Stat label={c.ptPeriodRedeem} value={money(d?.periodRedeemMinor ?? 0)} />
      </div>

      {/* 差额单独一行、并且给出方向 —— 这才是这一页的结论 */}
      <ConfigCard title={c.ptGapTitle}>
        {gap === 0 ? (
          <Badge tone="success">{c.ptBalanced}</Badge>
        ) : (
          <div className="space-y-1">
            <Badge tone="danger">{money(gap)}</Badge>
            <p className="txt-caption text-muted-foreground">
              {gap > 0 ? c.ptGapPoolMore : c.ptGapPoolLess}
            </p>
          </div>
        )}
      </ConfigCard>

      <ConfigCard title={c.ptByChannelTitle} notice={c.ptByChannelHint}>
        <DataTable
          columns={cols}
          rows={d?.byChannel ?? []}
          rowKey={(r) => `${r.market}-${r.payChannel}`}
          loading={q.isLoading}
          empty={c.ptEmpty}
        />
      </ConfigCard>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border p-3">
      <div className="txt-caption text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-medium tabular-nums">{value}</div>
    </div>
  );
}
