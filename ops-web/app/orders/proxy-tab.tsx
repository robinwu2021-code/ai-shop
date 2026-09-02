"use client";

// 代客下单 / 代客取消（矩阵 P-4.1.5）。
//
// 两件事放一起，因为客服接的是同一通电话：「帮我下一单」和「帮我把那单取消」。
//
// ⚠️ 四条硬规则在后端（PlatformOrderService#createProxyOrder），页面只是不给入口：
//   - **必须先选到顾客本人**（按手机后四位在人档里找）—— 没绑账号的下不了单：
//     那样的订单没有主人，顾客在 C 端看不到、付不了款、也退不了；
//   - **不代付款**：默认线下付（当面付给商家），线上付则由顾客自己在 App 里付；
//   - **不代用券、不代扣积分** —— 那是顾客的资产；
//   - **不代填地址**：只能到点自取，要送货得顾客自己下单（地址得他自己选）。
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
import { HelpNote } from "@/components/ui/help-note";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { ORDER_TRANSITIONS } from "@/lib/types";
import type { OrdersCopy } from "./copy";

interface Line { skuNo: string; qty: string }

/**
 * 代客能选的履约方式：**到点自取那几种**（与后端 PROXY_FULFILLMENTS 同一份）。
 * 快递 / 自送 / 上门都要收货地址 —— 那是顾客的个人信息，客服也没法当面核对。
 */
const PROXY_FULFILLMENTS: FulfillmentType[] = ["STORE_PICKUP", "NEIGHBOR_PICKUP", "STORE_VERIFY"];

export function ProxyTab({ c, canProxy }: { c: OrdersCopy; canProxy: boolean }) {
  const qc = useQueryClient();
  const fulfillMap = useFulfillmentTypeMap();
  const [keyword, setKeyword] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [cancelling, setCancelling] = useState<Order | null>(null);
  const [reason, setReason] = useState("");

  const [form, setForm] = useState({
    merchantNo: "", fulfillType: "STORE_PICKUP" as FulfillmentType,
    payMode: "OFFLINE" as "OFFLINE" | "ONLINE", reason: "",
  });
  const [lines, setLines] = useState<Line[]>([{ skuNo: "", qty: "1" }]);
  /** 顾客：按手机后四位在人档里找。**先有人，才有单** */
  const [phoneTail, setPhoneTail] = useState("");
  const [picked, setPicked] = useState<{ personNo: string; phoneTail: string | null } | null>(null);
  /**
   * 完整手机号：**没装过 App 的人**走这条 —— 后端按这个号建账号（走登录那条建户路），
   * 他日后用同一个号登录就能看到这张单。人档里找得到人时不用它。
   */
  const [fullPhone, setFullPhone] = useState("");
  /*
   * 幂等键在**打开这张表单时**生成，提交成功后换一把。
   * 连点两下 = 同一把钥匙 = 一单；顾客真要再来一单 = 新表单 = 新钥匙。
   */
  const [idemKey, setIdemKey] = useState(() => crypto.randomUUID());

  // 与后端同一条规矩：手机尾号要恰好四位才查（三位能查出人是 mock 放宽出来的假象）
  const candidates = useQuery({
    queryKey: ["proxy-persons", phoneTail],
    queryFn: () => api.listOpsMembers({ phoneTail, size: 20 }),
    enabled: phoneTail.length === 4,
  });
  const person = useQuery({
    queryKey: ["proxy-person", picked?.personNo],
    queryFn: () => api.getOpsPerson(picked!.personNo),
    enabled: !!picked,
  });
  const customerUserNo = person.data?.userNo ?? "";
  /** 人档里找到人（且绑了账号），或者填了完整手机号 —— 两条路都能落到一个真实账号上 */
  const canSubmitCustomer = !!customerUserNo || /^\d{11}$/.test(fullPhone.trim());

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
    setForm({ merchantNo: "", fulfillType: "STORE_PICKUP", payMode: "OFFLINE", reason: "" });
    setLines([{ skuNo: "", qty: "1" }]);
    setPhoneTail(""); setPicked(null); setFullPhone("");
    setIdemKey(crypto.randomUUID());
  };

  const create = useMutation({
    mutationFn: () =>
      api.createProxyOrder({
        userNo: customerUserNo || undefined,
        phone: customerUserNo ? undefined : fullPhone.trim(),
        merchantNo: form.merchantNo,
        fulfillType: form.fulfillType,
        payMode: form.payMode,
        reason: form.reason,
        idempotencyKey: idemKey,
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
      <HelpNote className="mb-3">{c.proxyNotice}</HelpNote>

      <Card className="mb-4">
        <CardHeader><CardTitle>{c.proxyCreateTitle}</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          {/* 先有人，才有单：这一格空着的时候，下面的东西都没有意义 */}
          <div className="space-y-1">
            <Label htmlFor="px-phone" required>{c.fieldCustomer}</Label>
            <div className="flex items-center gap-2">
              <Input id="px-phone" className="w-40" disabled={!canProxy} value={phoneTail}
                placeholder={c.phoneTailPlaceholder} maxLength={4}
                onChange={(e) => { setPhoneTail(e.target.value.replace(/\D/g, "").slice(0, 4)); setPicked(null); }} />
              {picked ? (
                <span className="txt-body">
                  {fill(c.customerPicked, { tail: picked.phoneTail ?? "—" })}
                  {person.isLoading ? "" : customerUserNo ? "" : ` · ${c.customerNoAccount}`}
                </span>
              ) : (
                <span className="text-muted-foreground txt-caption">{c.customerPickHint}</span>
              )}
            </div>
            {phoneTail.length === 4 && !picked && (
              <div className="flex flex-wrap gap-2 pt-1">
                {candidates.data?.records.length
                  ? candidates.data.records.map((m) => (
                    <Button key={m.memberNo} size="sm" variant="outline" disabled={!canProxy}
                      onClick={() => setPicked({ personNo: m.personNo, phoneTail: m.phoneTail })}>
                      {fill(c.customerCandidate, { tail: m.phoneTail ?? "—", entity: m.entityName })}
                    </Button>
                  ))
                  : <span className="text-muted-foreground txt-caption">{c.customerNotFound}</span>}
              </div>
            )}
            {/*
              * 人档里没有他、或者有但没绑账号 —— 两种情况的出路是同一条：
              * 填完整手机号，后端按它建号。**不是「不行」，是「这样就行」**
              */}
            {(customerUserNo === "" && (picked ? !person.isLoading : phoneTail.length === 4)) && (
              <div className="space-y-1 pt-2">
                <Label htmlFor="px-fullphone">{c.fieldFullPhone}</Label>
                <Input id="px-fullphone" className="w-56" disabled={!canProxy} value={fullPhone}
                  placeholder={c.fullPhonePlaceholder} maxLength={11}
                  onChange={(e) => setFullPhone(e.target.value.replace(/\D/g, "").slice(0, 11))} />
                <p className="txt-caption text-muted-foreground">{c.fullPhoneHint}</p>
              </div>
            )}
          </div>

          <FieldGrid>
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
              {/* 只给到点自取：要送货得顾客自己下单，地址得他自己选 */}
              <Select id="px-fulfill" className="w-full" disabled={!canProxy} value={form.fulfillType}
                onChange={(e) => setForm((p) => ({ ...p, fulfillType: e.target.value as FulfillmentType }))}>
                {PROXY_FULFILLMENTS.map((k) => (
                  <option key={k} value={k}>{fulfillMap[k]?.label ?? k}</option>
                ))}
              </Select>
              <p className="txt-caption text-muted-foreground">{c.fulfillProxyHint}</p>
            </div>
            <div className="space-y-1">
              <Label htmlFor="px-paymode" required>{c.fieldPayMode}</Label>
              <Select id="px-paymode" className="w-full" disabled={!canProxy} value={form.payMode}
                onChange={(e) => setForm((p) => ({ ...p, payMode: e.target.value as "OFFLINE" | "ONLINE" }))}>
                <option value="OFFLINE">{c.payModeOffline}</option>
                <option value="ONLINE">{c.payModeOnline}</option>
              </Select>
              <p className="txt-caption text-muted-foreground">
                {form.payMode === "OFFLINE" ? c.payModeOfflineHint : c.payModeOnlineHint}
              </p>
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
            <Button loading={create.isPending} disabled={!canProxy || !canSubmitCustomer}
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
