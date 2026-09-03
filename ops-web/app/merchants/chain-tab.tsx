"use client";

// 商家链条画像（M1）。一家一行：建品 → 提审 → 上架 → 建账 → 首次进货 → 持续记账。
//
// **这一页的重点是最后一列，不是前面六个数字。** 六个数字回答「这家怎么样」，
// 而运营要的是「今天该找谁、为什么」—— 那是 stuckAt。
// 所以卡点列不排在最右边当补充说明，它排在商家名右边，是这一行的结论。
//
// 已有的三块统计（库存健康度、库存对差、获客看板）都是某一环的快照，
// 没有一处是贯穿链条的漏斗。而线上那组数说明：问题不在某一环内部，在环与环之间。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fmtTime } from "@/lib/utils";
import { fill } from "@/lib/use-copy";
import type { MerchantChainRow, MerchantChainStuck } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { CheckboxField } from "@/components/ui/checkbox";
import { StatCard, StatRow } from "@/components/ui/misc";
import type { MerchantsCopy } from "./copy";

/**
 * 卡点的色调。**IN_AUDIT 是 warning 而不是 danger** —— 它是平台自己欠商家的，
 * 与「商家没动」不该长得一样；运营扫这一列时，颜色就是分诊。
 */
const useStuckMap = (c: MerchantsCopy): StatusMap<MerchantChainStuck> => ({
  NO_GOODS: { label: c.chainNoGoods, tone: "muted" },
  IN_AUDIT: { label: c.chainInAudit, tone: "warning" },
  NOT_ON_SALE: { label: c.chainNotOnSale, tone: "warning" },
  NO_ACCOUNT: { label: c.chainNoAccount, tone: "danger" },
  NO_INBOUND: { label: c.chainNoInbound, tone: "muted" },
  STALE_LEDGER: { label: c.chainStaleLedger, tone: "warning" },
});

export function ChainTab({ c }: { c: MerchantsCopy }) {
  const [stuckOnly, setStuckOnly] = useState(false);
  const stuckMap = useStuckMap(c);
  const q = useQuery({
    queryKey: ["merchant-chain", stuckOnly],
    queryFn: () => api.merchantChain({ limit: 200, stuckOnly }),
  });
  const rows = q.data ?? [];

  // 分子分母都要给：「3 家卡住」不如「8 家里 3 家卡住」——
  // 前者答不出这是常态还是异常
  const stuck = rows.filter((r) => r.stuckAt !== null).length;

  const columns: Column<MerchantChainRow>[] = [
    { header: c.chainMerchant, cell: (r) => r.merchantName ?? r.entityNo },
    { header: c.chainStuck, cell: (r) => (r.stuckAt
        ? <StatusBadge value={r.stuckAt} map={stuckMap} />
        : <span className="txt-caption text-[var(--success-ink)]">{c.chainHealthy}</span>) },
    { header: c.chainGoods, numeric: true, cell: (r) => r.goods },
    { header: c.chainPending, numeric: true, cell: (r) => r.pendingAudit },
    { header: c.chainOnSale, numeric: true, cell: (r) => r.onSale },
    { header: c.chainItems, numeric: true, cell: (r) => r.items },
    // 「一次都没进过货」与「进过货但很久没动」是两件事，空值不能显示成 0
    { header: c.chainFirstInbound, cell: (r) => (r.firstInbound ? fmtTime(r.firstInbound) : <Dash />) },
    { header: c.chainLastLedger, cell: (r) => (r.lastLedger ? fmtTime(r.lastLedger) : <Dash />) },
  ];

  return (
    <>
      <HelpNote title={c.chainHelpTitle}>{c.chainHelp}</HelpNote>

      <StatRow>
        <StatCard label={c.chainTotal} value={q.isPending ? null : rows.length} loading={q.isPending} />
        <StatCard label={c.chainStuckCount} value={q.isPending ? null : stuck}
          sub={rows.length ? fill(c.chainStuckSub, { n: rows.length }) : undefined}
          tone={stuck > 0 ? "down" : undefined} loading={q.isPending} />
      </StatRow>

      <Toolbar>
        <CheckboxField label={c.chainStuckOnly} checked={stuckOnly}
          onChange={(v) => setStuckOnly(v === true)} />
      </Toolbar>

      {/*
        * error / onRetry 必须传：不传的话取数失败会渲染成「没有符合条件的数据」，
        * 运营会去改筛选条件，而真正该做的是报障
        */}
      <DataTable rows={rows} columns={columns} loading={q.isPending}
        error={q.error} onRetry={() => void q.refetch()}
        rowKey={(r) => r.entityNo} empty={stuckOnly ? c.chainEmptyStuck : c.chainEmpty} />
    </>
  );
}

/** 「没有这件事」不是 0。0 是量出来的，— 是压根没发生过 */
function Dash() {
  return <span className="text-muted-foreground">—</span>;
}
