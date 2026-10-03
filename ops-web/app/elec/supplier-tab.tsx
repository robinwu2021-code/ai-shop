"use client";

// 供应商（P-19.2）。列表 · 详情（含他的库存与派单响应）· 暂停 / 恢复 / 改资料。
// **暂停当场生效**：他的货从买家那边撤下（后端重算他全部料号的投影），所以要写理由。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ElecOpsSupplierDetail, ElecOpsSupplierRow, ElecStockFilter, ElecStockView } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { FormDrawer, type FieldDef } from "@/components/ui/form-drawer";
import type { FormValues } from "@/lib/form-validate";
import { Input } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { StatusBadge } from "@/components/ui/status-badge";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { notify } from "@/lib/notify";
import { useCan } from "@/lib/use-can";
import { fill } from "@/lib/use-copy";
import type { ElecCopy } from "./copy";
import { lead, price, qty, when } from "./fmt";
import { supplierStatusMap } from "./status";

const KINDS = ["AGENT", "TRADER", "FACTORY", "OTHER"] as const;

export function kindLabel(kind: string, c: ElecCopy): string {
  const k = `kind${kind}` as keyof ElecCopy;
  return (c[k] as string | undefined) ?? kind;
}

export function SupplierTab({ c }: { c: ElecCopy }) {
  const [kwInput, setKwInput] = useState("");
  const [kw, setKw] = useState("");
  const [status, setStatus] = useState("");
  const [openNo, setOpenNo] = useState<string | null>(null);
  const can = useCan();
  const canManage = can("elec:supplier:manage");
  const statusMap = supplierStatusMap(c);

  const list = useQuery({
    queryKey: ["elec-sup", kw, status],
    queryFn: () => api.listElecSuppliers({ keyword: kw || undefined, status: (status || undefined) as "PENDING" | "ACTIVE" | "SUSPENDED" | undefined }),
  });

  const columns: Column<ElecOpsSupplierRow>[] = [
    { header: c.supColCompany, cell: (r) => <div>{r.companyName ?? c.supUnnamed}<div className="font-mono txt-caption text-muted-foreground">{r.supplierNo} · {r.maskCode}</div></div> },
    { header: c.colStatus, cell: (r) => <StatusBadge map={statusMap} value={r.status} /> },
    { header: c.supColKind, cell: (r) => [kindLabel(r.kind, c), r.city].filter(Boolean).join(" · ") },
    { header: c.supColContact, cell: (r) => <div>{r.contactName ?? c.none}<div className="txt-caption text-muted-foreground">{r.contactPhone}</div></div> },
    { header: c.supColOn, cell: (r) => qty(r.onCount), numeric: true },
    { header: c.supColExpiring, cell: (r) => qty(r.expiringCount), numeric: true },
    { header: c.supColLastUpload, cell: (r) => when(r.lastUploadAt) },
  ];

  return (
    <div className="space-y-4">
      {!canManage && <ReadOnlyNotice what={c.supManageWhat} perm="elec:supplier:manage" note={c.supManageNote} />}
      <Toolbar>
        <Input className="w-64" value={kwInput} placeholder={c.supKwPh}
          onChange={(e) => setKwInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && setKw(kwInput.trim())} />
        <FilterSelect value={status} onChange={setStatus} options={statusMap} allLabel={c.supStatusAll} aria-label={c.colStatus} />
      </Toolbar>
      <DataTable columns={columns} rows={list.data} loading={list.isLoading} error={list.error}
        onRetry={() => list.refetch()} empty={c.supEmpty} rowKey={(r) => r.supplierNo}
        rowProps={(r) => ({ onClick: () => setOpenNo(r.supplierNo), className: "cursor-pointer" })} />
      {openNo && <SupplierDrawer c={c} supplierNo={openNo} canManage={canManage} onClose={() => setOpenNo(null)} />}
    </div>
  );
}

function SupplierDrawer({ c, supplierNo, canManage, onClose }: {
  c: ElecCopy; supplierNo: string; canManage: boolean; onClose: () => void;
}) {
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [filter, setFilter] = useState<ElecStockFilter>("ALL");
  const [edit, setEdit] = useState(false);
  const [form, setForm] = useState<FormValues>({});
  const statusMap = supplierStatusMap(c);

  const detail = useQuery({ queryKey: ["elec-sup-detail", supplierNo], queryFn: () => api.getElecSupplier(supplierNo) });
  const stocks = useQuery({
    queryKey: ["elec-sup-stock", supplierNo, filter],
    queryFn: () => api.listElecSupplierStocks(supplierNo, { filter }),
  });
  const d = detail.data;
  const refresh = (next: ElecOpsSupplierDetail) => {
    qc.setQueryData(["elec-sup-detail", supplierNo], next);
    void qc.invalidateQueries({ queryKey: ["elec-sup"] });
  };

  const save = useMutation({
    mutationFn: () => api.updateElecSupplier(supplierNo, {
      companyName: String(form.companyName ?? "") || undefined,
      kind: String(form.kind ?? "") || undefined,
      city: String(form.city ?? "") || undefined,
      address: String(form.address ?? "") || undefined,
      contactName: String(form.contactName ?? "") || undefined,
      contactPhone: String(form.contactPhone ?? "") || undefined,
    }),
    onSuccess: (next) => { refresh(next); setEdit(false); notify.success(c.supSaved); },
    onError: (e) => notify.error((e as Error).message),
  });

  const fields: FieldDef[] = [
    { key: "companyName", label: c.fCompany, maxLength: 60 },
    { key: "kind", label: c.fKind, type: "select", options: KINDS.map((k) => ({ value: k, label: kindLabel(k, c) })) },
    { key: "city", label: c.fCity, maxLength: 20 },
    { key: "address", label: c.fAddress, maxLength: 120 },
    { key: "contactName", label: c.fContact, maxLength: 20 },
    { key: "contactPhone", label: c.fPhone, pattern: { re: "^1\\d{10}$", msg: c.fPhone } },
  ];

  const suspend = () => d && void confirm({
    title: fill(c.supSuspendTitle, { name: d.companyName ?? d.supplierNo }), desc: c.supSuspendDesc, danger: true,
    requireReason: true,
    action: async (reason) => { refresh(await api.suspendElecSupplier(supplierNo, reason)); notify.success(c.supSuspended); },
  });
  const resume = async () => {
    try {
      refresh(await api.resumeElecSupplier(supplierNo));
      notify.success(c.supResumed);
    } catch (e) {
      notify.error((e as Error).message);
    }
  };
  const approve = async () => {
    try {
      refresh(await api.approveElecSupplier(supplierNo));
      notify.success(c.supApproved);
    } catch (e) {
      notify.error((e as Error).message);
    }
  };

  const footer = d && canManage ? (
    <div className="flex gap-2">
      <Button variant="outline" onClick={() => {
        setForm({ companyName: d.companyName ?? "", kind: d.kind, city: d.city ?? "", address: d.address ?? "", contactName: d.contactName ?? "", contactPhone: d.contactPhone ?? "" });
        setEdit(true);
      }}>{c.supEdit}</Button>
      <div className="flex-1" />
      {d.status === "PENDING" ? (
        <>
          <Button variant="destructive" onClick={suspend}>{c.supReject}</Button>
          <Button onClick={() => void approve()}>{c.supApprove}</Button>
        </>
      ) : d.status === "ACTIVE" ? (
        <Button variant="destructive" onClick={suspend}>{c.supSuspend}</Button>
      ) : (
        <Button onClick={() => void resume()}>{c.supResume}</Button>
      )}
    </div>
  ) : undefined;

  const stockCols: Column<ElecStockView>[] = [
    { header: c.colMpn, cell: (s) => <div>{s.mpn}<div className="txt-caption text-muted-foreground">{s.mfr ?? c.none}</div></div> },
    { header: c.colQty, cell: (s) => qty(s.qty), numeric: true },
    { header: c.colDc, cell: (s) => s.dateCode ?? c.none },
    { header: c.colPrice, cell: (s) => `${price(s.priceE6, s.currency)} ${s.taxIncluded ? c.taxIn : c.taxEx}` },
    { header: c.colLead, cell: (s) => lead(s.leadDays, c) },
    { header: c.quoteColValid, cell: (s) => s.validUntil },
  ];

  return (
    <Drawer open onOpenChange={(o) => !o && onClose()} title={d?.companyName ?? supplierNo} width="w-[880px]" footer={footer}>
      {d && (
        <>
          {d.status === "SUSPENDED" && d.suspendReason && (
            <Notice tone="warning">{fill(c.supSuspendedBecause, { r: d.suspendReason })}</Notice>
          )}
          <DrawerSection title={c.supColCompany} first>
            <FieldGrid>
              <Field label={c.colStatus}><StatusBadge map={statusMap} value={d.status} /></Field>
              <Field label={c.supMaskCode}><span className="font-mono">{d.maskCode}</span></Field>
              <Field label={c.supColKind}>{[kindLabel(d.kind, c), d.city].filter(Boolean).join(" · ")}</Field>
              <Field label={c.supColContact}>{[d.contactName, d.contactPhone].filter(Boolean).join(" · ")}</Field>
              <Field label={c.fAddress}>{d.address || c.none}</Field>
              <Field label={c.supColOn}>{qty(d.onCount)}</Field>
              <Field label={c.supColExpiring}>{qty(d.expiringCount)}</Field>
              <Field label={c.supExpired}>{qty(d.expiredCount)}</Field>
              <Field label={c.supColLastUpload}>{when(d.lastUploadAt)}</Field>
            </FieldGrid>
            <div className="txt-caption text-muted-foreground">{d.registerNotified ? c.supRegisterNotified : c.supRegisterNotNotified}</div>
          </DrawerSection>
          <DrawerSection title={fill(c.supDispatch, { d: d.dispatch.days })}>
            <div className="txt-body tabular-nums">{fill(c.supDispatchLine, { ...d.dispatch })}</div>
          </DrawerSection>
          <DrawerSection title={c.supStock}>
            <Tabs tabs={[{ key: "ALL", label: c.stockAll }, { key: "EXPIRING", label: c.stockExpiring }, { key: "EXPIRED", label: c.stockExpired }]}
              value={filter} onChange={(k) => setFilter(k as ElecStockFilter)} />
            <DataTable columns={stockCols} rows={stocks.data} loading={stocks.isLoading} error={stocks.error}
              onRetry={() => stocks.refetch()} empty={c.supStockEmpty} rowKey={(s) => s.stockNo} />
          </DrawerSection>
        </>
      )}
      <FormDrawer open={edit} onOpenChange={setEdit} titleNew={c.supEdit} titleEdit={c.supEdit} isEdit
        fields={fields} value={form} onChange={setForm} onSubmit={() => save.mutate()} submitting={save.isPending} />
      {dialog}
    </Drawer>
  );
}
