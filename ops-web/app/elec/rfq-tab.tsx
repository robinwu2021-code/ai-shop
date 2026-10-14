"use client";

// 询报价（P-19.1）。一张单一屏：买家与电话、逐行填报价，**旁边就是库里谁有货、各家报了什么**
// （真名、原价、给平台的备注）—— 运营不用在两页之间来回抄。保存即通知买家。
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ElecCloseReason, ElecOpsLine, ElecOpsQuoteRow, ElecOpsRfq, ElecOpsSource, ElecPriceMode } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
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
import { demand, e6Of, lead, lineMfr, price, qty, when } from "./fmt";
import { dispatchStatusMap, quoteStatusMap, rfqStatusMap } from "./status";

export function RfqTab({ c }: { c: ElecCopy }) {
  const [view, setView] = useState<"orders" | "quotes">("orders");
  return (
    <div>
      <Tabs tabs={[{ key: "orders", label: c.rfqViewOrders }, { key: "quotes", label: c.rfqViewQuotes }]}
        value={view} onChange={(k) => setView(k as "orders" | "quotes")} />
      {view === "orders" ? <Orders c={c} /> : <Quotes c={c} />}
    </div>
  );
}

function Orders({ c }: { c: ElecCopy }) {
  const [status, setStatus] = useState("");
  // ?rfq=<单号> 直接打开这一单（基础数据页「认不出的厂牌」里点「看最近一单」过来）
  const sp = useSearchParams();
  const linked = sp.get("rfq");
  const [openNo, setOpenNo] = useState<string | null>(linked);
  useEffect(() => {
    if (linked) setOpenNo(linked);
  }, [linked]);
  const statusMap = rfqStatusMap(c);
  const list = useQuery({
    queryKey: ["elec-rfq", status],
    queryFn: () => api.listElecRfqs({ status: status || undefined }),
  });

  const columns: Column<ElecOpsRfq>[] = [
    { header: c.rfqColNo, cell: (r) => <span className="font-mono txt-caption">{r.rfqNo}</span> },
    { header: c.colStatus, cell: (r) => <StatusBadge map={statusMap} value={r.status} /> },
    { header: c.rfqColBuyer, cell: (r) => <div>{r.contactName ?? c.none}<div className="txt-caption text-muted-foreground">{r.contactPhone}</div></div> },
    {
      header: c.rfqColLines,
      cell: (r) => r.lines.length > 1 ? fill(c.rfqLinesMore, { mpn: r.lines[0]!.mpn, n: r.lines.length }) : `${r.lines[0]?.mpn ?? ""} × ${qty(r.lines[0]?.qty)}`,
    },
    { header: c.rfqColProgress, cell: (r) => `${r.dispatchCnt} / ${r.respondedCnt} / ${r.offerCnt}`, numeric: true },
    { header: c.colCreated, cell: (r) => when(r.createdAt) },
  ];

  return (
    <div className="space-y-4">
      <Toolbar>
        <FilterSelect value={status} onChange={setStatus} options={statusMap} allLabel={c.rfqStatusAll} aria-label={c.colStatus} />
      </Toolbar>
      <DataTable columns={columns} rows={list.data} loading={list.isLoading} error={list.error}
        onRetry={() => list.refetch()} empty={c.rfqEmpty} rowKey={(r) => r.rfqNo}
        rowProps={(r) => ({ onClick: () => setOpenNo(r.rfqNo), className: "cursor-pointer" })} />
      {openNo && <RfqDrawer c={c} rfqNo={openNo} onClose={() => setOpenNo(null)} />}
    </div>
  );
}

/** 每行一个报价草稿（元，字符串）。空价 = 这一行没找到货 */
type Draft = Record<number, { price: string; qty: string; dcYear: string; leadDays: string }>;

function RfqDrawer({ c, rfqNo, onClose }: { c: ElecCopy; rfqNo: string; onClose: () => void }) {
  const qc = useQueryClient();
  const can = useCan();
  const canQuote = can("elec:rfq:quote");
  const statusMap = rfqStatusMap(c);
  const { confirm, dialog } = useConfirm();
  const [draft, setDraft] = useState<Draft>({});
  const [validDays, setValidDays] = useState("3");
  const [note, setNote] = useState("");
  const [dispatchTo, setDispatchTo] = useState<Record<number, string>>({});

  const detail = useQuery({ queryKey: ["elec-rfq-detail", rfqNo], queryFn: () => api.getElecRfq(rfqNo) });
  const r = detail.data;
  const refresh = (next: ElecOpsRfq) => {
    qc.setQueryData(["elec-rfq-detail", rfqNo], next);
    void qc.invalidateQueries({ queryKey: ["elec-rfq"] });
  };

  // 草稿以平台已报的价为起点：改价时不必重抄一遍
  const valueOf = (l: ElecOpsLine) => draft[l.lineNo] ?? {
    price: l.quote ? String(l.quote.priceE6 / 1_000_000) : "",
    qty: l.quote?.qty != null ? String(l.quote.qty) : "",
    dcYear: l.quote?.dcYear != null ? String(l.quote.dcYear) : "",
    leadDays: l.quote?.leadDays != null ? String(l.quote.leadDays) : "",
  };
  const setField = (l: ElecOpsLine, k: keyof Draft[number], v: string) =>
    setDraft((d) => ({ ...d, [l.lineNo]: { ...valueOf(l), [k]: v } }));

  const quote = useMutation({
    mutationFn: () => {
      const lines = (r?.lines ?? []).flatMap((l) => {
        const v = valueOf(l);
        const p = e6Of(v.price);
        if (p == null) return [];
        const n = (s: string) => (/^\d+$/.test(s.trim()) ? Number(s.trim()) : undefined);
        return [{ lineNo: l.lineNo, priceE6: p, qty: n(v.qty), dcYear: n(v.dcYear), leadDays: n(v.leadDays) }];
      });
      return api.quoteElecRfq(rfqNo, { validDays: Number(validDays) || 3, note: note.trim() || undefined, lines });
    },
    onSuccess: (next) => { refresh(next); setDraft({}); notify.success(c.rfqQuoted); },
    onError: (e) => notify.error((e as Error).message),
  });
  const dispatch = useMutation({
    mutationFn: (v: { lineNo: number; nos: string[] }) => api.dispatchElecLine(rfqNo, v.lineNo, v.nos),
    onSuccess: (next, v) => { refresh(next); setDispatchTo((d) => ({ ...d, [v.lineNo]: "" })); notify.success(c.rfqDispatched); },
    onError: (e) => notify.error((e as Error).message),
  });

  // 报价模式（TDD-元器件-公开求购 D6）：加价 = 买家看平台价与代号 A/B/C；转发 = 原价与匿名编号，像直连。
  // 已有一行选定报价就不能再改 —— 成交价已经按旧模式给出去了（后端同样拦，回 90011）
  const mode: ElecPriceMode = r?.priceMode ?? "MARKUP";
  const chosen = (r?.lines ?? []).some((l) => (l.offers ?? []).some((o) => o.quoteStatus === "ACCEPTED"));
  const setMode = useMutation({
    mutationFn: (m: ElecPriceMode) => api.setElecPriceMode(rfqNo, m),
    onSuccess: (next, m) => { refresh(next); notify.success(fill(c.rfqModeChanged, { m: m === "FORWARD" ? c.rfqModeFORWARD : c.rfqModeMARKUP })); },
    onError: (e) => notify.error((e as Error).message),
  });

  const close = (reason: ElecCloseReason) => void confirm({
    title: fill(c.rfqCloseTitle, { no: rfqNo }), desc: c.rfqCloseDesc, danger: reason === "NO_SOURCE",
    action: async () => { refresh(await api.closeElecRfq(rfqNo, reason)); notify.success(c.rfqClosed); },
  });

  const sourceCols: Column<ElecOpsSource>[] = [
    { header: c.colSupplier, cell: (s) => <div>{s.companyName ?? s.supplierNo}<div className="text-muted-foreground">{s.contactPhone}</div></div> },
    { header: c.colQty, cell: (s) => qty(s.qty), numeric: true },
    { header: c.colDc, cell: (s) => s.dateCode ?? c.none },
    { header: c.colPrice, cell: (s) => `${price(s.priceE6, s.currency)} ${s.taxIncluded ? c.taxIn : c.taxEx}` },
    { header: c.colLead, cell: (s) => lead(s.leadDays, c) },
  ];
  const open = r != null && (r.status === "SUBMITTED" || r.status === "QUOTED");
  const footer = r && canQuote && open ? (
    <div className="flex flex-wrap items-center gap-2">
      <Button variant="outline" size="sm" onClick={() => close("NO_SOURCE")}>{c.rfqCloseNoSource}</Button>
      <Button variant="outline" size="sm" onClick={() => close("BUYER_CANCELLED")}>{c.rfqCloseCancelled}</Button>
      <Button variant="outline" size="sm" onClick={() => close("DONE")}>{c.rfqCloseDone}</Button>
      <div className="flex-1" />
      <Button onClick={() => quote.mutate()} disabled={quote.isPending}>{c.rfqSaveQuote}</Button>
    </div>
  ) : undefined;

  return (
    <Drawer open onOpenChange={(o) => !o && onClose()} title={rfqNo} width="w-[960px]" footer={footer}>
      {r && (
        <>
          {!canQuote && <ReadOnlyNotice what={c.rfqNoQuotePerm} perm="elec:rfq:quote" note={c.rfqNoQuoteNote} />}
          <DrawerSection title={c.rfqColBuyer} first>
            <FieldGrid>
              <Field label={c.colStatus}><StatusBadge map={statusMap} value={r.status} /></Field>
              <Field label={c.rfqColBuyer}>{[r.company, r.contactName, r.contactPhone].filter(Boolean).join(" · ")}</Field>
              <Field label={c.rfqReq}>{demand(r, c) || c.none}</Field>
              <Field label={c.rfqRemark}>{r.remark ?? c.none}</Field>
              <Field label={c.rfqPriceMode}>
                <div className="flex items-center gap-2">
                  {(["MARKUP", "FORWARD"] as const).map((m) => (
                    <Button key={m} size="sm" variant={mode === m ? "default" : "outline"}
                      disabled={mode !== m && (!canQuote || !open || chosen || setMode.isPending)}
                      aria-pressed={mode === m}
                      onClick={() => mode !== m && setMode.mutate(m)}>
                      {m === "FORWARD" ? c.rfqModeFORWARD : c.rfqModeMARKUP}
                    </Button>
                  ))}
                </div>
                <div className="mt-1 txt-caption text-muted-foreground">
                  {open && chosen ? c.rfqModeLocked : mode === "FORWARD" ? c.rfqModeFORWARDDesc : c.rfqModeMARKUPDesc}
                </div>
              </Field>
            </FieldGrid>
            <div className="txt-caption text-muted-foreground">{r.buyerNotified ? c.rfqNotified : c.rfqNotNotified}</div>
          </DrawerSection>

          {r.lines.map((l) => {
            const v = valueOf(l);
            return (
              <DrawerSection key={l.lineNo}
                title={`${fill(c.rfqLine, { n: l.lineNo })} · ${l.mpn}${lineMfr(l, c) ? ` (${lineMfr(l, c)})` : ""} × ${qty(l.qty)}`}
                desc={l.targetE6 != null ? fill(c.rfqTarget, { p: price(l.targetE6) }) : undefined}>
                {l.publicAt && (
                  <Notice tone="info">
                    <span className="font-medium">{c.rfqPublic}</span>{" · "}{fill(c.rfqPublicDesc, { t: when(l.publicAt) })}
                  </Notice>
                )}
                <div className="mb-2 txt-label text-muted-foreground">{c.rfqSources}</div>
                <div className="mb-3">
                  <DataTable columns={sourceCols} rows={l.sources ?? []} rowKey={(s) => s.stockNo}
                    error={detail.error} onRetry={() => detail.refetch()} empty={c.rfqSourcesEmpty} />
                </div>

                <div className="mb-2 txt-label text-muted-foreground">{c.rfqOffers}</div>
                {(l.offers ?? []).length === 0
                  ? <div className="mb-3 txt-caption text-muted-foreground">{c.rfqOffersEmpty}</div>
                  : (
                    <ul className="mb-3 space-y-1 txt-caption">
                      {(l.offers ?? []).map((o) => (
                        <li key={o.dispatchNo}>
                          <span className="font-medium">{o.companyName ?? o.supplierNo}</span>
                          {o.via === "OPEN" && <> · <span className="text-primary-ink">{c.rfqViaOPEN}</span></>}
                          {" · "}<StatusBadge map={dispatchStatusMap(c)} value={o.dispatchStatus} />
                          {o.priceE6 != null && <> · {price(o.priceE6, o.currency)} {o.taxIncluded ? c.taxIn : c.taxEx} · {fill(c.rfqBuyerPrice, { p: price(o.buyerPriceE6) })} · {qty(o.qtyAvailable)} · {lead(o.leadDays, c)}</>}
                          {o.quoteStatus && <> · <StatusBadge map={quoteStatusMap(c)} value={o.quoteStatus} /></>}
                          {o.remark && <div className="text-muted-foreground">{fill(mode === "FORWARD" ? c.rfqSupplierRemarkFwd : c.rfqSupplierRemark, { r: o.remark })}</div>}
                        </li>
                      ))}
                    </ul>
                  )}

                {canQuote && open && (
                  <>
                    <div className="grid grid-cols-4 gap-2">
                      <Input value={v.price} onChange={(e) => setField(l, "price", e.target.value)} placeholder={c.rfqPlatformPh} aria-label={c.rfqPlatform} />
                      <Input value={v.qty} onChange={(e) => setField(l, "qty", e.target.value)} placeholder={c.rfqQuoteQty} aria-label={c.rfqQuoteQty} />
                      <Input value={v.dcYear} onChange={(e) => setField(l, "dcYear", e.target.value)} placeholder={c.rfqQuoteDcYear} aria-label={c.rfqQuoteDcYear} />
                      <Input value={v.leadDays} onChange={(e) => setField(l, "leadDays", e.target.value)} placeholder={c.rfqQuoteLead} aria-label={c.rfqQuoteLead} />
                    </div>
                    <div className="mt-2 flex gap-2">
                      <Input className="w-72" value={dispatchTo[l.lineNo] ?? ""} placeholder={c.rfqDispatchPh}
                        onChange={(e) => setDispatchTo((d) => ({ ...d, [l.lineNo]: e.target.value }))} aria-label={c.rfqDispatch} />
                      <Button size="sm" variant="outline" disabled={!dispatchTo[l.lineNo]?.trim() || dispatch.isPending}
                        onClick={() => dispatch.mutate({ lineNo: l.lineNo, nos: (dispatchTo[l.lineNo] ?? "").split(/[,\uFF0C\s]+/).filter(Boolean) })}>
                        {c.rfqDispatch}
                      </Button>
                    </div>
                  </>
                )}
              </DrawerSection>
            );
          })}

          {canQuote && open && (
            <DrawerSection title={c.rfqPlatform}>
              <Notice tone="info">{c.rfqSaveQuoteHint}</Notice>
              <FieldGrid>
                <Field label={c.rfqValidDays}><Input value={validDays} onChange={(e) => setValidDays(e.target.value)} /></Field>
                <Field label={c.rfqNote}><Input value={note} maxLength={255} onChange={(e) => setNote(e.target.value)} /></Field>
              </FieldGrid>
            </DrawerSection>
          )}
        </>
      )}
      {dialog}
    </Drawer>
  );
}

function Quotes({ c }: { c: ElecCopy }) {
  const [supplierNo, setSupplierNo] = useState("");
  const [kw, setKw] = useState("");
  const statusMap = quoteStatusMap(c);
  const [status, setStatus] = useState("");
  const list = useQuery({
    queryKey: ["elec-quotes", kw, status],
    queryFn: () => api.listElecQuotes({ supplierNo: kw || undefined, status: status || undefined }),
  });
  const columns: Column<ElecOpsQuoteRow>[] = [
    { header: c.quoteColRfq, cell: (q) => <span className="font-mono txt-caption">{q.rfqNo} / {q.lineNo}</span> },
    { header: c.colMpn, cell: (q) => q.mpn },
    { header: c.colSupplier, cell: (q) => q.companyName ?? q.supplierNo },
    { header: c.colPrice, cell: (q) => `${price(q.priceE6, q.currency)} ${q.taxIncluded ? c.taxIn : c.taxEx}` },
    { header: c.quoteColBuyerPrice, cell: (q) => price(q.buyerPriceE6) },
    { header: c.quoteColWanted, cell: (q) => `${qty(q.qtyWanted)} / ${qty(q.qtyAvailable)}`, numeric: true },
    { header: c.quoteColValid, cell: (q) => q.validUntil ?? c.none },
    { header: c.colStatus, cell: (q) => <StatusBadge map={statusMap} value={q.status} /> },
    { header: c.colCreated, cell: (q) => when(q.createdAt) },
  ];
  return (
    <div className="space-y-4">
      <Toolbar>
        <Input className="w-56" value={supplierNo} placeholder={c.stockSupplierPh}
          onChange={(e) => setSupplierNo(e.target.value)} onKeyDown={(e) => e.key === "Enter" && setKw(supplierNo.trim())} />
        <FilterSelect value={status} onChange={setStatus} options={statusMap} allLabel={c.rfqStatusAll} aria-label={c.colStatus} />
      </Toolbar>
      <DataTable columns={columns} rows={list.data} loading={list.isLoading} error={list.error}
        onRetry={() => list.refetch()} empty={c.quoteEmpty} rowKey={(q) => q.quoteNo} />
    </div>
  );
}

