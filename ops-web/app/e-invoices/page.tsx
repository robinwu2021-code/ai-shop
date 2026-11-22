"use client";

// 运营端 · 电子发票（TDD-发票模块 §18）。三个页签：营业人一览（配置、余量、积压）、上传任务、发票查询。
// 处置按钮只在有 finance:invoice:verify 时出现；核验、领号、查单按后端给的供应商能力集合显示（TDD-发票-供应商能力定义完善 D1），不认厂商码。
import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type {
  EInvoice, EInvoiceProblem, EInvoiceSeller, EInvoiceTransmission, EInvoiceUploadStatus,
} from "@/lib/api/invoice-types";
import { useCan } from "@/lib/use-can";
import { usePaging } from "@/lib/use-paging";
import { useServerMenu, isPointUnimplemented } from "@/lib/stores/server-menu";
import { useCopy } from "@/lib/use-copy";
import { TabHeader } from "@/components/ui/tab-header";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { HelpNote } from "@/components/ui/help-note";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Pagination } from "@/components/ui/misc";
import { E_INVOICE_COPY } from "./copy";

const READ = "finance:invoice:read", VERIFY = "finance:invoice:verify";
const PROBLEMS: readonly EInvoiceProblem[] = ["HELD", "OVERDUE", "REVIEW", "BACKLOG", "LOW_RANGE"];
const UPLOAD: readonly EInvoiceUploadStatus[] = ["PENDING", "SENDING", "SENT", "FAILED", "HELD", "NEEDS_REVIEW"];
const GATEWAYS = ["STUB", "TURNKEY", "CETUSTEK", "UNIVERSALEC"] as const;
/** 供应商能力（后端 ProviderCapability） */
const can = (r: { capabilities?: string[] }, capability: string) => !!r.capabilities?.includes(capability);
type Copy = typeof E_INVOICE_COPY.zh;
type Tab = "sellers" | "transmissions" | "invoices";

/** 发票金额以分存（minor），票面是整元 */
const money = (minor: number, currency: string) => `${currency} ${(minor / 100).toLocaleString()}`;
const errorText = (e: unknown) => (e instanceof Error ? e.message : String(e));

export default function EInvoicesPage() {
  const c = useCopy(E_INVOICE_COPY), can = useCan();
  const closed = useServerMenu((s) => isPointUnimplemented(s.byHref, "/e-invoices"));
  const [tab, setTab] = useState<Tab>("sellers");
  const tabs = [
    { key: "sellers", label: c.tabSellers }, { key: "transmissions", label: c.tabTransmissions },
    { key: "invoices", label: c.tabInvoices },
  ];
  const verify = can(VERIFY);
  return <div className="space-y-4">
    <TabHeader tabs={tabs} value={tab} onChange={(k) => setTab(k as Tab)} desc={c.desc}/>
    <HelpNote>{c.notice}</HelpNote>
    {!can(READ) ? <p role="alert">{c.readDenied}</p>
      : closed ? <p role="alert">{c.closed}</p>
      : IS_MOCK ? <p role="status">{c.mock}</p>
      : <>
        {!verify && <p className="txt-caption text-muted-foreground">{c.readonly}</p>}
        {tab === "sellers" && <Sellers c={c} verify={verify}/>}
        {tab === "transmissions" && <Transmissions c={c} verify={verify}/>}
        {tab === "invoices" && <Invoices c={c} verify={verify}/>}
      </>}
  </div>;
}

/** 动作结果条：成功 / 失败各一行，避免每个页签各写一套 */
function useOutcome() {
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  async function run<T>(action: () => Promise<T>, done: (r: T) => string, after?: () => void) {
    if (busy) return;
    setBusy(true); setMsg(null);
    try { const r = await action(); setMsg({ ok: true, text: done(r) }); after?.(); }
    catch (e) { setMsg({ ok: false, text: errorText(e) }); }
    finally { setBusy(false); }
  }
  const bar = msg && <p role={msg.ok ? "status" : "alert"} className={msg.ok ? "text-success-ink" : "text-destructive"}>{msg.text}</p>;
  return { run, busy, bar };
}

function Sellers({ c, verify }: { c: Copy; verify: boolean }) {
  const [filter, setFilter] = useState<{ problem?: EInvoiceProblem; gatewayCode?: string; keyword?: string }>({});
  const { page, setPage, size, setSize } = usePaging();
  const q = useQuery({ queryKey: ["e-invoice-sellers", filter, page, size], queryFn: () => api.eInvoiceSellers({ ...filter, page, size }) });
  const { run, busy, bar } = useOutcome();
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget), v = (k: string) => String(f.get(k) ?? "").trim() || undefined;
    setFilter({ problem: v("problem") as EInvoiceProblem | undefined, gatewayCode: v("gatewayCode"), keyword: v("keyword") });
    setPage(1);
  }
  const cols: Column<EInvoiceSeller>[] = [
    { header: c.entity, cell: (r) => r.entityNo },
    { header: c.seller, cell: (r) => <>{r.sellerName}<br/><span className="txt-caption text-muted-foreground">{r.sellerTaxId}</span></> },
    { header: c.gateway, cell: (r) => <>{r.gatewayCode}{r.gatewaySource === "DEFAULT"
      && <span className="txt-caption text-muted-foreground">（{c.gatewayDefault}）</span>}</> },
    { header: c.remaining, numeric: true, cell: (r) => <span className={r.currentRemaining < r.rangeLowWater ? "text-destructive" : ""}>{r.currentRemaining} / {r.rangeLowWater}</span> },
    { header: c.pending, numeric: true, cell: (r) => r.pending },
    { header: c.held, numeric: true, cell: (r) => r.held },
    { header: c.needsReview, numeric: true, cell: (r) => r.needsReview },
    { header: c.dueSoon, numeric: true, cell: (r) => r.dueSoon },
    { header: c.overdue, numeric: true, cell: (r) => <span className={r.overdue ? "text-destructive" : ""}>{r.overdue}</span> },
    { header: c.holdReason, cell: (r) => r.providerHoldReason ?? "—" },
    {
      header: c.actions, cell: (r) => verify && (can(r, "VERIFY_ACCOUNT") || can(r, "ALLOCATE_RANGE")) ? <div className="flex gap-2">
        {can(r, "VERIFY_ACCOUNT") && <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => api.eInvoiceVerifyProvider(r.entityNo),
          (v) => `${r.entityNo}: ${c.verified} ${v.name ?? v.taxId}${v.qrKeyStored ? ` · ${c.qrSynced}` : ""}`, () => void q.refetch())}>{c.verify}</Button>}
        {can(r, "ALLOCATE_RANGE") && <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => api.eInvoiceFetchRange(r.entityNo),
          (g) => `${r.entityNo}: ${c.fetched} ${g.track} ${g.startNo}–${g.endNo}`, () => void q.refetch())}>{c.fetchRange}</Button>}
      </div> : null,
    },
  ];
  return <section className="space-y-3">
    <form className="flex flex-wrap items-end gap-3" onSubmit={submit}>
      <label>{c.problem}<Select name="problem" defaultValue="">
        <option value="">{c.problemAll}</option>
        {PROBLEMS.map((p) => <option key={p} value={p}>{c[`problem${p}`]}</option>)}
      </Select></label>
      <label>{c.gateway}<Select name="gatewayCode" defaultValue="">
        <option value="">{c.gatewayAll}</option>
        {GATEWAYS.map((g) => <option key={g} value={g}>{g}</option>)}
      </Select></label>
      <label>{c.keyword}<Input name="keyword" maxLength={64}/></label>
      <Button type="submit">{c.search}</Button>
    </form>
    {bar}
    <DataTable columns={cols} rows={q.data?.records} loading={q.isPending} error={q.error} onRetry={() => q.refetch()} rowKey={(r) => r.entityNo} empty={c.empty}/>
    <Pagination page={page} size={size} onSize={setSize} total={q.data?.total ?? 0} onPage={setPage}/>
  </section>;
}

function Transmissions({ c, verify }: { c: Copy; verify: boolean }) {
  const [filter, setFilter] = useState<{ entityNo?: string; status?: EInvoiceUploadStatus; overdue?: boolean }>({});
  const { page, setPage, size, setSize } = usePaging();
  const q = useQuery({ queryKey: ["e-invoice-transmissions", filter, page, size], queryFn: () => api.eInvoiceTransmissions({ ...filter, page, size }) });
  const { run, busy, bar } = useOutcome();
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget), v = (k: string) => String(f.get(k) ?? "").trim() || undefined;
    setFilter({ entityNo: v("entityNo"), status: v("status") as EInvoiceUploadStatus | undefined, overdue: f.get("overdue") === "on" || undefined });
    setPage(1);
  }
  const cols: Column<EInvoiceTransmission>[] = [
    { header: c.task, cell: (r) => r.transmissionNo },
    { header: c.entity, cell: (r) => `${r.entityNo} / ${r.storeNo ?? "—"}` },
    { header: c.type, cell: (r) => r.messageType },
    { header: c.fiscalNumber, cell: (r) => r.fiscalNumber ?? r.invoiceNo },
    { header: c.status, cell: (r) => <span className={r.status === "SENT" ? "" : r.status === "PENDING" || r.status === "SENDING" ? "" : "text-destructive"}>{r.status}</span> },
    { header: c.attempts, numeric: true, cell: (r) => r.attempts },
    { header: c.lastError, cell: (r) => r.lastError ?? "—" },
    { header: c.deadline, cell: (r) => <span className={r.overdue ? "text-destructive" : ""}>{r.reportDeadlineAt ?? "—"}</span> },
    {
      header: c.actions, cell: (r) => verify ? <div className="flex gap-2">
        {r.status === "NEEDS_REVIEW" && <Button size="sm" variant="outline" disabled={busy}
          onClick={() => run(() => api.eInvoiceRetransmit(r.invoiceNo), () => `${r.invoiceNo}: ${c.retransmitted}`, () => void q.refetch())}>{c.retransmit}</Button>}
        {can(r, "QUERY_STATUS") && r.status !== "SENT" && <Button size="sm" variant="outline" disabled={busy}
          onClick={() => run(() => api.eInvoiceProviderCheck(r.invoiceNo), (k) => `${r.invoiceNo}: ${c.checkResult} ${k.verdict} (${k.providerState})${k.reconciled.length ? ` · ${c.reconciled}` : ""}`, () => void q.refetch())}>{c.providerCheck}</Button>}
      </div> : null,
    },
  ];
  return <section className="space-y-3">
    <form className="flex flex-wrap items-end gap-3" onSubmit={submit}>
      <label>{c.entity}<Input name="entityNo" maxLength={64}/></label>
      <label>{c.status}<Select name="status" defaultValue="">
        <option value="">{c.statusAll}</option>
        {UPLOAD.map((s) => <option key={s} value={s}>{s}</option>)}
      </Select></label>
      <label className="flex items-center gap-2"><input className="focus-ring" type="checkbox" name="overdue"/>{c.onlyOverdue}</label>
      <Button type="submit">{c.search}</Button>
    </form>
    {bar}
    <DataTable columns={cols} rows={q.data?.records} loading={q.isPending} error={q.error} onRetry={() => q.refetch()} rowKey={(r) => r.transmissionNo} empty={c.empty}/>
    <Pagination page={page} size={size} onSize={setSize} total={q.data?.total ?? 0} onPage={setPage}/>
  </section>;
}

function Invoices({ c, verify }: { c: Copy; verify: boolean }) {
  const [filter, setFilter] = useState<{ entityNo?: string; fiscalNumber?: string; status?: string }>({});
  const { page, setPage, size, setSize } = usePaging();
  const q = useQuery({ queryKey: ["e-invoices", filter, page, size], queryFn: () => api.eInvoices({ ...filter, page, size }) });
  const { run, busy, bar } = useOutcome();
  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget), v = (k: string) => String(f.get(k) ?? "").trim() || undefined;
    setFilter({ entityNo: v("entityNo"), fiscalNumber: v("fiscalNumber")?.toUpperCase(), status: v("status") });
    setPage(1);
  }
  const cols: Column<EInvoice>[] = [
    { header: c.fiscalNumber, cell: (r) => r.fiscalNumber },
    { header: c.seller, cell: (r) => `${r.sellerName} · ${r.sellerTaxId}` },
    { header: c.store, cell: (r) => r.storeNo },
    { header: c.issuedAt, cell: (r) => r.issuedAt },
    { header: c.total, numeric: true, cell: (r) => money(r.totalMinor, r.currency) },
    { header: c.invoiceStatus, cell: (r) => r.status },
    { header: c.upload, cell: (r) => <span className={r.uploadStatus && !["SENT", "PENDING", "SENDING"].includes(r.uploadStatus) ? "text-destructive" : ""}>{r.uploadStatus ?? "—"}</span> },
    {
      header: c.actions, cell: (r) => verify ? <div className="flex gap-2">
        {r.uploadStatus === "NEEDS_REVIEW" && <Button size="sm" variant="outline" disabled={busy}
          onClick={() => run(() => api.eInvoiceRetransmit(r.invoiceNo), () => `${r.fiscalNumber}: ${c.retransmitted}`, () => void q.refetch())}>{c.retransmit}</Button>}
        <Button size="sm" variant="outline" disabled={busy}
          onClick={() => run(() => api.eInvoiceProviderCheck(r.invoiceNo), (k) => `${r.fiscalNumber}: ${c.checkResult} ${k.verdict} (${k.providerState})${k.reconciled.length ? ` · ${c.reconciled}` : ""}`, () => void q.refetch())}>{c.providerCheck}</Button>
      </div> : null,
    },
  ];
  return <section className="space-y-3">
    <form className="flex flex-wrap items-end gap-3" onSubmit={submit}>
      <label>{c.entity}<Input name="entityNo" maxLength={64}/></label>
      <label>{c.fiscalNumber}<Input name="fiscalNumber" maxLength={32}/></label>
      <label>{c.invoiceStatus}<Select name="status" defaultValue="">
        <option value="">{c.invoiceStatusAll}</option>
        <option value="ISSUED">ISSUED</option>
        <option value="VOIDED">VOIDED</option>
        <option value="REVOKING">REVOKING</option>
        <option value="REVOKED">REVOKED</option>
      </Select></label>
      <Button type="submit">{c.search}</Button>
    </form>
    {bar}
    <DataTable columns={cols} rows={q.data?.records} loading={q.isPending} error={q.error} onRetry={() => q.refetch()} rowKey={(r) => r.invoiceNo} empty={c.empty}/>
    <Pagination page={page} size={size} onSize={setSize} total={q.data?.total ?? 0} onPage={setPage}/>
  </section>;
}
