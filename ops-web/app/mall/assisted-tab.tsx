"use client";

// 代客线下购买：报价 → 下单 → 发付款指引。买方是门店（STORE），发起人是当前运营 ——
// 后端从可信会话取运营身份，页面不传任何 actor 字段。
//
// 报价由服务器冻结（15 分钟有效），金额以报价回执为准；这里不做任何前端算价。
// 下单幂等键在报价那一刻生成并保持不变：网络抖动重点一次不会下两单。
import * as React from "react";
import { useSearchParams } from "next/navigation";
import { useMutation, useQuery } from "@tanstack/react-query";
import {useFinancialLock} from "@/components/offline/financial-lock";
import {isApiError} from "@/lib/api/error";
import { api } from "@/lib/api";
import { describeError } from "@/lib/commerce-reason";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { MallOfflineInstructionsView, MallOrderView, MallQuoteComponent, MallQuoteView, MallShippingAddress } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Field, FieldGrid } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { Money, StoreBar, fmtAt, newOpNo, showErr, type MallCopy } from "./shared";
import { OrderDrawer } from "./order-drawer";

export function AssistedTab({ c, canReadSubscription }: { c: MallCopy; canReadSubscription: boolean }) {
  const catalog = useQuery({ queryKey: ["mall-catalog"], queryFn: () => api.mallCatalog() });
  const sp = useSearchParams();
  const [storeNo, setStoreNo] = React.useState(() => sp.get("store") ?? "");
  const context = useQuery({ queryKey: ["mall-subscription", storeNo], queryFn: () => api.mallSubscription(storeNo), enabled: !!storeNo && canReadSubscription });
  const [address, setAddress] = React.useState<MallShippingAddress>({ recipient: "", phone: "", countryCode: "", region: "", city: "", addressLine: "", postalCode: "" });
  const [frozenAddress, setFrozenAddress] = React.useState<MallShippingAddress | null>(null);
  const [currency, setCurrency] = React.useState("TWD");
  const [method,setMethod] = React.useState<"BANK_TRANSFER" | "SALES_COLLECTION">("SALES_COLLECTION");
  const [sku, setSku] = React.useState("");
  const [qty, setQty] = React.useState("1");
  const [quote, setQuote] = React.useState<MallQuoteView | null>(null);
  const [idem, setIdem] = React.useState("");
  const [order, setOrder] = React.useState<MallOrderView | null>(null);
  const [ins, setIns] = React.useState<MallOfflineInstructionsView | null>(null);
  const [pending,setPending]=React.useState<{storeNo:string;quoteNo:string;idempotencyKey:string;shippingAddress:MallShippingAddress|null}|null>(null);
  const [absent,setAbsent]=React.useState(false);
  const [checking,setChecking]=React.useState(false);
  const definiteRefusal=React.useRef(false);
  const [lookupError,setLookupError]=React.useState(false);
  const [open, setOpen] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (context.data?.currency) { setCurrency(context.data.currency); setSku(""); }
  }, [context.data?.currency]);
  const currentPlan = catalog.data?.planVersions?.find((v) => v.draft?.planVersionNo === context.data?.currentPlanVersionNo)?.draft;
  // 只列**当前在售且已发布**的版本 —— 草稿、停售报出来就是假价
  const sellable = (catalog.data?.skuDefinitions ?? []).filter((d) => d.saleStatus === "ON_SALE" && d.currentVersionNo)
    .map((d) => ({ def: d, v: catalog.data!.skuVersions!.find((x) => x.draft?.skuVersionNo === d.currentVersionNo) }))
    .filter((x) => x.v?.status === "PUBLISHED" && x.v.draft?.price?.currency === currency
      && (!context.data?.businessTypeCode || x.v.draft?.allowedBusinessTypes?.includes(context.data.businessTypeCode)));

  const reset = () => { setQuote(null); setOrder(null); setIns(null); setOpen(null); setFrozenAddress(null); place.reset(); doQuote.reset(); };
  const doQuote = useMutation({
    mutationFn: () => api.offlineAssistedQuote(storeNo, { lines: [{ skuVersionNo: sku, quantity: Number(qty) }], currency, collectionMethod:method }),
    onSuccess: (q) => { setQuote(q); setIdem(newOpNo("IDEM")); setOrder(null); setIns(null); setFrozenAddress(null); place.reset(); },
  });
  const place = useMutation({
    mutationFn: async (shippingAddress: MallShippingAddress | null = null) => {
      const intent = pending ?? { storeNo, quoteNo: quote!.quoteNo!, idempotencyKey: idem, shippingAddress };
      setPending(intent); setAbsent(false);
      return api.offlineAssistedPlace(intent.storeNo, { quoteNo: intent.quoteNo, idempotencyKey: intent.idempotencyKey, shippingAddress: intent.shippingAddress });
    },
    onError: (err) => { definiteRefusal.current = isApiError(err) && [10400,10403,10404,10409].includes(err.code); },
    onSuccess: (o) => { setPending(null); setAbsent(false); setOrder(o); notify.success(fill(c.toastPlaced, { no: o.orderNo ?? "" })); },
  });
  const issue = useMutation({
    mutationFn: () => api.issueOfflineInstructions(storeNo, order!.orderNo!),
    onSuccess: (i) => { setIns(i); notify.success(c.toastIssued); },
  });

  useFinancialLock("mall-assisted",!!pending||place.isPending);
  const locked=!!pending||checking||doQuote.isPending||place.isPending||issue.isPending;
  async function lookup(){
    if(!pending)return;setChecking(true);setLookupError(false);
    try{
      // Read through every page; a partial first page cannot prove absence.
      let afterOrderNo:string|undefined;
      let found: MallOrderView|undefined;
      do {
        const rows=await api.mallOrders(pending.storeNo,{afterOrderNo,limit:100});
        for(const row of rows){
          if(!row.order?.orderNo)throw new Error("INCOMPLETE_LOOKUP");
          const detail=await api.mallOrder(pending.storeNo,row.order.orderNo);
          if(detail.quote?.quoteNo===pending.quoteNo){found=row.order;break;}
        }
        if(found||rows.length<100){afterOrderNo=undefined;break;}
        const next=rows.at(-1)?.order?.orderNo;
        if(!next||next===afterOrderNo)throw new Error("INCOMPLETE_LOOKUP");
        afterOrderNo=next;
      }while(afterOrderNo);
      if(found){setOrder(found);setPending(null);}else if(definiteRefusal.current)setPending(null);else setAbsent(true);
    }catch{setLookupError(true);}finally{setChecking(false);}
  }

  const hasHardware = (quote?.components ?? []).some((x) => x.kind === "HARDWARE");
  const addressReady = [address.recipient, address.phone, address.countryCode, address.addressLine].every((v) => v?.trim());
  const addressFields = [
    ["recipient", c.shippingRecipient, true], ["phone", c.shippingPhone, true], ["countryCode", c.shippingCountry, true],
    ["region", c.shippingRegion, false], ["city", c.shippingCity, false], ["addressLine", c.shippingAddress, true], ["postalCode", c.shippingPostal, false],
  ] as const;
  const compColumns: Column<MallQuoteComponent>[] = [
    { header: c.colKind, cell: (x) => x.kind },
    { header: c.colQty, numeric: true, cell: (x) => x.quantity },
    { header: c.colCycle, numeric: true, cell: (x) => (x.cycleDays ? fill(c.daysN, { n: x.cycleDays }) : "—") },
    { header: c.colDeal, numeric: true, cell: (x) => <Money m={x.dealAmount} /> },
  ];

  return (
    <div className="space-y-4">
      <HelpNote>{c.assistedHelp}</HelpNote>
      <fieldset className="space-y-4 min-w-0" disabled={doQuote.isPending || place.isPending || issue.isPending}>
      <fieldset disabled={locked}><StoreBar c={c} id="assist-store" value={storeNo} onSubmit={(v) => { setStoreNo(v); setSku(""); reset(); }} /></fieldset>
      {!storeNo ? <p className="txt-caption text-muted-foreground">{c.storeFirst}</p> : (
        <div className="space-y-4">
          {canReadSubscription ? (
            context.error ? <DataTable columns={[]} rows={undefined} error={showErr(context.error)} onRetry={() => context.refetch()} rowKey={() => ""} empty={c.storeFirst} /> :
            <FieldGrid>
              <Field label={c.assistedBusinessType}>{context.data?.businessTypeCode ?? "—"}</Field>
              <Field label={c.assistedCurrentPlan}>{currentPlan?.displayName ?? context.data?.currentPlanVersionNo ?? "—"}</Field>
              <Field label={c.fieldCurrency}>{context.data?.currency ?? "—"}</Field>
            </FieldGrid>
          ) : <Notice tone="muted">{c.assistedContextUnavailable}</Notice>}
          {catalog.error && <DataTable columns={[]} rows={undefined} error={showErr(catalog.error)} onRetry={() => catalog.refetch()} rowKey={() => ""} empty={c.noSellable} />}
          <div className="space-y-1">
            <Label htmlFor="as-method">{c.fieldCollectionMethod}</Label>
            <Select id="as-method" value={method} onChange={e=>{setMethod(e.target.value as typeof method);reset();}}>
              <option value="SALES_COLLECTION">{c.methodSales}</option><option value="BANK_TRANSFER">{c.methodBank}</option>
            </Select>
          </div>
          <div className="grid gap-3 md:grid-cols-4">
            <div className="space-y-1">
              <Label htmlFor="as-cur">{c.fieldCurrency}</Label>
              <Select id="as-cur" className="w-full" value={currency} disabled={canReadSubscription || locked} onChange={(e) => { setCurrency(e.target.value); setSku(""); reset(); }}>
                {[...new Set([context.data?.currency, "TWD", "CNY"].filter((x): x is string => !!x))].map((x) => <option key={x} value={x}>{x}</option>)}
              </Select>
            </div>
            <div className="space-y-1 md:col-span-2">
              <Label htmlFor="as-sku" required>{c.fieldSku}</Label>
              <Select id="as-sku" disabled={locked} className="w-full" value={sku} onChange={(e) => { setSku(e.target.value); reset(); }}>
                <option value="">{sellable.length ? c.pickSku : c.noSellable}</option>
                {sellable.map(({ def, v }) => (
                  <option key={def.skuNo} value={v!.draft!.skuVersionNo}>{def.title} · {v!.draft!.skuVersionNo}</option>
                ))}
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="as-qty" required>{c.colQty}</Label>
              <Input id="as-qty" disabled={locked} inputMode="numeric" value={qty} onChange={(e) => { setQty(e.target.value); reset(); }} />
            </div>
          </div>
          <Button disabled={locked || !sku || !Number.isInteger(Number(qty)) || Number(qty) < 1 || (canReadSubscription && (!context.data?.currency || context.isFetching))} loading={doQuote.isPending} onClick={() => doQuote.mutate()}>{c.btnQuote}</Button>
          {!!doQuote.error && <Notice tone="danger">{describeError(doQuote.error)}</Notice>}

          {pending&&<Notice tone="warning"><p>{c.reviewUnknown}</p><p>{pending.storeNo} · {pending.quoteNo} · {pending.idempotencyKey}</p><Button disabled={checking||place.isPending} onClick={()=>void lookup()}>{c.reviewLookup}</Button>{absent&&<Button disabled={checking||place.isPending} onClick={()=>place.mutate(pending?.shippingAddress ?? null)}>{c.reviewRetry}</Button>}{lookupError&&<p role="alert">{c.reviewUnknown}</p>}</Notice>}
          {quote && (
            <div className="space-y-3 rounded-card border border-border p-4">
              <FieldGrid>
                <Field label={c.colQuoteNo}><code className="txt-caption">{quote.quoteNo}</code></Field>
                <Field label={c.colExpires}>{fmtAt(quote.expiresAt)}</Field>
                <Field label={c.colTotal}><Money m={quote.total} /></Field>
                <Field label={c.colPayable}><Money m={quote.payable} /></Field>
              </FieldGrid>
              <DataTable columns={compColumns} rows={quote.components} error={showErr(doQuote.error)} onRetry={() => doQuote.mutate()} rowKey={(x) => x.componentNo ?? ""} empty={c.componentsEmpty} />
              {hasHardware && <section className="space-y-2">
                <h3 className="txt-strong">{c.assistedShipping}</h3>
                <div className="grid gap-3 md:grid-cols-2">
                  {addressFields.map(([key, label, required]) => <div key={key} className="space-y-1">
                    <Label htmlFor={`as-${key}`} required={required}>{label}</Label>
                    <Input id={`as-${key}`} value={address[key] ?? ""} disabled={!!frozenAddress} maxLength={key === "countryCode" ? 8 : key === "addressLine" ? 512 : 128}
                      onChange={(e) => setAddress((a) => ({ ...a, [key]: e.target.value }))} />
                  </div>)}
                </div>
                {frozenAddress && <Notice tone="muted">{c.assistedAddressFrozen}</Notice>}
              </section>}
              {place.error && <Notice tone="danger">{describeError(place.error)}</Notice>}
              {!order && <Button disabled={locked || (hasHardware && !addressReady)} loading={place.isPending} onClick={() => {
                const shipping = hasHardware ? frozenAddress ?? Object.fromEntries(Object.entries(address).map(([k, v]) => [k, v?.trim() ?? ""])) : null;
                if (shipping) setFrozenAddress(shipping);
                place.mutate(shipping);
              }}>{c.btnPlace}</Button>}
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
                  <Notice tone="info">{ins.collectionMethod === "SALES_COLLECTION" ? c.salesInstructionsIssued : c.instructionsIssued}</Notice>
                  <FieldGrid>
                    <Field label={c.colPayable}><Money m={ins.payable} /></Field>
                    <Field label={c.colDueAt}>{fmtAt(ins.paymentDueAt)}</Field>
                    <Field label={c.colProfile}>{`${ins.profileNo} · v${ins.profileVersion}`}</Field>
                    <Field label={c.colInstructions}>{Object.entries(ins.instructions).filter(([key])=>key!=="collectionMethod").map(([k, v]) => `${k}: ${v}`).join(" · ")}</Field>
                  </FieldGrid>
                </>
              )}
              <Button variant="outline" onClick={() => setOpen(order.orderNo!)}>{c.btnDetail}</Button>
            </div>
          )}
        </div>
      )}
      </fieldset>
      {storeNo && <OrderDrawer c={c} storeNo={storeNo} orderNo={open} onClose={() => setOpen(null)} />}
    </div>
  );
}
