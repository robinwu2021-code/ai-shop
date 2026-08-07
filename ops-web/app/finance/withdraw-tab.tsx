"use client";

// 提现审批（矩阵 P-12.2.1）。
//
// 这是运营端**唯一会把钱打出去**的动作，界面上因此做了两件别的页面没做的事：
// 1. 抽屉里先摆「打款前置条件」清单（收款账户、封禁状态、余额），不满足的直接标红；
// 2. 通过后落「已通过」而不是「已打款」—— 打款结果来自渠道回执，
//    界面上根本不提供「标记已打款」，那样等于允许在钱没到账时把单子做平。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime, money } from "@/lib/utils";
import { MIN_WITHDRAW_AMOUNT, WITHDRAW_REVIEW_THRESHOLD } from "@/lib/constants";
import type { Merchant, Withdrawal, WithdrawStatus } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { FinanceCopy } from "./copy";

const useWithdrawStatusMap = (c: FinanceCopy): StatusMap<WithdrawStatus> => ({
  PENDING: { label: c.wdPending, tone: "warning" },
  APPROVED: { label: c.wdApproved, tone: "info" },
  REJECTED: { label: c.wdRejected, tone: "muted" },
  PAID: { label: c.wdPaid, tone: "success" },
  FAILED: { label: c.wdFailed, tone: "danger" },
});

export function WithdrawTab({ c, canApprove }: { c: FinanceCopy; canApprove: boolean }) {
  const qc = useQueryClient();
  const statusMap = useWithdrawStatusMap(c);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [current, setCurrent] = useState<Withdrawal | null>(null);
  const [remark, setRemark] = useState("");

  const q = { keyword, status, page, size };
  const list = useQuery({ queryKey: ["withdrawals", q], queryFn: () => api.listWithdrawals(q) });
  // 打款前置条件要看商家档案：收款账户报备与封禁状态都在那边
  const merchant = useQuery({
    queryKey: ["merchant", current?.merchantNo],
    queryFn: () => api.getMerchant(current!.merchantNo),
    enabled: !!current,
  });

  const decide = useMutation({
    mutationFn: (pass: boolean) => api.decideWithdrawal({ withdrawNo: current!.withdrawNo, pass, remark }),
    onSuccess: (w) => {
      qc.invalidateQueries({ queryKey: ["withdrawals"] });
      setCurrent(null);
      notify.success(w.status === "APPROVED" ? c.toastWdApproved : c.toastWdRejected);
    },
  });

  const columns: Column<Withdrawal>[] = [
    { header: c.colWithdrawNo, cell: (w) => w.withdrawNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (w) => w.merchantName },
    {
      header: c.colWdAmount,
      // 大额单独标出来：它走的是另一条（要写复核说明的）路径
      cell: (w) => (w.amount >= WITHDRAW_REVIEW_THRESHOLD ? <Badge tone="warning">{money(w.amount)}</Badge> : money(w.amount)),
      numeric: true,
    },
    { header: c.colAvailable, cell: (w) => money(w.availableBalance), numeric: true },
    { header: c.colBankAccount, cell: (w) => w.bankAccountMasked },
    { header: c.colWdStatus, cell: (w) => <StatusBadge map={statusMap} value={w.status} /> },
    { header: c.colAppliedAt, cell: (w) => fmtTime(w.appliedAt) },
    {
      header: c.colActions,
      cell: (w) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(w); setRemark(""); }}>
          {w.status === "PENDING" || w.status === "FAILED" ? c.actionApprove : c.actionView}
        </Button>
      ),
    },
  ];

  const m = merchant.data as Merchant | undefined;
  const decidable = !!current && (current.status === "PENDING" || current.status === "FAILED");
  // 三条前置条件在界面上先算一遍，让运营点之前就知道会不会被拒
  const overBalance = !!current && current.amount > current.availableBalance;
  const tooSmall = !!current && current.amount < MIN_WITHDRAW_AMOUNT;
  const needsReview = !!current && current.amount >= WITHDRAW_REVIEW_THRESHOLD;

  return (
    <>
      <Notice className="mb-3">{fill(c.withdrawNotice, { n: WITHDRAW_REVIEW_THRESHOLD / 100 })}</Notice>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchWithdraw}>
        <FilterSelect aria-label={c.filterWdStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }}
          options={statusMap} allLabel={c.filterWdStatusAll} />
      </Toolbar>
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(w) => w.withdrawNo}
        empty={c.emptyWithdraw}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? fill(c.withdrawTitle, { no: current.withdrawNo }) : ""}
        desc={current ? statusMap[current.status].label : undefined}
        width="w-[520px]"
        footer={
          current && canApprove && decidable ? (
            <>
              <Button variant="outline" loading={decide.isPending} onClick={() => decide.mutate(false)}>{c.btnReject}</Button>
              <Button loading={decide.isPending} onClick={() => decide.mutate(true)}>{c.btnApprove}</Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <DrawerSection first title={c.secWdOverview}>
              <FieldGrid>
                <Field className="mb-3" label={c.colMerchant}>{current.merchantName}</Field>
                <Field className="mb-3" label={c.colBankAccount}>{current.bankAccountMasked}</Field>
                <Field className="mb-3" label={c.colWdAmount}>{money(current.amount)}</Field>
                <Field className="mb-3" label={c.colAvailable}>{money(current.availableBalance)}</Field>
                <Field className="mb-3" label={c.colAppliedAt}>{fmtTime(current.appliedAt)}</Field>
                <Field className="mb-3" label={c.colDecidedAt}>
                  {current.decidedAt ? `${fmtTime(current.decidedAt)} · ${current.decidedBy}` : c.none}
                </Field>
              </FieldGrid>
            </DrawerSection>

            <DrawerSection title={c.secPrecheck}>
              {/* 前置条件清单：不满足的标红，省得点了才知道被拒 */}
              <ul className="space-y-2">
                <Precheck ok={!!m?.settleAccountReady} label={c.checkAccount} bad={c.checkAccountBad} />
                <Precheck ok={m?.status !== "SUSPENDED"} label={c.checkNotBanned} bad={c.checkNotBannedBad} />
                <Precheck ok={!overBalance} label={c.checkBalance} bad={c.checkBalanceBad} />
                <Precheck ok={!tooSmall} label={c.checkMin} bad={fill(c.checkMinBad, { n: MIN_WITHDRAW_AMOUNT / 100 })} />
              </ul>
              <p className="mt-3 txt-caption text-muted-foreground">{c.precheckHint}</p>
            </DrawerSection>

            <DrawerSection title={c.secWdRemark}>
              <Field className="mb-0" label={needsReview ? c.fieldReviewNote : c.fieldRejectReason}>
                <Textarea value={remark} onChange={setRemark} rows={3}
                  disabled={!decidable}
                  placeholder={needsReview ? c.reviewNotePlaceholder : c.rejectReasonPlaceholder} />
              </Field>
              <p className="mt-1 txt-caption text-muted-foreground">
                {needsReview ? fill(c.reviewNoteHint, { n: WITHDRAW_REVIEW_THRESHOLD / 100 }) : c.rejectReasonHint}
              </p>
              {current.remark && (
                <p className="mt-2 txt-caption text-muted-foreground">{fill(c.lastRemark, { text: current.remark })}</p>
              )}
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}

function Precheck({ ok, label, bad }: { ok: boolean; label: string; bad: string }) {
  return (
    <li className="flex items-start gap-2">
      {ok ? <Badge tone="success">✓</Badge> : <Badge tone="danger">✕</Badge>}
      <span className="txt-body">{ok ? label : bad}</span>
    </li>
  );
}
