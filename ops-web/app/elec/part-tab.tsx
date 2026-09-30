"use client";

// 料号与库存（P-19.3）。料号搜索与买家同一套命中（开头 / 中段 / 厂牌），但**不计入买家的搜索需求**；
// 点开一个料号就是「谁有货」—— 运营报价时最常看的一屏。另一视图按料号 / 供应商 / 到期查库存行。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { ElecOpsPartRow, ElecOpsSource, ElecOpsStockRow, ElecStockFilter } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import { StatusBadge } from "@/components/ui/status-badge";
import { Tabs } from "@/components/ui/tabs";
import { Toolbar } from "@/components/ui/toolbar";
import { fill } from "@/lib/use-copy";
import type { ElecCopy } from "./copy";
import { lead, price, qty } from "./fmt";
import { supplierStatusMap } from "./status";

export function PartTab({ c }: { c: ElecCopy }) {
  const [view, setView] = useState<"parts" | "stocks">("parts");
  return (
    <div>
      <Tabs tabs={[{ key: "parts", label: c.partViewParts }, { key: "stocks", label: c.partViewStocks }]}
        value={view} onChange={(k) => setView(k as "parts" | "stocks")} />
      {view === "parts" ? <Parts c={c} /> : <Stocks c={c} />}
    </div>
  );
}

function Parts({ c }: { c: ElecCopy }) {
  const [input, setInput] = useState("");
  const [q, setQ] = useState("");
  const [openNo, setOpenNo] = useState<string | null>(null);
  const list = useQuery({ queryKey: ["elec-part", q], queryFn: () => api.searchElecParts(q), enabled: q.length > 0 });

  const columns: Column<ElecOpsPartRow>[] = [
    { header: c.colMpn, cell: (p) => <span className="font-medium">{p.mpn}</span> },
    { header: c.colMfr, cell: (p) => p.mfrCode === "UNKNOWN" ? fill(c.partUnknownMfr, { raw: p.mfrNameRaw ?? c.none }) : (p.mfrName ?? p.mfrCode) },
    { header: c.partColPkg, cell: (p) => p.pkg ?? c.none },
    { header: c.partColSuppliers, cell: (p) => qty(p.supplierCnt), numeric: true },
    { header: c.partColTotal, cell: (p) => qty(p.totalQty), numeric: true },
    { header: c.partColBuyerFrom, cell: (p) => price(p.buyerPriceFromE6) },
  ];

  return (
    <div className="space-y-4">
      <Toolbar>
        <Input className="w-96" value={input} placeholder={c.partPh}
          onChange={(e) => setInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && setQ(input.trim())} />
      </Toolbar>
      <DataTable columns={columns} rows={q ? list.data : []} loading={q.length > 0 && list.isLoading} error={list.error}
        onRetry={() => list.refetch()} empty={q ? c.partNone : c.partEmpty} rowKey={(p) => p.partNo}
        rowProps={(p) => ({ onClick: () => setOpenNo(p.partNo), className: "cursor-pointer" })} />
      {openNo && <PartDrawer c={c} partNo={openNo} onClose={() => setOpenNo(null)} />}
    </div>
  );
}

function PartDrawer({ c, partNo, onClose }: { c: ElecCopy; partNo: string; onClose: () => void }) {
  const detail = useQuery({ queryKey: ["elec-part-detail", partNo], queryFn: () => api.getElecPart(partNo) });
  const d = detail.data;
  const cols: Column<ElecOpsSource>[] = [
    { header: c.colSupplier, cell: (s) => <div>{s.companyName ?? s.supplierNo}<div className="txt-caption text-muted-foreground">{s.contactPhone}</div></div> },
    { header: c.colQty, cell: (s) => qty(s.qty), numeric: true },
    { header: c.colDc, cell: (s) => s.dateCode ?? c.none },
    {
      header: c.colPrice,
      cell: (s) => s.tiers.length > 1
        ? s.tiers.map((t) => `${qty(t.minQty)}+ ${price(t.priceE6, s.currency)}`).join(" / ")
        : `${price(s.priceE6, s.currency)} ${s.taxIncluded ? c.taxIn : c.taxEx}`,
    },
    { header: c.colLead, cell: (s) => lead(s.leadDays, c) },
    { header: c.quoteColValid, cell: (s) => s.validUntil ?? c.none },
  ];
  return (
    <Drawer open onOpenChange={(o) => !o && onClose()} title={d?.part.mpn ?? partNo} width="w-[880px]">
      {d && (
        <>
          <DrawerSection title={d.part.mfrName ?? d.part.mfrCode} first>
            <div className="txt-caption text-muted-foreground">
              {fill(c.partBands, { q: d.qtyBand ?? c.none, s: d.sourceBand ?? c.none })}
            </div>
            {d.description && <div className="mt-2 txt-body">{d.description}</div>}
          </DrawerSection>
          <DrawerSection title={c.partSources}>
            <DataTable columns={cols} rows={d.sources} error={detail.error} onRetry={() => detail.refetch()}
              rowKey={(s) => s.stockNo} empty={c.rfqSourcesEmpty} />
          </DrawerSection>
        </>
      )}
    </Drawer>
  );
}

function Stocks({ c }: { c: ElecCopy }) {
  const [qInput, setQInput] = useState("");
  const [supInput, setSupInput] = useState("");
  const [cond, setCond] = useState<{ q: string; supplierNo: string }>({ q: "", supplierNo: "" });
  const [filter, setFilter] = useState<ElecStockFilter>("ALL");
  const statusMap = supplierStatusMap(c);
  const list = useQuery({
    queryKey: ["elec-stock", cond, filter],
    queryFn: () => api.listElecStocks({ q: cond.q || undefined, supplierNo: cond.supplierNo || undefined, filter }),
  });
  const apply = () => setCond({ q: qInput.trim(), supplierNo: supInput.trim() });
  const cols: Column<ElecOpsStockRow>[] = [
    { header: c.colMpn, cell: (r) => <div>{r.stock.mpn}<div className="txt-caption text-muted-foreground">{r.stock.mfr ?? c.none}</div></div> },
    { header: c.colSupplier, cell: (r) => <div>{r.companyName ?? r.supplierNo} <StatusBadge map={statusMap} value={r.supplierStatus} /></div> },
    { header: c.colQty, cell: (r) => qty(r.stock.qty), numeric: true },
    { header: c.colDc, cell: (r) => r.stock.dateCode ?? c.none },
    { header: c.colPrice, cell: (r) => `${price(r.stock.priceE6, r.stock.currency)} ${r.stock.taxIncluded ? c.taxIn : c.taxEx}` },
    { header: c.quoteColValid, cell: (r) => r.stock.validUntil },
  ];
  return (
    <div className="space-y-4">
      <Toolbar>
        <Input className="w-56" value={qInput} placeholder={c.colMpn}
          onChange={(e) => setQInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && apply()} />
        <Input className="w-48" value={supInput} placeholder={c.stockSupplierPh}
          onChange={(e) => setSupInput(e.target.value)} onKeyDown={(e) => e.key === "Enter" && apply()} />
      </Toolbar>
      <Tabs tabs={[{ key: "ALL", label: c.stockAll }, { key: "EXPIRING", label: c.stockExpiring }, { key: "EXPIRED", label: c.stockExpired }]}
        value={filter} onChange={(k) => setFilter(k as ElecStockFilter)} />
      <DataTable columns={cols} rows={list.data} loading={list.isLoading} error={list.error}
        onRetry={() => list.refetch()} empty={c.stockEmpty} rowKey={(r) => r.stock.stockNo} />
    </div>
  );
}
