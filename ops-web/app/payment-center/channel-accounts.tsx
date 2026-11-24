"use client";

// 支付中心 · 渠道账户（商户行操作抽屉）与门店渠道绑定（门店行操作抽屉）（TDD-支付中心运营页面 §2.11 P5）。
// 口径同 near-pay ops-web features/channel-accounts：列表 ↔ 账户详情在同一抽屉内切换（不嵌套抽屉）；核验与恢复只「提交审批」，
// 批与驳在审核待办；换绑执行同理。幂等号由客户端按路径 + 请求体生成（§2.10），不照搬「恢复上次操作」。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { BadgeTone } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Tabs } from "@/components/ui/tabs";
import { Skeleton, ErrorState } from "@/components/ui/misc";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { DetailBody } from "@/components/ui/detail-drawer";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import { PayOpsError } from "@/app/payment-center/_api/client";
import { notify } from "@/lib/notify";
import {
  ACCOUNT_STATUSES, CLAIM_STATES, FUNDS_PATHS, VERIFICATION_STATUSES,
  type BindingImpact, type ChannelAccountClaim, type ChannelAccountDetail, type ChannelAccountRow, type Detail, type FundsPath, type StoreBinding,
} from "@/app/payment-center/_api/types";
import { useCopy, fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { P8_COPY } from "./p8-copy";
import { ChannelAccountEdit } from "./p9-panels";
import { fmtTime } from "@/lib/utils";
import { MiniTable, errorText, usePc } from "./shared";
import { CHANNEL_ACCOUNTS_COPY, type ChannelAccountsCopy } from "./channel-accounts-copy";
import { CHANNEL_ACCOUNT_EXTRAS_COPY } from "./channel-account-extras-copy";
import { CloseUnavailableNote, QualificationsPanel, ResumeApprovalPanel, TransfersPanel } from "./channel-account-extras";

export const useCac = () => useCopy(CHANNEL_ACCOUNTS_COPY);
type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const KEY = "pay-center";
const tr = (m: ChannelAccountsCopy, k: string, fb: string) => (m as Record<string, string>)[k] ?? fb;
const SELECT = "h-[var(--ctl-h)] rounded-control border border-border bg-background px-2 txt-body";

/** 密钥形态（near-pay KEY_CHANNELS）：没有的渠道不出表单 */
export const KEY_CHANNELS: Record<string, "composite" | "secret" | "privateKey"> = {
  NEWEBPAY: "composite", LINEPAY: "secret", JKOPAY: "secret", MOCKPAY: "secret", PAYBY: "privateKey",
};

/** 录入的密钥是否齐（蓝新 32 + 16 位；PayBy 私钥 + 公钥 PEM；其余 secret ≥16 位） */
export function keyReady(shape: "composite" | "secret" | "privateKey", v: { secret: string; hashKey: string; hashIv: string; publicMaterial: string }): boolean {
  if (shape === "composite") return v.hashKey.length === 32 && v.hashIv.length === 16;
  if (shape === "privateKey") return v.secret.includes("PRIVATE KEY") && v.publicMaterial.includes("BEGIN");
  return v.secret.length >= 16;
}

/** 门店可直接绑定的账户：本商户已核验、可收款，且该渠道在门店上还没有生效绑定（near-pay StoreBindingsTab） */
export function bindableAccounts(accounts: ChannelAccountRow[], bindings: StoreBinding[]): ChannelAccountRow[] {
  const active = bindings.filter((b) => b.status === "ACTIVE");
  return accounts.filter((a) => a.verificationStatus === "VERIFIED" && a.status === "ACTIVE" && !active.some((b) => b.payChannel === a.payChannel));
}

function useMaps(m: ChannelAccountsCopy) {
  return React.useMemo(() => {
    const map = (values: readonly string[], prefix: string, tones: Record<string, BadgeTone>): StatusMap<string> =>
      Object.fromEntries(values.map((v) => [v, { label: tr(m, `${prefix}${v}`, v), tone: tones[v] ?? "muted" }]));
    return {
      account: map(ACCOUNT_STATUSES, "st_acc_", { ACTIVE: "success", REFUND_ONLY: "warning", SUSPENDED: "danger", CLOSED: "muted", UNVERIFIED: "info" }),
      verify: map(VERIFICATION_STATUSES, "st_ver_", { VERIFIED: "success", REJECTED: "danger", PENDING: "warning" }),
      binding: map(["ACTIVE", "INACTIVE"], "st_bind_", { ACTIVE: "success" }),
      claim: map(CLAIM_STATES, "st_claim_", { PENDING_VERIFY: "warning", LINKED: "success", REJECTED: "danger" }),
    };
  }, [m]);
}

const Note = ({ children }: { children: React.ReactNode }) => <p className="txt-caption text-muted-foreground">{children}</p>;

// ───────────────────────── 商户 · 渠道账户 ─────────────────────────

export function ChannelAccountsPanel({ api, merchantNo, run }: { api: PayCenterApi; merchantNo: string; run: Run }) {
  const [selected, setSelected] = React.useState<string | null>(null);
  const [prefill, setPrefill] = React.useState<Partial<RegisterForm> | null>(null);
  if (selected) return <AccountDetailView api={api} no={selected} run={run} back={() => setSelected(null)} />;
  return <AccountList api={api} merchantNo={merchantNo} run={run} open={setSelected} prefill={prefill} onPrefill={setPrefill} />;
}

interface RegisterForm { payChannel: string; channelMerchantId: string; subMerchantId: string; fundsPath: FundsPath; evidenceRef: string }
const EMPTY_FORM: RegisterForm = { payChannel: "", channelMerchantId: "", subMerchantId: "", fundsPath: "DIRECT", evidenceRef: "" };

function AccountList({ api, merchantNo, run, open, prefill, onPrefill }: {
  api: PayCenterApi; merchantNo: string; run: Run; open: (no: string) => void; prefill: Partial<RegisterForm> | null; onPrefill: (p: Partial<RegisterForm>) => void;
}) {
  const m = useCac();
  const maps = useMaps(m);
  const allow = useCan();
  const canManage = allow("pay:channel-account:manage");
  const { confirm, dialog } = useConfirm();
  const q = useQuery({ queryKey: [KEY, "merchants", "channel-accounts", api.source, merchantNo], queryFn: () => api.channelAccounts.list({ merchantNo, size: 100 }) });
  const choices = useQuery({ queryKey: [KEY, "merchants", "channel-choices", api.source, merchantNo], queryFn: () => api.merchants.channels(merchantNo), enabled: canManage, retry: false });
  const [form, setForm] = React.useState<RegisterForm>({ ...EMPTY_FORM, ...prefill });
  React.useEffect(() => { if (prefill) setForm((f) => ({ ...f, ...prefill })); }, [prefill]);
  const channels = choices.data?.channels ?? [];
  const nameOf = (code: string) => channels.find((c) => c.payChannel === code)?.channelName ?? code;
  const valid = !!form.payChannel && !!form.channelMerchantId.trim() && !!form.evidenceRef.trim();
  const register = () => void confirm({
    title: fill(m.registerConfirm, { merchant: merchantNo, channel: nameOf(form.payChannel) }), desc: m.registerDesc, requireReason: true, confirmText: m.register,
    action: async (reason) => {
      const d = await run(() => api.channelAccounts.register({
        ownerType: "MERCHANT", merchantNo, payChannel: form.payChannel, channelMerchantId: form.channelMerchantId.trim(), subMerchantId: form.subMerchantId.trim() || null,
        accountMode: "MERCHANT_OWN", fundsPath: form.fundsPath, evidenceRef: form.evidenceRef.trim(), reason,
      }), undefined);
      setForm(EMPTY_FORM);
      await q.refetch();
      return d;
    },
  });
  return (
    <div className="space-y-5">
      <section className="space-y-2">
        <h3 className="txt-label">{m.registered}</h3>
        {q.isPending ? <Skeleton className="h-24 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
          <MiniTable<ChannelAccountRow> rows={q.data.records} rowKey={(r) => r.payMerchantNo} empty={m.emptyAccounts} columns={[
            { header: m.colChannel, cell: (r) => nameOf(r.payChannel) },
            { header: m.colAccount, cell: (r) => r.payMerchantNo },
            { header: m.colMid, cell: (r) => r.channelMerchantId },
            { header: m.colVerify, cell: (r) => <StatusBadge value={r.verificationStatus} map={maps.verify} /> },
            { header: m.colStatus, cell: (r) => <StatusBadge value={r.status} map={maps.account} /> },
            { header: m.colBound, numeric: true, cell: (r) => r.boundStoreCount },
            { header: "", cell: (r) => <Button size="sm" variant="ghost" onClick={() => open(r.payMerchantNo)}>{m.open}</Button> },
          ]} />
        )}
      </section>
      <ClaimsBlock api={api} merchantNo={merchantNo} run={run} onPrefill={onPrefill} />
      {canManage && (
        <section className="space-y-2">
          <h3 className="txt-label">{m.register}</h3>
          <div className="grid gap-2 sm:grid-cols-2">
            <select aria-label={m.payChannel} className={"focus-ring " + SELECT} value={form.payChannel} disabled={choices.isPending || !!choices.error} onChange={(e) => setForm((f) => ({ ...f, payChannel: e.target.value }))}>
              <option value="">{m.payChannel}</option>
              {channels.map((c) => <option key={c.payChannel} value={c.payChannel}>{c.channelName ?? c.payChannel} · {c.payChannel}</option>)}
            </select>
            <select aria-label={m.fundsPath} className={"focus-ring " + SELECT} value={form.fundsPath} onChange={(e) => setForm((f) => ({ ...f, fundsPath: e.target.value as FundsPath }))}>
              {FUNDS_PATHS.map((p) => <option key={p} value={p}>{tr(m, `path_${p}`, p)}</option>)}
            </select>
            <Input aria-label={m.channelMerchantId} placeholder={m.channelMerchantId} maxLength={64} value={form.channelMerchantId} onChange={(e) => setForm((f) => ({ ...f, channelMerchantId: e.target.value }))} />
            <Input aria-label={m.subMerchantId} placeholder={m.subMerchantId} maxLength={64} value={form.subMerchantId} onChange={(e) => setForm((f) => ({ ...f, subMerchantId: e.target.value }))} />
            <Input aria-label={m.evidenceRef} placeholder={m.evidenceHelp} maxLength={255} value={form.evidenceRef} onChange={(e) => setForm((f) => ({ ...f, evidenceRef: e.target.value }))} className="sm:col-span-2" />
          </div>
          {choices.error && <ErrorState error={choices.error} onRetry={() => void choices.refetch()} />}
          <div className="flex items-center justify-between gap-3">
            <Note>{m.registerNote}</Note>
            <Button size="sm" disabled={!valid} onClick={register}>{m.register}</Button>
          </div>
        </section>
      )}
      {dialog}
    </div>
  );
}

function AccountDetailView({ api, no, run, back }: { api: PayCenterApi; no: string; run: Run; back: () => void }) {
  const m = useCac();
  const mx = useCopy(CHANNEL_ACCOUNT_EXTRAS_COPY);
  const c = usePc();
  const maps = useMaps(m);
  const allow = useCan();
  const canManage = allow("pay:channel-account:manage");
  const { confirm, dialog } = useConfirm();
  const [tab, setTab] = React.useState("info");
  const [keyId, setKeyId] = React.useState("");
  const q = useQuery({ queryKey: [KEY, "merchants", "channel-account", api.source, no], queryFn: () => api.channelAccounts.detail(no) });
  const bindings = useQuery({ queryKey: [KEY, "merchants", "channel-account-bindings", api.source, no], queryFn: () => api.channelAccounts.bindings(no), enabled: tab === "stores" });
  if (q.isPending) return <Skeleton className="h-40 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const d = q.data;
  const a = d.record.account;
  const has = (x: string) => d.allowedActions.includes(x);
  const act = (title: string, desc: string, fn: (reason: string) => Promise<unknown>, danger = false) => void confirm({
    title, desc, danger, requireReason: true, reasonHint: c.reasonHint, action: async (reason) => { await run(() => fn(reason)); await q.refetch(); },
  });
  const cmd = (reason: string) => ({ expectedVersion: d.version, reason });
  const approval = (subjectType: string, action: string, title: string) => act(title, "", (reason) =>
    api.approvals.submit({ subjectType, subjectRef: no, subjectRevision: d.version + 1, action, reason }));
  return (
    <section aria-label={a.payMerchantNo} className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span className="txt-label">{a.payMerchantNo}</span>
          <StatusBadge value={a.verificationStatus} map={maps.verify} />
          <StatusBadge value={a.status} map={maps.account} />
        </div>
        <Button size="sm" variant="ghost" onClick={back}>{m.back}</Button>
      </div>
      {canManage && (
        <div className="flex flex-wrap gap-2">
          {has("submitVerify") && <Button size="sm" variant="outline" onClick={() => approval("CHANNEL_ACCOUNT_VERIFY", "VERIFY", fill(m.submitVerifyTitle, { no }))}>{m.submitVerify}</Button>}
          {has("verify") && allow("pay:channel-account:approve") && <Button size="sm" variant="outline" onClick={() => act(fill(m.rejectVerifyTitle, { no }), m.rejectVerifyDesc, (r) => api.channelAccounts.reject(no, cmd(r)), true)}>{m.rejectVerify}</Button>}
          {has("activate") && <Button size="sm" onClick={() => act(fill(m.activateTitle, { no }), m.activateDesc, (r) => api.channelAccounts.activate(no, cmd(r)))}>{m.activate}</Button>}
          {has("resume") && <Button size="sm" variant="outline" onClick={() => approval("CHANNEL_ACCOUNT_RESUME", "RESUME", fill(m.submitResumeTitle, { no }))}>{m.submitResume}</Button>}
          {has("refundOnly") && <Button size="sm" variant="outline" onClick={() => act(fill(m.refundOnlyTitle, { no }), m.refundOnlyDesc, (r) => api.channelAccounts.refundOnly(no, cmd(r)))}>{m.refundOnly}</Button>}
          {has("suspend") && <Button size="sm" variant="ghost" onClick={() => act(fill(m.suspendTitle, { no }), m.suspendDesc, (r) => api.channelAccounts.suspend(no, cmd(r)), true)}>{m.suspend}</Button>}
        </div>
      )}
      {has("update") && canManage && <ChannelAccountEdit api={api} account={a} version={d.version} run={run} onDone={() => void q.refetch()} />}
      {has("resume") && <ResumeApprovalPanel api={api} no={no} run={run} changed={() => void q.refetch()} />}
      {has("close") && <CloseUnavailableNote />}
      <Tabs value={tab} onChange={setTab} tabs={[
        { key: "info", label: m.tabInfo }, { key: "products", label: m.tabProducts }, { key: "credential", label: m.tabCredential },
        { key: "stores", label: m.tabStores }, { key: "settle", label: m.tabSettle },
        { key: "transfers", label: mx.tabTransfers }, { key: "qualifications", label: mx.tabQualifications }, { key: "logs", label: m.tabLogs },
      ]} />
      {tab === "transfers" && <TransfersPanel api={api} detail={d} run={run} changed={() => void q.refetch()} />}
      {tab === "qualifications" && <QualificationsPanel api={api} detail={d} run={run} />}
      {tab === "info" && <DetailBody sections={[{ title: m.tabInfo, fields: [
        { label: m.merchant, value: a.merchantName || a.merchantNo || m.platformParent },
        { label: m.payChannel, value: a.payChannel },
        { label: m.channelMerchantId, value: a.channelMerchantId },
        { label: m.subMerchantId, value: a.subMerchantId ?? "—" },
        { label: m.modePath, value: `${tr(m, `mode_${a.accountMode}`, a.accountMode)} · ${tr(m, `path_${a.fundsPath}`, a.fundsPath)}` },
        { label: m.marketCurrency, value: `${a.market} · ${a.currency}` },
        { label: m.source, value: `${tr(m, `src_${a.source}`, a.source)}${a.onboardingNo ? ` · ${a.onboardingNo}` : ""}` },
        { label: m.evidenceRef, value: a.evidenceRef ?? "—" },
        { label: m.registeredBy, value: d.record.registeredBy ?? "—" },
        { label: m.verified, value: d.record.verifiedBy ? `${d.record.verifiedBy} · ${fmtTime(d.record.verifiedAt)}${d.record.verifyApprovalNo ? ` · ${d.record.verifyApprovalNo}` : ""}` : m.notVerified },
        ...(d.record.verifyReason ? [{ label: m.verifyReason, value: d.record.verifyReason, full: true }] : []),
      ] }]} />}
      {tab === "products" && <AccountProducts api={api} detail={d} run={run} changed={() => void q.refetch()} />}
      {tab === "credential" && (
        <div className="space-y-3">
          <DetailBody sections={[{ title: m.tabCredential, fields: [
            { label: m.keyId, value: a.credentialKeyId ? String(a.credentialKeyId) : m.keyIdUnbound },
            { label: m.credentialVersion, value: d.record.credentialVersion ? `v${d.record.credentialVersion}` : "—" },
          ] }]} />
          {has("credential") && canManage && (
            <div className="flex items-center gap-2">
              <Input aria-label={m.keyId} placeholder={m.bindKeyPh} value={keyId} onChange={(e) => setKeyId(e.target.value.replace(/\D/g, ""))} className="max-w-60" />
              <Button size="sm" variant="outline" disabled={!keyId} onClick={() => act(fill(m.bindKeyTitle, { no, key: keyId }), m.bindKeyDesc, async (r) => { await api.channelAccounts.bindCredential(no, { expectedVersion: d.version, keyId: Number(keyId), reason: r }); setKeyId(""); })}>{m.bindKey}</Button>
            </div>
          )}
          {canManage && <KeyForm api={api} account={a} run={run} changed={() => void q.refetch()} />}
          <Note>{m.credentialNote}</Note>
        </div>
      )}
      {tab === "stores" && (bindings.isPending ? <Skeleton className="h-16 w-full" /> : bindings.error ? <ErrorState error={bindings.error} onRetry={() => void bindings.refetch()} /> : <BindingTable rows={bindings.data.records} />)}
      {tab === "settle" && <DetailBody sections={[{ title: m.tabSettle, desc: m.settleNote, fields: [
        { label: m.settleCycle, value: d.record.settleCycle ?? "—" },
        { label: m.poolAccountRef, value: d.record.poolAccountRef ?? "—" },
        { label: m.maxSplitRate, value: d.record.maxSplitRate == null ? "—" : String(d.record.maxSplitRate) },
      ] }]} />}
      {tab === "logs" && (d.record.statusLogs.length === 0 ? <Note>{m.noLogs}</Note> : (
        <ul className="space-y-1">{d.record.statusLogs.map((l, i) => (
          <li key={i} className="flex flex-wrap gap-2 txt-caption">
            <span>{fmtTime(l.changedAt)}</span><span>{tr(m, `st_acc_${l.fromStatus}`, l.fromStatus)} → {tr(m, `st_acc_${l.toStatus}`, l.toStatus)}</span>
            <span>{l.operatorNo}</span><span className="text-muted-foreground">{l.reason}{l.approvalNo ? ` · ${l.approvalNo}` : ""}</span>
          </li>
        ))}</ul>
      ))}
      {dialog}
    </section>
  );
}

function AccountProducts({ api, detail, run, changed }: { api: PayCenterApi; detail: Detail<ChannelAccountDetail>; run: Run; changed: () => void }) {
  const m = useCac();
  const c = usePc();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const a = detail.record.account;
  const canRead = allow("pay:product:view");
  const canManage = detail.allowedActions.includes("products") && allow("pay:channel-account:manage");
  const catalog = useQuery({ queryKey: [KEY, "merchants", "account-products", api.source, a.payChannel], enabled: canRead, retry: false,
    queryFn: () => api.products.list({ channelCode: a.payChannel, page: 1, size: 100 }) });
  const [opening, setOpening] = React.useState<string | null>(null);
  const directory = canRead && catalog.data ? catalog.data.records : [];
  const codes = [...new Set([...directory.map((p) => p.productCode), ...detail.record.products.map((p) => p.productCode)])];
  const ready = canRead && catalog.isSuccess;
  const close = (code: string) => void confirm({
    title: fill(m.closeProductTitle, { no: a.payMerchantNo, product: code }), desc: m.closeProductDesc, danger: true, requireReason: true, reasonHint: c.reasonHint,
    action: async (reason) => { await run(() => api.channelAccounts.closeProduct(a.payMerchantNo, code, { expectedVersion: detail.version, reason })); changed(); },
  });
  if (opening) return <ProductOpening api={api} detail={detail} productCode={opening} run={run} done={() => { setOpening(null); changed(); }} cancel={() => setOpening(null)} />;
  return (
    <div className="space-y-2">
      {!canRead ? <Notice tone="warning">{m.productNoCatalogPerm}</Notice> : catalog.error ? <ErrorState error={catalog.error} onRetry={() => void catalog.refetch()} /> : catalog.isPending ? <Skeleton className="h-16 w-full" /> : null}
      <ul className="space-y-1.5">{codes.map((code) => {
        const p = directory.find((x) => x.productCode === code);
        const cur = detail.record.products.find((x) => x.productCode === code);
        const opened = cur?.status === "OPEN";
        const eligible = ready && p?.channelCode === a.payChannel && p.status === "ENABLED" && p.accountModes.includes(a.accountMode);
        return (
          <li key={code} className="flex flex-wrap items-center gap-2 txt-caption">
            <StatusBadge value={opened ? "OPEN" : cur?.status === "CLOSED" ? "CLOSED" : "NONE"} map={{ OPEN: { label: m.productOpened, tone: "success" }, CLOSED: { label: m.productClosed, tone: "muted" }, NONE: { label: m.productNotOpened, tone: "muted" } }} />
            <span className="txt-label">{code}</span><span>{p?.displayName ?? cur?.productName}</span>
            {!p && ready && <span className="text-muted-foreground">{m.productNotInCatalog}</span>}
            {p?.status === "DISABLED" && <span className="text-muted-foreground">{m.productCatalogDisabled}</span>}
            {p && !p.accountModes.includes(a.accountMode) && <span className="text-muted-foreground">{m.productModeUnsupported}</span>}
            {cur && <span className="text-muted-foreground">{tr(m, `psrc_${cur.source}`, cur.source)}{cur.openedBy ? ` · ${cur.openedBy}` : ""}{cur.evidenceRef ? ` · ${cur.evidenceRef}` : ""}</span>}
            {canManage && (opened
              ? <Button size="sm" variant="ghost" onClick={() => close(code)}>{m.closeProduct}</Button>
              : eligible && <Button size="sm" variant="outline" onClick={() => setOpening(code)}>{m.openProduct}</Button>)}
          </li>
        );
      })}</ul>
      {ready && codes.length === 0 && <Note>{m.productsEmpty}</Note>}
      <Note>{m.productsNote}</Note>
      {dialog}
    </div>
  );
}

function ProductOpening({ api, detail, productCode, run, done, cancel }: { api: PayCenterApi; detail: Detail<ChannelAccountDetail>; productCode: string; run: Run; done: () => void; cancel: () => void }) {
  const m = useCac();
  const c = usePc();
  const a = detail.record.account;
  const [form, setForm] = React.useState({ evidenceRef: "", reason: "", covered: false });
  const [busy, setBusy] = React.useState(false);
  const valid = !!form.evidenceRef.trim() && !!form.reason.trim() && form.covered;
  const submit = async () => {
    setBusy(true);
    try {
      await run(() => api.channelAccounts.openProduct(a.payMerchantNo, productCode, { expectedVersion: detail.version, evidenceRef: form.evidenceRef.trim(), reason: form.reason.trim() }), fill(m.openedProduct, { product: productCode }));
      done();
    } catch { /* run 已提示 */ } finally { setBusy(false); }
  };
  const proof = (v: string) => setForm((f) => ({ ...f, evidenceRef: v, covered: false }));
  return (
    <div className="space-y-3">
      <h3 className="txt-label">{fill(m.openProductTitle, { no: a.payMerchantNo, product: productCode })}</h3>
      <Notice tone="info">{m.openProductNote}</Notice>
      <Input aria-label={m.productEvidence} placeholder={m.productEvidence} maxLength={255} value={form.evidenceRef} onChange={(e) => proof(e.target.value)} />
      {a.evidenceRef && <Button size="sm" variant="outline" onClick={() => proof(a.evidenceRef!)}>{m.useAccountEvidence}</Button>}
      <Notice tone="warning">{m.openProductWarn}</Notice>
      <Input aria-label={m.openReason} placeholder={m.openReason} maxLength={255} value={form.reason} onChange={(e) => setForm((f) => ({ ...f, reason: e.target.value }))} />
      <label className="flex items-center gap-2 txt-body">
        <Checkbox checked={form.covered} onChange={(v) => setForm((f) => ({ ...f, covered: v === true }))} />
        {fill(m.coveredCheck, { no: a.payMerchantNo, product: productCode })}
      </label>
      <div className="flex justify-end gap-2">
        <Button variant="ghost" disabled={busy} onClick={cancel}>{m.cancel}</Button>
        <Button disabled={!valid} loading={busy} onClick={() => void submit()}>{m.openProduct}</Button>
      </div>
    </div>
  );
}

function KeyForm({ api, account, run, changed }: { api: PayCenterApi; account: ChannelAccountRow; run: Run; changed: () => void }) {
  const m = useCac();
  const c = usePc();
  const { confirm, dialog } = useConfirm();
  const shape = KEY_CHANNELS[account.payChannel];
  const [v, setV] = React.useState({ secret: "", hashKey: "", hashIv: "", publicMaterial: "" });
  if (!shape) return <Notice>{fill(m.keyNoAdapter, { channel: account.payChannel })}</Notice>;
  if (account.accountMode !== "MERCHANT_OWN" || account.status === "CLOSED") return <Notice>{account.status === "CLOSED" ? m.keyClosed : m.keyParent}</Notice>;
  const rotating = account.status !== "UNVERIFIED";
  const clear = () => setV({ secret: "", hashKey: "", hashIv: "", publicMaterial: "" });
  const submit = () => void confirm({
    title: fill(rotating ? m.keyRotateTitle : m.keyFirstTitle, { no: account.payMerchantNo }), desc: rotating ? m.keyRotateDesc : m.keyFirstDesc,
    requireReason: true, reasonHint: c.reasonHint, confirmText: rotating ? m.keySubmitRotate : m.keySubmitFirst,
    action: async (reason) => {
      const body = shape === "composite" ? { hashKey: v.hashKey, hashIv: v.hashIv, reason } : shape === "privateKey" ? { secret: v.secret, publicMaterial: v.publicMaterial, reason } : { secret: v.secret, reason };
      try {
        await run(() => api.channelAccounts.enterKey(account.payMerchantNo, body), rotating ? m.keyRotated : undefined);
      } finally { clear(); }
      changed();
    },
  });
  const FIELD = "w-full rounded-control border border-border bg-background px-2 py-1.5 txt-body font-mono";
  return (
    <div className="space-y-2 rounded-card border border-border p-3">
      <p className="txt-label">{rotating ? m.keyRotate : m.keyEnter}</p>
      {shape === "composite" && <>
        <input type="password" autoComplete="off" aria-label={m.hashKey} placeholder={m.hashKey} className={"focus-ring " + FIELD} value={v.hashKey} onChange={(e) => setV((x) => ({ ...x, hashKey: e.target.value.trim() }))} />
        <input type="password" autoComplete="off" aria-label={m.hashIv} placeholder={m.hashIv} className={"focus-ring " + FIELD} value={v.hashIv} onChange={(e) => setV((x) => ({ ...x, hashIv: e.target.value.trim() }))} />
      </>}
      {shape === "secret" && <input type="password" autoComplete="off" aria-label={m.secret} placeholder={m.secret} className={"focus-ring " + FIELD} value={v.secret} onChange={(e) => setV((x) => ({ ...x, secret: e.target.value.trim() }))} />}
      {shape === "privateKey" && <>
        <textarea rows={4} autoComplete="off" aria-label={m.privateKey} placeholder="-----BEGIN PRIVATE KEY-----" className={"focus-ring " + FIELD} value={v.secret} onChange={(e) => setV((x) => ({ ...x, secret: e.target.value }))} />
        <textarea rows={3} autoComplete="off" aria-label={m.publicKey} placeholder="-----BEGIN PUBLIC KEY-----" className={"focus-ring " + FIELD} value={v.publicMaterial} onChange={(e) => setV((x) => ({ ...x, publicMaterial: e.target.value }))} />
      </>}
      <div className="flex items-center gap-2">
        <Button size="sm" disabled={!keyReady(shape, v)} onClick={submit}>{rotating ? m.keySubmitRotate : m.keySubmitFirst}</Button>
        <Button size="sm" variant="ghost" onClick={clear}>{m.keyClear}</Button>
      </div>
      <Note>{m.keyHint}</Note>
      {dialog}
    </div>
  );
}

// ───────────────────────── 商户号认领 ─────────────────────────

function ClaimsBlock({ api, merchantNo, run, onPrefill }: { api: PayCenterApi; merchantNo: string; run: Run; onPrefill: (p: Partial<RegisterForm>) => void }) {
  const m = useCac();
  const maps = useMaps(m);
  const allow = useCan();
  const [openNo, setOpenNo] = React.useState<string | null>(null);
  const q = useQuery({ queryKey: [KEY, "merchants", "claims", api.source, merchantNo], queryFn: () => api.claims.list(merchantNo), enabled: allow("pay:channel-account:view"), retry: false });
  if (!allow("pay:channel-account:view") || q.isPending) return null;
  if (q.error) return q.error instanceof PayOpsError && q.error.status === 404 ? <Note>{m.claimsUnavailable}</Note> : <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (!q.data.length) return null;
  return (
    <section className="space-y-2">
      <h3 className="txt-label">{fill(m.claims, { n: q.data.filter((r) => r.state === "PENDING_VERIFY").length })}</h3>
      <Note>{m.claimsNote}</Note>
      {q.data.map((row) => (
        <div key={row.claimNo} className="space-y-2">
          <Button size="sm" variant="ghost" onClick={() => setOpenNo(openNo === row.claimNo ? null : row.claimNo)}>
            {row.payChannel} · {row.channelMerchantMasked} · <StatusBadge value={row.state} map={maps.claim} />
          </Button>
          {openNo === row.claimNo && <ClaimDetail api={api} merchantNo={merchantNo} claimNo={row.claimNo} run={run} changed={() => void q.refetch()} onPrefill={onPrefill} />}
        </div>
      ))}
    </section>
  );
}

function ClaimDetail({ api, merchantNo, claimNo, run, changed, onPrefill }: {
  api: PayCenterApi; merchantNo: string; claimNo: string; run: Run; changed: () => void; onPrefill: (p: Partial<RegisterForm>) => void;
}) {
  const m = useCac();
  const c = usePc();
  const maps = useMaps(m);
  const allow = useCan();
  const canManage = allow("pay:channel-account:manage");
  const { confirm, dialog } = useConfirm();
  const [account, setAccount] = React.useState("");
  const [proof, setProof] = React.useState<{ url: string; type: string } | null>(null);
  const q = useQuery({ queryKey: [KEY, "merchants", "claim", api.source, claimNo], queryFn: () => api.claims.detail(claimNo) });
  const pending = q.data?.state === "PENDING_VERIFY";
  const accounts = useQuery({ queryKey: [KEY, "merchants", "claim-accounts", api.source, merchantNo, q.data?.payChannel], enabled: pending && canManage,
    queryFn: () => api.channelAccounts.list({ merchantNo, payChannel: q.data!.payChannel, ownerType: "MERCHANT", size: 100 }) });
  React.useEffect(() => () => { if (proof) URL.revokeObjectURL(proof.url); }, [proof]);
  if (q.isPending) return <Skeleton className="h-20 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const row: ChannelAccountClaim = q.data;
  const resolve = (result: "LINKED" | "REJECTED") => void confirm({
    title: result === "LINKED" ? m.claimLinkTitle : m.claimRejectTitle, desc: m.claimDesc, requireReason: true, reasonHint: c.reasonHint, danger: result === "REJECTED",
    action: async (reason) => {
      const target = result === "LINKED" ? accounts.data?.records.find((x) => x.payMerchantNo === account) : undefined;
      if (result === "LINKED" && target) {
        // 认领关联已切 OpsKit 审批时（第二批 T26）：先送审，审批通过后才关联；未启用时 near-pay 回 CAPABILITY_NOT_READY，照旧直接关联
        const submitted = await api.claims.submitApproval(claimNo, { expectedVersion: row.version, payMerchantNo: target.payMerchantNo, expectedAccountVersion: target.version, reason })
          .then(() => true, (e: unknown) => {
            if ((e as { reasonCode?: string } | null)?.reasonCode === "CAPABILITY_NOT_READY") return false;
            throw e;
          })
          .catch((e: unknown) => { notify.error(errorText(e, c)); throw e; });
        if (submitted) { notify.info(m.claimSubmittedForApproval); await q.refetch(); changed(); return; }
      }
      await run(() => api.claims.resolve(claimNo, { expectedVersion: row.version, result, reason, ...(target ? { payMerchantNo: target.payMerchantNo, expectedAccountVersion: target.version } : {}) }));
      await q.refetch(); changed();
    },
  });
  const showFile = async (fileNo: string) => { const blob = await api.claims.file(claimNo, fileNo); setProof({ url: URL.createObjectURL(blob), type: blob.type }); };
  return (
    <div className="space-y-3 rounded-control bg-muted p-3">
      <DetailBody sections={[{ title: m.claimNo, fields: [
        { label: m.claimNo, value: row.claimNo }, { label: m.claimState, value: <StatusBadge value={row.state} map={maps.claim} /> },
        { label: m.claimMid, value: row.channelMerchantId ?? row.channelMerchantMasked }, { label: m.claimSub, value: row.subMerchantId ?? "—" },
        { label: m.claimAccount, value: row.payMerchantNo ?? "—" }, { label: m.claimAccountVerify, value: row.accountVerificationStatus ?? "—" },
        { label: m.claimAccountStatus, value: row.accountStatus ?? "—" }, { label: m.claimReason, value: row.reason ?? "—" },
      ] }]} />
      {row.files.map((f) => (
        <div key={f.fileNo} className="flex items-center gap-2 txt-caption">
          <Button size="sm" variant="ghost" disabled={f.expired} onClick={() => void showFile(f.fileNo).catch(() => undefined)}>{m.claimProof}</Button>
          <span>{f.pinned ? m.claimPinned : fill(m.claimExpires, { at: fmtTime(f.expiresAt) })}</span>
        </div>
      ))}
      {proof && (proof.type === "application/pdf"
        ? <a className="focus-ring txt-caption underline" href={proof.url} target="_blank" rel="noopener noreferrer">{m.claimPdf}</a>
        : <img src={proof.url} alt={m.claimProof} className="max-h-80 max-w-full rounded-control" />)}
      {pending && canManage && (
        <div className="flex flex-wrap items-center gap-2">
          {row.channelMerchantId && <Button size="sm" variant="outline" onClick={() => onPrefill({ payChannel: row.payChannel, channelMerchantId: row.channelMerchantId!, subMerchantId: row.subMerchantId ?? "", ...(row.files[0] ? { evidenceRef: `file:channel-account-claim/${row.files[0].fileNo}` } : {}) })}>{m.claimPrefill}</Button>}
          <select aria-label={m.claimPick} className={"focus-ring " + SELECT} value={account} onChange={(e) => setAccount(e.target.value)}>
            <option value="">{m.claimPick}</option>
            {accounts.data?.records.map((x) => <option key={x.payMerchantNo} value={x.payMerchantNo}>{x.payMerchantNo} · {x.channelMerchantId} · {tr(m, `st_acc_${x.status}`, x.status)}</option>)}
          </select>
          <Button size="sm" variant="outline" disabled={!account} onClick={() => resolve("LINKED")}>{m.claimLink}</Button>
          <Button size="sm" variant="ghost" onClick={() => resolve("REJECTED")}>{m.claimReject}</Button>
        </div>
      )}
      {dialog}
    </div>
  );
}

// ───────────────────────── 门店 · 渠道绑定 ─────────────────────────

function BindingTable({ rows }: { rows: StoreBinding[] }) {
  const m = useCac();
  const maps = useMaps(m);
  return <MiniTable<StoreBinding> rows={rows} rowKey={(r) => r.bindingNo} empty={m.noBindings} columns={[
    { header: m.colStatus, cell: (r) => <StatusBadge value={r.status} map={maps.binding} /> },
    { header: m.bindingNo, cell: (r) => r.bindingNo },
    { header: m.store, cell: (r) => r.storeNo },
    { header: m.colChannel, cell: (r) => `${r.payChannel} · ${r.payMerchantNo}` },
    { header: m.effective, cell: (r) => `${fmtTime(r.effectiveFrom)} ~ ${r.effectiveTo ? fmtTime(r.effectiveTo) : m.untilNow}` },
    { header: m.bindingReason, cell: (r) => `${r.endedReason ?? r.reason}${r.approvalNo ? ` · ${r.approvalNo}` : ""}` },
  ]} />;
}

export function StoreBindingsPanel({ api, storeNo, merchantNo, run }: { api: PayCenterApi; storeNo: string; merchantNo: string | null; run: Run }) {
  const m = useCac();
  const c = usePc();
  const allow = useCan();
  const canManage = allow("pay:store-binding:manage");
  const canApprove = allow("pay:store-binding:approve");
  const p8 = useCopy(P8_COPY);
  const { confirm, dialog } = useConfirm();
  const q = useQuery({ queryKey: [KEY, "stores", "bindings", api.source, storeNo], queryFn: () => api.storeBindings.list(storeNo) });
  const accounts = useQuery({ queryKey: [KEY, "stores", "binding-accounts", api.source, merchantNo], enabled: !!merchantNo,
    queryFn: () => api.channelAccounts.list({ merchantNo: merchantNo!, size: 100 }) });
  const [target, setTarget] = React.useState("");
  const [impact, setImpact] = React.useState<BindingImpact | null>(null);
  if (!merchantNo) return <Notice tone="warning">{m.storeNoMerchant}</Notice>;
  const rows = q.data?.records ?? [];
  const active = rows.filter((b) => b.status === "ACTIVE");
  const verified = (accounts.data?.records ?? []).filter((a) => a.verificationStatus === "VERIFIED" && a.status === "ACTIVE");
  const free = bindableAccounts(accounts.data?.records ?? [], rows);
  const refresh = async () => { await q.refetch(); await accounts.refetch(); };
  const act = (title: string, desc: string, fn: (reason: string) => Promise<unknown>, danger = false) => void confirm({
    title, desc, danger, requireReason: true, reasonHint: c.reasonHint, action: async (reason) => { await run(() => fn(reason)); setImpact(null); await refresh(); },
  });
  const preview = async (b: StoreBinding) => {
    setImpact(null);
    const i = await run(() => api.storeBindings.impact(storeNo, b.bindingNo, target), undefined).catch(() => null);
    if (i) setImpact(i);
  };
  return (
    <div className="space-y-5">
      {q.isPending ? <Skeleton className="h-16 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : <BindingTable rows={rows} />}
      {active.map((b) => (
        <section key={b.bindingNo} className="space-y-2">
          <h3 className="txt-label">{fill(m.activeBinding, { no: b.bindingNo, channel: b.payChannel, account: b.payMerchantNo })}</h3>
          {b.pendingTargetPayMerchantNo ? (
            <div className="space-y-2">
              <Notice tone="warning">{fill(m.pendingRebind, { target: b.pendingTargetPayMerchantNo, reason: b.pendingReason ?? "" })}</Notice>
              {(canManage || canApprove) && (
                <div className="flex flex-wrap gap-2">
                  {canApprove && <Button size="sm" onClick={() => act(fill(p8.executeRebindTitle, { no: b.bindingNo, target: b.pendingTargetPayMerchantNo ?? "" }), p8.executeRebindDesc, (r) => api.storeBindings.rebind(storeNo, b.bindingNo, { expectedVersion: b.version, reason: r }))}>{p8.executeRebind}</Button>}
                  {canManage && <><Button size="sm" variant="outline" onClick={() => act(fill(m.submitRebindTitle, { no: b.bindingNo }), "", (r) => api.approvals.submit({ subjectType: "STORE_BINDING_REBIND", subjectRef: b.bindingNo, subjectRevision: b.version + 1, action: "REBIND", reason: r }))}>{m.submitRebind}</Button>
                  <Button size="sm" variant="ghost" onClick={() => act(fill(m.withdrawRebindTitle, { no: b.bindingNo }), m.withdrawRebindDesc, (r) => api.storeBindings.rebindWithdraw(storeNo, b.bindingNo, { expectedVersion: b.version, reason: r }))}>{m.withdrawRebind}</Button></>}
                </div>
              )}
            </div>
          ) : canManage && (
            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2">
                <select aria-label={m.rebindTarget} className={"focus-ring " + SELECT} value={target} onChange={(e) => { setTarget(e.target.value); setImpact(null); }}>
                  <option value="">{m.rebindTargetPh}</option>
                  {verified.filter((a) => a.payChannel === b.payChannel && a.payMerchantNo !== b.payMerchantNo).map((a) => <option key={a.payMerchantNo} value={a.payMerchantNo}>{a.payMerchantNo} · {a.channelMerchantId}</option>)}
                </select>
                <Button size="sm" variant="outline" disabled={!target} onClick={() => void preview(b)}>{m.preview}</Button>
                <Button size="sm" variant="outline" disabled={!impact?.targetBindable} onClick={() => act(fill(m.requestRebindTitle, { from: b.payMerchantNo, to: target }), m.requestRebindDesc, (r) => api.storeBindings.rebindRequest(storeNo, b.bindingNo, { expectedVersion: b.version, targetPayMerchantNo: target, reason: r }))}>{m.requestRebind}</Button>
                <Button size="sm" variant="ghost" onClick={() => act(fill(m.unbindTitle, { no: b.bindingNo }), m.unbindDesc, (r) => api.storeBindings.unbind(storeNo, b.bindingNo, { expectedVersion: b.version, reason: r }), true)}>{m.unbind}</Button>
              </div>
              {impact && impact.bindingNo === b.bindingNo && <DetailBody sections={[{ title: m.preview, fields: [
                { label: m.targetBindable, value: impact.targetBindable ? c.yes : fill(m.previewBlocked, { reason: impact.blockedReason ?? "" }) },
                { label: m.productsKept, value: impact.productsKept.join("、") || "—" },
                { label: m.productsLost, value: impact.productsLost.join("、") || "—" },
                { label: m.productsGained, value: impact.productsGained.join("、") || "—" },
                { label: m.inflight, value: impact.inflightPayments === "UNKNOWN" ? m.inflightUnknown : impact.inflightPayments },
              ] }]} />}
            </div>
          )}
        </section>
      ))}
      {canManage && (
        <section className="space-y-2">
          <h3 className="txt-label">{m.bindTitle}</h3>
          {free.length === 0 ? <Note>{m.bindNone}</Note> : (
            <div className="flex flex-wrap gap-2">{free.map((a) => (
              <Button key={a.payMerchantNo} size="sm" variant="outline" onClick={() => act(fill(m.bindConfirm, { store: storeNo, channel: a.payChannel, account: a.payMerchantNo }), m.bindDesc, (r) => api.storeBindings.bind(storeNo, { payChannel: a.payChannel, payMerchantNo: a.payMerchantNo, reason: r }))}>
                {fill(m.bindAccount, { channel: a.payChannel, account: a.payMerchantNo })}
              </Button>
            ))}</div>
          )}
        </section>
      )}
      {dialog}
    </div>
  );
}
