"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PlatformCashFilter, PlatformCashSummary, PlatformCashRow } from "@/lib/api/contracts/platform-mall-reporting";
import { useCan } from "@/lib/use-can";
import { useReportingIdentity } from "@/lib/use-reporting-identity";
import { useCopy } from "@/lib/use-copy";
import { formatReportingMinor, validReportingRange } from "@/lib/platform-mall-reporting";
import { Input, Select } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { REPORTING_COPY } from "./reporting-copy";

function localTime(epoch: number): string {
  const date = new Date(epoch);
  return new Date(epoch - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
export function PlatformCashReport({ details = false }: { details?: boolean }) {
  const identity = useReportingIdentity();
  return <PlatformCashReportSession key={JSON.stringify(identity)} identity={identity} details={details} />;
}
function PlatformCashReportSession({ identity, details }: { identity: readonly [string, string, number]; details: boolean }) {
  const c = useCopy(REPORTING_COPY), allow = useCan(), permitted = allow("commerce:platform:cash:read");
  const [initial] = useState(() => { const end = Math.floor(Date.now() / 60000) * 60000; return { fromInclusive: end - 7 * 86400000, toExclusive: end }; });
  const [from, setFrom] = useState(localTime(initial.fromInclusive)), [to, setTo] = useState(localTime(initial.toExclusive));
  const [draft, setDraft] = useState<Pick<PlatformCashFilter, "model" | "entityNo" | "storeNo" | "currency">>({});
  const [filter, setFilter] = useState<PlatformCashFilter>(initial);
  const [invalid, setInvalid] = useState(false), [cursors, setCursors] = useState<string[]>([]);
  const after = cursors.at(-1);
  const q = useQuery({ queryKey: ["platform-mall-cash", identity, filter, details ? after ?? "" : ""], enabled: permitted,
    queryFn: () => api.platformMallCash({ ...filter, after: details ? after : undefined, limit: 20 }) });
  const set = (key: keyof typeof draft, value: string) => setDraft(v => ({ ...v, [key]: value || undefined }));
  const model = (value: string) => value === "ENTITY" ? c.entity : value === "STORE" ? c.store : value;
  const classification = (value: string) => value === "NORMAL" ? c.normal : value === "UNMATCHED" ? c.unmatched : value === "CLOSED_ORDER" ? c.closed : value;
  const summary: Column<PlatformCashSummary>[] = [
    { header: c.model, cell: r => model(r.model) }, { header: c.currency, cell: r => r.currency }, { header: c.classification, cell: r => classification(r.classification) },
    { header: c.received, numeric: true, cell: r => formatReportingMinor(r.currency, r.receivedMinor) }, { header: c.refunded, numeric: true, cell: r => formatReportingMinor(r.currency, r.refundedMinor) }, { header: c.net, numeric: true, cell: r => formatReportingMinor(r.currency, r.netMinor) },
    { header: c.receiptCount, numeric: true, cell: r => r.receiptCount }, { header: c.refundCount, numeric: true, cell: r => r.refundCount },
  ];
  const rows: Column<PlatformCashRow>[] = [
    { header: c.paymentNo, cell: r => <code>{r.paymentNo}</code> }, { header: c.orderNo, cell: r => <code>{r.orderNo}</code> },
    { header: c.model, cell: r => model(r.model) }, { header: c.entityNo, cell: r => r.entityNo }, { header: c.storeNo, cell: r => r.storeNo ?? "—" },
    { header: c.direction, cell: r => r.direction === "PAY" ? c.pay : r.direction === "REFUND" ? c.refund : r.direction },
    { header: c.actualAmount, numeric: true, cell: r => formatReportingMinor(r.currency, r.amountMinor) }, { header: c.classification, cell: r => classification(r.classification) },
    { header: c.originalPaymentNo, cell: r => r.originalPaymentNo ?? "—" }, { header: c.occurredAt, cell: r => new Date(r.occurredAt).toLocaleString() },
  ];
  if (!permitted) return <p role="alert">{c.denied}</p>;
  return <div className="space-y-3">
    <p className="txt-caption text-muted-foreground">{c.cashHint}</p>
    <form className="flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); const start = new Date(from).getTime(), end = new Date(to).getTime(); if (!validReportingRange(start, end)) { setInvalid(true); return; } setInvalid(false); setCursors([]); setFilter({ ...draft, fromInclusive: start, toExclusive: end }); }}>
      <label>{c.from}<Input aria-label={c.from} type="datetime-local" value={from} invalid={invalid} onChange={e => setFrom(e.target.value)} /></label>
      <label>{c.to}<Input aria-label={c.to} type="datetime-local" value={to} invalid={invalid} onChange={e => setTo(e.target.value)} /></label>
      <label>{c.model}<Select aria-label={c.model} value={draft.model ?? ""} onChange={e => set("model", e.target.value)}><option value="">{c.all}</option>{allow("merchant:merchant:read") && <option value="ENTITY">{c.entity}</option>}{allow("commerce:mall:order:read") && <option value="STORE">{c.store}</option>}</Select></label>
      {(["entityNo", "storeNo", "currency"] as const).map(key => <label key={key}>{c[key]}<Input aria-label={c[key]} value={draft[key] ?? ""} maxLength={key === "currency" ? 3 : 100} onChange={e => set(key, e.target.value.trim())} /></label>)}
      <Button type="submit" disabled={q.isFetching}>{c.query}</Button><Button type="button" variant="outline" disabled={q.isFetching} onClick={() => { setCursors([]); if (!after) void q.refetch(); }}>{c.refresh}</Button>
    </form>
    {invalid && <p role="alert">{c.invalidRange}</p>}
    <DataTable columns={summary} rows={q.data?.summary} loading={q.isLoading} error={q.error} onRetry={() => q.refetch()} rowKey={r => `${r.model}:${r.currency}:${r.classification}`} empty={c.empty} />
    <p className="txt-caption text-muted-foreground">{c.cashCoverage}</p>
    {details && <><DataTable columns={rows} rows={q.data?.items} loading={q.isLoading} error={q.error} onRetry={() => q.refetch()} rowKey={r => r.paymentNo} empty={c.empty} />
      <div className="flex justify-end gap-2"><Button variant="outline" disabled={!cursors.length || q.isFetching} onClick={() => setCursors(v => v.slice(0, -1))}>{c.previous}</Button><Button variant="outline" disabled={!q.data?.nextCursor || q.isFetching} onClick={() => setCursors(v => [...v, q.data!.nextCursor!])}>{c.next}</Button></div></>}
  </div>;
}
