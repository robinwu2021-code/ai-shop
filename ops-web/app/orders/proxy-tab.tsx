"use client";

// 代客下单 / 代客取消（矩阵 P-4.1.5）。
//
// 两件事放一起，因为客服接的是同一通电话：「帮我下一单」和「帮我把那单取消」。
//
// ⚠️ 两条硬规则在 mock 层（api/mocks/order.ts），页面写不出违规操作：
//   - 代客下单落到**待支付**，不代付款 —— 钱必须由用户自己付；
//   - 一次只能下一个商家的货 —— 全站按商家拆单（E3）。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { money } from "@/lib/utils";
import type { FulfillmentType, Order } from "@/lib/types";
import { OrderStatusBadge, useFulfillmentTypeMap } from "@/components/status";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Pagination } from "@/components/ui/misc";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ORDER_TRANSITIONS } from "@/lib/types";
import type { OrdersCopy } from "./copy";

interface Line { skuNo: string; qty: string }

export function ProxyTab({ c, canProxy }: { c: OrdersCopy; canProxy: boolean }) {
  const qc = useQueryClient();
  const fulfillMap = useFulfillmentTypeMap();
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [cancelling, setCancelling] = useState<Order | null>(null);
  const [reason, setReason] = useState("");

  const [form, setForm] = useState({
    buyerNickname: "", communityNo: "", merchantNo: "",
    fulfillType: "STORE_PICKUP" as FulfillmentType, reason: "",
  });
  const [lines, setLines] = useState<Line[]>([{ skuNo: "", qty: "1" }]);

  const q = { keyword, page, size };
  const list = useQuery({ queryKey: ["orders", q], queryFn: () => api.listOrders(q) });
  const communities = useQuery({ queryKey: ["communities", "proxy"], queryFn: () => api.listCommunities({ size: 100 }) });
  const merchants = useQuery({
    queryKey: ["merchants", "proxy"],
    queryFn: () => api.listMerchants({ size: 100, status: "APPROVED" }),
  });
  // 商品按所选商家过滤：跨商家下单在 mock 层就会被拒，选项里干脆不给
  const skus = useQuery({
    queryKey: ["skus", "proxy", form.merchantNo],
    queryFn: () => api.listSkus({ size: 100, merchantNo: form.merchantNo, status: "ON_SALE" }),
    enabled: !!form.merchantNo,
  });

  const reset = () => {
    setForm({ buyerNickname: "", communityNo: "", merchantNo: "", fulfillType: "STORE_PICKUP", reason: "" });
    setLines([{ skuNo: "", qty: "1" }]);
  };

  const create = useMutation({
    mutationFn: () =>
      api.createProxyOrder({
        ...form,
        items: lines.filter((l) => l.skuNo).map((l) => ({ skuNo: l.skuNo, qty: Number(l.qty) })),
      }),
    onSuccess: (o) => {
      qc.invalidateQueries({ queryKey: ["orders"] });
      reset();
      notify.success(fill(c.toastProxyCreated, { no: o.orderNo }));
    },
  });

  const cancel = useMutation({
    mutationFn: () => api.proxyCancelOrder({ orderNo: cancelling!.orderNo, reason }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["orders"] });
      setCancelling(null);
      notify.success(c.toastProxyCancelled);
    },
  });

  const columns: Column<Order>[] = [
    { header: c.colSubOrderNo, cell: (o) => o.orderNo, numeric: true, align: "start" },
    { header: c.colMerchant, cell: (o) => o.merchantName },
    { header: c.colBuyer, cell: (o) => o.buyerNickname },
    { header: c.colPaid, cell: (o) => money(o.payAmount), numeric: true },
    { header: c.colStatus, cell: (o) => <OrderStatusBadge value={o.status} /> },
    {
      header: c.colActions,
      // 只有状态机允许取消的单才给按钮：已完成的要走售后，摆个会报错的按钮是在骗人点一次
      cell: (o) =>
        canProxy && ORDER_TRANSITIONS[o.status].includes("CANCELLED") ? (
          <Button size="sm" variant="outline" onClick={() => { setCancelling(o); setReason(""); }}>
            {c.actionProxyCancel}
          </Button>
        ) : <span className="text-muted-foreground">{c.none}</span>,
    },
  ];

  const skuOf = (skuNo: string) => skus.data?.records.find((s) => s.skuNo === skuNo);
  const total = lines.reduce((s, l) => s + (skuOf(l.skuNo)?.prices.CN ?? 0) * (Number(l.qty) || 0), 0);

  return (
    <>
      <Notice className="mb-3">{c.proxyNotice}</Notice>

      <Card className="mb-4">
        <CardHeader><CardTitle>{c.proxyCreateTitle}</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <FieldGrid>
            <div className="space-y-1">
              <Label htmlFor="px-buyer" required>{c.fieldBuyer}</Label>
              <Input id="px-buyer" className="w-full" disabled={!canProxy} value={form.buyerNickname}
                onChange={(e) => setForm((p) => ({ ...p, buyerNickname: e.target.value }))} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="px-community" required>{c.fieldCommunity}</Label>
              <Select id="px-community" className="w-full" disabled={!canProxy} value={form.communityNo}
                onChange={(e) => setForm((p) => ({ ...p, communityNo: e.target.value }))}>
                <option value="">{c.pickCommunity}</option>
                {communities.data?.records.map((x) => <option key={x.communityNo} value={x.communityNo}>{x.name}</option>)}
              </Select>
            </div>
            <div className="space-y-1">
              <Label htmlFor="px-merchant" required>{c.fieldMerchant}</Label>
              <Select id="px-merchant" className="w-full" disabled={!canProxy} value={form.merchantNo}
                onChange={(e) => { setForm((p) => ({ ...p, merchantNo: e.target.value })); setLines([{ skuNo: "", qty: "1" }]); }}>
                <option value="">{c.pickMerchant}</option>
                {merchants.data?.records.map((x) => <option key={x.merchantNo} value={x.merchantNo}>{x.name}</option>)}
              </Select>
              <p className="txt-caption text-muted-foreground">{c.merchantHint}</p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="px-fulfill" required>{c.fieldFulfill}</Label>
              <Select id="px-fulfill" className="w-full" disabled={!canProxy} value={form.fulfillType}
                onChange={(e) => setForm((p) => ({ ...p, fulfillType: e.target.value as FulfillmentType }))}>
                {Object.entries(fulfillMap).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
              </Select>
            </div>
          </FieldGrid>

          <div className="space-y-2">
            <Label required>{c.fieldItems}</Label>
            {lines.map((l, i) => (
              <div key={i} className="flex items-center gap-2">
                <Select className="flex-1" disabled={!canProxy || !form.merchantNo} value={l.skuNo}
                  aria-label={c.fieldItems}
                  onChange={(e) => setLines((p) => p.map((x, j) => (j === i ? { ...x, skuNo: e.target.value } : x)))}>
                  <option value="">{form.merchantNo ? c.pickSku : c.pickMerchantFirst}</option>
                  {skus.data?.records.map((s) => (
                    <option key={s.skuNo} value={s.skuNo}>
                      {s.title.zh} · {money(s.prices.CN ?? 0)} · {fill(c.stockLeft, { n: s.stock })}
                    </option>
                  ))}
                </Select>
                <Input className="w-24" disabled={!canProxy} value={l.qty} aria-label={c.fieldQty}
                  onChange={(e) => setLines((p) => p.map((x, j) => (j === i ? { ...x, qty: e.target.value } : x)))} />
                <Button size="sm" variant="ghost" disabled={!canProxy || lines.length === 1}
                  onClick={() => setLines((p) => p.filter((_, j) => j !== i))}>
                  {c.btnRemoveLine}
                </Button>
              </div>
            ))}
            <Button size="sm" variant="outline" disabled={!canProxy || !form.merchantNo}
              onClick={() => setLines((p) => [...p, { skuNo: "", qty: "1" }])}>
              {c.btnAddLine}
            </Button>
          </div>

          <div className="space-y-1">
            <Label htmlFor="px-reason" required>{c.fieldProxyReason}</Label>
            <Textarea id="px-reason" value={form.reason} disabled={!canProxy}
              onChange={(v) => setForm((p) => ({ ...p, reason: v }))} placeholder={c.proxyReasonPlaceholder} rows={2} />
            <p className="txt-caption text-muted-foreground">{c.proxyReasonHint}</p>
          </div>

          <div className="flex items-center justify-between">
            <span className="txt-body">{fill(c.proxyTotal, { amount: money(total) })}</span>
            <Button loading={create.isPending} disabled={!canProxy}
              onClick={() => create.mutate()}>{c.btnProxyCreate}</Button>
          </div>
        </CardContent>
      </Card>

      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPlaceholder} />
      <DataTable
        columns={columns} rows={list.data?.records} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(o) => o.orderNo}
        empty={c.empty}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!cancelling}
        onOpenChange={(o) => !o && setCancelling(null)}
        title={cancelling ? fill(c.cancelTitle, { no: cancelling.orderNo }) : ""}
        footer={<Button loading={cancel.isPending} onClick={() => cancel.mutate()}>{c.btnProxyCancel}</Button>}
      >
        {cancelling && (
          <div>
            <DrawerSection first title={c.secOverview}>
              <FieldGrid>
                <Field className="mb-3" label={c.colMerchant}>{cancelling.merchantName}</Field>
                <Field className="mb-3" label={c.colBuyer}>{cancelling.buyerNickname}</Field>
                <Field className="mb-3" label={c.colPaid}>{money(cancelling.payAmount)}</Field>
                <Field className="mb-3" label={c.colStatus}><OrderStatusBadge value={cancelling.status} /></Field>
              </FieldGrid>
            </DrawerSection>
            <DrawerSection title={c.secCancel}>
              <Field className="mb-0" label={c.fieldCancelReason}>
                <Textarea value={reason} onChange={setReason} placeholder={c.cancelReasonPlaceholder} rows={3} />
              </Field>
              <p className="txt-caption text-muted-foreground">
                {cancelling.paidAt ? c.cancelRefundHint : c.cancelUnpaidHint}
              </p>
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}
