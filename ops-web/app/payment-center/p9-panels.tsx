"use client";

// 支付中心 P9 的嵌入式面板（TDD-支付中心运营页面 §2.15）：渠道账户改坐标、沙箱试运行资质准备与详情、终端凭证引用与退役、应用撤销门店授权。
// 每个面板只做一件事，挂在已有详情里；写操作走 run（统一提示与刷新）。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { ActionForm } from "@/components/ui/action-form";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { DetailBody } from "@/components/ui/detail-drawer";
import { Skeleton } from "@/components/ui/misc";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { ACCOUNT_MODES, FUNDS_PATHS, type AppGrant, type ChannelAccountRow } from "@/app/payment-center/_api/types";
import { useCopy, fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { DetailLoader, options, parseLocalDateTime, reasonField, usePc } from "./shared";
import { P9_COPY, type P9Copy } from "./p9-copy";

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const label = (m: P9Copy, prefix: string, v: string | null | undefined) => (v ? (m as unknown as Record<string, string>)[`${prefix}_${v}`] ?? v : "—");
const blank = (v: unknown) => { const s = String(v ?? "").trim(); return s ? s : null; };

/** 渠道账户改坐标（allowedActions 含 update，即 UNVERIFIED）。渠道商户号详情里是脱敏的，必须重填。 */
export function ChannelAccountEdit({ api, account, version, run, onDone }: { api: PayCenterApi; account: ChannelAccountRow; version: number; run: Run; onDone: () => void }) {
  const c = usePc();
  const m = useCopy(P9_COPY);
  const [open, setOpen] = React.useState(false);
  if (!open) return <Button size="sm" variant="outline" onClick={() => setOpen(true)}>{m.accountEdit}</Button>;
  return (
    <section className="space-y-3 rounded-card border border-border p-3" aria-label={m.accountEditTitle}>
      <h4 className="txt-label">{m.accountEditTitle}</h4>
      <Notice tone="info">{m.accountEditNote}</Notice>
      <ActionForm notify={false} draft={null /* 含渠道商户号等，不存本地草稿 */}
        fields={[
          { key: "channelMerchantId", label: m.channelMerchantId, required: true, maxLength: 64 },
          { key: "subMerchantId", label: m.subMerchantId, maxLength: 64 },
          { key: "accountMode", label: m.accountMode, type: "select", required: true, options: options(ACCOUNT_MODES, (v) => label(m, "mode", v)) },
          { key: "fundsPath", label: m.fundsPath, type: "select", required: true, options: options(FUNDS_PATHS, (v) => label(m, "path", v)) },
          { key: "parentPayMerchantNo", label: m.parentPayMerchantNo, maxLength: 64, help: m.parentHelp, disabledWhen: (v) => v.accountMode === "MERCHANT_OWN" },
          { key: "evidenceRef", label: m.evidenceRef, maxLength: 255, help: m.evidenceHelp, pattern: { re: "^(file:[\\w./:#@-]{1,250})?$", msg: "file:..." } },
          reasonField(c),
        ]}
        initial={{ channelMerchantId: "", subMerchantId: "", accountMode: account.accountMode ?? "MERCHANT_OWN", fundsPath: account.fundsPath ?? "DIRECT", parentPayMerchantNo: account.parentPayMerchantNo ?? "", evidenceRef: account.evidenceRef ?? "", reason: "" }}
        onSubmit={(v) => run(() => api.channelAccounts.update(account.payMerchantNo, {
          expectedVersion: version, channelMerchantId: String(v.channelMerchantId).trim(), subMerchantId: blank(v.subMerchantId),
          accountMode: String(v.accountMode), fundsPath: String(v.fundsPath),
          parentPayMerchantNo: v.accountMode === "MERCHANT_OWN" ? null : blank(v.parentPayMerchantNo), evidenceRef: blank(v.evidenceRef), reason: String(v.reason),
        }))}
        onDone={() => { setOpen(false); onDone(); }} />
    </section>
  );
}

/** 沙箱试运行资质准备（只 NEWEBPAY_TEST 账户）：建 DRAFT，经「渠道资质」审批生效 */
export function QualificationPrepare({ api, payMerchantNo, nextRevision, run, onDone }: { api: PayCenterApi; payMerchantNo: string; nextRevision: number; run: Run; onDone: () => void }) {
  const c = usePc();
  const m = useCopy(P9_COPY);
  const [open, setOpen] = React.useState(false);
  if (!open) return <Button size="sm" variant="outline" onClick={() => setOpen(true)}>{m.qualPrepare}</Button>;
  return (
    <section className="space-y-3 rounded-card border border-border p-3" aria-label={m.qualPrepareTitle}>
      <h4 className="txt-label">{m.qualPrepareTitle}</h4>
      <Notice tone="info">{m.qualPrepareNote}</Notice>
      <ActionForm notify={false} draft={null /* 含渠道商户号等，不存本地草稿 */}
        fields={[
          { key: "evidenceNo", label: m.evidenceNo, required: true, maxLength: 32, pattern: { re: "^[A-Z0-9_-]{1,32}$", msg: "A-Z 0-9 _ -" } },
          { key: "revision", label: m.revision, type: "number", required: true, min: 1, help: m.revisionHelp },
          { key: "protocolEvidence", label: m.protocolEvidence, required: true, maxLength: 255 },
          { key: "trialEvidence", label: m.trialEvidence, required: true, maxLength: 255, help: m.trialHelp, pattern: { re: "^sandbox-trial:.+", msg: "sandbox-trial:..." } },
          { key: "enablementEvidence", label: m.enablementEvidence, required: true, maxLength: 255 },
          { key: "returnUrl", label: m.returnUrl, required: true, maxLength: 200, pattern: { re: "^https://.+", msg: "https://" } },
          { key: "notifyUrl", label: m.notifyUrl, required: true, maxLength: 200, help: m.notifyHelp, pattern: { re: "^https://.+", msg: "https://" } },
          { key: "validUntil", label: m.validUntil, required: true, help: m.validUntilHelp, pattern: { re: "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$", msg: "YYYY-MM-DD HH:mm" } },
          reasonField(c),
        ]}
        initial={{ evidenceNo: "", revision: nextRevision, protocolEvidence: "", trialEvidence: "sandbox-trial:", enablementEvidence: "", returnUrl: "", notifyUrl: "", validUntil: "", reason: "" }}
        onSubmit={(v) => run(() => api.qualifications.prepare(payMerchantNo, {
          evidenceNo: String(v.evidenceNo).trim(), revision: Number(v.revision), productCode: "NEWEBPAY_CARD_ONETIME",
          protocolEvidence: String(v.protocolEvidence).trim(), trialEvidence: String(v.trialEvidence).trim(), enablementEvidence: String(v.enablementEvidence).trim(),
          returnUrl: String(v.returnUrl).trim(), notifyUrl: String(v.notifyUrl).trim(), validUntil: new Date(parseLocalDateTime(v.validUntil)).toISOString(), reason: String(v.reason),
        }))}
        onDone={() => { setOpen(false); onDone(); }} />
    </section>
  );
}

/** 资质详情：坐标与资质内容（GET /channel-qualifications/{ref}） */
export function QualificationDetail({ api, ref }: { api: PayCenterApi; ref: string }) {
  const m = useCopy(P9_COPY);
  return (
    <DetailLoader name="merchants" id={`qualification:${ref}`} load={() => api.qualifications.detail(ref)}>
      {(q) => <DetailBody sections={[
        { title: m.qualCoordinates, content: <pre className="whitespace-pre-wrap break-all txt-caption">{JSON.stringify(q.coordinates ?? {}, null, 2)}</pre> },
        { title: m.qualPayload, content: <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all txt-caption">{prettyJson(q.payloadJson)}</pre> },
      ]} />}
    </DetailLoader>
  );
}
export function prettyJson(s: string | null | undefined): string {
  if (!s) return "—";
  try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; }
}

/** 终端详情里的「凭证与退役」：凭证引用需要 pay:credential:view，没有就只提示，不发请求 */
export function TerminalCredRetire({ api, no }: { api: PayCenterApi; no: string }) {
  const m = useCopy(P9_COPY);
  const allow = useCan();
  const canCred = allow("pay:credential:view");
  const retire = useQuery({ queryKey: ["pay-center", "terminals", "retirement", api.source, no], queryFn: () => api.terminals.retirement(no) });
  const cred = useQuery({ queryKey: ["pay-center", "terminals", "credential-ref", api.source, no], queryFn: () => api.terminals.credentialReference(no), enabled: canCred });
  if (retire.isPending || (canCred && cred.isPending)) return <Skeleton className="h-10 w-full" />;
  const rows: [string, string][] = [
    [m.retirementStatus, retire.data ? label(m, "retire", retire.data.retirementStatus) : "—"],
    [m.credentialRef, !canCred ? m.credNoPerm : cred.data?.credentialRef ?? m.noCredential],
    [m.keyId, canCred && cred.data?.keyId != null ? String(cred.data.keyId) : "—"],
  ];
  return <dl className="grid grid-cols-[8rem_1fr] gap-x-4 gap-y-2 txt-body">{rows.map(([k, v]) => <React.Fragment key={k}><dt className="text-muted-foreground">{k}</dt><dd className="break-all">{v}</dd></React.Fragment>)}</dl>;
}

/** 应用门店授权的「撤销」：expectedVersion 是授权自己的版本；已撤销的不显示 */
export function GrantRevokeButton({ api, grant, run }: { api: PayCenterApi; grant: AppGrant; run: Run }) {
  const c = usePc();
  const m = useCopy(P9_COPY);
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  if (grant.status === "REVOKED" || !allow("pay:app:manage")) return null;
  return (
    <>
      <Button size="sm" variant="ghost" onClick={() => void confirm({
        title: fill(m.grantRevokeTitle, { store: grant.storeNo }), desc: m.grantRevokeDesc, danger: true, requireReason: true, reasonHint: c.reasonHint,
        action: async (reason) => { await run(() => api.apps.revokeGrant(grant.appNo, grant.storeNo, { expectedVersion: grant.version, reason })); },
      })}>{m.grantRevoke}</Button>
      {dialog}
    </>
  );
}
