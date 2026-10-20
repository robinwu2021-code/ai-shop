"use client";
import { EarningEvents } from "../earning-events";
import { useCan } from "@/lib/use-can";
import { OrderEarningDetails } from "../order-earning-details";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PointsTransactionDirection, PointsTransactionView } from "@/lib/types";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { TabHeader } from "@/components/ui/tab-header";

function today() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; }
function monthStart() { return `${today().slice(0, 7)}-01`; }
export default function PointsTransactionsPage() {
  const can = useCan(); const [eventsOpen,setEventsOpen]=useState(false);
  const { t } = usePointsText(); const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [chosenType, setChosenType] = useState(""); const typeCode = chosenType || types.data?.[0]?.typeCode || "";
  const [direction, setDirection] = useState<PointsTransactionDirection>("EARN"); const [from, setFrom] = useState(monthStart); const [to, setTo] = useState(today);
  const [storeInput, setStoreInput] = useState(""); const [userInput, setUserInput] = useState(""); const [storeNo, setStoreNo] = useState(""); const [userNo, setUserNo] = useState(""); const [after, setAfter] = useState(0);
  const [selected, setSelected] = useState<PointsTransactionView | null>(null);
  const query = useQuery({ queryKey: ["points-transactions", typeCode, direction, from, to, storeNo, userNo, after], queryFn: () => api.pointsTransactions({ typeCode, direction, from, to, ...(storeNo ? { storeNo } : {}), ...(userNo ? { userNo } : {}), after, limit: 50 }), enabled: !!typeCode && !!from && !!to && to >= from });
  const detail = useQuery({ queryKey: ["points-transaction", typeCode, selected?.voucher.voucherNo], queryFn: () => api.pointsTransaction(typeCode, selected!.voucher.voucherNo), enabled: !!selected && !!typeCode });
  const stats = useQuery({ queryKey: ["points-period-stats", typeCode, from, to], queryFn: () => api.pointsPeriodStats({ typeCode, from, to }), enabled: !!typeCode && !!from && !!to && to >= from });
  const columns = [
    { header: t("时间", "Time"), cell: (r: PointsTransactionView) => r.voucher.createdAt ? new Date(r.voucher.createdAt).toLocaleString() : "—" },
    { header: t("业务凭证", "Business voucher"), cell: (r: PointsTransactionView) => <div><b>{r.voucher.bizRef}</b><p className="txt-caption text-muted-foreground">{r.voucher.voucherNo}</p></div> },
    { header: t("方向", "Direction"), cell: (r: PointsTransactionView) => t(r.direction === "EARN" ? "发放" : r.direction === "REDEEM" ? "核销" : "冲正 / 退回", r.direction) },
    { header: t("会员", "Member"), cell: (r: PointsTransactionView) => r.userNo ?? "—" },
    { header: t("门店", "Store"), cell: (r: PointsTransactionView) => r.storeNo ?? "—" },
    { header: t("会员净变化", "Member net change"), numeric: true, cell: (r: PointsTransactionView) => r.pointsDelta.toLocaleString() },
  ];
  return <main className="space-y-4">
    <TabHeader tabs={[{ key: "transactions", label: t("发放与核销", "Issuance & redemption") }]} value="transactions" onChange={() => {}} desc={t("查询发放、核销及冲正凭证，并查看不可变分录", "Review issuance, redemption and reversal vouchers with immutable ledger entries")} />
    <div className="flex flex-wrap items-end justify-between gap-3"><SectionHeader title={t("业务流水", "Transactions")} summary={query.data?.rows.length ?? 0} className="mb-0" /><div className="flex flex-wrap items-end gap-3"><label>{t("积分类型", "Points type")}<Select value={typeCode} onChange={(e) => { setChosenType(e.target.value); setAfter(0); }} >{types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode} · {x.currency}</option>)}</Select></label><label>{t("业务方向", "Direction")}<Select value={direction} onChange={(e) => { setDirection(e.target.value as PointsTransactionDirection); setAfter(0); }}><option value="EARN">{t("发放", "Issuance")}</option><option value="REDEEM">{t("核销", "Redemption")}</option><option value="REVERSAL">{t("冲正 / 退回", "Reversal / refund")}</option></Select></label></div></div>
    <section className="flex flex-wrap items-end gap-3 rounded-card border border-border p-4"><label>{t("开始日期", "From")}<Input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setAfter(0); }} /></label><label>{t("结束日期", "To")}<Input type="date" value={to} onChange={(e) => { setTo(e.target.value); setAfter(0); }} /></label><label>{t("门店编号", "Store number")}<Input value={storeInput} onChange={(e) => setStoreInput(e.target.value)} /></label><label>{t("会员编号", "Member number")}<Input value={userInput} onChange={(e) => setUserInput(e.target.value)} /></label><Button variant="outline" onClick={() => { setStoreNo(storeInput.trim()); setUserNo(userInput.trim()); setAfter(0); }}>{t("查询", "Search")}</Button></section>
    {to < from && <Notice tone="warning">{t("结束日期不能早于开始日期。", "End date must not precede start date.")}</Notice>}
    {stats.data && <section className="rounded-card border border-border p-4"><div className="flex flex-wrap justify-between gap-2"><b>{t("统计概览", "Period summary")}</b><span className="txt-caption text-muted-foreground">{stats.data.from} — {stats.data.to} · {t("有效日", "available days")} {stats.data.availableDays} / {t("缺失日", "missing days")} {stats.data.missingDays}</span></div><div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{Object.entries(stats.data.metrics).map(([key, value]) => <div key={key} className="rounded-field bg-muted/40 p-3"><p className="txt-caption text-muted-foreground">{key}</p><b className="tabular-nums">{value.toLocaleString()}</b></div>)}</div></section>}
    {(query.error || stats.error || types.error) && <Notice tone="warning">{String(query.error?.message ?? stats.error?.message ?? types.error?.message)}<Button onClick={() => { void query.refetch(); void stats.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
    <div className="flex justify-end"><Button variant="outline" disabled={!typeCode} onClick={()=>setEventsOpen(true)}>{t("处理记录","Processing records")}</Button></div>
    <DataTable columns={columns} rows={query.data?.rows} loading={query.isPending} error={query.error} onRetry={() => void query.refetch()} rowKey={(r) => r.voucher.voucherNo} empty={t("当前条件下没有发放、核销或冲正记录。", "No issuance, redemption or reversal records match these filters.")} rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setSelected(r), onKeyDown: (e) => { if (e.key === "Enter") setSelected(r); } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={() => setAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!query.data?.hasMore || query.isFetching} onClick={() => setAfter(query.data?.next ?? 0)}>{t("下一页", "Next page")}</Button></div>
    <EarningEvents key={typeCode} typeCode={typeCode} open={eventsOpen} onOpenChange={setEventsOpen} canRetry={can("finance:settle:execute")}/>
    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) setSelected(null); }} title={selected?.voucher.bizRef ?? ""} desc={t("凭证、比例快照和不可变账务分录", "Voucher, conversion snapshot and immutable ledger entries")} width="w-[760px]">
      {selected && <div className="space-y-4"><dl className="grid grid-cols-2 gap-3 rounded-card border border-border p-4"><dt>{t("凭证号", "Voucher")}</dt><dd>{detail.data?.voucher.voucherNo ?? selected.voucher.voucherNo}</dd><dt>{t("业务引用", "Business reference")}</dt><dd>{selected.voucher.bizRef}</dd><dt>{t("方向", "Direction")}</dt><dd>{t(selected.direction === "EARN" ? "发放" : selected.direction === "REDEEM" ? "核销" : "冲正 / 退回", selected.direction)}</dd><dt>{t("兑换比例快照", "Conversion rate snapshot")}</dt><dd>{selected.voucher.ratePoints} : {selected.voucher.rateMinor} {selected.voucher.currency}</dd><dt>{t("会员净变化", "Member net change")}</dt><dd>{selected.pointsDelta.toLocaleString()}</dd><dt>{t("门店 / 会员", "Store / member")}</dt><dd>{selected.storeNo ?? "—"} / {selected.userNo ?? "—"}</dd></dl>
        {selected.direction === "EARN" && selected.voucher.bizRef.startsWith("GRANT:") && <OrderEarningDetails subOrderNo={selected.voucher.bizRef.slice(6)} />}
        {detail.error && <Notice tone="warning">{detail.error.message}<Button onClick={() => void detail.refetch()}>{t("重试", "Retry")}</Button></Notice>}
        <DataTable columns={[{ header: t("记账时间", "Posted at"), cell: (e) => e.at ? new Date(e.at).toLocaleString() : "—" }, { header: t("持有人", "Holder"), cell: (e) => `${e.holderType} · ${e.holderNo}` }, { header: t("分桶", "Bucket"), cell: (e) => e.bucket }, { header: t("积分变化", "Delta"), numeric: true, cell: (e) => e.deltaPoints.toLocaleString() }, { header: t("记账后余额", "Balance after"), numeric: true, cell: (e) => e.afterPoints.toLocaleString() }]} rows={detail.data?.entries.rows} loading={detail.isPending} error={detail.error} onRetry={() => void detail.refetch()} rowKey={(e) => e.entryNo} empty={t("该凭证没有账务分录。", "No ledger entries for this voucher.")} />
      </div>}
    </Drawer>
  </main>;
}
