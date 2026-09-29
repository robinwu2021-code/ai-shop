"use client";

// 结算口径的经营统计 —— 接 `/ops/settle-stats`（TDD-供应商结算与双轨资金 §2.1）。
//
// **与门店经营排行不是一回事**：那个读订单（GMV、退款率）、是最近 N 天 Top N；
// 这里读结算单、按区间全量、三维可切。两个数对不上是正常的（GMV 没扣佣金与手续费），
// 所以顶部那句覆盖范围说明**必须显示** —— 不写的话，「这个数比订单后台小」
// 会被当成缺陷来报。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { money } from "@/lib/utils";
import type { SettleStatRow } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { SectionHeader } from "@/components/ui/section-header";
import type { FinanceCopy } from "./copy";

/** 默认看最近 30 天。**含两端** —— 与后端同一口径 */
function defaultRange(): { from: string; to: string } {
  const to = new Date();
  const from = new Date(to.getTime() - 29 * 86_400_000);
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  return { from: fmt(from), to: fmt(to) };
}

const DIMS = ["STORE", "ENTITY", "PAY_MERCHANT"] as const;

export function SettleStatsTab({ c }: { c: FinanceCopy }) {
  const [dim, setDim] = useState<string>("STORE");
  const [range, setRange] = useState(defaultRange);
  const [draft, setDraft] = useState(range);

  const list = useQuery({
    queryKey: ["settle-stats", dim, range.from, range.to],
    queryFn: () => api.listSettleStats({ dim, from: range.from, to: range.to }),
  });

  const rows = list.data ?? [];
  const totalNet = rows.reduce((n, r) => n + r.netMinor, 0);

  const columns: Column<SettleStatRow>[] = [
    {
      header: c.ssColDim,
      cell: (r) => (
        <div>
          <div className="txt-body">{r.dimName}</div>
          {/* 键与名都给：名字是给人读的，而对账时要的是那个号 */}
          {r.dimName !== r.dimKey && (
            <div className="txt-caption text-muted-foreground font-mono">{r.dimKey}</div>
          )}
        </div>
      ),
      width: "14rem",
    },
    { header: c.ssColGross, cell: (r) => money(r.grossMinor), numeric: true, width: "8rem" },
    { header: c.ssColCommission, cell: (r) => money(r.commissionMinor), numeric: true, width: "7rem" },
    { header: c.ssColServiceFee, cell: (r) => money(r.serviceFeeMinor), numeric: true, width: "7rem" },
    { header: c.ssColChannelFee, cell: (r) => money(r.channelFeeMinor), numeric: true, width: "7rem" },
    { header: c.ssColNet, cell: (r) => <span className="txt-strong">{money(r.netMinor)}</span>, numeric: true, width: "8rem" },
    { header: c.ssColCount, cell: (r) => r.billCount, numeric: true, width: "5rem" },
  ];

  return (
    <>
      <SectionHeader title={c.ssTitle}
        summary={c.ssSummary.replace("{n}", String(rows.length)).replace("{amount}", money(totalNet))} />
      <HelpNote className="mb-3">{c.ssNotice}</HelpNote>

      <div className="mb-3 flex flex-wrap items-center gap-1.5">
        {DIMS.map((d) => (
          <button key={d} type="button" onClick={() => setDim(d)}
            className={`focus-ring rounded-chip border px-2.5 py-1 txt-caption ${
              dim === d ? "border-foreground bg-foreground text-background" : "border-border hover:bg-muted"
            }`}>
            {d === "STORE" ? c.ssDimStore : d === "ENTITY" ? c.ssDimEntity : c.ssDimPayMerchant}
          </button>
        ))}

        <span className="ms-2 txt-caption text-muted-foreground">{c.ssFrom}</span>
        <input type="date" value={draft.from}
          onChange={(e) => setDraft({ ...draft, from: e.target.value })}
          className="focus-ring h-[calc(var(--ctl-h)-4px)] rounded-input border border-border bg-background px-1.5 txt-caption" />
        <span className="txt-caption text-muted-foreground">{c.ssTo}</span>
        <input type="date" value={draft.to}
          onChange={(e) => setDraft({ ...draft, to: e.target.value })}
          className="focus-ring h-[calc(var(--ctl-h)-4px)] rounded-input border border-border bg-background px-1.5 txt-caption" />
        <Button size="sm" variant="secondary" onClick={() => setRange(draft)}>{c.ssQuery}</Button>
      </div>

      {/* 资金维度要额外说一句 —— 它最容易被当成经营维度来读 */}
      {dim === "PAY_MERCHANT" && <HelpNote className="mb-3">{c.ssDimHint}</HelpNote>}

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.dimKey}
        empty={c.ssEmpty}
      />

      {/* 合计放在表下面而不是表头：它要跟着筛选变，放表头会让人以为是全量合计 */}
      {rows.length > 0 && (
        <div className="mt-2 flex justify-end gap-2 txt-body">
          <span className="text-muted-foreground">{c.ssTotal}</span>
          <span className="txt-strong">{money(totalNet)}</span>
        </div>
      )}
    </>
  );
}
