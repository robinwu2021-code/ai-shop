"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/use-can";
import { usePointsText } from "@/lib/points-i18n";
import type { PointsAccountsQ, PointsTransactionsQ, PointsPeriodQ, PointsTransactionDirection } from "@/lib/api/inspection-types";
import type { PointsHolder } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { DataTable } from "@/components/ui/data-table";

export function PointsInspectionLists({ typeCode }: { typeCode: string }) {
  const can = useCan();
  const { t } = usePointsText();
  if (!can("finance:settle:read")) return <Notice>{t("没有积分账务读取权限。", "Points ledger read permission required.")}</Notice>;
  return <InspectionLists key={typeCode} typeCode={typeCode} />;
}
function InspectionLists({ typeCode }: { typeCode: string }) {
  const { t } = usePointsText();
  const [accountsQ, setAccountsQ] = useState<PointsAccountsQ | null>(null);
  const [transactionsQ, setTransactionsQ] = useState<PointsTransactionsQ | null>(null);
  const [periodQ, setPeriodQ] = useState<PointsPeriodQ | null>(null);
  const [voucher, setVoucher] = useState("");
  const accounts = useQuery({ queryKey: ["points-accounts", accountsQ], queryFn: () => api.pointsAccounts(accountsQ!), enabled: !!accountsQ });
  const transactions = useQuery({ queryKey: ["points-transactions", transactionsQ], queryFn: () => api.pointsTransactions(transactionsQ!), enabled: !!transactionsQ });
  const detail = useQuery({ queryKey: ["points-transaction", typeCode, voucher], queryFn: () => api.pointsTransaction(typeCode, voucher), enabled: !!typeCode && !!voucher });
  const period = useQuery({ queryKey: ["points-period", periodQ], queryFn: () => api.pointsPeriodStats(periodQ!), enabled: !!periodQ });
  const card = "space-y-3 rounded-card border border-border p-4";
  const error = accounts.error || transactions.error || detail.error || period.error;
  return <>
    {error && <Notice tone="warning" className="flex flex-wrap items-center gap-3"><span className="min-w-0 break-words">{error.message}</span><Button onClick={() => { if (accountsQ) void accounts.refetch(); if (transactionsQ) void transactions.refetch(); if (voucher) void detail.refetch(); if (periodQ) void period.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
    {(accounts.isFetching || transactions.isFetching || detail.isFetching || period.isFetching) && <p>{t("加载中…", "Loading…")}</p>}
    <section className={card}><h3 className="txt-heading">{t("账户目录", "Account directory")}</h3>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setAccountsQ({ typeCode, holderType: String(d.get("holderType")) as PointsHolder, holderNo: String(d.get("holderNo")).trim() || undefined, after: 0, limit: 50 }); }}>
        <label>{t("账户类型", "Holder type")}<Select name="holderType">{["STORE", "USER", "ISSUE", "RECEIVE", "MINT"].map((h) => <option key={h}>{h}</option>)}</Select></label>
        <label>{t("对象编号（可选）", "Holder ID (optional)")}<Input name="holderNo" /></label><Button disabled={!typeCode}>{t("查询账户", "Search accounts")}</Button>
      </form>
      {accountsQ && <><DataTable error={accounts.error} onRetry={() => void accounts.refetch()} loading={accounts.isFetching} rows={accounts.data?.rows ?? []} rowKey={(r) => `${r.snapshot.holderType}/${r.snapshot.holderNo}`} columns={[
        { header: t("账户", "Account"), cell: (r) => `${r.snapshot.holderType} / ${r.snapshot.holderNo}` },
        { header: t("冻结 / 版本", "Frozen / version"), cell: (r) => `${r.snapshot.frozen} / ${r.version}` },
        { header: t("余额分格", "Balance buckets"), cell: (r) => Object.entries(r.snapshot.buckets).map(([k, v]) => `${k}: ${v}`).join(" · ") },
      ]} empty={t("无匹配账户。", "No matching accounts.")} /><div className="flex flex-wrap items-center gap-3"><Button disabled={!accountsQ?.after} onClick={() => setAccountsQ({ ...accountsQ!, after: 0 })}>{t("首页", "First page")}</Button><Button disabled={!accounts.data?.hasMore || accounts.isFetching} onClick={() => setAccountsQ({ ...accountsQ!, after: accounts.data!.next })}>{t("下一页", "Next page")}</Button></div></>}
    </section>
    <section className={card}><h3 className="txt-heading">{t("业务交易与期间统计", "Business transactions and period statistics")}</h3>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); const q = { typeCode, from: String(d.get("from")), to: String(d.get("to")) }; setPeriodQ(q); setTransactionsQ({ ...q, direction: String(d.get("direction")) as PointsTransactionDirection, storeNo: String(d.get("storeNo")).trim() || undefined, userNo: String(d.get("userNo")).trim() || undefined, after: 0, limit: 50 }); setVoucher(""); }}>
        <label>{t("开始日期", "From date")}<Input name="from" type="date" required /></label><label>{t("结束日期", "To date")}<Input name="to" type="date" required /></label>
        <label>{t("方向", "Direction")}<Select name="direction">{["EARN", "REDEEM", "REVERSAL"].map((d) => <option key={d}>{d}</option>)}</Select></label>
        <label>{t("门店编号（可选）", "Store ID (optional)")}<Input name="storeNo" /></label><label>{t("用户编号（可选）", "User ID (optional)")}<Input name="userNo" /></label><Button disabled={!typeCode}>{t("查询交易与统计", "Search transactions and statistics")}</Button>
      </form>
      <p className="txt-caption">{t("期间统计按积分类型汇总全部范围，不受门店、用户或交易方向筛选影响。", "Period statistics cover the entire points type, independent of store, user and transaction direction filters.")}</p>
      {period.data && <><Notice tone={period.data.missingDays ? "warning" : "muted"}>{t("已有 / 缺失快照天数", "Available / missing snapshot days")}: {period.data.availableDays} / {period.data.missingDays} · {t("缺失快照不代表零业务量。", "Missing snapshots do not imply zero activity.")}</Notice><dl>{Object.entries(period.data.metrics).map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl></>}
      {transactionsQ && <><DataTable error={transactions.error} onRetry={() => void transactions.refetch()} loading={transactions.isFetching} rows={transactions.data?.rows ?? []} rowKey={(r) => r.voucher.voucherNo} empty={t("无匹配交易。", "No matching transactions.")} columns={[
        { header: t("方向", "Direction"), cell: (r) => r.direction }, { header: t("凭证", "Voucher"), cell: (r) => <Button onClick={() => setVoucher(r.voucher.voucherNo)}>{r.voucher.voucherNo}</Button> }, { header: t("业务引用", "Business reference"), cell: (r) => r.voucher.bizRef },
      ]} /><div className="flex flex-wrap items-center gap-3"><Button disabled={!transactionsQ?.after} onClick={() => setTransactionsQ({ ...transactionsQ!, after: 0 })}>{t("首页", "First page")}</Button><Button disabled={!transactions.data?.hasMore || transactions.isFetching} onClick={() => setTransactionsQ({ ...transactionsQ!, after: transactions.data!.next })}>{t("下一页", "Next page")}</Button></div></>}
      {detail.data && <div><h4>{detail.data.voucher.voucherNo} · {detail.data.direction}</h4><p>{detail.data.voucher.bizRef} · {detail.data.voucher.currency} · {detail.data.voucher.ratePoints}:{detail.data.voucher.rateMinor}</p><DataTable error={detail.error} onRetry={() => void detail.refetch()} loading={detail.isFetching} rows={detail.data.entries.rows} rowKey={(r) => r.entryNo} columns={[
        { header: t("账户", "Account"), cell: (r) => `${r.holderType}/${r.holderNo}` }, { header: t("分格", "Bucket"), cell: (r) => r.bucket }, { header: t("增减 / 变后", "Delta / after"), cell: (r) => `${r.deltaPoints} / ${r.afterPoints}` },
      ]} empty={t("无凭证分录。", "No voucher entries.")} />{detail.data.entries.hasMore && <Notice tone="warning">{t("明细尚未完整；请在下方凭证流水查询中继续核对。", "Detail is incomplete; continue in the voucher entry search below.")}</Notice>}</div>}
    </section>
  </>;
}
