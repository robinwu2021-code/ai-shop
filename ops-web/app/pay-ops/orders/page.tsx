"use client";
// 支付单（F50）：列表 + 全貌抽屉（交易尝试、退款、流水凭证、答复、异常放在一页上）。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsOrderRow } from "@/lib/api/contracts/payops";
import { money, fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { TabHeader } from "@/components/ui/tab-header";
import { usePaging } from "@/lib/use-paging";
import { StatusBadge, useTT } from "../shared";

const STATUSES = ["", "CREATED", "AWAITING_PAYMENT", "SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED", "CLOSED"];

export default function PayOpsOrdersPage() {
  const tt = useTT();
  const [status, setStatus] = useState("");
  const [no, setNo] = useState("");
  const [query, setQuery] = useState<{ payOrderNo?: string; outOrderNo?: string }>({});
  const { page, setPage, size } = usePaging(20);
  const [open, setOpen] = useState<string | null>(null);
  const list = useQuery({ queryKey: ["pay-ops-orders", status, query, page, size],
    queryFn: () => api.payOpsOrders({ status: status || undefined, ...query, page, size }) });
  const detail = useQuery({ queryKey: ["pay-ops-order", open], queryFn: () => api.payOpsOrder(open!), enabled: !!open });

  function search() {
    const v = no.trim();
    setQuery(!v ? {} : v.startsWith("PYO") ? { payOrderNo: v } : { outOrderNo: v });
    setPage(1);
  }

  const columns = [
    { header: tt("支付单号", "Pay order"), cell: (r: PayOpsOrderRow) => <span dir="ltr">{r.payOrderNo}</span> },
    { header: tt("业务单号", "Business no."), cell: (r: PayOpsOrderRow) => <span dir="ltr">{r.outOrderNo}</span> },
    { header: tt("来源", "Source"), cell: (r: PayOpsOrderRow) => `${r.bizType} · ${r.sourceType ?? "—"}` },
    { header: tt("门店", "Store"), cell: (r: PayOpsOrderRow) => r.storeName ?? "—" },
    { header: tt("金额", "Amount"), numeric: true, cell: (r: PayOpsOrderRow) => money(r.amountMinor, r.currency) },
    { header: tt("已退", "Refunded"), numeric: true, cell: (r: PayOpsOrderRow) => money(r.refundedMinor, r.currency) },
    { header: tt("渠道", "Channel"), cell: (r: PayOpsOrderRow) => r.payChannel ?? "—" },
    { header: tt("状态", "Status"), cell: (r: PayOpsOrderRow) => <StatusBadge status={r.status} /> },
    { header: tt("建单时间", "Created"), cell: (r: PayOpsOrderRow) => fmtTime(r.createdAt) },
  ];
  const d = detail.data;
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "orders", label: tt("支付单", "Pay orders") }]} value="orders" onChange={() => {}}
      desc={tt("所有入口建的支付单：内部业务、第三方、收银台、商家代收。点一行看全貌。", "Pay orders from every entry. Click a row for the full timeline.")} />
    <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); search(); }}>
      <label className="flex flex-col gap-1">{tt("状态", "Status")}
        <Select value={status} onChange={(e) => { setStatus(e.target.value); setPage(1); }}>
          {STATUSES.map((s) => <option key={s} value={s}>{s || tt("全部", "All")}</option>)}
        </Select></label>
      <label className="flex flex-col gap-1">{tt("支付单号或业务单号", "Pay order or business no.")}
        <Input dir="ltr" value={no} onChange={(e) => setNo(e.target.value)} placeholder="PYO… / SO…" /></label>
      <Button variant="outline" type="submit">{tt("查询", "Search")}</Button>
    </form>
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(r) => r.payOrderNo} empty={tt("没有匹配的支付单。", "No matching pay orders.")}
      rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setOpen(r.payOrderNo) })} />
    <div className="flex justify-end gap-2">
      <Button variant="outline" disabled={page === 1} onClick={() => setPage(page - 1)}>{tt("上一页", "Previous")}</Button>
      <Button variant="outline" disabled={(list.data?.length ?? 0) < size} onClick={() => setPage(page + 1)}>{tt("下一页", "Next")}</Button>
    </div>
    <Drawer open={!!open} onOpenChange={(o) => { if (!o) setOpen(null); }} title={open ?? ""} width="w-[860px]"
      desc={tt("交易尝试、退款、流水凭证、业务通知与异常", "Attempts, refunds, ledger, notifications and exceptions")}>
      {detail.isPending && open && <p>{tt("加载中…", "Loading…")}</p>}
      {detail.error && <p className="text-destructive">{detail.error.message}</p>}
      {d && <div className="space-y-4">
        <section><h3 className="font-semibold">{tt("交易尝试", "Attempts")}</h3>
          <DataTable rows={d.attempts} error={detail.error} onRetry={() => void detail.refetch()} rowKey={(a) => a.paymentNo} empty={tt("还没有交易尝试。", "No attempts yet.")} columns={[
            { header: tt("流水号", "Payment no."), cell: (a) => <span dir="ltr">{a.paymentNo}</span> },
            { header: tt("方向", "Direction"), cell: (a) => a.direction },
            { header: tt("渠道单号", "Channel trade no."), cell: (a) => <span dir="ltr">{a.tradeNo ?? "—"}</span> },
            { header: tt("金额", "Amount"), numeric: true, cell: (a) => money(a.amountMinor, a.currency) },
            { header: tt("资金路径", "Funds path"), cell: (a) => a.fundsPath ?? "—" },
            { header: tt("状态", "Status"), cell: (a) => <StatusBadge status={a.status} /> },
          ]} /></section>
        <section><h3 className="font-semibold">{tt("退款", "Refunds")}</h3>
          <DataTable rows={d.refunds} error={detail.error} onRetry={() => void detail.refetch()} rowKey={(r) => r.payRefundNo} empty={tt("没有退款。", "No refunds.")} columns={[
            { header: tt("退款单号", "Refund no."), cell: (r) => <span dir="ltr">{r.payRefundNo}</span> },
            { header: tt("金额", "Amount"), numeric: true, cell: (r) => money(r.amountMinor, r.currency) },
            { header: tt("发起", "By"), cell: (r) => `${r.sourceType ?? ""} ${r.requestedBy ?? ""}` },
            { header: tt("状态", "Status"), cell: (r) => <StatusBadge status={r.status} /> },
          ]} /></section>
        <section><h3 className="font-semibold">{tt("流水凭证", "Ledger vouchers")}</h3>
          <DataTable rows={d.vouchers} error={detail.error} onRetry={() => void detail.refetch()} rowKey={(v) => v.voucherNo} empty={tt("没有流水凭证（存量单或免单）。", "No vouchers (legacy or free order).")} columns={[
            { header: tt("凭证号", "Voucher"), cell: (v) => <span dir="ltr">{v.voucherNo}</span> },
            { header: tt("类型", "Type"), cell: (v) => v.voucherType },
            { header: tt("金额", "Amount"), numeric: true, cell: (v) => money(v.totalMinor, v.currency) },
            { header: tt("业务日", "Biz date"), cell: (v) => v.bizDate ?? "—" },
          ]} /></section>
        <section><h3 className="font-semibold">{tt("业务通知与异常", "Notifications & exceptions")}</h3>
          <p className="text-muted-foreground">{tt(`通知 ${d.notifies.length} 条 · 异常 ${d.exceptions.length} 条（到对应页处置）`,
            `${d.notifies.length} notifications · ${d.exceptions.length} exceptions (handle them on their pages)`)}</p></section>
      </div>}
    </Drawer>
  </main>;
}
