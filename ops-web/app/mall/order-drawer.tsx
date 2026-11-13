"use client";

// 商城订单详情抽屉：三维状态 · 冻结报价 · 付款尝试 · 发货 · 退款审核 · 线下核款。
//
// 订单页、待审队列、代客下单三处共用这一个抽屉 —— 同一张单在三个地方长得不一样，
// 运营会以为是三张单。
//
// 线下面板把**三类状态分开**：申报（买家说付了）、资金（平台确认到账没有）、权益（门店能不能用）。
// 核款回 ACCEPTED 只表示受理：资金端还没确认，恢复任务会继续推进，界面不能写「已到账」。
import * as React from "react";
import {CashRefundsSection} from "@/components/offline/cash-refunds";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { MallOfflineReviewAttempt, mallOfflineReceiver } from "@/lib/mall-offline-review";
import { api } from "@/lib/api";
import { isApiError } from "@/lib/api/error";
import { describeError } from "@/lib/commerce-reason";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { useCan } from "@/lib/use-can";
import { compareMinor, parseMajor, toMajorInput } from "@/lib/commerce-money";
import type {
  MallEvidencePurpose, MallMoney, MallOfflineActionView, MallOfflineClaimView, MallOfflineInstructionsView, MallOrderDetailView, MallPaymentAttempt,
  MallQuoteComponent, MallRefundReviewQuoteView, MallRefundView, MallShipment,
} from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CheckboxField } from "@/components/ui/checkbox";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Notice } from "@/components/ui/notice";
import { StatusBadge } from "@/components/ui/status-badge";
import { Textarea } from "@/components/ui/textarea";
import { useConfirm } from "@/components/ui/confirm-dialog";
import {
  ActionResult, Money, OrderAxes, asyncMap, claimMap, fmtAt, fulfillmentMap, newOpNo, paymentMap, showErr, type MallCopy,
} from "./shared";

/** 子表的数据都来自同一个订单查询：错误态与重试也跟着它走 */
interface Src { error: unknown; onRetry: () => void }

const OfflineReviewLock = React.createContext<(locked: boolean) => void>(() => {});

export function OrderDrawer({ c, storeNo, orderNo, onClose }: { c: MallCopy; storeNo: string; orderNo: string | null; onClose: () => void }) {
  const q = useQuery({ queryKey: ["mall-order", storeNo, orderNo], queryFn: () => api.mallOrder(storeNo, orderNo!), enabled: !!orderNo });
  const d = q.data;
  const [reviewPending, setReviewPending] = React.useState(false);
  return (
    <Drawer open={!!orderNo} onOpenChange={(o) => !o && !reviewPending && onClose()} width="w-[760px]"
      title={orderNo ? fill(c.orderTitle, { no: orderNo }) : ""} desc={fill(c.orderStoreDesc, { store: storeNo })}>
      <OfflineReviewLock.Provider value={setReviewPending}>
      {q.error && !d ? (
        <DataTable columns={[]} rows={undefined} error={showErr(q.error)} onRetry={() => q.refetch()} rowKey={() => ""} empty={c.ordersEmpty} />
      ) : q.isLoading || !d ? <p className="txt-caption text-muted-foreground">{c.loading}</p> : (
        <OrderBody key={`${storeNo}:${orderNo}`} c={c} storeNo={storeNo} d={d} src={{ error: showErr(q.error), onRetry: () => q.refetch() }} />
      )}
      </OfflineReviewLock.Provider>
    </Drawer>
  );
}

function useRefreshOrder(storeNo: string, orderNo: string) {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: ["mall-order", storeNo, orderNo] });
    qc.invalidateQueries({ queryKey: ["mall-orders", storeNo] });
    qc.invalidateQueries({ queryKey: ["mall-offline", storeNo, orderNo] });
    qc.invalidateQueries({ queryKey: ["mall-claims-queue"] });
  };
}

function OrderBody({ c, storeNo, d, src }: { c: MallCopy; storeNo: string; d: MallOrderDetailView; src: Src }) {
  const o = d.summary?.order;
  const compColumns: Column<MallQuoteComponent>[] = [
    { header: c.colComponent, cell: (x) => <code className="txt-caption">{x.componentNo}</code> },
    { header: c.colKind, cell: (x) => x.kind },
    { header: c.colQty, numeric: true, cell: (x) => x.quantity },
    { header: c.colCycle, numeric: true, cell: (x) => (x.cycleDays ? fill(c.daysN, { n: x.cycleDays }) : "—") },
    { header: c.colDeal, numeric: true, cell: (x) => <Money m={x.dealAmount} /> },
    { header: c.colCredit, numeric: true, cell: (x) => <Money m={x.creditAmount} /> },
    { header: c.colCash, numeric: true, cell: (x) => <Money m={x.cashAmount} /> },
  ];
  const attemptColumns: Column<MallPaymentAttempt>[] = [
    { header: c.colChannel, cell: (x) => `${x.channel} / ${x.payMethod}` },
    { header: c.colAmount, numeric: true, cell: (x) => <Money m={x.amount} /> },
    { header: c.colStatus, cell: (x) => <StatusBadge map={asyncMap(c)} value={x.status ?? "PENDING"} /> },
    { header: c.colPaymentNo, cell: (x) => x.paymentNo ? <code className="txt-caption">{x.paymentNo}</code> : "—" },
    { header: c.colPaidAt, cell: (x) => fmtAt(x.paidAt) },
    { header: c.colNote, cell: (x) => x.errorCode === "MALL_UNMATCHED"
      ? <Badge tone="danger">{c.unmatchedBadge}</Badge> : x.errorCode ? <code className="txt-caption">{x.errorCode}</code> : "—" },
  ];
  return (
    <div className="space-y-2">
      <FieldGrid>
        <Field label={c.colOrderStatus}><Badge tone="outline">{o?.status}</Badge></Field>
        <Field label={c.colPayable}><Money m={o?.payable} /></Field>
        <Field label={c.colCreatedAt}>{fmtAt(o?.createdAt)}</Field>
        <Field label={c.colQuoteNo}><code className="txt-caption">{d.quote?.quoteNo}</code></Field>
      </FieldGrid>
      <Field label={c.axesLabel}><OrderAxes c={c} s={d.summary} /></Field>
      <Notice tone="muted">{c.axesNotice}</Notice>

      <DrawerSection title={c.secQuote} desc={c.secQuoteDesc}>
        <DataTable columns={compColumns} rows={d.quote?.components ?? o?.components} error={src.error} onRetry={src.onRetry} rowKey={(x) => x.componentNo ?? ""} empty={c.componentsEmpty} />
      </DrawerSection>
      <DrawerSection title={c.secAttempts} desc={c.secAttemptsDesc}>
        <DataTable columns={attemptColumns} rows={d.paymentAttempts} error={src.error} onRetry={src.onRetry} rowKey={(x) => x.attemptNo ?? ""} empty={c.attemptsEmpty} />
      </DrawerSection>
      <OfflinePanel c={c} storeNo={storeNo} d={d} />
      <ShipmentsSection c={c} storeNo={storeNo} d={d} src={src} />
      <RefundsSection c={c} storeNo={storeNo} d={d} src={src} />
    </div>
  );
}

// ── 发货 ──────────────────────────────────────────────────────────────

function ShipmentsSection({ c, storeNo, d, src }: { c: MallCopy; storeNo: string; d: MallOrderDetailView; src: Src }) {
  const allow = useCan();
  const orderNo = d.summary!.order!.orderNo!;
  const refresh = useRefreshOrder(storeNo, orderNo);
  const hardware = (d.quote?.components ?? d.summary?.order?.components ?? []).filter((x) => x.kind === "HARDWARE");
  const [form, setForm] = React.useState({ componentNo: hardware[0]?.componentNo ?? "", carrierCode: "", trackingNo: "", quantity: "1" });
  const [opNo, setOpNo] = React.useState(() => newOpNo("SHIP"));
  const ship = useMutation({
    mutationFn: () => api.shipMallOrder(storeNo, orderNo, { orderComponentNo: form.componentNo, operationNo: opNo,
      carrierCode: form.carrierCode.trim(), trackingNo: form.trackingNo.trim(), quantity: Number(form.quantity) }),
    onSuccess: () => { refresh(); setOpNo(newOpNo("SHIP")); setForm((f) => ({ ...f, trackingNo: "", quantity: "1" })); notify.success(c.toastShipped); },
  });
  if (!hardware.length) return null;
  const shipped = (no?: string) => (d.shipments ?? []).filter((s) => s.orderComponentNo === no && s.status !== "FAILED").reduce((a, s) => a + (s.quantity ?? 0), 0);
  const columns: Column<MallShipment>[] = [
    { header: c.colComponent, cell: (x) => <code className="txt-caption">{x.orderComponentNo}</code> },
    { header: c.colCarrier, cell: (x) => x.carrierCode },
    { header: c.colTracking, cell: (x) => x.trackingNo },
    { header: c.colQty, numeric: true, cell: (x) => x.quantity },
    { header: c.colStatus, cell: (x) => <StatusBadge map={asyncMap(c)} value={x.status ?? "PENDING"} /> },
  ];
  const paid = d.summary?.paymentStatus === "PAID";
  const valid = form.componentNo && form.carrierCode.trim() && form.trackingNo.trim() && Number(form.quantity) >= 1;
  return (
    <DrawerSection title={c.secShipments} desc={c.secShipmentsDesc}>
      <p className="mb-2 txt-caption text-muted-foreground">
        {hardware.map((h) => fill(c.shippedOf, { no: h.componentNo ?? "", done: shipped(h.componentNo), total: h.quantity ?? 0 })).join(" · ")}
      </p>
      <DataTable columns={columns} rows={d.shipments} error={src.error} onRetry={src.onRetry} rowKey={(x) => x.operationNo ?? ""} empty={c.shipmentsEmpty} />
      {allow("commerce:mall:shipment:write") && paid && (
        <div className="mt-3 grid gap-2 md:grid-cols-5">
          <Select aria-label={c.colComponent} value={form.componentNo} onChange={(e) => setForm((f) => ({ ...f, componentNo: e.target.value }))}>
            {hardware.map((h) => <option key={h.componentNo} value={h.componentNo}>{h.componentNo}</option>)}
          </Select>
          <Input aria-label={c.colCarrier} placeholder={c.colCarrier} value={form.carrierCode} onChange={(e) => setForm((f) => ({ ...f, carrierCode: e.target.value }))} />
          <Input aria-label={c.colTracking} placeholder={c.colTracking} value={form.trackingNo} onChange={(e) => setForm((f) => ({ ...f, trackingNo: e.target.value }))} />
          <Input aria-label={c.colQty} inputMode="numeric" value={form.quantity} onChange={(e) => setForm((f) => ({ ...f, quantity: e.target.value }))} />
          <Button disabled={!valid} loading={ship.isPending} onClick={() => ship.mutate()}>{c.btnShip}</Button>
        </div>
      )}
      {!paid && <Notice tone="muted" className="mt-2">{c.shipNeedsPaid}</Notice>}
    </DrawerSection>
  );
}

// ── 退款 ──────────────────────────────────────────────────────────────

function RefundsSection({ c, storeNo, d, src }: { c: MallCopy; storeNo: string; d: MallOrderDetailView; src: Src }) {
  const allow = useCan();
  const orderNo = d.summary!.order!.orderNo!;
  const refresh = useRefreshOrder(storeNo, orderNo);
  const { confirm, dialog } = useConfirm();
  const [review, setReview] = React.useState<{ refund: MallRefundView; quote: MallRefundReviewQuoteView } | null>(null);
  const [payoutFor, setPayoutFor] = React.useState<MallRefundView | null>(null);
  const [approve, setApprove] = React.useState({ cash: "", credit: "", evidence: "" });
  const offline = (d.paymentAttempts ?? []).some((a) => a.channel === "OFFLINE");

  const quote = useMutation({
    mutationFn: (r: MallRefundView) => api.reviewMallRefund(storeNo, orderNo, r.refundNo!, { requestDigest: r.requestDigest!, reviewQuoteNo: null }),
    onSuccess: (qv, r) => {
      setReview({ refund: r, quote: qv });
      setApprove({ cash: qv.preview?.cashLimit ? toMajorInput(qv.preview.cashLimit) : "", credit: qv.preview?.creditLimit ? toMajorInput(qv.preview.creditLimit) : "", evidence: "" });
    },
  });
  const cashMinor = review?.quote.preview?.cashLimit ? parseMajor(approve.cash, review.quote.preview.cashLimit.currency) : null;
  const creditMinor = review?.quote.preview?.creditLimit ? parseMajor(approve.credit || "0", review.quote.preview.creditLimit.currency) : null;
  const within = (v: string | null, lim?: MallMoney) => v !== null && !!lim && compareMinor({ currency: lim.currency, minor: v }, lim) <= 0;
  const approveValid = review && within(cashMinor, review.quote.preview?.cashLimit) && within(creditMinor, review.quote.preview?.creditLimit)
    && approve.evidence.trim() && (cashMinor !== "0" || creditMinor !== "0");
  const doApprove = useMutation({
    mutationFn: () => api.approveMallRefund(storeNo, orderNo, review!.refund.refundNo!, {
      approvalDigest: review!.quote.preview!.approvalDigest!, reviewQuoteNo: review!.quote.reviewQuoteNo!,
      cashAmount: { currency: review!.quote.preview!.cashLimit!.currency, minor: cashMinor! },
      restoreCredit: { currency: review!.quote.preview!.creditLimit!.currency, minor: creditMinor! }, reviewEvidence: approve.evidence.trim(),
    }),
    onSuccess: () => { refresh(); setReview(null); notify.success(c.toastRefundApproved); },
  });
  const reject = (r: MallRefundView) => confirm({
    title: c.refundRejectTitle, desc: c.refundRejectDesc, requireReason: true, danger: true,
    action: (reason) => api.rejectMallRefund(storeNo, orderNo, r.refundNo!, { requestDigest: r.requestDigest!, reviewEvidence: reason })
      .then(() => { refresh(); notify.success(c.toastRefundRejected); }),
  });

  if (!d.refunds?.length) return null;
  const canReview = allow("commerce:refund:review");
  const columns: Column<MallRefundView>[] = [
    { header: c.colRefundNo, cell: (x) => <code className="txt-caption">{x.refundNo}</code> },
    { header: c.colReview, cell: (x) => <Badge tone={x.reviewStatus === "APPROVED" ? "success" : x.reviewStatus === "DECLINED" ? "muted" : "warning"}>{x.reviewStatus ?? "—"}</Badge> },
    { header: c.colFunds, cell: (x) => <StatusBadge map={asyncMap(c)} value={x.status ?? "PENDING"} /> },
    { header: c.colCash, numeric: true, cell: (x) => <Money m={x.cashAmount} /> },
    { header: c.colRestore, numeric: true, cell: (x) => <Money m={x.restoredCredit} /> },
    { header: c.colActions, stickyEnd: "always", cell: (x) => (
      <span className="flex flex-wrap gap-2">
        {canReview && x.reviewStatus === "REQUESTED" && (
          <>
            <Button size="sm" variant="outline" loading={quote.isPending} onClick={() => quote.mutate(x)}>{c.btnReview}</Button>
            <Button size="sm" variant="outline" onClick={() => reject(x)}>{c.btnReject}</Button>
          </>
        )}
        {offline && allow("commerce:mall:offline:refund:confirm") && x.reviewStatus === "APPROVED" && x.status === "PENDING" && (
          <Button size="sm" onClick={() => setPayoutFor(x)}>{c.btnPayout}</Button>
        )}
      </span>
    ) },
  ];
  return (
    <DrawerSection title={c.secRefunds} desc={c.secRefundsDesc}>
      <DataTable columns={columns} rows={d.refunds} error={src.error} onRetry={src.onRetry} rowKey={(x) => x.refundNo ?? ""} empty={c.refundsEmpty} />
      {review && (
        <div className="mt-3 space-y-3 rounded-card border border-border p-3">
          <FieldGrid>
            <Field label={c.colReviewQuote}><code className="txt-caption">{review.quote.reviewQuoteNo}</code></Field>
            <Field label={c.colExpires}>{fmtAt(review.quote.preview?.expiresAt)}</Field>
            <Field label={c.cashLimit}><Money m={review.quote.preview?.cashLimit} /></Field>
            <Field label={c.creditLimit}><Money m={review.quote.preview?.creditLimit} /></Field>
          </FieldGrid>
          <div className="grid gap-3 md:grid-cols-2">
            <div className="space-y-1">
              <Label htmlFor="rf-cash" required>{c.colCash}</Label>
              <Input id="rf-cash" inputMode="decimal" value={approve.cash} invalid={!!approve.cash && !within(cashMinor, review.quote.preview?.cashLimit)}
                onChange={(e) => setApprove((a) => ({ ...a, cash: e.target.value }))} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="rf-credit">{c.colRestore}</Label>
              <Input id="rf-credit" inputMode="decimal" value={approve.credit} onChange={(e) => setApprove((a) => ({ ...a, credit: e.target.value }))} />
            </div>
          </div>
          <div className="space-y-1">
            <Label htmlFor="rf-ev" required>{c.fieldReviewEvidence}</Label>
            <Textarea id="rf-ev" value={approve.evidence} maxLength={1024} onChange={(v) => setApprove((a) => ({ ...a, evidence: v }))} />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setReview(null)}>{c.btnCancel}</Button>
            <Button disabled={!approveValid} loading={doApprove.isPending} onClick={() => doApprove.mutate()}>{c.btnApprove}</Button>
          </div>
        </div>
      )}
      {payoutFor && <PayoutForm c={c} storeNo={storeNo} orderNo={orderNo} refund={payoutFor} onDone={() => { setPayoutFor(null); refresh(); }} />}
      {dialog}
    </DrawerSection>
  );
}

function PayoutForm({ c, storeNo, orderNo, refund, onDone }: { c: MallCopy; storeNo: string; orderNo: string; refund: MallRefundView; onDone: () => void }) {
  const cur = refund.cashAmount!.currency;
  const [f, setF] = React.useState({ amount: toMajorInput(refund.cashAmount!), at: "", payee: "", bankRef: "", evidenceKey: "" });
  const minor = parseMajor(f.amount, cur);
  const save = useMutation({
    mutationFn: () => api.confirmOfflineRefundPayout(storeNo, orderNo, refund.refundNo!, { actualAmount: { currency: cur, minor: minor! },
      paidOutAt: new Date(f.at).toISOString(), payeeReference: f.payee.trim(), bankReference: f.bankRef.trim(), evidenceKey: f.evidenceKey }),
    onSuccess: () => { notify.success(c.toastPayout); onDone(); },
  });
  const valid = minor !== null && f.at && f.payee.trim() && f.bankRef.trim() && f.evidenceKey;
  return (
    <div className="mt-3 space-y-3 rounded-card border border-border p-3">
      <Notice tone="warning">{fill(c.payoutNotice, { amount: refund.cashAmount ? toMajorInput(refund.cashAmount) : "", cur })}</Notice>
      <EvidenceBox c={c} storeNo={storeNo} orderNo={orderNo} purposes={["REFUND_PROOF"]} selected={f.evidenceKey}
        onSelect={(k) => setF((x) => ({ ...x, evidenceKey: k }))} />
      <div className="grid gap-3 md:grid-cols-2">
        <div className="space-y-1">
          <Label htmlFor="po-amt" required>{c.fieldPayoutAmount}</Label>
          <Input id="po-amt" inputMode="decimal" value={f.amount} onChange={(e) => setF((x) => ({ ...x, amount: e.target.value }))} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="po-at" required>{c.fieldPaidOutAt}</Label>
          <Input id="po-at" type="datetime-local" value={f.at} onChange={(e) => setF((x) => ({ ...x, at: e.target.value }))} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="po-payee" required>{c.fieldPayee}</Label>
          <Input id="po-payee" value={f.payee} onChange={(e) => setF((x) => ({ ...x, payee: e.target.value }))} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="po-ref" required>{c.fieldBankRef}</Label>
          <Input id="po-ref" value={f.bankRef} onChange={(e) => setF((x) => ({ ...x, bankRef: e.target.value }))} />
        </div>
      </div>
      <div className="flex justify-end"><Button disabled={!valid} loading={save.isPending} onClick={() => save.mutate()}>{c.btnConfirmPayout}</Button></div>
    </div>
  );
}

// ── 凭证 ──────────────────────────────────────────────────────────────

/**
 * 运营凭证：预留键 → 上传字节（image/jpeg|png，≤10MB，每个键一次）→ 选中给核款/作废/出款用。
 *
 * ⚠️ 契约里没有「列出某单所有凭证」的接口。买家凭证随申报给出（见申报表的「买家凭证」列）；
 * 这里只列**本次打开抽屉期间上传的平台凭证键**，另给一个「按键查看」入口（键来自线下沟通或日志）。
 */
export function EvidenceBox({
  c, storeNo, orderNo, purposes, selected, onSelect,
}: { c: MallCopy; storeNo: string; orderNo: string; purposes: MallEvidencePurpose[]; selected: string; onSelect: (k: string) => void }) {
  const allow = useCan();
  const can = allow("commerce:mall:offline:evidence");
  const [purpose, setPurpose] = React.useState<MallEvidencePurpose>(purposes[0]!);
  const [keys, setKeys] = React.useState<{ key: string; purpose: string }[]>([]);
  const [lookup, setLookup] = React.useState("");
  const [preview, setPreview] = React.useState<string | null>(null);
  const fileRef = React.useRef<HTMLInputElement>(null);

  const upload = useMutation({
    mutationFn: async (file: File) => {
      if (!["image/jpeg", "image/png"].includes(file.type)) throw new Error(c.evidenceType);
      if (file.size > 10 * 1024 * 1024) throw new Error(c.evidenceSize);
      const t = await api.reserveOfflineEvidence(storeNo, orderNo, purpose);
      await api.uploadOfflineEvidence(storeNo, orderNo, t.assetKey, file);
      return t;
    },
    onSuccess: (t) => { setKeys((k) => [...k, { key: t.assetKey, purpose: t.purpose }]); onSelect(t.assetKey); notify.success(c.toastEvidence); },
  });
  const view = useMutation({
    mutationFn: (key: string) => api.offlineEvidenceUrl(storeNo, orderNo, key),
    onSuccess: (r) => setPreview(r.url),
  });

  if (!can) return <Notice tone="muted">{c.evidenceNoPerm}</Notice>;
  return (
    <div className="space-y-2 rounded-card border border-dashed border-border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="txt-label">{c.evidenceTitle}</span>
        <Select aria-label={c.evidencePurpose} value={purpose} onChange={(e) => setPurpose(e.target.value as MallEvidencePurpose)}>
          {purposes.map((p) => <option key={p} value={p}>{p}</option>)}
        </Select>
        <input ref={fileRef} type="file" accept="image/jpeg,image/png" className="sr-only focus-ring" aria-label={c.evidenceUpload}
          onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = ""; }} />
        <Button size="sm" variant="outline" loading={upload.isPending} onClick={() => fileRef.current?.click()}>{c.evidenceUpload}</Button>
      </div>
      {keys.length > 0 && (
        <ul className="space-y-1">
          {keys.map((k) => (
            <li key={k.key} className="flex flex-wrap items-center gap-2">
              <CheckboxField label={`${k.key} · ${k.purpose}`} checked={selected === k.key} onChange={(v) => v === true && onSelect(k.key)} />
              <Button size="sm" variant="ghost" onClick={() => view.mutate(k.key)}>{c.btnView}</Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Input aria-label={c.evidenceLookup} className="w-56" placeholder={c.evidenceLookup} value={lookup} onChange={(e) => setLookup(e.target.value)} />
        <Button size="sm" variant="ghost" disabled={!lookup.trim()} onClick={() => view.mutate(lookup.trim())}>{c.btnView}</Button>
        <Button size="sm" variant="ghost" disabled={!lookup.trim()} onClick={() => onSelect(lookup.trim())}>{c.btnUseKey}</Button>
      </div>
      {selected && <p className="txt-caption text-muted-foreground">{fill(c.evidenceSelected, { k: selected })}</p>}
      {preview && (
        <div className="space-y-1">
          {/* 签名地址 5 分钟有效；预览只为核对，不缓存 */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={preview} alt={c.evidenceTitle} className="max-h-64 rounded-card border border-border" />
          <Button size="sm" variant="ghost" onClick={() => setPreview(null)}>{c.btnClose}</Button>
        </div>
      )}
    </div>
  );
}

/**
 * 买家随申报上传的转账凭证：每张一个按钮，点开取 5 分钟签名地址预览。
 * 只为在银行流水里找到这笔钱 —— 核款仍以银行实际到账为准（申报 ≠ 到账）。
 */
function ClaimReceipts({ c, storeNo, orderNo, keys }: { c: MallCopy; storeNo: string; orderNo: string; keys: string[] }) {
  const allow = useCan();
  const [preview, setPreview] = React.useState<string | null>(null);
  const view = useMutation({
    mutationFn: (key: string) => api.offlineEvidenceUrl(storeNo, orderNo, key),
    onSuccess: (r) => setPreview(r.url),
  });
  if (!keys.length) return <span className="txt-caption text-muted-foreground">—</span>;
  if (!allow("commerce:mall:offline:evidence")) return <span className="txt-caption text-muted-foreground">{fill(c.receiptN, { n: keys.length })}</span>;
  return (
    <div className="space-y-1">
      <span className="flex flex-wrap gap-1">
        {keys.map((k, i) => (
          <Button key={k} size="sm" variant="ghost" title={c.receiptsHelp} loading={view.isPending && view.variables === k}
            onClick={() => view.mutate(k)}>{fill(c.receiptN, { n: i + 1 })}</Button>
        ))}
      </span>
      {view.error && <span className="txt-caption text-destructive">{String((showErr(view.error) as Error)?.message ?? view.error)}</span>}
      {preview && (
        <div className="space-y-1">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={preview} alt={c.colReceipts} className="max-h-64 rounded-card border border-border" />
          <Button size="sm" variant="ghost" onClick={() => setPreview(null)}>{c.btnClose}</Button>
        </div>
      )}
    </div>
  );
}

// ── 线下核款面板 ──────────────────────────────────────────────────────

function OfflinePanel({ c, storeNo, d }: { c: MallCopy; storeNo: string; d: MallOrderDetailView }) {
  const allow = useCan();
  const exceptionLock = React.useContext(OfflineReviewLock);
  const orderNo = d.summary!.order!.orderNo!;
  const refresh = useRefreshOrder(storeNo, orderNo);
  const { confirm, dialog } = useConfirm();
  const ins = useQuery({ queryKey: ["mall-offline", storeNo, orderNo, "instructions"], retry: false,
    queryFn: () => api.offlineInstructions(storeNo, orderNo) });
  const claims = useQuery({ queryKey: ["mall-offline", storeNo, orderNo, "claims"], retry: false,
    queryFn: () => api.offlineClaims(storeNo, orderNo) });
  const [lastAction, setLastAction] = React.useState<MallOfflineActionView | null>(null);
  const [mode, setMode] = React.useState<"confirm" | "void" | null>(null);
  const [claimFor, setClaimFor] = React.useState<string | null>(null);

  const insErr = ins.error;
  const reason = isApiError(insErr) ? insErr.message : null;
  const notOffline = reason === "OFFLINE_INTENT_REQUIRED";
  const issue = useMutation({
    mutationFn: () => api.issueOfflineInstructions(storeNo, orderNo),
    onSuccess: () => { refresh(); notify.success(c.toastIssued); },
  });

  if (notOffline) return null;

  const decline = (cl: MallOfflineClaimView) => confirm({
    title: c.declineTitle, desc: c.declineDesc, requireReason: true,
    action: (r) => api.declineOfflineClaim(storeNo, orderNo, cl.claimNo, r).then(() => { refresh(); notify.success(c.toastDeclined); }),
  });

  const claimColumns: Column<MallOfflineClaimView>[] = [
    { header: c.colClaimNo, cell: (x) => <code className="txt-caption">{x.claimNo}</code> },
    { header: c.colClaimStatus, cell: (x) => <StatusBadge map={claimMap(c)} value={x.status} /> },
    { header: c.colDeclared, numeric: true, cell: (x) => <Money m={x.declaredAmount} /> },
    { header: c.colDeclaredAt, cell: (x) => fmtAt(x.declaredPaidAt) },
    { header: c.colBankReference, cell: (x) => <code className="txt-caption">{x.bankReference || "—"}</code> },
    { header: c.colReceipts, cell: (x) => <ClaimReceipts c={c} storeNo={storeNo} orderNo={orderNo} keys={x.evidenceKeys ?? []} /> },
    { header: c.colSubmittedAt, cell: (x) => fmtAt(x.submittedAt) },
    { header: c.colActions, stickyEnd: "always", cell: (x) => (x.status === "SUBMITTED" || x.status === "REVIEWING") ? (
      <span className="flex gap-2">
        {allow("commerce:mall:offline:confirm") && <Button size="sm" disabled={mode !== null} onClick={() => { setClaimFor(x.claimNo); setMode("confirm"); }}>{c.btnConfirmClaim}</Button>}
        {allow("commerce:mall:offline:review") && <Button size="sm" variant="outline" disabled={mode !== null} onClick={() => decline(x)}>{c.btnDecline}</Button>}
      </span>
    ) : null },
  ];

  const paid = d.summary?.paymentStatus === "PAID";
  const closed = d.summary?.paymentStatus === "CLOSED";
  const unmatched = (d.paymentAttempts ?? []).filter((a) => a.errorCode === "MALL_UNMATCHED" && a.paymentNo);
  const pendingClaims = (claims.data ?? []).filter((x) => x.status === "SUBMITTED" || x.status === "REVIEWING").length;
  return (
    <DrawerSection title={c.secOffline} desc={c.secOfflineDesc}>
      {/* 三类状态并排，各说各的 */}
      <div className="mb-3 grid gap-2 md:grid-cols-3">
        <div className="rounded-card border border-border p-2">
          <div className="txt-caption text-muted-foreground">{c.stateClaim}</div>
          <div className="txt-body">{claims.data ? fill(c.stateClaimValue, { n: claims.data.length, p: pendingClaims }) : "—"}</div>
        </div>
        <div className="rounded-card border border-border p-2">
          <div className="txt-caption text-muted-foreground">{c.stateFunds}</div>
          <StatusBadge map={paymentMap(c)} value={d.summary?.paymentStatus ?? ""} />
          {unmatched.length > 0 && <Badge tone="danger" className="ms-2">{fill(c.unmatchedN, { n: unmatched.length })}</Badge>}
        </div>
        <div className="rounded-card border border-border p-2">
          <div className="txt-caption text-muted-foreground">{c.stateEntitlement}</div>
          <StatusBadge map={fulfillmentMap(c)} value={d.summary?.fulfillmentStatus ?? ""} />
        </div>
      </div>
      <Notice tone="info" className="mb-3">{c.offlineStatesNotice}</Notice>

      {reason === "OFFLINE_INSTRUCTIONS_NOT_ISSUED" ? (
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <Badge tone="warning">{c.instructionsNotIssued}</Badge>
          {allow("commerce:mall:assisted:purchase") && <Button size="sm" loading={issue.isPending} onClick={() => issue.mutate()}>{c.btnIssue}</Button>}
        </div>
      ) : ins.error ? (
        <DataTable columns={[]} rows={undefined} error={showErr(ins.error)} onRetry={() => ins.refetch()} rowKey={() => ""} empty={c.claimsEmpty} />
      ) : ins.data && (
        <FieldGrid>
          <Field label={c.colPayable}><Money m={ins.data.payable} /></Field>
          <Field label={c.colDueAt}>{fmtAt(ins.data.paymentDueAt)}</Field>
          <Field label={c.colProfile}>{`${ins.data.profileNo} · v${ins.data.profileVersion}`}</Field>
          <Field label={c.colInstructions}>{Object.entries(ins.data.instructions).filter(([key])=>key!=="collectionMethod").map(([k, v]) => `${k}: ${v}`).join(" · ")}</Field>
        </FieldGrid>
      )}

      <DataTable columns={claimColumns} rows={claims.data} loading={claims.isLoading} error={claims.error && !notOffline ? showErr(claims.error) : undefined}
        onRetry={() => claims.refetch()} rowKey={(x) => x.claimNo} empty={ins.data?.collectionMethod === "SALES_COLLECTION" || d.quote?.collectionMethod === "SALES_COLLECTION" ? c.salesClaimsNotRequired : c.claimsEmpty} />

      {lastAction && <div className="mt-2"><ActionResult c={c} r={lastAction} /></div>}

      {!paid && !closed && ins.data && (
        <div className="mt-3 flex flex-wrap gap-2">
          {allow("commerce:mall:offline:confirm") && <Button size="sm" variant="outline" disabled={mode !== null} onClick={() => { setClaimFor(null); setMode("confirm"); }}>{c.btnDirectConfirm}</Button>}
        </div>
      )}
      {mode === "confirm" && ins.data && (
        <ConfirmForm c={c} storeNo={storeNo} orderNo={orderNo} instructions={ins.data} claimNo={claimFor}
          claim={(claims.data ?? []).find((x) => x.claimNo === claimFor)}
          onDone={(r) => { setLastAction(r); setMode(null); refresh(); }} onCancel={() => setMode(null)} />
      )}
      {mode === "void" && (
        <VoidForm c={c} storeNo={storeNo} orderNo={orderNo} unmatched={unmatched}
          onDone={(r) => { setLastAction(r); setMode(null); refresh(); }} onCancel={() => setMode(null)} />
      )}
      <ReceiptDocumentationSection c={c} storeNo={storeNo} orderNo={orderNo} />
      <CashRefundsSection key={`${storeNo}:${orderNo}`} kind="mall" scope={storeNo} orderNo={orderNo} canRelease={!paid&&!closed} onChanged={refresh} onLock={exceptionLock} />
      {dialog}
    </DrawerSection>
  );
}

/** An unresolved review keeps its exact request in RAM for this mounted form. */
function useOfflineReview(c: MallCopy, storeNo: string, orderNo: string, onDone: (r: MallOfflineActionView) => void) {
  const lock = React.useContext(OfflineReviewLock);
  React.useEffect(() => () => lock(false), [lock]);
  const [attempt, setAttempt] = React.useState<MallOfflineReviewAttempt | null>(null);
  const [result, setResult] = React.useState<MallOfflineActionView | null>(null);
  const [, render] = React.useReducer((n: number) => n + 1, 0);
  const refresh = useRefreshOrder(storeNo, orderNo);
  const outcome = (r: MallOfflineActionView | null) => {
    render();
    if (!r) return;
    setResult(r); refresh();
    if (r.status === "ACCEPTED") return;
    // UNMATCHED and REJECTED must never look like an entitlement success.
    lock(false); onDone(r);
  };
  const save = useMutation({ mutationFn: (a: MallOfflineReviewAttempt) => a.submit(api, storeNo, orderNo), onSuccess: outcome, onSettled: () => render() });
  const lookup = useMutation({ mutationFn: () => attempt!.resolve((actionNo) => api.offlineAction(storeNo, orderNo, actionNo)),
    onSuccess: outcome, onSettled: () => render() });
  return { attempt, result, save, lookup,
    start: (intent: ConstructorParameters<typeof MallOfflineReviewAttempt>[0]) => {
      const a = attempt ?? new MallOfflineReviewAttempt(intent);
      lock(true); setAttempt(a); save.mutate(a);
    } };
}

function ReviewRecovery({ c, review }: { c: MallCopy; review: ReturnType<typeof useOfflineReview> }) {
  const { attempt, result, save, lookup } = review;
  if (!attempt) return null;
  const error = showErr(lookup.error ?? save.error);
  return <div className="space-y-2">
    {result && <ActionResult c={c} r={result} />}
    <Notice tone="warning">{attempt.phase === "accepted" ? c.actAcceptedHint : attempt.phase === "absent" ? c.reviewAbsent : c.reviewUnknown}</Notice>
    {!!(save.error || lookup.error) && <Notice tone="warning">{error instanceof Error ? error.message : String(error)}</Notice>}
    <Button variant="outline" loading={lookup.isPending} disabled={save.isPending} onClick={() => lookup.mutate()}>{c.reviewLookup}</Button>
    {attempt.phase === "absent" && <Button loading={save.isPending} disabled={lookup.isPending} onClick={() => save.mutate(attempt)}>{c.reviewRetry}</Button>}
  </div>;
}

function ConfirmForm({
  c, storeNo, orderNo, instructions, claimNo, claim, onDone, onCancel,
}: {
  c: MallCopy; storeNo: string; orderNo: string; instructions: MallOfflineInstructionsView; claimNo: string | null; claim?: MallOfflineClaimView;
  onDone: (r: MallOfflineActionView) => void; onCancel: () => void;
}) {
  const [actionNo] = React.useState(() => newOpNo("CFM"));
  const docs=useQuery({queryKey:["mall-offline",storeNo,orderNo,"documentation"],queryFn:()=>api.offlineReceiptDocumentation(storeNo,orderNo)});
  const payable = instructions.payable;
  const receiver = mallOfflineReceiver(instructions);
  const sales = instructions.collectionMethod === "SALES_COLLECTION";
  const collectors = useQuery({ queryKey: ["mall-sales-collectors", storeNo], queryFn: () => api.offlineCollectors(storeNo), enabled: sales });
  const review = useOfflineReview(c, storeNo, orderNo, onDone);
  const [f, setF] = React.useState({ collectorNo: "", currency: payable.currency, amount: toMajorInput(payable), at: new Date(Date.now()-new Date().getTimezoneOffset()*60000).toISOString().slice(0,19), bankRef: "", evidenceKey: "", historical: false, reason: "",testPayment:false });
  const minor = parseMajor(f.amount, f.currency);
  const submit = () => review.start({ kind: "confirm", request: { actionNo, claimNo: sales ? null : claimNo,
    actualAmount: { currency: f.currency, minor: minor! }, actualPaidAt: new Date(f.at).toISOString(),
    actualReceiver: receiver!, bankReference: sales ? null : f.bankRef.trim(),
    collectorNo: sales ? f.collectorNo : undefined, collectionReference: sales ? f.bankRef.trim() : undefined, evidenceKey: f.evidenceKey || null,testPayment:f.testPayment,
    historical: f.historical, reason: f.reason.trim() } });
  const mismatch = minor !== null && (minor !== payable.minor || f.currency !== payable.currency);
  const valid = /^[A-Z]{3}$/.test(f.currency) && (!sales || collectors.data?.some(x => x.collectorNo === f.collectorNo)) && minor !== null && f.at && receiver?.trim() && f.bankRef.trim() && (sales || f.evidenceKey) && f.reason.trim();
  return (
    <div className="mt-3 space-y-3 rounded-card border border-border p-3">
      <div className="txt-strong">{claimNo ? fill(c.confirmForClaim, { no: claimNo }) : c.confirmDirect}</div>
      <Notice tone="warning">{f.testPayment ? c.testReceiptHint : sales ? c.salesConfirmNotice : c.confirmNotice}</Notice>
      {claim && <p className="txt-caption text-muted-foreground">{fill(c.claimDeclaredEcho, { amount: toMajorInput(claim.declaredAmount), at: fmtAt(claim.declaredPaidAt), ref: claim.bankReference || "—" })}</p>}
      {!receiver?.trim() && <Notice tone="warning">{c.receiverMissing}</Notice>}
      <fieldset disabled={review.attempt !== null} className="space-y-3">
        {sales && docs.data?.testAllowed && <CheckboxField label={c.testReceipt} checked={f.testPayment} onChange={v=>setF(x=>({...x,testPayment:v===true,bankRef:v===true?`TEST-${actionNo}`:"",reason:v===true?c.testReceipt:""}))}/>}
        {sales && <p className="txt-caption text-muted-foreground">{c.docsLater}</p>}
        {sales && <div className="space-y-1">
          <Label htmlFor="cf-collector" required>{c.fieldCollector}</Label>
          <Select id="cf-collector" value={f.collectorNo} onChange={e => setF(x => ({ ...x, collectorNo: e.target.value }))}>
            <option value="">{c.pickCollector}</option>
            {collectors.data?.map(x => <option key={x.collectorNo} value={x.collectorNo}>{x.displayName}</option>)}
          </Select>
          {!!collectors.error && <Notice tone="danger">{describeError(collectors.error)}</Notice>}
          {!collectors.isFetching && !collectors.error && !collectors.data?.length && <Notice tone="warning">{c.collectorsEmpty}</Notice>}
        </div>}

        <EvidenceBox c={c} storeNo={storeNo} orderNo={orderNo} purposes={["PLATFORM_RECEIPT"]} selected={f.evidenceKey}
          onSelect={(k) => setF((x) => ({ ...x, evidenceKey: k }))} />
        <div className="grid gap-3 md:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="cf-currency" required>{c.fieldActualCurrency}</Label>
            <Input id="cf-currency" maxLength={3} value={f.currency} onChange={e => setF(x => ({ ...x, currency: e.target.value.toUpperCase() }))} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="cf-amt" required>{fill(c.fieldActualAmount, { cur: f.currency })}</Label>
            <Input id="cf-amt" inputMode="decimal" value={f.amount} invalid={!!f.amount && minor === null} onChange={(e) => setF((x) => ({ ...x, amount: e.target.value }))} />
            {mismatch && <p className="txt-caption text-destructive">{fill(c.amountMismatchHint, { want: toMajorInput(payable) })}</p>}
          </div>
          <div className="space-y-1">
            <Label htmlFor="cf-at" required>{c.fieldActualPaidAt}</Label>
            <Input id="cf-at" type="datetime-local" step={1} value={f.at} onChange={(e) => setF((x) => ({ ...x, at: e.target.value }))} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="cf-recv" required>{c.fieldReceiver}</Label>
            <div id="cf-recv" className="txt-body">{Object.entries(instructions.instructions).map(([k, v]) => `${k}: ${v}`).join(" · ") || "—"}</div>
          </div>
          <div className="space-y-1">
            <Label htmlFor="cf-ref" required>{sales ? c.fieldSalesReference : c.fieldBankRef}</Label>
            <Input id="cf-ref" value={f.bankRef} onChange={(e) => setF((x) => ({ ...x, bankRef: e.target.value }))} />
          </div>
        </div>
        <CheckboxField label={c.fieldHistorical} checked={f.historical} onChange={(v) => setF((x) => ({ ...x, historical: v === true }))} />
        <div className="space-y-1">
          <Label htmlFor="cf-reason" required>{c.fieldReason}</Label>
          <Textarea id="cf-reason" value={f.reason} onChange={(v) => setF((x) => ({ ...x, reason: v }))} />
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel}>{c.btnCancel}</Button>
          <Button disabled={!valid} loading={review.save.isPending} onClick={submit}>{c.btnSubmitConfirm}</Button>
        </div>
      </fieldset>
      <ReviewRecovery c={c} review={review} />
    </div>
  );
}

function ReceiptDocumentationSection({c,storeNo,orderNo}:{c:MallCopy;storeNo:string;orderNo:string}) {
  const allow=useCan();
  const docs=useQuery({queryKey:["mall-offline",storeNo,orderNo,"documentation"],queryFn:()=>api.offlineReceiptDocumentation(storeNo,orderNo),retry:false});
  const [selected,setSelected]=React.useState<string|null>(null);
  return <div className="mt-4 space-y-3 border-t pt-3">
    <div className="txt-strong">{c.documentation}</div>
    {docs.error && <Notice tone="danger">{describeError(docs.error)}</Notice>}
    {docs.data?.confirmations.map(row=><div key={row.confirmationActionNo} className="space-y-2 rounded-card border p-3">
      <div className="flex flex-wrap items-center gap-2"><Badge>{row.complete?c.docsComplete:c.docsPending}</Badge>{row.testPayment && <Badge>{c.testReceipt}</Badge>}<span>{row.status}</span></div>
      <p className="txt-caption">{row.confirmationActionNo} · {fmtAt(row.confirmedAt)} · {row.operatorNo}</p>
      <p><Money m={row.actualAmount}/> · {row.collectorNo} · {row.collectionReference}</p>
      {row.evidenceKey && <ClaimReceipts c={c} storeNo={storeNo} orderNo={orderNo} keys={[row.evidenceKey]}/>}
      {row.supplements.map(item=><div key={item.actionNo} className="border-l-2 pl-3">
        <p className="txt-caption text-muted-foreground">{fmtAt(item.createdAt)} · {item.operatorNo}</p><p>{item.note}</p>
        <ClaimReceipts c={c} storeNo={storeNo} orderNo={orderNo} keys={item.evidenceKeys}/>
      </div>)}
      {allow("commerce:mall:offline:confirm") && ["APPLIED","UNMATCHED"].includes(row.status) && selected===null && <Button size="sm" variant="outline" onClick={()=>setSelected(row.confirmationActionNo)}>{c.supplement}</Button>}
      {selected===row.confirmationActionNo && <SupplementForm key={selected} c={c} storeNo={storeNo} orderNo={orderNo} confirmationActionNo={selected} onClose={()=>setSelected(null)}/>}
    </div>)}
  </div>;
}
function SupplementForm({c,storeNo,orderNo,confirmationActionNo,onClose}:{c:MallCopy;storeNo:string;orderNo:string;confirmationActionNo:string;onClose:()=>void}) {
  const [actionNo]=React.useState(()=>newOpNo("docs"));const [note,setNote]=React.useState("");const [key,setKey]=React.useState("");
  const review=useOfflineReview(c,storeNo,orderNo,onClose);
  return <div className="space-y-3 border-t pt-3"><p className="txt-caption text-muted-foreground">{c.supplementHint}</p>
    <fieldset disabled={review.attempt!==null} className="space-y-3">
      <Label htmlFor="supplement-note">{c.supplementNote}</Label><Textarea id="supplement-note" value={note} onChange={setNote}/>
      <EvidenceBox c={c} storeNo={storeNo} orderNo={orderNo} purposes={["PLATFORM_RECEIPT"]} selected={key} onSelect={setKey}/>
      <div className="flex justify-end gap-2"><Button variant="ghost" onClick={onClose}>{c.btnCancel}</Button>
        <Button disabled={!note.trim()&&!key} loading={review.save.isPending} onClick={()=>review.start({kind:"supplement",request:{actionNo,confirmationActionNo,note:note.trim(),evidenceKeys:key?[key]:[]}})}>{c.btnSave}</Button></div>
    </fieldset><ReviewRecovery c={c} review={review}/>
  </div>;
}

function VoidForm({
  c, storeNo, orderNo, unmatched, onDone, onCancel,
}: { c: MallCopy; storeNo: string; orderNo: string; unmatched: MallPaymentAttempt[]; onDone: (r: MallOfflineActionView) => void; onCancel: () => void }) {
  const [actionNo] = React.useState(() => newOpNo("VOID"));
  const [f, setF] = React.useState({ recon: "", reason: "", evidenceKey: "" });
  const [disp, setDisp] = React.useState<Record<string, string>>(() => Object.fromEntries(unmatched.map((a) => [a.paymentNo!, ""])));
  const [extra, setExtra] = React.useState("");
  const review = useOfflineReview(c, storeNo, orderNo, onDone);
  const submit = () => review.start({ kind: "void", request: { actionNo, reconciliationReference: f.recon.trim(), reason: f.reason.trim(),
    evidenceKey: f.evidenceKey, unmatchedDispositions: Object.fromEntries(Object.entries(disp).map(([k, v]) => [k, v.trim()])) } });
  const valid = f.recon.trim() && f.reason.trim() && f.evidenceKey && Object.values(disp).every((v) => v.trim());
  return (
    <div className="mt-3 space-y-3 rounded-card border border-border p-3">
      <div className="txt-strong">{c.voidTitle}</div>
      <Notice tone="warning">{c.voidNotice}</Notice>
      <fieldset disabled={review.attempt !== null} className="space-y-3">
        <EvidenceBox c={c} storeNo={storeNo} orderNo={orderNo} purposes={["RECONCILIATION"]} selected={f.evidenceKey}
          onSelect={(k) => setF((x) => ({ ...x, evidenceKey: k }))} />
        <div className="space-y-1">
          <Label htmlFor="vd-recon" required>{c.fieldReconRef}</Label>
          <Input id="vd-recon" value={f.recon} onChange={(e) => setF((x) => ({ ...x, recon: e.target.value }))} />
        </div>
        <div className="space-y-2">
          <Label>{c.fieldDispositions}</Label>
          {Object.keys(disp).length === 0 && <p className="txt-caption text-muted-foreground">{c.noUnmatched}</p>}
          {Object.keys(disp).map((no) => (
            <div key={no} className="space-y-1">
              <code className="txt-caption">{no}</code>
              <Textarea aria-label={no} value={disp[no]} placeholder={c.dispositionPh} onChange={(v) => setDisp((d) => ({ ...d, [no]: v }))} />
            </div>
          ))}
          <div className="flex flex-wrap items-center gap-2">
            <Input aria-label={c.addUnmatched} className="w-56" placeholder={c.addUnmatched} value={extra} onChange={(e) => setExtra(e.target.value)} />
            <Button size="sm" variant="ghost" disabled={!extra.trim()} onClick={() => { setDisp((d) => ({ ...d, [extra.trim()]: "" })); setExtra(""); }}>{c.btnAdd}</Button>
          </div>
        </div>
        <div className="space-y-1">
          <Label htmlFor="vd-reason" required>{c.fieldReason}</Label>
          <Textarea id="vd-reason" value={f.reason} onChange={(v) => setF((x) => ({ ...x, reason: v }))} />
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel}>{c.btnCancel}</Button>
          <Button variant="destructive" disabled={!valid} loading={review.save.isPending} onClick={submit}>{c.btnSubmitVoid}</Button>
        </div>
      </fieldset>
      <ReviewRecovery c={c} review={review} />
    </div>
  );
}
