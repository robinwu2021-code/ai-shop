"use client";

// 账期批次（P-12.1）。
//
// **批次管「能不能放」，单据管「放得成不成」** —— 这一页回答的是
// 「这家的钱卡在哪一批」，而不是「这一笔多少钱」。后者在「结算单与分账」那一栏。
//
// 这一页的重心是**挂起队列**：BLOCKED 的批次是唯一需要人动手的，
// 其余四个状态都是系统自己会推进的。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime, money } from "@/lib/utils";
import type { SettleBatch } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Toolbar } from "@/components/ui/toolbar";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { FinanceCopy } from "./copy";

/**
 * 状态映射：**筛选项文案与徽标文案同一处**，改一次就够。
 *
 * <p>色调的判据是<b>「球在谁那边」</b>，不是状态好不好听：
 * 只有 BLOCKED 要人动手（warning），RECONCILED 是系统会自己放（info），
 * 其余都是过程态（muted）。
 */
function statusMap(c: FinanceCopy): StatusMap<SettleBatch["status"]> {
  return {
    DRAFT: { label: c.sbStatusDRAFT, tone: "muted" },
    COLLECTED: { label: c.sbStatusCOLLECTED, tone: "muted" },
    RECONCILING: { label: c.sbStatusRECONCILING, tone: "muted" },
    BLOCKED: { label: c.sbStatusBLOCKED, tone: "warning" },
    RECONCILED: { label: c.sbStatusRECONCILED, tone: "info" },
    RELEASED: { label: c.sbStatusRELEASED, tone: "success" },
  };
}

export function SettleBatchTab({ c, canExecute }: { c: FinanceCopy; canExecute: boolean }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState("");
  const [remark, setRemark] = useState<Record<string, string>>({});

  const batches = useQuery({
    queryKey: ["settle-batches", status],
    queryFn: () => api.listSettleBatches(status ? { status } : undefined),
  });

  const decide = useMutation({
    mutationFn: ({ batchNo, pass }: { batchNo: string; pass: boolean }) =>
      pass
        ? api.releaseSettleBatch(batchNo, remark[batchNo] ?? "")
        : api.holdSettleBatch(batchNo, remark[batchNo] ?? ""),
    onSuccess: (_r, v) => {
      qc.invalidateQueries({ queryKey: ["settle-batches"] });
      setRemark((m) => ({ ...m, [v.batchNo]: "" }));
      notify.success(c.sbToastDecided);
    },
  });

  const STATUS = statusMap(c);

  const columns: Column<SettleBatch>[] = [
    { header: c.sbColBatch, cell: (b) => b.batchNo },
    { header: c.sbColEntity, cell: (b) => b.entityNo },
    { header: c.sbColChannel, cell: (b) => b.payChannel },
    { header: c.sbColCycle, cell: (b) => b.settleCycle },
    { header: c.sbColStatus, cell: (b) => <StatusBadge map={STATUS} value={b.status} /> },
    { header: c.sbColBills, cell: (b) => b.billCount, numeric: true },
    { header: c.sbColNet, cell: (b) => money(b.netMinor), numeric: true },
    { header: c.sbColDue, cell: (b) => fmtTime(new Date(b.dueAt).toISOString()) },
    {
      /*
       * **对账覆盖面要如实标出来。** 今天只有 A 侧（我方自查），
       * 通道账单下载还没有 —— 显示成「已对账」是一句自证的话。
       */
      header: c.sbColScope,
      cell: (b) => (b.reconScope === "BOTH"
        ? <Badge tone="success">{c.sbScopeBoth}</Badge>
        : <Badge tone="warning">{c.sbScopeSelfOnly}</Badge>),
    },
    {
      header: c.sbColDecided,
      /*
       * 超时自动放行要**看得出来**：它不是异常（设计的一部分），
       * 但这个数持续出现意味着挂起时限比运营的处置能力短 ——
       * 那时要调的是时限或人手，不是把自动放行关掉。
       */
      cell: (b) => (b.decidedBy === "SYSTEM_TIMEOUT"
        ? <Badge tone="warning">{c.sbTimeoutReleased}</Badge>
        : b.decidedBy ?? "—"),
    },
  ];

  const blocked = (batches.data ?? []).filter((b) => b.status === "BLOCKED");

  return (
    <div className="space-y-4">
      {!canExecute && (
        <ReadOnlyNotice what={c.sbReadOnlyWhat} perm="finance:settle:execute" note={c.sbReadOnlyNote} />
      )}

      <Notice tone="info">{c.sbNotice}</Notice>

      <Toolbar>
        <FilterSelect
          aria-label={c.sbColStatus}
          value={status}
          onChange={setStatus}
          options={STATUS}
          allLabel={c.sbStatusAll}
        />
      </Toolbar>

      <DataTable
        columns={columns} rows={batches.data} rowKey={(b) => b.batchNo}
        loading={batches.isLoading} error={batches.error}
        onRetry={() => batches.refetch()} empty={c.sbEmpty}
      />

      {/*
        挂起队列单独一块，而不是在表里加一列按钮：
        运营来这一页九成是为了处置挂起的那几批，其余四个状态只是看看。
        混在一张表里的话，要处置的那几行要自己去找。
      */}
      {blocked.length > 0 && (
        <div className="space-y-3">
          <h3 className="txt-heading">{c.sbBlockedTitle}</h3>
          {blocked.map((b) => (
            <div key={b.batchNo} className="rounded-sheet border border-warning-line bg-warning-tint p-4">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="txt-strong">{b.batchNo} · {b.entityNo}</span>
                <span className="txt-body tabular-nums">{money(b.netMinor)} · {b.billCount} {c.sbUnitBill}</span>
              </div>
              {/*
                挂起原因**原样展示**：它是要给商家看的原话，含具体数字与阈值。
                运营在这里看到的和商家看到的是同一句 —— 客服才答得上「为什么」。
              */}
              <p className="mt-2 txt-body">{b.blockedReason}</p>
              {b.blockExpireAt && (
                <p className="mt-1 txt-caption text-muted-foreground">
                  {c.sbExpireHint} {fmtTime(new Date(b.blockExpireAt).toISOString())}
                </p>
              )}
              {canExecute && (
                <div className="mt-3 space-y-2">
                  <Label htmlFor={`remark-${b.batchNo}`}>{c.sbRemarkLabel}</Label>
                  <Input
                    id={`remark-${b.batchNo}`}
                    value={remark[b.batchNo] ?? ""}
                    onChange={(e) => setRemark((m) => ({ ...m, [b.batchNo]: e.target.value }))}
                    placeholder={c.sbRemarkPh}
                  />
                  <div className="flex gap-2">
                    {/*
                      两个按钮都**要求先写原因**（disabled 到写了为止）。
                      事后要能回答「当时凭什么放的」—— 而那句话只有此刻的人写得出来。
                    */}
                    <Button
                      disabled={!(remark[b.batchNo] ?? "").trim() || decide.isPending}
                      onClick={() => decide.mutate({ batchNo: b.batchNo, pass: true })}
                    >
                      {c.sbRelease}
                    </Button>
                    <Button
                      variant="outline"
                      disabled={!(remark[b.batchNo] ?? "").trim() || decide.isPending}
                      onClick={() => decide.mutate({ batchNo: b.batchNo, pass: false })}
                    >
                      {c.sbHold}
                    </Button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
