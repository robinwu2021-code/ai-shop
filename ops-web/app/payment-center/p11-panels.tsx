"use client";

// 支付中心 P11（《支付中心-渠道专属能力运营需求》）：测试渠道试运行续期、收款入口测试单（沙箱 H5 / PayBy SGS）。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { ActionForm } from "@/components/ui/action-form";
import { Skeleton } from "@/components/ui/misc";
import { Select } from "@/components/ui/input";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import type { TestSession, TrialChannel } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCopy, fill } from "@/lib/use-copy";
import { ErrorState } from "@/components/ui/misc";
import { parseLocalDateTime, reasonField, usePc } from "./shared";
import { P11_COPY } from "./p11-copy";

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;

/** 测试渠道账户的试运行渠道：只有蓝新 / LINE Pay 的测试渠道才有续期入口 */
export function trialChannelOf(payChannel: string): TrialChannel | null {
  if (payChannel === "NEWEBPAY_TEST") return "newebpay";
  if (payChannel === "LINEPAY_TEST") return "linepay";
  return null;
}

/** 续期试运行：先取准备信息（账户版本、可选产品、开通证据），再建草稿；生效走「渠道资质」审批 */
export function TrialRenew({ api, payMerchantNo, channel, nextRevision, run, onDone }: {
  api: PayCenterApi; payMerchantNo: string; channel: TrialChannel; nextRevision: number; run: Run; onDone: () => void;
}) {
  const c = usePc();
  const m = useCopy(P11_COPY);
  const [open, setOpen] = React.useState(false);
  const prep = useQuery({ queryKey: ["pay-center", "trial-prep", api.source, payMerchantNo, channel], queryFn: () => api.trials.preparation(payMerchantNo, channel), enabled: open });
  if (!open) return <Button size="sm" variant="outline" onClick={() => setOpen(true)}>{m.trialRenew}</Button>;
  const line = channel === "linepay";
  return (
    <section className="space-y-3 rounded-card border border-border p-3" aria-label={m.trialRenewTitle}>
      <h4 className="txt-label">{m.trialRenewTitle}</h4>
      <Notice tone="info">{m.trialRenewNote}</Notice>
      {prep.isPending ? <Skeleton className="h-16 w-full" /> : prep.error ? <ErrorState error={prep.error} onRetry={() => void prep.refetch()} /> : prep.data.products.length === 0 ? <Notice tone="warning">{m.trialNoProducts}</Notice> : (
        <>
          <dl className="grid grid-cols-[7rem_1fr] gap-x-4 gap-y-1 txt-body">
            <dt className="text-muted-foreground">{m.trialAccountVersion}</dt><dd>{prep.data.accountVersion}</dd>
            <dt className="text-muted-foreground">{m.trialProducts}</dt><dd>{prep.data.products.join("、")}</dd>
            <dt className="text-muted-foreground">{m.trialEnablement}</dt><dd className="break-all">{prep.data.enablementEvidence}</dd>
          </dl>
          <ActionForm notify={false} draft={null}
            fields={[
              { key: "productCode", label: m.productCode, type: "select", required: true, options: prep.data.products.map((p) => ({ value: p, label: p })) },
              { key: "entry", label: m.entry, type: "select", required: true, options: [{ value: "H5", label: "H5" }, { value: "API", label: "API" }] },
              { key: "evidenceNo", label: m.evidenceNo, required: true, maxLength: 32, pattern: { re: "^[A-Z0-9_-]{1,32}$", msg: "A-Z 0-9 _ -" } },
              { key: "revision", label: m.revision, type: "number", required: true, min: 1, help: m.revisionHelp },
              { key: "protocolEvidence", label: m.protocolEvidence, required: true, maxLength: 255 },
              { key: "returnUrl", label: line ? m.confirmUrl : m.returnUrl, required: true, maxLength: 200, pattern: { re: "^https://.+", msg: "https://" } },
              { key: "notifyUrl", label: line ? m.cancelUrl : m.notifyUrl, required: true, maxLength: 200, help: line ? m.lineUrlHelp : m.newebNotifyHelp, pattern: { re: "^https://.+", msg: "https://" } },
              { key: "validUntil", label: m.validUntil, required: true, help: m.validUntilHelp, pattern: { re: "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$", msg: "YYYY-MM-DD HH:mm" } },
              reasonField(c),
            ]}
            initial={{ productCode: prep.data.products[0], entry: "H5", evidenceNo: "", revision: nextRevision, protocolEvidence: "", returnUrl: "", notifyUrl: "", validUntil: "", reason: "" }}
            onSubmit={(v) => run(() => api.trials.create(payMerchantNo, channel, {
              expectedVersion: prep.data!.accountVersion, evidenceNo: String(v.evidenceNo).trim(), revision: Number(v.revision), productCode: String(v.productCode),
              entry: v.entry === "API" ? "API" : "H5", protocolEvidence: String(v.protocolEvidence).trim(), returnUrl: String(v.returnUrl).trim(), notifyUrl: String(v.notifyUrl).trim(),
              validUntil: new Date(parseLocalDateTime(v.validUntil)).toISOString(), reason: String(v.reason),
            }), m.submitApprovalHint)}
            onDone={() => { setOpen(false); onDone(); }} />
        </>
      )}
    </section>
  );
}

/** 入口只含测试产品：沙箱测试链接（产品码约定带 _TEST；以 near-pay 校验为准） */
export const isSandboxEntry = (codes: string[]) => codes.length > 0 && codes.every((x) => x.includes("_TEST")) && !isPaybyTestEntry(codes);
/** PayBy SGS 测试入口：产品只能是 PAYBY_SGS_H5_TEST（near-pay OpsPayBySgsAcceptanceController） */
export const isPaybyTestEntry = (codes: string[]) => codes.length === 1 && codes[0] === "PAYBY_SGS_H5_TEST";

/** 生成测试单：选有效期（≤30 分钟），生成后显示单号、收银台链接、到期时间 */
export function TestSessionPanel({ api, entryNo, kind, run }: { api: PayCenterApi; entryNo: string; kind: "sandbox" | "payby"; run: Run }) {
  const m = useCopy(P11_COPY);
  const [minutes, setMinutes] = React.useState(15);
  const [busy, setBusy] = React.useState(false);
  const [session, setSession] = React.useState<TestSession | null>(null);
  const generate = async () => {
    setBusy(true);
    try {
      const expiresAt = Date.now() + minutes * 60_000;
      setSession(await run(() => (kind === "payby" ? api.testSessions.payby(entryNo, { expiresAt }) : api.testSessions.sandbox(entryNo, { expiresAt }))));
    } catch { /* run 已提示 */ } finally { setBusy(false); }
  };
  return (
    <div className="space-y-4">
      <Notice tone="info">{kind === "payby" ? m.paybySessionNote : m.sandboxSessionNote}</Notice>
      <div className="flex items-end gap-2">
        <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{m.sessionMinutes}</span>
          <Select value={String(minutes)} onChange={(e) => setMinutes(Number(e.target.value))}>
            {[5, 15, 30].map((n) => <option key={n} value={n}>{fill(m.minutesN, { n })}</option>)}
          </Select></label>
        <Button onClick={() => void generate()} loading={busy}>{m.generate}</Button>
      </div>
      {session && (
        <dl className="grid grid-cols-[7rem_1fr] gap-x-4 gap-y-2 txt-body">
          <dt className="text-muted-foreground">{m.payOrderNo}</dt><dd className="font-mono">{session.payOrderNo}</dd>
          <dt className="text-muted-foreground">{m.checkoutUrl}</dt>
          <dd className="break-all"><a className="focus-ring text-primary-ink underline" href={session.checkoutUrl} target="_blank" rel="noopener noreferrer">{session.checkoutUrl}</a></dd>
          <dt className="text-muted-foreground">{m.expireAt}</dt><dd>{fmtTime(session.expireAt)}</dd>
        </dl>
      )}
    </div>
  );
}
