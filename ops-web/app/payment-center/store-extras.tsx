"use client";

// 门店详情的三块补缺（TDD-支付中心运营页面 §2.13 P7）：收款码、支付方式、同步事件。
// 写操作都留理由（收款码）或走门店收款策略的版本与资格校验（支付方式），near-pay 侧强制。
import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { BadgeTone } from "@/components/ui/badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Skeleton, ErrorState } from "@/components/ui/misc";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { PayCenterApi } from "@/app/payment-center/_api/api";
import {
  QR_KINDS,
  type MethodChoice, type StoreMethodItem, type StoreMethodSave, type StoreMethodView, type StoreQrCode, type StoreSyncEvent,
} from "@/app/payment-center/_api/types";
import { useCopy, fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { fmtTime, money } from "@/lib/utils";
import { MiniTable } from "./shared";
import { STORE_EXTRAS_COPY, type StoreExtrasCopy } from "./store-extras-copy";

type Run = <R>(fn: () => Promise<R>, ok?: string) => Promise<R>;
const KEY = "pay-center";
const tr = (m: StoreExtrasCopy, k: string, fb: string) => (m as Record<string, string>)[k] ?? fb;
const Note = ({ children }: { children: React.ReactNode }) => <p className="txt-caption text-muted-foreground">{children}</p>;
const key = (c: MethodChoice) => `${c.paymentMethodCode}|${c.test ? 1 : 0}`;

// ── 1 收款码 ─────────────────────────────────────────────────────────────────

const EMPTY_QR = { kind: "STORE", tableRef: "", single: "", daily: "" };
/** 元 → 最小单位；留空交给 near-pay 用默认 */
const toMinor = (v: string): number | null => (v.trim() === "" ? null : Math.round(Number(v) * 100));

export function StoreQrCodes({ api, storeNo, hasMerchant, run }: { api: PayCenterApi; storeNo: string; hasMerchant: boolean; run: Run }) {
  const m = useCopy(STORE_EXTRAS_COPY);
  const allow = useCan();
  const canManage = allow("pay:collection-entry:manage");
  const { confirm, dialog } = useConfirm();
  const [form, setForm] = React.useState(EMPTY_QR);
  const statusMap = React.useMemo<StatusMap<string>>(() => ({ ACTIVE: { label: m.qst_ACTIVE, tone: "success" as BadgeTone }, DISABLED: { label: m.qst_DISABLED, tone: "muted" as BadgeTone } }), [m]);
  const q = useQuery({ queryKey: [KEY, "stores", "qr-codes", api.source, storeNo], queryFn: () => api.stores.qrCodes(storeNo), enabled: hasMerchant });
  if (!hasMerchant) return <Note>{m.qrNoMerchant}</Note>;
  const single = toMinor(form.single), daily = toMinor(form.daily);
  const valid = (form.kind !== "TABLE" || !!form.tableRef.trim())
    && (single == null || (Number.isFinite(single) && single > 0)) && (daily == null || (Number.isFinite(daily) && daily > 0));

  const create = async () => {
    const fresh = await q.refetch(); // 建码非幂等：确认前用最新列表告诉人现在有几个
    const active = (fresh.data ?? []).filter((c) => c.status === "ACTIVE").length;
    void confirm({
      title: m.qrNewTitle, desc: fill(m.qrNewDesc, { n: active }), requireReason: true, confirmText: m.qrNew,
      action: async (reason) => {
        await run(() => api.stores.createQrCode(storeNo, { kind: form.kind, tableRef: form.kind === "TABLE" ? form.tableRef.trim() : null, singleLimitMinor: single, dailyLimitMinor: daily, reason }));
        setForm(EMPTY_QR);
        await q.refetch();
      },
    });
  };
  const disable = (c: StoreQrCode) => void confirm({
    title: fill(m.qrDisableTitle, { code: c.qrCode }), desc: m.qrDisableDesc, danger: true, requireReason: true, confirmText: m.qrDisable,
    action: async (reason) => { await run(() => api.stores.disableQrCode(storeNo, c.qrCode, { expectedVersion: c.version ?? 0, reason })); await q.refetch(); },
  });

  return (
    <div className="space-y-3">
      <Note>{m.qrIntro}</Note>
      {q.isPending ? <Skeleton className="h-16 w-full" /> : q.error ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
        <MiniTable<StoreQrCode> rows={q.data} rowKey={(c) => c.qrCode} empty={m.qrEmpty} columns={[
          { header: m.colCode, cell: (c) => <span className="txt-caption">{c.qrCode}</span> },
          { header: m.colKind, cell: (c) => tr(m, `kind_${c.kind}`, c.kind) },
          { header: m.colTable, cell: (c) => c.tableRef ?? "—" },
          { header: m.colLimits, cell: (c) => `${money(c.singleLimitMinor, c.currency)} / ${money(c.dailyLimitMinor, c.currency)}` },
          { header: m.colStatus, cell: (c) => <StatusBadge value={c.status} map={statusMap} /> },
          { header: m.colLinks, cell: (c) => (c.url || c.storeCodeUrl ? (
            <span className="flex flex-col gap-0.5">
              {c.url && <a className="focus-ring txt-caption underline" href={c.url} target="_blank" rel="noopener noreferrer">{m.linkPay}</a>}
              {c.storeCodeUrl && <a className="focus-ring txt-caption underline" href={c.storeCodeUrl} target="_blank" rel="noopener noreferrer">{m.linkStore}</a>}
            </span>
          ) : <span className="txt-caption text-warning-ink">{m.noLink}</span>) },
          { header: "", cell: (c) => canManage && c.status === "ACTIVE" && (c.version == null
            ? <span className="txt-caption text-muted-foreground" title={m.qrNoVersion}>—</span>
            : <Button size="sm" variant="ghost" onClick={() => disable(c)}>{m.qrDisable}</Button>) },
        ]} />
      )}
      {canManage && (
        <section className="space-y-2" aria-label={m.qrNew}>
          <h4 className="txt-label">{m.qrNew}</h4>
          <div className="grid gap-2 sm:grid-cols-2">
            <div className="flex gap-2" role="radiogroup" aria-label={m.qrKind}>
              {QR_KINDS.map((k) => (
                <Button key={k} size="sm" variant={form.kind === k ? "default" : "outline"} aria-pressed={form.kind === k} onClick={() => setForm((f) => ({ ...f, kind: k }))}>{tr(m, `kind_${k}`, k)}</Button>
              ))}
            </div>
            <Input aria-label={m.qrTableRef} placeholder={m.qrTableRefPh} disabled={form.kind !== "TABLE"} value={form.tableRef} onChange={(e) => setForm((f) => ({ ...f, tableRef: e.target.value }))} />
            <Input aria-label={m.qrSingleLimit} placeholder={m.qrSingleLimit} inputMode="decimal" value={form.single} onChange={(e) => setForm((f) => ({ ...f, single: e.target.value }))} />
            <Input aria-label={m.qrDailyLimit} placeholder={m.qrDailyLimit} inputMode="decimal" value={form.daily} onChange={(e) => setForm((f) => ({ ...f, daily: e.target.value }))} />
          </div>
          <Button size="sm" variant="outline" disabled={!valid} onClick={() => void create()}>{m.qrNew}</Button>
        </section>
      )}
      {dialog}
    </div>
  );
}

// ── 2 支付方式 ───────────────────────────────────────────────────────────────

/**
 * 与 near-pay StoreCollectionMethodService 同一口径：可改项全部开启 = 跟随商户（INHERIT，开启集合留空）；
 * 否则单独设置（OVERRIDE，带开启集合）。不可改的项不出现在开启集合里。
 */
export function buildMethodSave(view: StoreMethodView, on: ReadonlySet<string>): StoreMethodSave {
  const editable = view.items.filter((i) => i.editable);
  const enabled = editable.filter((i) => on.has(key(i.choice))).map((i) => i.choice);
  const all = enabled.length === editable.length;
  return { expectedVersion: view.version, eligibilityRevision: view.eligibilityRevision, mode: all ? "INHERIT" : "OVERRIDE", enabledMethods: all ? [] : enabled };
}

export function initialOn(view: StoreMethodView): Set<string> {
  return new Set(view.items.filter((i) => i.editable && i.configuredEnabled).map((i) => key(i.choice)));
}

export function StoreMethods({ api, storeNo, hasMerchant, run }: { api: PayCenterApi; storeNo: string; hasMerchant: boolean; run: Run }) {
  const m = useCopy(STORE_EXTRAS_COPY);
  const allow = useCan();
  const canManage = allow("pay:collection-policy:manage");
  const { confirm, dialog } = useConfirm();
  const q = useQuery({ queryKey: [KEY, "stores", "methods", api.source, storeNo], queryFn: () => api.stores.methods(storeNo), enabled: hasMerchant });
  const names = useQuery({ queryKey: [KEY, "payment-methods", api.source], queryFn: () => api.products.paymentMethods(), staleTime: 10 * 60_000, retry: false });
  const [on, setOn] = React.useState<Set<string> | null>(null);
  React.useEffect(() => { if (q.data) setOn(initialOn(q.data)); }, [q.data]);
  if (!hasMerchant) return <Note>{m.qrNoMerchant}</Note>;
  if (q.isPending || !on) return <Skeleton className="h-16 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const view = q.data;
  const nameOf = (code: string) => names.data?.find((n) => n.code === code)?.name ?? code;
  const save = buildMethodSave(view, on);
  const changed = [...initialOn(view)].sort().join() !== [...on].sort().join();
  const toggle = (i: StoreMethodItem, v: boolean) => setOn((prev) => { const next = new Set(prev); if (v) next.add(key(i.choice)); else next.delete(key(i.choice)); return next; });
  const submit = () => void confirm({
    title: m.methodsSaveTitle, desc: fill(m.methodsSaveDesc, { mode: save.mode === "INHERIT" ? m.modeInherit : m.modeOverride }), confirmText: m.methodsSave,
    action: async () => { await run(() => api.stores.saveMethods(storeNo, save)); await q.refetch(); },
  });

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge>{view.mode === "INHERIT" ? m.modeInherit : m.modeOverride}</Badge>
        <Note>{m.methodsIntro}</Note>
      </div>
      <MiniTable<StoreMethodItem> rows={view.items} rowKey={(i) => key(i.choice)} empty={m.methodsEmpty} columns={[
        { header: m.colMethod, cell: (i) => (
          <span className="flex items-center gap-1">{nameOf(i.choice.paymentMethodCode)}{i.choice.test && <Badge tone="warning">{m.testTag}</Badge>}</span>
        ) },
        { header: m.colEnabled, cell: (i) => (canManage && i.editable
          ? <Switch aria-label={nameOf(i.choice.paymentMethodCode)} checked={on.has(key(i.choice))} onChange={(v) => toggle(i, v)} />
          : (i.configuredEnabled ? m.yes : m.no)) },
        { header: m.colEffective, cell: (i) => (i.effectiveEnabled ? m.yes : m.no) },
        { header: m.colChannels, cell: (i) => <span className="txt-caption">{[...i.channelCodes, ...i.productCodes].join(" · ") || "—"}</span> },
        { header: m.colBlocked, cell: (i) => (i.blockedReason ? tr(m, `blocked_${i.blockedReason}`, i.blockedReason) : "—") },
      ]} />
      {canManage && view.items.some((i) => i.editable) && (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" disabled={!changed} onClick={submit}>{m.methodsSave}</Button>
          <Button size="sm" variant="ghost" onClick={() => setOn(new Set(view.items.filter((i) => i.editable).map((i) => key(i.choice))))}>{m.methodsReset}</Button>
          {changed && <span className="txt-caption text-warning-ink">{m.methodsChanged}</span>}
        </div>
      )}
      {dialog}
    </div>
  );
}

// ── 3 同步事件 ───────────────────────────────────────────────────────────────

export function StoreSyncEvents({ api, storeNo }: { api: PayCenterApi; storeNo: string }) {
  const m = useCopy(STORE_EXTRAS_COPY);
  const q = useQuery({ queryKey: [KEY, "stores", "sync-events", api.source, storeNo], queryFn: () => api.stores.syncEvents(storeNo, { page: 1, size: 20 }) });
  if (q.isPending) return <Skeleton className="h-12 w-full" />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  return (
    <MiniTable<StoreSyncEvent> rows={q.data.records} rowKey={(e) => e.eventNo} empty={m.syncEmpty} columns={[
      { header: m.colSource, cell: (e) => <span className="txt-caption">{e.sourceSystem} · {e.eventId.length > 24 ? `${e.eventId.slice(0, 24)}…` : e.eventId}</span> },
      { header: m.colChannel, cell: (e) => e.channel },
      { header: m.colRevision, cell: (e) => <span className="txt-caption">{e.revision}</span> },
      { header: m.colOutcome, cell: (e) => tr(m, `outcome_${e.outcome}`, e.outcome) },
      { header: m.colIssue, cell: (e) => (e.issue ? <span className="text-warning-ink">{e.issue}</span> : "—") },
      { header: m.colTime, cell: (e) => fmtTime(e.createdAt) },
    ]} />
  );
}
