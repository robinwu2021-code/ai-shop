"use client";

// 渠道账户详情的三块补缺（TDD-支付中心运营页面 §2.12 P6）：恢复审批的执行入口、转移（换主体）、资质版本。
// 都在渠道账户详情里；写操作都要理由，留 near-pay 审计。转移与资质激活由另一人审核，near-pay 侧强制。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { BadgeTone } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Skeleton, ErrorState } from "@/components/ui/misc";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { QualificationDetail } from "./p9-panels";
import { TrialRenew, trialChannelOf } from "./p11-panels";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import {
  QUALIFICATION_STATUSES, TRANSFER_STATUSES,
  type ChannelAccountDetail, type ChannelAccountTransfer, type Detail, type QualificationVersion,
} from "@/app/payment-center/_api/types";
import { useCopy, fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { fmtTime } from "@/lib/utils";
import { MiniTable } from "./shared";
import { CHANNEL_ACCOUNT_EXTRAS_COPY, type ChannelAccountExtrasCopy } from "./channel-account-extras-copy";

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const KEY = "pay-center";
const tr = (m: ChannelAccountExtrasCopy, k: string, fb: string) => (m as Record<string, string>)[k] ?? fb;
const Note = ({ children }: { children: React.ReactNode }) => <p className="txt-caption text-muted-foreground">{children}</p>;

function statusMap(m: ChannelAccountExtrasCopy, values: readonly string[], prefix: string, tones: Record<string, BadgeTone>): StatusMap<string> {
  return Object.fromEntries(values.map((v) => [v, { label: tr(m, `${prefix}${v}`, v), tone: tones[v] ?? "muted" }]));
}

/** near-pay 的 Instant 可能序列化成 ISO 串或毫秒数 */
const timeOf = (v: string | number | null) => (v == null ? "—" : fmtTime(typeof v === "number" ? v : Date.parse(v)));

// ── 1 恢复审批 ───────────────────────────────────────────────────────────────

/** 账户可恢复时：显示最近一张恢复审批；已通过未执行时直接执行（审批执行即调 near-pay ChannelAccountService.resume） */
export function ResumeApprovalPanel({ api, no, run, changed }: { api: PayCenterApi; no: string; run: Run; changed: () => void }) {
  const m = useCopy(CHANNEL_ACCOUNT_EXTRAS_COPY);
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const q = useQuery({
    queryKey: [KEY, "merchants", "channel-account-resume", api.source, no],
    queryFn: () => api.approvals.list({ bizType: "CHANNEL_ACCOUNT_RESUME", subjectRef: no, page: 1, size: 5 }),
  });
  if (q.isPending) return <Skeleton className="h-10 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const latest = [...q.data.records].sort((a, b) => b.createdAt - a.createdAt)[0];
  const ready = latest?.status === "APPROVED" && latest.executionStatus === "NOT_STARTED";
  const closed = latest && !ready && !latest.status.startsWith("PENDING");
  const execute = () => void confirm({
    title: fill(m.resumeExecuteTitle, { no }), desc: fill(m.resumeExecuteDesc, { approval: latest!.approvalNo }), requireReason: true, confirmText: m.resumeExecute,
    action: async (reason) => {
      const d = await api.approvals.detail(latest!.approvalNo);
      await run(() => api.approvals.execute(latest!.approvalNo, { expectedVersion: d.record.version, reason }));
      await q.refetch();
      changed();
    },
  });
  return (
    <section className="space-y-2 rounded-card border border-border p-3" aria-label={m.resumeTitle}>
      <h4 className="txt-label">{m.resumeTitle}</h4>
      <Note>
        {!latest ? m.resumeNone
          : ready ? fill(m.resumeApproved, { no: latest.approvalNo })
            : closed ? fill(m.resumeClosed, { no: latest.approvalNo, status: latest.status })
              : fill(m.resumePending, { no: latest.approvalNo, status: latest.status })}
      </Note>
      {ready && (allow("pay:channel-account:approve")
        ? <Button size="sm" onClick={execute}>{m.resumeExecute}</Button>
        : <Note>{m.resumeNoPerm}</Note>)}
      {dialog}
    </section>
  );
}

export function CloseUnavailableNote() {
  const m = useCopy(CHANNEL_ACCOUNT_EXTRAS_COPY);
  return <Note>{m.closeNote}</Note>;
}

// ── 2 转移（换主体） ──────────────────────────────────────────────────────────

export function TransfersPanel({ api, detail, run, changed }: { api: PayCenterApi; detail: Detail<ChannelAccountDetail>; run: Run; changed: () => void }) {
  const m = useCopy(CHANNEL_ACCOUNT_EXTRAS_COPY);
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const a = detail.record.account;
  const canRequest = allow("pay:channel-account:transfer");
  const canApprove = allow("pay:channel-account:transfer:approve");
  const [to, setTo] = React.useState("");
  const [evidence, setEvidence] = React.useState("");
  const map = React.useMemo(() => statusMap(m, TRANSFER_STATUSES, "st_", { PENDING_APPROVAL: "warning", APPROVED: "info", EXECUTED: "success", REJECTED: "danger" }), [m]);
  const q = useQuery({
    queryKey: [KEY, "merchants", "channel-account-transfers", api.source, a.payMerchantNo],
    queryFn: () => api.transfers.list({ payMerchantNo: a.payMerchantNo, page: 1, size: 50 }),
  });
  const rows = q.data?.records ?? [];
  const busy = rows.some((t) => t.status === "PENDING_APPROVAL" || t.status === "APPROVED");
  const target = to.trim();
  const valid = !!a.merchantNo && !!target && target !== a.merchantNo && !!evidence.trim() && !busy;
  const refresh = async () => { await q.refetch(); changed(); };

  const create = () => void confirm({
    title: fill(m.transferConfirm, { no: a.payMerchantNo }), desc: fill(m.transferConfirmDesc, { from: a.merchantNo ?? "—", to: target }), requireReason: false, confirmText: m.transferNew,
    action: async () => {
      await run(() => api.transfers.create({ payMerchantNo: a.payMerchantNo, fromMerchantNo: a.merchantNo!, toMerchantNo: target, evidence: evidence.trim(), expectedVersion: detail.version }));
      setTo(""); setEvidence("");
      await refresh();
    },
  });
  /** 通过 / 驳回用审批单版本，执行用申请版本（near-pay Action 的约定） */
  const decide = (t: ChannelAccountTransfer, kind: "approve" | "reject" | "execute") => void confirm({
    title: fill(kind === "approve" ? m.approveTitle : kind === "reject" ? m.rejectTitle : m.executeTitle, { no: t.transferNo }),
    desc: kind === "execute" ? fill(m.executeDesc, { to: t.toMerchantNo }) : undefined,
    danger: kind === "reject", requireReason: true, confirmText: kind === "approve" ? m.approve : kind === "reject" ? m.reject : m.execute,
    action: async (reason) => {
      const fresh = await api.transfers.detail(t.transferNo);
      const expectedVersion = kind === "execute" ? fresh.version : (fresh.record.approvalVersion ?? 0);
      await run(() => api.transfers[kind](t.transferNo, { expectedVersion, reason }));
      await refresh();
    },
  });

  return (
    <div className="space-y-4">
      <Note>{m.transferIntro}</Note>
      {q.isPending ? <Skeleton className="h-16 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
        <MiniTable<ChannelAccountTransfer> rows={rows} rowKey={(t) => t.transferNo} empty={m.transferEmpty} columns={[
          { header: m.colTransferNo, cell: (t) => t.transferNo },
          { header: m.colFromTo, cell: (t) => `${t.fromMerchantNo} → ${t.toMerchantNo}` },
          { header: m.colStatus, cell: (t) => <StatusBadge value={t.status} map={map} /> },
          { header: m.colEvidence, cell: (t) => <span className="txt-caption">{t.evidence}</span> },
          { header: m.colRequestedBy, cell: (t) => `${t.requestedBy ?? "—"} · ${fmtTime(t.createdAt)}` },
          { header: m.colExecuted, cell: (t) => (t.executedAt ? `${t.executedBy ?? "—"} · ${fmtTime(t.executedAt)}` : "—") },
          { header: "", cell: (t) => canApprove && (
            <div className="flex gap-1">
              {t.status === "PENDING_APPROVAL" && <Button size="sm" variant="outline" onClick={() => decide(t, "approve")}>{m.approve}</Button>}
              {t.status === "PENDING_APPROVAL" && <Button size="sm" variant="ghost" onClick={() => decide(t, "reject")}>{m.reject}</Button>}
              {t.status === "APPROVED" && <Button size="sm" onClick={() => decide(t, "execute")}>{m.execute}</Button>}
            </div>
          ) },
        ]} />
      )}
      {canRequest && a.merchantNo ? (
        <section className="space-y-2" aria-label={m.transferNew}>
          <h4 className="txt-label">{m.transferNew}</h4>
          {busy && <Notice tone="warning">{m.transferBusy}</Notice>}
          <div className="grid gap-2 sm:grid-cols-2">
            <Input aria-label={m.toMerchantNo} placeholder={m.toMerchantPh} value={to} onChange={(e) => setTo(e.target.value)} />
            <Input aria-label={m.evidence} placeholder={m.evidencePh} value={evidence} maxLength={500} onChange={(e) => setEvidence(e.target.value)} />
          </div>
          <Button size="sm" variant="outline" disabled={!valid} onClick={create}>{m.transferNew}</Button>
        </section>
      ) : !canRequest && !canApprove ? <Note>{m.noTransferPerm}</Note> : null}
      {dialog}
    </div>
  );
}

// ── 3 资质版本 ────────────────────────────────────────────────────────────────

const EMPTY_IMPORT = { evidenceNo: "", revision: "1", purpose: "", payloadJson: "", validUntil: "" };

export function QualificationsPanel({ api, detail, run }: { api: PayCenterApi; detail: Detail<ChannelAccountDetail>; run: Run }) {
  const m = useCopy(CHANNEL_ACCOUNT_EXTRAS_COPY);
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const a = detail.record.account;
  const canManage = allow("pay:channel-account:manage");
  const [open, setOpen] = React.useState<string | null>(null);
  const [form, setForm] = React.useState(EMPTY_IMPORT);
  const map = React.useMemo(() => statusMap(m, QUALIFICATION_STATUSES, "qst_", { DRAFT: "warning", ACTIVE: "success", REVOKED: "danger" }), [m]);
  const q = useQuery({
    queryKey: [KEY, "merchants", "channel-account-qualifications", api.source, a.payMerchantNo],
    queryFn: () => api.qualifications.ofAccount(a.payMerchantNo),
  });
  const jsonOk = (() => { try { JSON.parse(form.payloadJson); return true; } catch { return false; } })();
  const revision = Number(form.revision);
  const valid = /^[A-Z0-9_-]{1,32}$/.test(form.evidenceNo) && Number.isInteger(revision) && revision >= 1 && !!form.purpose.trim() && jsonOk && !!form.validUntil;

  const importVersion = () => void confirm({
    title: fill(m.qualImportTitle, { ref: `${form.evidenceNo}:${revision}` }), desc: m.qualImportDesc, requireReason: true, confirmText: m.qualImport,
    action: async (reason) => {
      await run(() => api.qualifications.importVersion({
        evidenceNo: form.evidenceNo, revision, channel: a.payChannel, purpose: form.purpose.trim(), payloadJson: form.payloadJson,
        validUntil: new Date(form.validUntil).toISOString(), reason,
      }));
      setForm(EMPTY_IMPORT);
      await q.refetch();
    },
  });
  const activate = (v: QualificationVersion) => void confirm({
    title: fill(m.qualActivateTitle, { ref: v.ref }), requireReason: true, confirmText: m.qualActivate,
    action: async (reason) => {
      await run(() => api.approvals.submit({ subjectType: "CHANNEL_QUALIFICATION", subjectRef: v.ref, subjectRevision: v.revision, action: "ACTIVATE", reason }));
      await q.refetch();
    },
  });
  const revoke = (v: QualificationVersion) => void confirm({
    title: fill(m.qualRevokeTitle, { ref: v.ref }), desc: m.qualRevokeDesc, danger: true, requireReason: true, confirmText: m.qualRevoke,
    action: async (reason) => { await run(() => api.qualifications.revoke(v.ref, reason)); await q.refetch(); },
  });

  return (
    <div className="space-y-4">
      <Note>{m.qualIntro}</Note>
      {q.isPending ? <Skeleton className="h-16 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
        <>
          <MiniTable<QualificationVersion> rows={q.data} rowKey={(v) => v.ref} empty={m.qualEmpty} columns={[
            { header: m.colRef, cell: (v) => v.ref },
            { header: m.colPurpose, cell: (v) => v.purpose },
            { header: m.colStatus, cell: (v) => <StatusBadge value={v.status} map={map} /> },
            { header: m.colValidUntil, cell: (v) => timeOf(v.validUntil) },
            { header: m.colMaker, cell: (v) => `${v.maker ?? "—"}${v.approvalNo ? ` · ${v.approvalNo}` : ""}` },
            { header: "", cell: (v) => (
              <div className="flex gap-1">
                <Button size="sm" variant="ghost" onClick={() => setOpen(open === v.ref ? null : v.ref)}>{open === v.ref ? m.qualHide : m.qualView}</Button>
                {canManage && v.status === "DRAFT" && <Button size="sm" variant="outline" onClick={() => activate(v)}>{m.qualActivate}</Button>}
                {canManage && v.status !== "REVOKED" && <Button size="sm" variant="ghost" onClick={() => revoke(v)}>{m.qualRevoke}</Button>}
              </div>
            ) },
          ]} />
          {open && (() => {
            const v = q.data.find((x) => x.ref === open);
            return v ? (
              <div className="space-y-1">
                <Note>{m.sha}: {v.payloadSha256}</Note>
                <QualificationDetail api={api} ref={v.ref} />
              </div>
            ) : null;
          })()}
        </>
      )}
      {canManage && trialChannelOf(a.payChannel) && (
        <TrialRenew api={api} payMerchantNo={a.payMerchantNo} channel={trialChannelOf(a.payChannel)!} nextRevision={Math.max(0, ...(q.data ?? []).map((x) => x.revision)) + 1} run={run} onDone={() => void q.refetch()} />
      )}
      {canManage && (
        <section className="space-y-2" aria-label={m.qualImport}>
          <h4 className="txt-label">{m.qualImport}</h4>
          <div className="grid gap-2 sm:grid-cols-2">
            <Input aria-label={m.evidenceNo} placeholder={m.evidenceNoPh} value={form.evidenceNo} onChange={(e) => setForm((f) => ({ ...f, evidenceNo: e.target.value.toUpperCase() }))} />
            <Input aria-label={m.revision} type="number" min={1} value={form.revision} onChange={(e) => setForm((f) => ({ ...f, revision: e.target.value }))} />
            <Input aria-label={m.purpose} placeholder={m.purposePh} value={form.purpose} onChange={(e) => setForm((f) => ({ ...f, purpose: e.target.value }))} />
            <Input aria-label={m.validUntil} type="datetime-local" value={form.validUntil} onChange={(e) => setForm((f) => ({ ...f, validUntil: e.target.value }))} />
          </div>
          <Textarea aria-label={m.payloadJson} placeholder={m.payloadJson} rows={5} value={form.payloadJson} onChange={(v) => setForm((f) => ({ ...f, payloadJson: v }))} />
          {form.payloadJson && !jsonOk && <Notice tone="warning">{m.jsonInvalid}</Notice>}
          <Button size="sm" variant="outline" disabled={!valid} onClick={importVersion}>{m.qualImport}</Button>
        </section>
      )}
      {dialog}
    </div>
  );
}
