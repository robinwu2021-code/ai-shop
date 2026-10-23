"use client";
import { EarningEvents } from "../earning-events";
import { useCan } from "@/lib/use-can";
import { OrderEarningDetails } from "../order-earning-details";
import { useState, type ReactNode } from "react";
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
import styles from "./page.module.css";

function today() { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`; }
function monthStart() { return `${today().slice(0, 7)}-01`; }
export default function PointsTransactionsPage() {
  const can = useCan(); const [eventsOpen,setEventsOpen]=useState(false);
  const { t, locale } = usePointsText(); const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [chosenType, setChosenType] = useState(""); const typeCode = chosenType || types.data?.[0]?.typeCode || "";
  const [direction, setDirection] = useState<PointsTransactionDirection>("EARN"); const [from, setFrom] = useState(monthStart); const [to, setTo] = useState(today);
  const [storeInput, setStoreInput] = useState(""); const [userInput, setUserInput] = useState(""); const [storeNo, setStoreNo] = useState(""); const [userNo, setUserNo] = useState(""); const [after, setAfter] = useState(0);
  const [selected, setSelected] = useState<PointsTransactionView | null>(null);
  const [showStats, setShowStats] = useState(false);
  const validDates = !!from && !!to && to >= from;
  function resetSelection() { setAfter(0); setSelected(null); }
  function searchRecords() { setStoreNo(storeInput.trim()); setUserNo(userInput.trim()); resetSelection(); }
  const number = (n: number) => n.toLocaleString(locale === "zh" ? "zh-CN" : locale);
  const time = (at: number) => at ? new Date(at).toLocaleString(locale === "zh" ? "zh-CN" : locale) : "—";
  const directionLabel = (value: string) => value === "EARN" ? t("发放", "Issuance") : value === "REDEEM" ? t("核销", "Redemption") : t("冲正 / 退回", "Reversal / refund");
  const holderLabel = (value: string) => ({ USER: t("会员", "Member"), STORE: t("门店", "Store"), MINT: t("铸造账户", "Mint account"), ISSUE: t("发行账户", "Issuance account"), RECEIVE: t("接收账户", "Receiving account") }[value] ?? value);
  const bucketLabel = (value: string) => ({ AVAILABLE: t("可用积分", "Available points"), HELD: t("抵扣冻结", "Held for redemption"), PENDING: t("待入账积分", "Pending points"), REFUND_RESERVED: t("退款预留", "Reserved for refunds"), RECIRCULATABLE: t("可再流通积分", "Recirculatable points"), ISSUED: t("已发行积分", "Issued points"), ISSUABLE: t("可发行积分", "Issuable points") }[value] ?? value);
  const query = useQuery({ queryKey: ["points-transactions", typeCode, direction, from, to, storeNo, userNo, after], queryFn: () => api.pointsTransactions({ typeCode, direction, from, to, ...(storeNo ? { storeNo } : {}), ...(userNo ? { userNo } : {}), after, limit: 50 }), enabled: !!typeCode && validDates });
  const detail = useQuery({ queryKey: ["points-transaction", typeCode, selected?.voucher.voucherNo], queryFn: () => api.pointsTransaction(typeCode, selected!.voucher.voucherNo), enabled: !!selected && !!typeCode });
  const stats = useQuery({ queryKey: ["points-period-stats", typeCode, from, to], queryFn: () => api.pointsPeriodStats({ typeCode, from, to }), enabled: showStats && !!typeCode && validDates });
  const columns = [
    { header: t("积分名称", "Points name"), cell: (_r: PointsTransactionView) => types.data?.find(x => x.typeCode === typeCode)?.name ?? "—" },
    { header: t("积分代码", "Points code"), cell: (_r: PointsTransactionView) => typeCode },
    { header: t("时间", "Time"), cell: (r: PointsTransactionView) => time(r.voucher.createdAt) },
    { header: t("业务凭证", "Business voucher"), cell: (r: PointsTransactionView) => <b>{r.voucher.documentNo ?? "—"}</b> },
    { header: t("方向", "Direction"), cell: (r: PointsTransactionView) => directionLabel(r.direction) },
    { header: t("会员", "Member"), cell: (r: PointsTransactionView) => r.userNo ?? "—" },
    { header: t("门店", "Store"), cell: (r: PointsTransactionView) => r.storeNo ?? "—" },
    { header: t("会员净变化", "Member net change"), numeric: true, cell: (r: PointsTransactionView) => number(r.pointsDelta) },
  ];
  return <main className={`space-y-3 ${styles.page}`}>
    <div className="flex flex-wrap items-center justify-between gap-2"><SectionHeader title={t("发放与核销", "Issuance & redemption")} summary={query.data?.rows.length ?? 0} className="mb-0" /><div className="flex gap-2"><Button variant="ghost" aria-expanded={showStats} aria-controls="points-period-summary" onClick={() => setShowStats(!showStats)}>{t("统计概览", "Period summary")}</Button><Button variant="outline" disabled={!typeCode} onClick={() => setEventsOpen(true)}>{t("处理记录", "Processing records")}</Button></div></div>
    <form className={styles.filters} onSubmit={e => { e.preventDefault(); if (validDates) searchRecords(); }}>
      <label>{t("积分名称", "Points name")}<Select value={typeCode} onChange={e => { setChosenType(e.target.value); resetSelection(); setEventsOpen(false); }}>{types.data?.map(x => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode}</option>)}</Select></label>
      <label>{t("业务方向", "Direction")}<Select value={direction} onChange={e => { setDirection(e.target.value as PointsTransactionDirection); resetSelection(); }}><option value="EARN">{t("发放", "Issuance")}</option><option value="REDEEM">{t("核销", "Redemption")}</option><option value="REVERSAL">{t("冲正 / 退回", "Reversal / refund")}</option></Select></label>
      <label>{t("开始日期", "From")}<Input type="date" required value={from} onChange={e => { setFrom(e.target.value); resetSelection(); }} /></label>
      <label>{t("结束日期", "To")}<Input type="date" required min={from || undefined} value={to} onChange={e => { setTo(e.target.value); resetSelection(); }} /></label>
      <label>{t("门店编号", "Store number")}<Input type="text" dir="ltr" value={storeInput} onChange={e => setStoreInput(e.target.value)} /></label>
      <label>{t("会员编号", "Member number")}<Input type="text" dir="ltr" value={userInput} onChange={e => setUserInput(e.target.value)} /></label>
      <div className={styles.filterActions}><Button variant="outline" type="submit" disabled={!typeCode || !validDates}>{t("查询", "Search")}</Button><Button variant="ghost" type="button" onClick={() => { setFrom(monthStart()); setTo(today()); setDirection("EARN"); setStoreInput(""); setUserInput(""); setStoreNo(""); setUserNo(""); resetSelection(); }}>{t("重置", "Reset")}</Button></div>
    </form>
    {(!from || !to) && <Notice tone="warning">{t("请选择开始日期和结束日期。", "Select a start date and end date.")}</Notice>}
    {to < from && <Notice tone="warning">{t("结束日期不能早于开始日期。", "End date must not precede start date.")}</Notice>}
    {showStats && validDates && !!typeCode && stats.isPending && <p role="status" className="txt-caption text-muted-foreground">{t("正在加载统计…", "Loading summary…")}</p>}
    {showStats && validDates && stats.data && <section id="points-period-summary" className="rounded-card border border-border p-4"><div className="flex flex-wrap justify-between gap-2"><b>{t("当前积分类型的日期区间汇总（不按门店、会员或方向筛选）", "Date-range totals for this points type (not filtered by store, member or direction)")}</b><span className="txt-caption text-muted-foreground">{stats.data.from} — {stats.data.to} · {t("有效日", "available days")} {stats.data.availableDays} / {t("缺失日", "missing days")} {stats.data.missingDays}</span></div><div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">{Object.entries(stats.data.metrics).map(([key, value]) => <div key={key} className="rounded-field bg-muted/40 p-3"><p className="txt-caption text-muted-foreground">{key}</p><b className="tabular-nums">{value.toLocaleString()}</b></div>)}</div></section>}
    {(query.error || (showStats && stats.error) || types.error) && <Notice tone="warning">{String(query.error?.message ?? (showStats ? stats.error?.message : undefined) ?? types.error?.message)}<Button onClick={() => { void types.refetch(); if (validDates && typeCode) { void query.refetch(); if (showStats) void stats.refetch(); } }}>{t("重试", "Retry")}</Button></Notice>}
    <DataTable columns={columns} rows={validDates ? query.data?.rows : []} loading={types.isPending || (!!typeCode && validDates && query.isPending)} error={query.error} onRetry={() => { if (validDates && typeCode) void query.refetch(); }} rowKey={(r) => r.voucher.voucherNo} empty={t("当前条件下没有发放、核销或冲正记录。", "No issuance, redemption or reversal records match these filters.")} rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setSelected(r), onKeyDown: (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setSelected(r); } } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={() => { setAfter(0); setSelected(null); }}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!validDates || !query.data?.hasMore || query.isFetching} onClick={() => { setAfter(query.data?.next ?? 0); setSelected(null); }}>{t("下一页", "Next page")}</Button></div>
    <EarningEvents key={typeCode} typeCode={typeCode} typeName={types.data?.find(x => x.typeCode === typeCode)?.name} open={eventsOpen} onOpenChange={setEventsOpen} canRetry={can("finance:settle:execute")}/>
    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) setSelected(null); }} title={t("流水详情", "Transaction details")} desc={t("凭证、比例快照和不可变账务分录", "Voucher, conversion snapshot and immutable ledger entries")} width="w-[680px]" headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody}>
      {selected && <div className="space-y-4"><section className={styles.surface}><h3>{t("业务信息", "Transaction information")}</h3><dl className={styles.fields}>
          <DetailField label={t("积分名称", "Points name")}>{types.data?.find(x => x.typeCode === typeCode)?.name ?? "—"}</DetailField><DetailField label={t("积分代码", "Points code")}>{typeCode}</DetailField>
          <DetailField label={t("凭证编号", "Voucher number")}>{detail.data?.voucher.documentNo ?? selected.voucher.documentNo ?? "—"}</DetailField><DetailField label={t("业务引用", "Business reference")}>{selected.voucher.bizRef}</DetailField>
          <DetailField label={t("业务方向", "Direction")}>{directionLabel(selected.direction)}</DetailField><DetailField label={t("会员净变化", "Member net change")}>{number(selected.pointsDelta)}</DetailField>
          <DetailField label={t("门店编号", "Store number")}>{selected.storeNo ?? "—"}</DetailField><DetailField label={t("会员编号", "Member number")}>{selected.userNo ?? "—"}</DetailField>
          <DetailField label={t("比例积分数量", "Snapshot points units")}>{selected.voucher.ratePoints}</DetailField><DetailField label={t("比例货币数量（最小单位）", "Snapshot minor currency units")}>{selected.voucher.rateMinor}</DetailField>
          <DetailField label={t("币种", "Currency")}>{selected.voucher.currency}</DetailField><DetailField label={t("时间", "Time")}>{time(selected.voucher.createdAt)}</DetailField>
        </dl></section>
        {selected.direction === "EARN" && selected.voucher.bizRef.startsWith("GRANT:") && <OrderEarningDetails subOrderNo={selected.voucher.bizRef.slice(6)} />}
        {detail.error && <Notice tone="warning">{detail.error.message}<Button onClick={() => void detail.refetch()}>{t("重试", "Retry")}</Button></Notice>}
        <DataTable columns={[{ header: t("记账时间", "Posted at"), cell: (e) => time(e.at) }, { header: t("持有人类型", "Holder type"), cell: (e) => holderLabel(e.holderType) }, { header: t("持有人编号", "Holder number"), cell: (e) => e.holderNo }, { header: t("分录编号", "Entry number"), cell: (e) => e.entryNo }, { header: t("余额类型", "Balance type"), cell: (e) => bucketLabel(e.bucket) }, { header: t("积分变化", "Delta"), numeric: true, cell: (e) => `${e.deltaPoints > 0 ? "+" : ""}${number(e.deltaPoints)}` }, { header: t("记账后余额", "Balance after"), numeric: true, cell: (e) => number(e.afterPoints) }]} rows={detail.data?.entries.rows} loading={detail.isPending} error={detail.error} onRetry={() => void detail.refetch()} rowKey={(e) => e.entryNo} empty={t("该凭证没有账务分录。", "No ledger entries for this voucher.")} />
      </div>}
    </Drawer>
  </main>;
}

function DetailField({ label, children }: { label: string; children: ReactNode }) { return <div><dt>{label}</dt><dd>{children}</dd></div>; }
