"use client";

// 代客线下购买：报价 → 下单 → 发付款指引。买方是门店（STORE），发起人是当前运营 ——
// 后端从可信会话取运营身份，页面不传任何 actor 字段。
//
// 报价由服务器冻结（15 分钟有效），金额以报价回执为准；这里不做任何前端算价。
// 下单幂等键在报价那一刻生成并保持不变：网络抖动重点一次不会下两单。
import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { MallOfflineInstructionsView, MallOrderView, MallQuoteComponent, MallQuoteView } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Money, StoreBar, fmtAt, newOpNo, showErr, type MallCopy } from "./shared";
import { OrderDrawer } from "./order-drawer";

export function AssistedTab({ c }: { c: MallCopy }) {
  const catalog = useQuery({ queryKey: ["mall-catalog"], queryFn: () => api.mallCatalog() });
  const [storeNo, setStoreNo] = React.useState("");
  const [currency, setCurrency] = React.useState("TWD");
  const [sku, setSku] = React.useState("");
  const [qty, setQty] = React.useState("1");
  const [quote, setQuote] = React.useState<MallQuoteView | null>(null);
  const [idem, setIdem] = React.useState("");
  const [order, setOrder] = React.useState<MallOrderView | null>(null);
  const [ins, setIns] = React.useState<MallOfflineInstructionsView | null>(null);
  const [open, setOpen] = React.useState<string | null>(null);

  // 只列**当前在售且已发布**的版本 —— 草稿、停售报出来就是假价
  const sellable = (catalog.data?.skuDefinitions ?? []).filter((d) => d.saleStatus === "ON_SALE" && d.currentVersionNo)
    .map((d) => ({ def: d, v: catalog.data!.skuVersions!.find((x) => x.draft?.skuVersionNo === d.currentVersionNo) }))
    .filter((x) => x.v?.status === "PUBLISHED" && x.v.draft?.price?.currency === currency);

  const reset = () => { setQuote(null); setOrder(null); setIns(null); };
  const doQuote = useMutation({
    mutationFn: () => api.offlineAssistedQuote(storeNo, { lines: [{ skuVersionNo: sku, quantity: Number(qty) }], currency }),
    onSuccess: (q) => { setQuote(q); setIdem(newOpNo("IDEM")); setOrder(null); setIns(null); },
  });
  const place = useMutation({
    mutationFn: () => api.offlineAssistedPlace(storeNo, { quoteNo: quote!.quoteNo!, idempotencyKey: idem, shippingAddress: null }),
    onSuccess: (o) => { setOrder(o); notify.success(fill(c.toastPlaced, { no: o.orderNo ?? "" })); },
  });
  const issue = useMutation({
    mutationFn: () => api.issueOfflineInstructions(storeNo, order!.orderNo!),
    onSuccess: (i) => { setIns(i); notify.success(c.toastIssued); },
  });

  const hasHardware = (quote?.components ?? []).some((x) => x.kind === "HARDWARE");
  const compColumns: Column<MallQuoteComponent>[] = [
    { header: c.colKind, cell: (x) => x.kind },
    { header: c.colQty, numeric: true, cell: (x) => x.quantity },
    { header: c.colCycle, numeric: true, cell: (x) => (x.cycleDays ? fill(c.daysN, { n: x.cycleDays }) : "—") },
    { header: c.colDeal, numeric: true, cell: (x) => <Money m={x.dealAmount} /> },
  ];

  return (
    <div className="space-y-4">
      <HelpNote>{c.assistedHelp}</HelpNote>
      <StoreBar c={c} id="assist-store" value={storeNo} onSubmit={(v) => { setStoreNo(v); reset(); }} />
      {!storeNo ? <p className="txt-caption text-muted-foreground">{c.storeFirst}</p> : (
        <div className="space-y-4">
          {catalog.error && <DataTable columns={[]} rows={undefined} error={showErr(catalog.error)} onRetry={() => catalog.refetch()} rowKey={() => ""} empty={c.noSellable} />}
          <div className="grid gap-3 md:grid-cols-4">
            <div className="space-y-1">
              <Label htmlFor="as-cur">{c.fieldCurrency}</Label>
              <Select id="as-cur" className="w-full" value={currency} onChange={(e) => { setCurrency(e.target.value); setSku(""); reset(); }}>
                {["TWD", "CNY"].map((x) => <option key={x} value={x}>{x}</option>)}
              </Select>
            </div>
            <div className="space-y-1 md:col-span-2">
              <Label htmlFor="as-sku" required>{c.fieldSku}</Label>
              <Select id="as-sku" className="w-full" value={sku} onChange={(e) => { setSku(e.target.value); reset(); }}>
                <option value="">{sellable.length ? c.pickSku : c.noSellable}</option>
                {sellable.map(({ def, v }) => (
                  <option key={def.skuNo} value={v!.draft!.skuVersionNo}>{def.title} · {v!.draft!.skuVersionNo}</option>
                ))}
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="as-qty" required>{c.colQty}</Label>
              <Input id="as-qty" inputMode="numeric" value={qty} onChange={(e) => { setQty(e.target.value); reset(); }} />
            </div>
          </div>
          <Button disabled={!sku || Number(qty) < 1} loading={doQuote.isPending} onClick={() => doQuote.mutate()}>{c.btnQuote}</Button>

          {quote && (
            <div className="space-y-3 rounded-card border border-border p-4">
              <FieldGrid>
                <Field label={c.colQuoteNo}><code className="txt-caption">{quote.quoteNo}</code></Field>
                <Field label={c.colExpires}>{fmtAt(quote.expiresAt)}</Field>
                <Field label={c.colTotal}><Money m={quote.total} /></Field>
                <Field label={c.colPayable}><Money m={quote.payable} /></Field>
              </FieldGrid>
              <DataTable columns={compColumns} rows={quote.components} error={showErr(doQuote.error)} onRetry={() => doQuote.mutate()} rowKey={(x) => x.componentNo ?? ""} empty={c.componentsEmpty} />
              {hasHardware && <Notice tone="warning">{c.assistedNoHardware}</Notice>}
              {!order && <Button disabled={hasHardware} loading={place.isPending} onClick={() => place.mutate()}>{c.btnPlace}</Button>}
            </div>
          )}

          {order && (
            <div className="space-y-3 rounded-card border border-border p-4">
              <FieldGrid>
                <Field label={c.colOrderNo}><code className="txt-caption">{order.orderNo}</code></Field>
                <Field label={c.colOrderStatus}>{order.status}</Field>
                <Field label={c.colPayable}><Money m={order.payable} /></Field>
                <Field label={c.colCreatedAt}>{fmtAt(order.createdAt)}</Field>
              </FieldGrid>
              {!ins ? <Button loading={issue.isPending} onClick={() => issue.mutate()}>{c.btnIssue}</Button> : (
                <>
                  <Notice tone="info">{c.instructionsIssued}</Notice>
                  <FieldGrid>
                    <Field label={c.colPayable}><Money m={ins.payable} /></Field>
                    <Field label={c.colDueAt}>{fmtAt(ins.paymentDueAt)}</Field>
                    <Field label={c.colProfile}>{`${ins.profileNo} · v${ins.profileVersion}`}</Field>
                    <Field label={c.colInstructions}>{Object.entries(ins.instructions).map(([k, v]) => `${k}: ${v}`).join(" · ")}</Field>
                  </FieldGrid>
                </>
              )}
              <Button variant="outline" onClick={() => setOpen(order.orderNo!)}>{c.btnDetail}</Button>
            </div>
          )}
        </div>
      )}
      {storeNo && <OrderDrawer c={c} storeNo={storeNo} orderNo={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
