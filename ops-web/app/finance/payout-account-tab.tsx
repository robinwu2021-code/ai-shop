"use client";

// 供应商收款账户审核 —— 接 `/ops/payout-accounts`（V358，ADR-011 自营供应商模式）。
//
// **这一页决定的是钱的去向**：通过一张卡，这个主体下一期的货款就打到它上面。
// 所以它与「登记付款」同一个权限码（finance:payout:execute），
// 也所以后端在审核时写 critical 审计。
//
// 界面上要让两件事看得见：
// ① 通过会顶替旧卡 —— 同主体只有一张能收钱，这条规则在列表里能直接看到
//    （生效中那张就在同一个主体名下）；
// ② 驳回必须写原因 —— 输入框不填就点不动，与后端同一套判据，
//    而不是给一个点了报错的按钮。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayoutAccount } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { HelpNote } from "@/components/ui/help-note";
import { SectionHeader } from "@/components/ui/section-header";
import type { FinanceCopy } from "./copy";

export function PayoutAccountTab({ c, canAudit }: { c: FinanceCopy; canAudit: boolean }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState<string>("PENDING");
  const [rejecting, setRejecting] = useState<string | null>(null);
  const [remark, setRemark] = useState("");

  const list = useQuery({
    queryKey: ["payout-accounts", status],
    queryFn: () => api.listPayoutAccounts(status ? { status } : {}),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["payout-accounts"] });

  const audit = useMutation({
    mutationFn: (v: { no: string; pass: boolean; remark?: string }) =>
      api.auditPayoutAccount(v.no, v.pass, v.remark),
    onSuccess: () => { setRejecting(null); setRemark(""); refresh(); },
  });

  const rows = list.data?.records ?? [];
  const pendingCount = rows.filter((r) => r.status === "PENDING").length;

  const columns: Column<PayoutAccount>[] = [
    {
      header: c.paColAccount,
      cell: (r) => (
        <div>
          {/* 账号只有掩码 —— 后端不回明文，这一列永远看不到全号 */}
          <div className="font-mono txt-body">{r.accountMasked}</div>
          <div className="txt-caption text-muted-foreground">
            {c[`payoutType_${r.accountType}` as keyof FinanceCopy] ?? r.accountType}
          </div>
        </div>
      ),
      width: "11rem",
    },
    {
      header: c.paColHolder,
      cell: (r) => (
        <div>
          <div className="txt-body">{r.accountName}</div>
          <div className="txt-caption text-muted-foreground font-mono">{r.entityNo}</div>
        </div>
      ),
      width: "14rem",
    },
    {
      header: c.paColBank,
      cell: (r) => (r.bankName
        ? <span className="txt-body">{r.bankName}{r.bankBranch ? ` · ${r.bankBranch}` : ""}</span>
        : <span className="text-muted-foreground">—</span>),
      width: "13rem",
    },
    {
      header: c.paColStatus,
      cell: (r) => (
        <div>
          <Badge tone={r.status === "ACTIVE" ? "default"
            : r.status === "PENDING" ? "warning"
              : r.status === "REJECTED" ? "danger" : "muted"}>
            {c[`payoutStatus_${r.status}` as keyof FinanceCopy] ?? r.status}
          </Badge>
          {/* 驳回原因原样展示 —— 它是回给商家的那句话，运营要能复看 */}
          {r.auditRemark && (
            <div className="mt-0.5 txt-caption text-muted-foreground">{r.auditRemark}</div>
          )}
        </div>
      ),
      width: "16rem",
    },
    {
      header: "",
      cell: (r) => {
        if (!canAudit || r.status !== "PENDING") return null;
        if (rejecting === r.accountNo) {
          return (
            <div className="flex items-center gap-1.5">
              <input
                className="focus-ring h-[calc(var(--ctl-h)-4px)] w-48 rounded-input border border-border bg-background px-1.5 txt-caption"
                placeholder={c.paRejectPlaceholder}
                value={remark}
                onChange={(e) => setRemark(e.target.value)}
              />
              {/* 不填原因点不动 —— 与后端同一套判据 */}
              <Button size="sm" variant="secondary"
                disabled={!remark.trim() || audit.isPending}
                onClick={() => audit.mutate({ no: r.accountNo, pass: false, remark: remark.trim() })}>
                {c.paRejectConfirm}
              </Button>
              <Button size="sm" variant="ghost"
                onClick={() => { setRejecting(null); setRemark(""); }}>
                {c.cancel}
              </Button>
            </div>
          );
        }
        return (
          <div className="flex items-center gap-1.5">
            <Button size="sm" disabled={audit.isPending}
              onClick={() => audit.mutate({ no: r.accountNo, pass: true })}>
              {c.paApprove}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setRejecting(r.accountNo)}>
              {c.paReject}
            </Button>
          </div>
        );
      },
      width: "22rem",
    },
  ];

  return (
    <>
      <SectionHeader title={c.paTitle}
        summary={c.paSummary.replace("{n}", String(pendingCount))} />
      <HelpNote className="mb-3">{c.paNotice}</HelpNote>

      <div className="mb-3 flex gap-1.5">
        {["PENDING", "ACTIVE", "REJECTED", "DISABLED", ""].map((s) => (
          <button key={s || "all"} type="button" onClick={() => setStatus(s)}
            className={`focus-ring rounded-chip border px-2.5 py-1 txt-caption ${
              status === s ? "border-foreground bg-foreground text-background" : "border-border hover:bg-muted"
            }`}>
            {s ? (c[`payoutStatus_${s}` as keyof FinanceCopy] ?? s) : c.all}
          </button>
        ))}
      </div>

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(r) => r.accountNo}
        empty={c.paEmpty}
      />
    </>
  );
}
