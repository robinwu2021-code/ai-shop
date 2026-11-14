"use client";
import { useCallback, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PlatformOrderFilter, PlatformOrderRow, PurchaseModel } from "@/lib/api/contracts/platform-mall-reporting";
import { useCan } from "@/lib/use-can";
import { useReportingIdentity } from "@/lib/use-reporting-identity";
import { useCopy } from "@/lib/use-copy";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { FinancialLockContext } from "@/components/offline/financial-lock";
import { EntityPlanPurchaseDetail } from "@/app/merchants/plan-purchases";
import { OrderDrawer } from "./order-drawer";
import { MALL_COPY } from "./copy";
import { REPORTING_COPY } from "./reporting-copy";
import { formatReportingMinor } from "@/lib/platform-mall-reporting";

function localTime(epoch: number): string {
  return new Date(epoch - new Date(epoch).getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

export function PlatformOrderList({ pendingOnly = false }: { pendingOnly?: boolean }) {
  const identity = useReportingIdentity();
  return <PlatformOrderListSession key={JSON.stringify(identity)} identity={identity} pendingOnly={pendingOnly} />;
}
function PlatformOrderListSession({ identity, pendingOnly }: { identity: readonly [string, string, number]; pendingOnly: boolean }) {
  const c = useCopy(REPORTING_COPY), mall = useCopy(MALL_COPY), allow = useCan();
  const permitted = allow(pendingOnly ? "commerce:platform:claims:read" : "commerce:platform:orders:read");
  const [draft, setDraft] = useState<PlatformOrderFilter>({});
  const [filter, setFilter] = useState<PlatformOrderFilter>({});
  const [cursors, setCursors] = useState<string[]>([]);
  const [selected, setSelected] = useState<PlatformOrderRow | null>(null);
  const [locks, setLocks] = useState<Set<string>>(() => new Set());
  const lock = useCallback((id: string, locked: boolean) => setLocks(before => {
    if (before.has(id) === locked) return before;
    const next = new Set(before); if (locked) next.add(id); else next.delete(id); return next;
  }), []);
  const after = cursors.at(-1);
  const q = useQuery({ queryKey: ["platform-mall-orders", identity, pendingOnly, filter, after ?? ""], enabled: permitted,
    queryFn: () => (pendingOnly ? api.platformMallClaims : api.platformMallOrders)({ ...filter, after, limit: 20 }) });
  const detail = useQuery({ queryKey: ["entity-plan-purchase", selected?.entityNo, selected?.orderNo, identity],
    enabled: permitted && selected?.model === "ENTITY" && allow("merchant:merchant:read"),
    queryFn: () => api.entityPlanPurchase(selected!.entityNo, selected!.orderNo) });
  const locked = !!selected || locks.size > 0;
  const set = (key: keyof PlatformOrderFilter, value: string) => setDraft(v => ({ ...v, [key]: value || undefined }));
  const modelLabel = (model: PurchaseModel) => model === "ENTITY" ? c.entity : c.store;
  const queueModels = {
    ENTITY: allow("merchant:merchant:read") && (!pendingOnly || (allow("merchant:merchant:ban") && allow("commerce:mall:offline:review"))),
    STORE: allow("commerce:mall:order:read") && (!pendingOnly || allow("commerce:mall:offline:review")),
  };
  const canDetail = (row: PlatformOrderRow) => allow(row.model === "ENTITY" ? "merchant:merchant:read" : "commerce:mall:order:read") && (row.model === "ENTITY" || !!row.storeNo);
  const columns: Column<PlatformOrderRow>[] = [
    { header: c.model, cell: r => modelLabel(r.model) },
    { header: c.orderNo, cell: r => <code>{r.orderNo}</code> },
    { header: c.entityNo, cell: r => r.entityNo }, { header: c.storeNo, cell: r => r.storeNo ?? "—" },
    { header: c.amount, numeric: true, cell: r => formatReportingMinor(r.currency, r.payableMinor) },
    { header: c.status, cell: r => r.status }, { header: c.claimStatus, cell: r => r.claimStatus ?? "—" },
    { header: c.createdAt, cell: r => new Date(r.createdAt).toLocaleString() },
    { header: c.detail, stickyEnd: "always", cell: r => <Button size="sm" disabled={locked || !canDetail(r)} title={canDetail(r) ? undefined : !r.storeNo && r.model === "STORE" ? c.missingStore : c.noDetail} onClick={() => setSelected(r)}>{c.detail}</Button> },
  ];
  if (!permitted) return <p role="alert">{c.denied}</p>;
  return <FinancialLockContext.Provider value={lock}><div className="space-y-3">
    <p className="txt-caption text-muted-foreground">{pendingOnly ? c.claimHint : c.orderHint}</p>
    <form className="flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); if (locked) return; setFilter(draft); setCursors([]); }}>
      <label>{c.model}<Select aria-label={c.model} value={draft.model ?? ""} disabled={locked} onChange={e => set("model", e.target.value)}><option value="">{c.all}</option>{queueModels.ENTITY && <option value="ENTITY">{c.entity}</option>}{queueModels.STORE && <option value="STORE">{c.store}</option>}</Select></label>
      {(["entityNo", "storeNo", "orderNo", "status", "currency"] as const).map(key => <label key={key}>{c[key]}<Input aria-label={c[key]} value={draft[key] ?? ""} maxLength={key === "currency" ? 3 : 100} disabled={locked} onChange={e => set(key, e.target.value.trim())} /></label>)}
      {(["createdFrom", "createdTo"] as const).map(key => <label key={key}>{c[key]}<Input aria-label={c[key]} type="datetime-local" disabled={locked} value={draft[key] === undefined ? "" : localTime(draft[key])} onChange={e => { const epoch = e.target.value ? new Date(e.target.value).getTime() : undefined; if (epoch === undefined || Number.isSafeInteger(epoch)) setDraft(v => ({ ...v, [key]: epoch })); }} /></label>)}
      <Button type="submit" disabled={locked || q.isFetching}>{c.query}</Button>
      <Button type="button" variant="outline" disabled={locked || q.isFetching} onClick={() => { setCursors([]); if (!after) void q.refetch(); }}>{c.refresh}</Button>
    </form>
    {q.data && <p className="txt-caption">{c.included}: {q.data.includedModels.map(modelLabel).join(" / ")}</p>}
    <DataTable columns={columns} rows={q.data?.items} loading={q.isLoading} error={q.error} onRetry={() => q.refetch()} rowKey={r => `${r.model}:${r.orderNo}`} empty={c.empty} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!cursors.length || locked || q.isFetching} onClick={() => setCursors(v => v.slice(0, -1))}>{c.previous}</Button><Button variant="outline" disabled={!q.data?.nextCursor || locked || q.isFetching} onClick={() => setCursors(v => [...v, q.data!.nextCursor!])}>{c.next}</Button></div>
    {selected?.model === "STORE" && <OrderDrawer key={`${selected.storeNo}:${selected.orderNo}`} c={mall} storeNo={selected.storeNo!} orderNo={selected.orderNo} onClose={() => { if (!locks.size) { setSelected(null); void q.refetch(); } }} />}
    <Drawer open={selected?.model === "ENTITY"} onOpenChange={open => { if (!open && !locks.size) { setSelected(null); void q.refetch(); } }} title={selected?.orderNo ?? ""} desc={selected?.entityNo}>
      {detail.error && <p role="alert">{c.error}<Button variant="outline" onClick={() => detail.refetch()}>{c.retry}</Button></p>}
      {detail.data && <EntityPlanPurchaseDetail key={`${selected?.entityNo}:${selected?.orderNo}`} order={detail.data} onChanged={() => Promise.all([q.refetch(), detail.refetch()])} />}
    </Drawer>
  </div></FinancialLockContext.Provider>;
}
