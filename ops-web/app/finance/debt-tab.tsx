"use client";

// 商家欠款（Z4 追偿第二层）。
//
// **与保证金方向相反，所以不合成一个数看**：保证金是商家的钱（平台代管、将来要退还），
// 欠款是商家欠平台的。合起来看的话，「应退还多少保证金」就永远算不清了 ——
// 而那是退店结账时必须给出的数。
//
// 这一页只有一个能动手的动作：**用保证金抵扣（人工）**。
// 货款抵扣是自动的，不在这里按。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fmtTime, money } from "@/lib/utils";
import type { DebtTxn } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { Notice } from "@/components/ui/notice";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Toolbar } from "@/components/ui/toolbar";
import type { FinanceCopy } from "./copy";

export function DebtTab({ c, canExecute }: { c: FinanceCopy; canExecute: boolean }) {
  const qc = useQueryClient();
  const [entityNo, setEntityNo] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");

  const debt = useQuery({
    queryKey: ["merchant-debt", entityNo],
    queryFn: () => api.merchantDebt(entityNo),
    // 主体号是必填的：没有它这一页无从查起，而空查一次只会拿回一个假的「0 欠款」
    enabled: !!entityNo.trim(),
  });

  const offset = useMutation({
    mutationFn: () => api.offsetDebtByDeposit(entityNo, Number(amount) * 100, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["merchant-debt"] });
      setAmount("");
      setReason("");
      notify.success(c.dbToastOffset);
    },
  });

  const columns: Column<DebtTxn>[] = [
    {
      header: c.dbColType,
      cell: (t) => <Badge tone={t.txnType === "INCUR" ? "warning" : "success"}>
        {c[`dbType${t.txnType}` as keyof FinanceCopy] as string}
      </Badge>,
    },
    {
      header: c.dbColAmount,
      numeric: true,
      /*
       * **带符号显示**：产生为正、偿还为负。
       * 都显示成正数的话，一列数字里看不出哪笔是欠、哪笔是还 ——
       * 而这一页存在的理由就是让人看得懂这本账。
       */
      cell: (t) => (
        <span className={t.amountMinor > 0 ? "text-warning" : "text-success"}>
          {t.amountMinor > 0 ? "+" : "−"}{money(Math.abs(t.amountMinor))}
        </span>
      ),
    },
    { header: c.dbColAfter, cell: (t) => money(t.balanceAfterMinor), numeric: true },
    {
      // 指不出源头的欠款没法向商家解释 —— 这一列空着就是个信号
      header: c.dbColSource,
      cell: (t) => t.sourceNo ?? t.batchNo ?? "—",
    },
    { header: c.dbColReason, cell: (t) => t.reason ?? "—" },
    { header: c.dbColAt, cell: (t) => fmtTime(new Date(t.at).toISOString()) },
  ];

  return (
    <div className="space-y-4">
      {!canExecute && (
        <ReadOnlyNotice what={c.dbReadOnlyWhat} perm="finance:payout:execute" note={c.dbReadOnlyNote} />
      )}

      <HelpNote>{c.dbNotice}</HelpNote>

      <Toolbar>
        <Input
          aria-label={c.dbEntityLabel}
          value={entityNo}
          onChange={(e) => setEntityNo(e.target.value.trim())}
          placeholder={c.dbEntityPh}
        />
      </Toolbar>

      {!entityNo.trim() ? (
        <Notice>{c.dbPickFirst}</Notice>
      ) : (
        <>
          <div className="rounded-sheet border border-line bg-surface p-4">
            <span className="txt-caption text-muted-foreground">{c.dbBalance}</span>
            <p className="txt-display tabular-nums">{money(debt.data?.balanceMinor ?? 0)}</p>
            {debt.data && debt.data.balanceMinor === 0 && (
              // 空态要说人话：绝大多数商家从没欠过，这是常态不是异常
              <p className="mt-1 txt-caption text-muted-foreground">{c.dbNoDebt}</p>
            )}
          </div>

          {canExecute && (debt.data?.balanceMinor ?? 0) > 0 && (
            <div className="rounded-sheet border border-line bg-surface p-4 space-y-3">
              <h3 className="txt-heading">{c.dbOffsetTitle}</h3>
              {/*
                这段说明不能省：它是**人工而非自动**的理由。
                写清楚之后，下一个人才不会顺手把它接进自动追偿链路。
              */}
              <p className="txt-body text-muted-foreground">{c.dbOffsetWhy}</p>
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <Label htmlFor="offset-amount">{c.dbOffsetAmount}</Label>
                  <Input
                    id="offset-amount" inputMode="decimal"
                    value={amount} onChange={(e) => setAmount(e.target.value)}
                    placeholder={c.dbOffsetAmountPh}
                  />
                </div>
                <div>
                  <Label htmlFor="offset-reason">{c.dbOffsetReason}</Label>
                  <Input
                    id="offset-reason" value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    placeholder={c.dbOffsetReasonPh}
                  />
                </div>
              </div>
              <p className="txt-caption text-muted-foreground">{c.dbOffsetCapHint}</p>
              <Button
                disabled={!Number(amount) || !reason.trim() || offset.isPending}
                loading={offset.isPending}
                onClick={() => offset.mutate()}
              >
                {c.dbOffsetSubmit}
              </Button>
            </div>
          )}

          <DataTable
            columns={columns} rows={debt.data?.txns} rowKey={(t) => t.txnNo}
            loading={debt.isLoading} error={debt.error}
            onRetry={() => debt.refetch()} empty={c.dbEmptyTxns}
          />
        </>
      )}
    </div>
  );
}
