"use client";

// 支付终端详情里的 P11 区块（《支付中心-渠道专属能力运营需求》）：
// PayBy POS 执行配置（制单 → 另一人核验 → 发布 / 停用）与验收批次（D01–D20，独立签核）；蓝新 SoftPOS 绑定（登记 / 查看 / 撤销）。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Notice } from "@/components/ui/notice";
import { ActionForm } from "@/components/ui/action-form";
import { StatusBadge } from "@/components/ui/status-badge";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ErrorState, Skeleton } from "@/components/ui/misc";
import { Input } from "@/components/ui/input";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import {
  POS_MODES, POS_STATES, UAT_CASES, UAT_RESULTS,
  type PosConfigView, type PosMode, type SoftPosBinding, type TerminalRow, type UatRun,
} from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { useCopy, fill } from "@/lib/use-copy";
import { MiniTable, options, parseLocalDateTime, reasonField, usePc, yesNo } from "./shared";
import { P11_COPY, type P11Copy } from "./p11-copy";
import { p8Map } from "./members-tab";
import { prettyJson } from "./p9-panels";

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const KEY = "pay-center";
const lbl = (m: P11Copy, prefix: string, v: string | null | undefined) => (v ? (m as unknown as Record<string, string>)[`${prefix}_${v}`] ?? v : "—");
const SAFE = { re: "^file:[A-Za-z0-9_./:#@-]{1,245}$", msg: "file:..." };
const REF = { re: "^$|^(vault|secret):[A-Za-z0-9_./:#@-]{1,240}$", msg: "vault:... / secret:..." };
const LABEL = { re: "^[A-Za-z0-9_./:#@-]+$", msg: "A-Z a-z 0-9 _ . / : # @ -" };

/** 只给 PayBy 终端显示 POS 区块；蓝新终端显示 SoftPOS 区块（near-pay 再校验市场与币种） */
export const isPaybyTerminal = (t: TerminalRow) => t.payChannel === "PAYBY";
export const isNewebpayTerminal = (t: TerminalRow) => t.payChannel === "NEWEBPAY";

// ════ PayBy POS 执行配置 ════

export function PosConfigSection({ api, terminal, run }: { api: PayCenterApi; terminal: TerminalRow; run: Run }) {
  const m = useCopy(P11_COPY);
  const c = usePc();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const stMap = React.useMemo(() => p8Map(m, "st_pos", POS_STATES, { PUBLISHED: "success", VERIFIED: "info", DRAFT: "warning", DISABLED: "danger" }), [m]);
  const q = useQuery({ queryKey: [KEY, "terminals", "payby-pos", api.source, terminal.terminalNo], queryFn: () => api.pos.list(terminal.terminalNo) });
  const [selected, setSelected] = React.useState<number | null>(null);
  const [editing, setEditing] = React.useState<PosConfigView | "new" | null>(null);
  const [verifying, setVerifying] = React.useState<PosConfigView | null>(null);
  const t = terminal.terminalNo;
  const refresh = async () => { await q.refetch(); };
  if (q.isPending) return <Skeleton className="h-16 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void refresh()} />;
  const rows = q.data;
  const cur = rows.find((r) => r.id === selected) ?? rows[0] ?? null;
  const canMake = allow("pay:terminal:manage") && allow("pay:credential:view");
  const openDraft = rows.some((r) => r.state === "DRAFT" || r.state === "VERIFIED");
  const act = (title: string, desc: string, fn: (reason: string) => Promise<unknown>, danger = false) => void confirm({
    title, desc, danger, requireReason: true, reasonHint: c.reasonHint, action: async (reason) => { await run(() => fn(reason)); await refresh(); },
  });
  return (
    <div className="space-y-4">
      <Notice tone="info">{m.posIntro}</Notice>
      {rows.length === 0 ? <p className="txt-body text-muted-foreground">{m.posEmpty}</p> : (
        <MiniTable<PosConfigView> rows={rows} rowKey={(r) => String(r.id)} empty={m.posEmpty} columns={[
          { header: m.posRevision, cell: (r) => <button type="button" className="text-primary-ink underline" onClick={() => setSelected(r.id)}>#{r.id} · v{r.version}</button> },
          { header: m.posState, cell: (r) => <span className="inline-flex items-center gap-1"><StatusBadge value={r.state} map={stMap} />{r.stale ? <span className="txt-caption text-warning-ink">{m.posStale}</span> : null}</span> },
          { header: m.posEnv, cell: (r) => r.settings.environment },
          { header: m.posModes, cell: (r) => `${r.settings.allowedModes.map((x) => lbl(m, "mode", x)).join("、")} / ${lbl(m, "mode", r.settings.defaultMode)}` },
          { header: m.posConfiguredBy, cell: (r) => `${r.configuredBy} / ${r.verifiedBy ?? "—"}` },
        ]} />
      )}
      <div className="flex flex-wrap gap-2">
        {canMake && !openDraft && <Button size="sm" variant="outline" onClick={() => setEditing("new")}>{m.posNew}</Button>}
        {cur && canMake && (cur.state === "DRAFT" || cur.state === "VERIFIED") && <Button size="sm" variant="outline" onClick={() => setEditing(cur)}>{m.posEdit}</Button>}
        {cur && cur.state === "DRAFT" && allow("pay:terminal:verify") && <Button size="sm" onClick={() => setVerifying(cur)}>{m.posVerify}</Button>}
        {cur && cur.canPublish && allow("pay:terminal:manage") && <Button size="sm" onClick={() => act(fill(m.posPublishTitle, { id: cur.id }), m.posPublishDesc, (r) => api.pos.publish(t, cur.id, { expectedVersion: cur.version, reason: r }))}>{m.posPublish}</Button>}
        {cur && cur.state === "PUBLISHED" && allow("pay:terminal:manage") && <Button size="sm" variant="ghost" onClick={() => act(fill(m.posDisableTitle, { id: cur.id }), m.posDisableDesc, (r) => api.pos.disable(t, cur.id, { expectedVersion: cur.version, reason: r }), true)}>{m.posDisable}</Button>}
      </div>
      {editing && <PosDraftForm api={api} terminal={terminal} base={editing === "new" ? null : editing} run={run} onDone={() => { setEditing(null); void refresh(); }} />}
      {verifying && (
        <section className="space-y-2 rounded-card border border-border p-3">
          <h4 className="txt-label">{fill(m.posVerifyTitle, { id: verifying.id })}</h4>
          <Notice tone="info">{m.posVerifyNote}</Notice>
          <ActionForm notify={false} draft={null} fields={[{ key: "evidenceRef", label: m.evidenceRef, required: true, help: m.evidenceHelp, pattern: SAFE }, reasonField(c)]} initial={{ evidenceRef: "", reason: "" }}
            onSubmit={(v) => run(() => api.pos.verify(t, verifying.id, { expectedVersion: verifying.version, evidenceRef: String(v.evidenceRef).trim(), reason: String(v.reason) }))}
            onDone={() => { setVerifying(null); void refresh(); }} />
        </section>
      )}
      {cur && <PosDetailLoader api={api} terminalNo={t} id={cur.id} version={cur.version} />}
      {cur && cur.state === "PUBLISHED" && <UatSection api={api} terminalNo={t} cfg={cur} run={run} />}
      {dialog}
    </div>
  );
}

/** 选中的配置取详情（GET …/payby-pos/{id}），不只用列表里的那份 */
function PosDetailLoader({ api, terminalNo, id, version }: { api: PayCenterApi; terminalNo: string; id: number; version: number }) {
  const m = useCopy(P11_COPY);
  const c = usePc();
  const q = useQuery({ queryKey: [KEY, "terminals", "payby-pos-detail", api.source, terminalNo, id, version], queryFn: () => api.pos.detail(terminalNo, id) });
  if (q.isPending) return <Skeleton className="h-16 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  return <PosDetail m={m} c={c} cfg={q.data} />;
}

function PosDetail({ m, c, cfg }: { m: P11Copy; c: ReturnType<typeof usePc>; cfg: PosConfigView }) {
  const s = cfg.settings; const b = cfg.binding;
  const rows: [string, React.ReactNode][] = [
    [m.posVersions, `${s.protocolVersion} / ${s.contractVersion} / ${s.readerKitVersion} / ${s.firmwareVersion}`],
    [m.posBinding, b ? `${b.status} · ${b.channelMerchantNo} / ${b.channelStoreNo} / ${b.channelTerminalNo} · ${b.deviceId}` : "—"],
    [m.posCredLinked, b ? `${yesNo(b.deviceCredentialLinked, c)} / ${yesNo(b.queryCredentialLinked, c)}` : "—"],
    [m.posRuntime, cfg.runtime ? yesNo(cfg.runtime.canReceivePayments, c) : "—"],
    [m.posReasons, cfg.runtime?.reasons.join("、") || "—"],
  ];
  const reports = POS_MODES.map((md) => cfg.modes[md]).filter((x): x is NonNullable<typeof x> => !!x);
  return (
    <div className="space-y-3">
      <dl className="grid grid-cols-[9rem_1fr] gap-x-4 gap-y-1 txt-body">{rows.map(([k, v]) => <React.Fragment key={k}><dt className="text-muted-foreground">{k}</dt><dd className="break-all">{v}</dd></React.Fragment>)}</dl>
      <h5 className="txt-label">{m.posReadiness}</h5>
      <MiniTable rows={reports} rowKey={(r) => r.mode} empty={m.posReadinessEmpty} columns={[
        { header: m.defaultMode, cell: (r) => lbl(m, "mode", r.mode) },
        { header: m.posState, cell: (r) => yesNo(r.ready, c) },
        { header: m.posReasons, cell: (r) => r.reasons.join("、") || "—" },
      ]} />
      <h5 className="txt-label">{m.posAudit}</h5>
      <MiniTable rows={cfg.audit} rowKey={(a) => `${String(a.created_at)}:${String(a.operation)}`} empty={m.posAuditEmpty} columns={[
        { header: m.posState, cell: (a) => `${String(a.operation ?? "")} → ${String(a.state ?? "")}` },
        { header: m.posConfiguredBy, cell: (a) => String(a.operator_no ?? "—") },
        { header: m.evidenceRef, cell: (a) => String(a.evidence_ref ?? "—") },
        { header: m.expireAt, cell: (a) => (typeof a.created_at === "number" ? fmtTime(a.created_at) : String(a.created_at ?? "—")) },
      ]} />
    </div>
  );
}

/** 制单表单：新建或修改草稿；凭据只收引用，修改时留空沿用原引用；不存本地草稿 */
function PosDraftForm({ api, terminal, base, run, onDone }: { api: PayCenterApi; terminal: TerminalRow; base: PosConfigView | null; run: Run; onDone: () => void }) {
  const m = useCopy(P11_COPY);
  const c = usePc();
  const s = base?.settings; const b = base?.binding;
  const mode = (v: unknown) => (String(v) === "TERMINAL_DIRECT" ? "TERMINAL_DIRECT" : "PLATFORM_PROXY") as PosMode;
  const allowed = (v: unknown): PosMode[] => (String(v) === "BOTH" ? [...POS_MODES] : [mode(v)]);
  const fields: FieldDef[] = [
    { key: "environment", label: m.environment, type: "select", required: true, options: [{ value: "SANDBOX", label: "SANDBOX" }, { value: "PRODUCTION", label: "PRODUCTION" }] },
    { key: "allowedModes", label: m.allowedModes, type: "select", required: true, options: [...options(POS_MODES, (v) => lbl(m, "mode", v)), { value: "BOTH", label: POS_MODES.map((x) => lbl(m, "mode", x)).join(" + ") }] },
    { key: "defaultMode", label: m.defaultMode, type: "select", required: true, options: options(POS_MODES, (v) => lbl(m, "mode", v)) },
    { key: "protocolVersion", label: m.protocolVersion, required: true, maxLength: 32, pattern: LABEL },
    { key: "contractVersion", label: m.contractVersion, required: true, maxLength: 64 },
    { key: "readerKitVersion", label: m.readerKitVersion, required: true, maxLength: 64 },
    { key: "firmwareVersion", label: m.firmwareVersion, required: true, maxLength: 64 },
    { key: "queryContractRef", label: m.queryContractRef, required: true, help: m.evidenceHelp, pattern: SAFE },
    { key: "certificationRef", label: m.certificationRef, required: true, help: m.evidenceHelp, pattern: SAFE },
    ...(["issuerCode", "issuerType", "channelMerchantNo", "channelStoreNo", "deviceId", "channelTerminalNo"] as const).map((k): FieldDef => ({ key: k, label: m[k], required: true, maxLength: 64, pattern: LABEL })),
    { key: "payeeMemberId", label: m.payeeMemberId, maxLength: 64 },
    { key: "deviceKeyRef", label: m.deviceKeyRef, required: !base, help: base ? m.keepRefHelp : undefined, pattern: REF },
    { key: "deviceKeyVersion", label: m.deviceKeyVersion, required: true, maxLength: 64, pattern: LABEL },
    { key: "queryCredentialRef", label: m.queryCredentialRef, required: !base, help: base ? m.keepRefHelp : undefined, pattern: REF },
    { key: "queryKeyVersion", label: m.queryKeyVersion, required: true, maxLength: 64, pattern: LABEL },
    { key: "activationRef", label: m.activationRef, required: true, help: m.evidenceHelp, pattern: SAFE },
    { key: "authorizationRef", label: m.authorizationRef, required: true, help: m.evidenceHelp, pattern: SAFE },
    reasonField(c),
  ];
  const initial = {
    environment: s?.environment ?? "SANDBOX", allowedModes: s ? (s.allowedModes.length > 1 ? "BOTH" : s.allowedModes[0]) : "PLATFORM_PROXY", defaultMode: s?.defaultMode ?? "PLATFORM_PROXY",
    protocolVersion: s?.protocolVersion ?? "", contractVersion: s?.contractVersion ?? "", readerKitVersion: s?.readerKitVersion ?? "", firmwareVersion: s?.firmwareVersion ?? "",
    queryContractRef: s?.queryContractRef ?? "", certificationRef: s ? s.certificationRefs[s.defaultMode] ?? "" : "",
    issuerCode: b?.issuerCode ?? "", issuerType: b?.issuerType ?? "", channelMerchantNo: b?.channelMerchantNo ?? "", channelStoreNo: b?.channelStoreNo ?? "", deviceId: b?.deviceId ?? "",
    channelTerminalNo: b?.channelTerminalNo ?? terminal.channelTerminalNo ?? "", payeeMemberId: b?.payeeMemberId ?? "", deviceKeyRef: "", deviceKeyVersion: b?.deviceKeyVersion ?? "",
    queryCredentialRef: "", queryKeyVersion: b?.queryKeyVersion ?? "", activationRef: b?.activationRef ?? "", authorizationRef: b?.authorizationRef ?? "", reason: "",
  };
  const str = (v: unknown) => String(v ?? "").trim();
  return (
    <section className="space-y-2 rounded-card border border-border p-3" aria-label={m.posEditTitle}>
      <h4 className="txt-label">{m.posEditTitle}</h4>
      <Notice tone="info">{m.posEditNote}</Notice>
      <ActionForm notify={false} draft={null} fields={fields} initial={initial}
        onSubmit={(v) => run(() => api.pos.save(terminal.terminalNo, {
          expectedTerminalVersion: terminal.version, configurationId: base?.id ?? null, expectedVersion: base?.version ?? 0, reason: str(v.reason),
          settings: {
            environment: v.environment === "PRODUCTION" ? "PRODUCTION" : "SANDBOX", allowedModes: allowed(v.allowedModes), defaultMode: mode(v.defaultMode),
            protocolVersion: str(v.protocolVersion), contractVersion: str(v.contractVersion), readerKitVersion: str(v.readerKitVersion), firmwareVersion: str(v.firmwareVersion),
            queryContractRef: str(v.queryContractRef), certificationRefs: { [mode(v.defaultMode)]: str(v.certificationRef) },
          },
          binding: {
            issuerCode: str(v.issuerCode), issuerType: str(v.issuerType), channelMerchantNo: str(v.channelMerchantNo), channelStoreNo: str(v.channelStoreNo), deviceId: str(v.deviceId),
            channelTerminalNo: str(v.channelTerminalNo), payeeMemberId: str(v.payeeMemberId) || null, deviceKeyRef: str(v.deviceKeyRef) || null, deviceKeyVersion: str(v.deviceKeyVersion),
            queryCredentialRef: str(v.queryCredentialRef) || null, queryKeyVersion: str(v.queryKeyVersion), activationRef: str(v.activationRef), authorizationRef: str(v.authorizationRef),
          },
        }))}
        onDone={onDone} />
    </section>
  );
}

// ════ POS 验收批次 ════

function UatSection({ api, terminalNo, cfg, run }: { api: PayCenterApi; terminalNo: string; cfg: PosConfigView; run: Run }) {
  const m = useCopy(P11_COPY);
  const c = usePc();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const stMap = React.useMemo(() => p8Map(m, "st_uat", ["COLLECTING", "ACCEPTED"], { ACCEPTED: "success", COLLECTING: "warning" }), [m]);
  const resMap = React.useMemo(() => p8Map(m, "result", UAT_RESULTS, { PASS: "success", FAIL: "danger" }), [m]);
  const q = useQuery({ queryKey: [KEY, "terminals", "pos-uat", api.source, terminalNo, cfg.id], queryFn: () => api.posUat.runs(terminalNo, cfg.id) });
  const [open, setOpen] = React.useState<string | null>(null);
  const [recording, setRecording] = React.useState<UatRun | null>(null);
  const [manifest, setManifest] = React.useState<string | null>(null);
  const [reviewing, setReviewing] = React.useState<UatRun | null>(null);
  const refresh = async () => { await q.refetch(); };
  const sandbox = cfg.settings.environment === "SANDBOX" && !cfg.stale;
  return (
    <section className="space-y-3" aria-label={m.uatTitle}>
      <h4 className="txt-label">{m.uatTitle}</h4>
      <Notice tone="info">{m.uatIntro}</Notice>
      {q.isPending ? <Skeleton className="h-12 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void refresh()} /> : (
        <MiniTable<UatRun> rows={q.data} rowKey={(r) => r.runNo} empty={m.uatEmpty} columns={[
          { header: m.uatRun, cell: (r) => <button type="button" className="text-primary-ink underline" onClick={() => setOpen(open === r.runNo ? null : r.runNo)}>{r.runNo}</button> },
          { header: m.uatState, cell: (r) => <StatusBadge value={r.state} map={stMap} /> },
          { header: m.uatCases, cell: (r) => `${r.cases.length} / ${UAT_CASES.length}` },
          { header: m.uatBlockers, cell: (r) => r.blockers.length ? r.blockers.join("、") : m.uatNoBlockers },
          { header: "", cell: (r) => (
            <div className="flex gap-1">
              {r.state === "COLLECTING" && allow("pay:terminal:manage") && <Button size="sm" variant="outline" onClick={() => setRecording(r)}>{m.uatRecord}</Button>}
              {r.state === "COLLECTING" && allow("pay:terminal:verify") && (r.reviewerEligible
                ? <Button size="sm" disabled={r.blockers.length > 0} onClick={() => setReviewing(r)}>{m.uatReview}</Button>
                : <span className="txt-caption text-muted-foreground">{m.uatNotEligible}</span>)}
              <Button size="sm" variant="ghost" onClick={async () => setManifest(prettyJson(JSON.stringify(await run(() => api.posUat.manifest(terminalNo, r.runNo), m.uatManifest))))}>{m.uatManifest}</Button>
            </div>
          ) },
        ]} />
      )}
      {sandbox && allow("pay:terminal:manage") && <Button size="sm" variant="outline" onClick={() => void confirm({ title: m.uatNew, requireReason: true, reasonHint: c.reasonHint,
        action: async (reason) => { await run(() => api.posUat.start(terminalNo, cfg.id, { expectedVersion: cfg.version, mode: cfg.settings.defaultMode, reason })); await refresh(); } })}>{m.uatNew}</Button>}
      {open && <UatRunCases api={api} terminalNo={terminalNo} runNo={open} />}
      {recording && (
        <section className="space-y-2 rounded-card border border-border p-3">
          <h4 className="txt-label">{m.uatRecordTitle}</h4>
          <ActionForm notify={false} draft={null}
            fields={[
              { key: "caseCode", label: m.caseCode, type: "select", required: true, options: UAT_CASES.map((x) => ({ value: x, label: x })) },
              { key: "result", label: m.caseResult, type: "select", required: true, options: options(UAT_RESULTS, (v) => lbl(m, "result", v)) },
              { key: "evidenceRef", label: m.evidenceRef, required: true, help: m.evidenceHelp, pattern: SAFE },
              { key: "executionNo", label: m.executionNo, help: m.executionHelp, pattern: { re: "^$|^POS-[A-Za-z0-9-]{1,60}$", msg: "POS-..." } },
              reasonField(c),
            ]}
            initial={{ caseCode: "D01", result: "PASS", evidenceRef: "", executionNo: "", reason: "" }}
            onSubmit={(v) => run(() => api.posUat.recordCase(terminalNo, recording.runNo, { expectedVersion: recording.version, caseCode: String(v.caseCode), result: String(v.result), evidenceRef: String(v.evidenceRef).trim(), executionNo: String(v.executionNo ?? "").trim() || null, reason: String(v.reason) }))}
            onDone={() => { setRecording(null); void refresh(); }} />
        </section>
      )}
      {reviewing && (
        <section className="space-y-2 rounded-card border border-border p-3">
          <h4 className="txt-label">{fill(m.uatReviewTitle, { run: reviewing.runNo })}</h4>
          <Notice tone="info">{m.uatReviewDesc}</Notice>
          <ActionForm notify={false} draft={null} fields={[{ key: "evidenceRef", label: m.evidenceRef, required: true, help: m.evidenceHelp, pattern: SAFE }, reasonField(c)]} initial={{ evidenceRef: "", reason: "" }}
            onSubmit={(v) => run(() => api.posUat.review(terminalNo, reviewing.runNo, { expectedVersion: reviewing.version, evidenceRef: String(v.evidenceRef).trim(), reason: String(v.reason) }))}
            onDone={() => { setReviewing(null); void refresh(); }} />
        </section>
      )}
      {manifest && <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all txt-caption">{manifest}</pre>}
      {dialog}
    </section>
  );
}

/** 展开的批次取详情（GET …/acceptance-runs/{runNo}）：用例按每条最新一次显示 */
function UatRunCases({ api, terminalNo, runNo }: { api: PayCenterApi; terminalNo: string; runNo: string }) {
  const m = useCopy(P11_COPY);
  const resMap = React.useMemo(() => p8Map(m, "result", UAT_RESULTS, { PASS: "success", FAIL: "danger" }), [m]);
  const q = useQuery({ queryKey: [KEY, "terminals", "pos-uat-run", api.source, terminalNo, runNo], queryFn: () => api.posUat.run(terminalNo, runNo) });
  if (q.isPending) return <Skeleton className="h-12 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  return (
    <MiniTable rows={q.data.cases} rowKey={(x) => x.caseCode} empty={m.uatCasesEmpty} columns={[
      { header: m.caseCode, cell: (x) => x.caseCode },
      { header: m.caseResult, cell: (x) => <StatusBadge value={x.result} map={resMap} /> },
      { header: m.evidenceRef, cell: (x) => <span className="break-all">{x.evidenceRef}</span> },
      { header: m.executionNo, cell: (x) => x.executionNo ?? "—" },
      { header: m.posConfiguredBy, cell: (x) => `${x.recordedBy} · ${x.recordedAt}` },
    ]} />
  );
}

// ════ SoftPOS 绑定 ════

export function SoftPosSection({ api, terminal, run }: { api: PayCenterApi; terminal: TerminalRow; run: Run }) {
  const m = useCopy(P11_COPY);
  const c = usePc();
  const allow = useCan();
  const { confirm, dialog } = useConfirm();
  const stMap = React.useMemo(() => p8Map(m, "st_sp", ["DRAFT", "REVOKED"], { DRAFT: "success", REVOKED: "danger" }), [m]);
  const [lookup, setLookup] = React.useState("");
  const [binding, setBinding] = React.useState<SoftPosBinding | null>(null);
  const [registering, setRegistering] = React.useState(false);
  const t = terminal.terminalNo;
  const load = async (id: number) => setBinding(await run(() => api.softpos.detail(t, id), m.softposLookup));
  return (
    <div className="space-y-3">
      <Notice tone="info">{m.softposIntro}</Notice>
      <div className="flex items-end gap-2">
        <label className="block"><span className="mb-1 block txt-label text-muted-foreground">{m.bindingId}</span>
          <Input value={lookup} onChange={(e) => setLookup(e.target.value.replace(/\D/g, ""))} className="w-40" /></label>
        <Button size="sm" variant="outline" disabled={!lookup} onClick={() => void load(Number(lookup)).catch(() => undefined)}>{m.softposLookup}</Button>
        {allow("pay:terminal:manage") && <Button size="sm" onClick={() => setRegistering(true)}>{m.softposRegister}</Button>}
      </div>
      {binding && (
        <div className="space-y-2">
          <dl className="grid grid-cols-[8rem_1fr] gap-x-4 gap-y-1 txt-body">
            {([[m.bindingId, String(binding.id)], [m.posState, <StatusBadge key="s" value={binding.status} map={stMap} />], [m.packageName, binding.packageName],
              [m.signatureSha256, <span key="sig" className="font-mono break-all">{binding.signatureSha256}</span>], [m.appVersion, binding.appVersion], [m.sdkVersion, binding.sdkVersion],
              [m.providerIds, `${binding.providerMid} / ${binding.providerTid}`], [m.physicalDevice, binding.physicalDevice], [m.epoch, binding.epoch], [m.configVersion, String(binding.configVersion)],
              [m.bindingValidUntil, binding.validUntil]] as [string, React.ReactNode][]).map(([k, v]) => <React.Fragment key={k}><dt className="text-muted-foreground">{k}</dt><dd>{v}</dd></React.Fragment>)}
          </dl>
          {binding.status !== "REVOKED" && allow("pay:terminal:manage") && <Button size="sm" variant="ghost" onClick={() => void confirm({
            title: fill(m.softposRevokeTitle, { id: binding.id }), desc: m.softposRevokeDesc, danger: true, requireReason: true, reasonHint: c.reasonHint,
            action: async (reason) => { setBinding(await run(() => api.softpos.revoke(t, binding.id, reason))); },
          })}>{m.softposRevoke}</Button>}
        </div>
      )}
      {registering && (
        <section className="space-y-2 rounded-card border border-border p-3">
          <h4 className="txt-label">{m.softposRegisterTitle}</h4>
          <ActionForm notify={false} draft={null}
            fields={[
              { key: "packageName", label: m.packageName, required: true, maxLength: 128, pattern: { re: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", msg: "com.vendor.app" } },
              { key: "signatureSha256", label: m.signatureSha256, required: true, help: m.signatureHelp, pattern: { re: "^[a-f0-9]{64}$", msg: "64 hex" } },
              { key: "appVersion", label: m.appVersion, required: true, maxLength: 64, pattern: { re: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$", msg: "1.2.3" } },
              { key: "sdkVersion", label: m.sdkVersion, required: true, maxLength: 32, help: m.sdkHelp, pattern: { re: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,31}$", msg: "1.0" } },
              { key: "validUntil", label: m.bindingValidUntil, required: true, help: m.bindingValidHelp, pattern: { re: "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}$", msg: "YYYY-MM-DD HH:mm" } },
              reasonField(c),
            ]}
            initial={{ packageName: "", signatureSha256: "", appVersion: "", sdkVersion: "", validUntil: "", reason: "" }}
            onSubmit={(v) => run(async () => {
              const b = await api.softpos.register(t, { packageName: String(v.packageName).trim(), signatureSha256: String(v.signatureSha256).trim(), appVersion: String(v.appVersion).trim(), sdkVersion: String(v.sdkVersion).trim(), validUntil: new Date(parseLocalDateTime(v.validUntil)).toISOString(), reason: String(v.reason) });
              setBinding(b); setLookup(String(b.id)); return b;
            })}
            onDone={() => setRegistering(false)} />
        </section>
      )}
      {dialog}
    </div>
  );
}
