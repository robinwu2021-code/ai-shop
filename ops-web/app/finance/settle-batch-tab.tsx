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
import type { Payout, SettleBatch } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Toolbar } from "@/components/ui/toolbar";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { SectionHeader } from "@/components/ui/section-header";
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

export function SettleBatchTab({ c, canExecute, canPayout }: { c: FinanceCopy; canExecute: boolean; canPayout: boolean }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState("");
  const [remark, setRemark] = useState<Record<string, string>>({});
  /*
   * 放款（V391）。**「放行」与「放款」是两件事**：放行是处置挂起（批次回到待放款），
   * 放款才让钱出去 —— 按收款号生成放款记录，再导清单去网银。
   * 两个动作两个权限码：放行 finance:settle:execute，放款 finance:payout:execute。
   */
  const [payoutStatus, setPayoutStatus] = useState("");
  const [ref, setRef] = useState<Record<string, string>>({});
  const [failReason, setFailReason] = useState<Record<string, string>>({});
  const payouts = useQuery({
    queryKey: ["payouts", payoutStatus],
    queryFn: () => api.listPayouts(payoutStatus ? { status: payoutStatus } : undefined),
  });
  const refreshBoth = () => {
    qc.invalidateQueries({ queryKey: ["settle-batches"] });
    qc.invalidateQueries({ queryKey: ["payouts"] });
  };
  const release = useMutation({
    mutationFn: (batchNo: string) => api.releaseSettleBatch(batchNo),
    onSuccess: () => { refreshBoth(); notify.success(c.sbPayoutToast); },
  });
  const pay = useMutation({
    mutationFn: (v: { payoutNo: string; paymentRef: string }) => api.payPayout(v.payoutNo, v.paymentRef),
    onSuccess: (_r, v) => {
      refreshBoth();
      setRef((m) => ({ ...m, [v.payoutNo]: "" }));
      notify.success(c.poPaidToast);
    },
  });
  const failPo = useMutation({
    mutationFn: (v: { payoutNo: string; reason: string }) => api.failPayout(v.payoutNo, v.reason),
    onSuccess: (_r, v) => {
      refreshBoth();
      setFailReason((m) => ({ ...m, [v.payoutNo]: "" }));
      notify.success(c.poFailToast);
    },
  });

  const batches = useQuery({
    queryKey: ["settle-batches", status],
    queryFn: () => api.listSettleBatches(status ? { status } : undefined),
  });

  const decide = useMutation({
    mutationFn: ({ batchNo, pass }: { batchNo: string; pass: boolean }) =>
      pass
        ? api.approveSettleBatch(batchNo, remark[batchNo] ?? "")
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
  const ready = (batches.data ?? []).filter((b) => b.status === "RECONCILED");

  /** 放款记录的状态映射：筛选项与徽标同一处。只有 FAILED 要人动手（warning），MATCHED 是闭环（success） */
  const PO_STATUS: StatusMap<Payout["status"]> = {
    PENDING: { label: c.poStatusPENDING, tone: "muted" },
    EXPORTED: { label: c.poStatusEXPORTED, tone: "info" },
    PAID: { label: c.poStatusPAID, tone: "info" },
    MATCHED: { label: c.poStatusMATCHED, tone: "success" },
    FAILED: { label: c.poStatusFAILED, tone: "warning" },
  };
  const poColumns: Column<Payout>[] = [
    { header: c.poColNo, cell: (p) => p.payoutNo },
    { header: c.poColBatch, cell: (p) => p.batchNo },
    { header: c.poColEntity, cell: (p) => p.entityNo },
    // 收款户只给户名与掩码：明文账号只在导出付款清单那一刻存在
    { header: c.poColAccount, cell: (p) => `${p.accountName ?? "—"} ${p.accountNoMasked ?? ""}` },
    { header: c.poColAmount, cell: (p) => money(p.amountMinor), numeric: true },
    { header: c.poColBills, cell: (p) => p.billCount, numeric: true },
    { header: c.poColStatus, cell: (p) => <StatusBadge map={PO_STATUS} value={p.status} /> },
    { header: c.poColRef, cell: (p) => p.paymentRef ?? "—" },
    { header: c.poColPaidAt, cell: (p) => (p.paidAt ? fmtTime(new Date(p.paidAt).toISOString()) : "—") },
  ];
  /** 还能动手的那几笔：待导出 / 已导出可登记凭证；已登记可退回 */
  const actionable = (payouts.data ?? []).filter((p) => p.status !== "MATCHED" && p.status !== "FAILED");

  return (
    <div className="space-y-4">
      {!canExecute && (
        <ReadOnlyNotice what={c.sbReadOnlyWhat} perm="finance:settle:execute" note={c.sbReadOnlyNote} />
      )}

      <HelpNote>{c.sbNotice}</HelpNote>

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
          <SectionHeader className="mb-0" title={c.sbBlockedTitle} />
          {blocked.map((b) => (
            <div key={b.batchNo} className="rounded-card bg-warning-tint p-4">
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

      {/*
        待放款：自查全过的批次在这里放款。与挂起队列分开 —— 它们是两种动作、两个权限码，
        混在一起的话「放行」与「放款」在同一块里长得一样，而只有一个真的让钱出去。
      */}
      {ready.length > 0 && (
        <div className="space-y-3">
          <SectionHeader className="mb-0" title={c.sbPayoutTitle} />
          <HelpNote>{c.sbPayoutHint}</HelpNote>
          {!canPayout && <ReadOnlyNotice what={c.sbReadOnlyPayout} perm="finance:payout:execute" />}
          {ready.map((b) => (
            <div key={b.batchNo} className="rounded-card bg-muted p-4 flex flex-wrap items-center justify-between gap-2">
              <span className="txt-strong">{b.batchNo} · {b.entityNo}</span>
              <span className="txt-body tabular-nums">{money(b.netMinor)} · {b.billCount} {c.sbUnitBill}</span>
              {canPayout && (
                <Button disabled={release.isPending} onClick={() => release.mutate(b.batchNo)}>
                  {c.sbPayoutBtn}
                </Button>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 放款记录：一笔网银转账一条。凭证号与银行流水挂在这里，不再逐张结算单回填 */}
      <div className="space-y-3">
        <SectionHeader className="mb-0" title={c.poTitle} />
        <HelpNote>{c.poHint}</HelpNote>
        <Toolbar>
          <FilterSelect
            aria-label={c.poColStatus}
            value={payoutStatus}
            onChange={setPayoutStatus}
            options={PO_STATUS}
            allLabel={c.poStatusAll}
          />
        </Toolbar>
        <DataTable
          columns={poColumns} rows={payouts.data} rowKey={(p) => p.payoutNo}
          loading={payouts.isLoading} error={payouts.error}
          onRetry={() => payouts.refetch()} empty={c.poEmpty}
        />
        {canPayout && actionable.length > 0 && (
          <div className="space-y-3">
            {actionable.map((p) => (
              <div key={p.payoutNo} className="rounded-card bg-muted p-4 space-y-2">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="txt-strong">{p.payoutNo} · {p.entityNo}</span>
                  <span className="txt-body tabular-nums">{money(p.amountMinor)} · {p.billCount} {c.sbUnitBill}</span>
                </div>
                {p.status !== "PAID" && (
                  <div className="flex flex-wrap gap-2 items-end">
                    <div className="space-y-1">
                      <Label htmlFor={`ref-${p.payoutNo}`}>{c.poColRef}</Label>
                      <Input
                        id={`ref-${p.payoutNo}`}
                        value={ref[p.payoutNo] ?? ""}
                        onChange={(e) => setRef((m) => ({ ...m, [p.payoutNo]: e.target.value }))}
                        placeholder={c.poRefPh}
                      />
                    </div>
                    {/* 凭证号必填：没有号的「已付」事后对不上银行流水，也说不清是谁付的 */}
                    <Button
                      disabled={!(ref[p.payoutNo] ?? "").trim() || pay.isPending}
                      onClick={() => pay.mutate({ payoutNo: p.payoutNo, paymentRef: (ref[p.payoutNo] ?? "").trim() })}
                    >
                      {c.poPaidBtn}
                    </Button>
                  </div>
                )}
                <div className="flex flex-wrap gap-2 items-end">
                  <div className="space-y-1">
                    <Label htmlFor={`fail-${p.payoutNo}`}>{c.poFailBtn}</Label>
                    <Input
                      id={`fail-${p.payoutNo}`}
                      value={failReason[p.payoutNo] ?? ""}
                      onChange={(e) => setFailReason((m) => ({ ...m, [p.payoutNo]: e.target.value }))}
                      placeholder={c.poFailReasonPh}
                    />
                  </div>
                  {/* 退回也要原因：退回之后批次回到待放款，下一个人要知道上次为什么没打成 */}
                  <Button
                    variant="outline"
                    disabled={!(failReason[p.payoutNo] ?? "").trim() || failPo.isPending}
                    onClick={() => failPo.mutate({ payoutNo: p.payoutNo, reason: (failReason[p.payoutNo] ?? "").trim() })}
                  >
                    {c.poFailBtn}
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
