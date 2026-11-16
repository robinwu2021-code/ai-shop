"use client";
import { useCallback, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/use-can";
import { useCopy } from "@/lib/use-copy";
import type { EntityPlanPurchase } from "@/lib/entity-plan-purchase-types";
import { FinancialLockContext } from "@/components/offline/financial-lock";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { EntityPlanPurchaseDetail } from "@/app/merchants/plan-purchases";
import { EntityAssistedPurchase } from "@/app/merchants/plan-assisted-purchase";
import { Money } from "./shared";
import { PURCHASE_WORKSPACE_COPY } from "./purchase-workspace-copy";

const PAGE = 20;
export function EntityOrdersTab({ entityNo, initialOrderNo, onContextLockChange }: {
  entityNo: string; initialOrderNo: string | null; onContextLockChange: (locked: boolean) => void;
}) {
  const c = useCopy(PURCHASE_WORKSPACE_COPY), allow = useCan();
  const [cursors, setCursors] = useState<string[]>([]), [selected, setSelected] = useState<string | null>(initialOrderNo);
  const [locks, setLocks] = useState<Set<string>>(() => new Set());
  const [assisting, setAssisting] = useState(false);
  const lock = useCallback((id: string, locked: boolean) => setLocks(before => {
    if (before.has(id) === locked) return before;
    const next = new Set(before); if (locked) next.add(id); else next.delete(id); return next;
  }), []);
  useEffect(() => { onContextLockChange(!!selected || assisting || locks.size > 0); return () => onContextLockChange(false); }, [selected, assisting, locks.size, onContextLockChange]);
  const after = cursors[cursors.length - 1];
  const q = useQuery({ queryKey: ["entity-plan-purchase-page", entityNo, after ?? ""], queryFn: () => api.entityPlanPurchasePage(entityNo, { afterOrderNo: after, limit: PAGE }) });
  const detail = useQuery({ queryKey: ["entity-plan-purchase", entityNo, selected], enabled: !!selected, queryFn: () => api.entityPlanPurchase(entityNo, selected!) });
  const canAssist = allow("merchant:merchant:ban") && allow("commerce:mall:assisted:purchase");
  const offers = useQuery({ queryKey: ["entity-plan-offers", entityNo], enabled: assisting && canAssist && q.isSuccess, queryFn: () => api.entityPlanOffers(entityNo) });
  const columns: Column<EntityPlanPurchase>[] = [
    { header: c.order, cell: r => <code>{r.orderNo}</code> }, { header: c.storeNo, cell: r => r.storeNo },
    { header: c.offer, cell: r => r.offer.name }, { header: c.amount, numeric: true, cell: r => <Money m={{ currency: r.offer.price.currency, minor: String(r.offer.price.minor) }} /> },
    { header: c.status, cell: r => r.status }, { header: c.detail, cell: r => <Button size="sm" disabled={assisting || locks.size > 0} onClick={() => setSelected(r.orderNo)}>{c.detail}</Button> },
  ];
  return <FinancialLockContext.Provider value={lock}><div className="space-y-3">
    <DataTable columns={columns} rows={q.data} loading={q.isLoading} error={q.error} onRetry={() => q.refetch()} rowKey={r => r.orderNo} empty={c.empty} />
    <div className="flex justify-end gap-2">
      <Button variant="outline" disabled={!cursors.length || !!selected || assisting || locks.size > 0} onClick={() => setCursors(v => v.slice(0, -1))}>{c.previous}</Button>
      <Button variant="outline" disabled={q.isFetching || (q.data?.length ?? 0) < PAGE || !!selected || assisting || locks.size > 0} onClick={() => setCursors(v => [...v, q.data![q.data!.length - 1].orderNo])}>{c.next}</Button>
    </div>
    {canAssist && <Button disabled={!q.isSuccess || !!selected || locks.size > 0} onClick={() => setAssisting(true)}>{c.assist}</Button>}
    <Drawer open={assisting} onOpenChange={open => { if (!open && !locks.size) setAssisting(false); }} title={c.assist} desc={entityNo}>
      {offers.error && <p role="alert">{c.error}<Button variant="outline" onClick={() => offers.refetch()}>{c.retry}</Button></p>}
      {canAssist && offers.data && <EntityAssistedPurchase key={entityNo} entityNo={entityNo} offers={offers.data} onChanged={() => q.refetch()} />}
    </Drawer>
    <Drawer open={!!selected} onOpenChange={open => { if (!open && !locks.size) setSelected(null); }} title={selected ?? ""} desc={entityNo}>
      {detail.error && <p role="alert">{c.error}<Button variant="outline" onClick={() => detail.refetch()}>{c.retry}</Button></p>}
      {detail.data && <EntityPlanPurchaseDetail key={`${entityNo}:${selected}`} order={detail.data} onChanged={() => Promise.all([q.refetch(), detail.refetch()])} />}
    </Drawer>
  </div></FinancialLockContext.Provider>;
}
