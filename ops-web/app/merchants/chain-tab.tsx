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
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { fill } from "@/lib/use-copy";
import Link from "next/link";
import type { MerchantChainRow, MerchantChainStuck, MerchantNudgeReason } from "@/lib/types";
import { NUDGEABLE } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { HelpNote } from "@/components/ui/help-note";
import { CheckboxField } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
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
  const allow = useCan();
  const canNudge = allow("merchant:merchant:nudge");
  const { confirm, dialog } = useConfirm();

  /*
   * 提醒。**三种结局分开说** —— 发出去了 / 今天已经提醒过了 / 这家店没人收。
   * 混成一个「成功」，运营看不出区别就会一直点，而商家那头什么也没多收到。
   */
  const nudge = useMutation({
    mutationFn: api.nudgeMerchant,
    onSuccess: (r) => {
      if (r.noRecipient) notify.error(c.chainNudgeNoRecipient);
      else if (r.alreadySentToday) notify.info(c.chainNudgeAlready);
      else notify.success(fill(c.chainNudgeSent, { n: r.sent }));
    },
  });
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
    {
      header: c.chainAction,
      /*
       * **IN_AUDIT 那一行不给提醒按钮，给去审核队列的入口。**
       * 那一档的意思是「他的品全卡在平台的审核队列里」—— 欠账的是平台。
       * 就这件事去提醒商家，等于把自己的积压说成对方的问题，
       * 而商家收到之后能做的只有再等。后端也会拒（事由枚举里就没有它），
       * 但界面不该画一个点了必然失败的按钮。
       */
      cell: (r) => {
        if (r.stuckAt === "IN_AUDIT") {
          return <Link href="/products?tab=audit" className="txt-caption underline">{c.chainGoAudit}</Link>;
        }
        if (!r.stuckAt || !canNudge || !NUDGEABLE.includes(r.stuckAt as MerchantNudgeReason)) {
          return <Dash />;
        }
        return (
          <Button size="sm" variant="outline" onClick={() => void confirm({
            title: fill(c.chainNudgeTitle, { name: r.merchantName ?? r.entityNo }),
            desc: c.chainNudgeDesc,
            confirmText: c.chainNudgeConfirm,
            requireReason: false,
            action: (note) => nudge.mutateAsync({
              entityNo: r.entityNo,
              reason: r.stuckAt as MerchantNudgeReason,
              note: note || undefined,
            }),
          })}>{c.chainNudge}</Button>
        );
      },
    },
  ];

  return (
    <>
      {dialog}
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
