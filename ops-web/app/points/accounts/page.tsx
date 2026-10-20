"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PointsAccountView, PointsHolder, PointsBucket } from "@/lib/types";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";
import { TabHeader } from "@/components/ui/tab-header";

const buckets: PointsBucket[] = ["PENDING", "AVAILABLE", "HELD", "REFUND_RESERVED", "RECIRCULATABLE"];
export default function PointsAccountsPage() {
  const { t } = usePointsText(); const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [chosenType, setChosenType] = useState(""); const typeCode = chosenType || types.data?.[0]?.typeCode || "";
  const [holderType, setHolderType] = useState<PointsHolder>("USER"); const [holderNo, setHolderNo] = useState(""); const [search, setSearch] = useState(""); const [after, setAfter] = useState(0);
  const [selected, setSelected] = useState<PointsAccountView | null>(null); const [entryAfter, setEntryAfter] = useState(0);
  const query = useQuery({ queryKey: ["points-accounts", typeCode, holderType, search, after], queryFn: () => api.pointsAccounts({ typeCode, holderType, ...(search ? { holderNo: search } : {}), after, limit: 50 }), enabled: !!typeCode });
  const account = selected?.snapshot;
  const entries = useQuery({ queryKey: ["points-account-entries", typeCode, account?.holderType, account?.holderNo, entryAfter], queryFn: () => api.pointsEntries({ typeCode, holderType: account!.holderType, holderNo: account!.holderNo, after: entryAfter, limit: 50 }), enabled: !!account });
  const columns = [
    { header: t("账户编号", "Account"), cell: (r: PointsAccountView) => <b>{r.snapshot.holderNo}</b> },
    { header: t("账户类型", "Holder"), cell: (r: PointsAccountView) => r.snapshot.holderType },
    ...buckets.map((bucket) => ({ header: t(bucket, bucket), numeric: true, cell: (r: PointsAccountView) => (r.snapshot.buckets[bucket] ?? 0).toLocaleString() })),
    { header: t("状态", "State"), cell: (r: PointsAccountView) => !r.exists ? t("未建账", "No account") : r.snapshot.frozen ? t("冻结", "Frozen") : t("正常", "Active") },
  ];
  return <main className="space-y-4">
    <TabHeader tabs={[{ key: "accounts", label: t("积分账户", "Points accounts") }]} value="accounts" onChange={() => {}} desc={t("按门店或会员查看积分余额与账务分录", "Review points balances and ledger entries by store or member")} />
    <div className="flex flex-wrap items-end justify-between gap-3"><SectionHeader title={t("账户清单", "Account directory")} summary={query.data?.rows.length ?? 0} className="mb-0" /><div className="flex flex-wrap items-end gap-3"><label>{t("积分类型", "Points type")}<Select value={typeCode} onChange={(e) => { setChosenType(e.target.value); setAfter(0); setSelected(null); }}>{types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode} · {x.currency}</option>)}</Select></label><label>{t("账户对象", "Account holder")}<Select value={holderType} onChange={(e) => { setHolderType(e.target.value as PointsHolder); setAfter(0); setSearch(""); }}><option value="USER">{t("会员", "Member")}</option><option value="STORE">{t("门店", "Store")}</option></Select></label><label>{t("编号", "Number")}<Input value={holderNo} onChange={(e) => setHolderNo(e.target.value)} onKeyDown={(e) => { if (e.key === "Enter") { setSearch(holderNo.trim()); setAfter(0); } }} /></label><Button variant="outline" onClick={() => { setSearch(holderNo.trim()); setAfter(0); }}>{t("查询", "Search")}</Button></div></div>
    <Notice>{t("余额来自不可变积分账本。未建账与余额为零是两种状态；本页只读，不提供人工改余额。", "Balances come from the immutable points ledger. A missing account differs from a zero balance; this page is read-only.")}</Notice>
    {types.error && <Notice tone="warning">{types.error.message}</Notice>}
    <DataTable columns={columns} rows={query.data?.rows} loading={query.isPending} error={query.error} onRetry={() => void query.refetch()} rowKey={(r) => `${r.snapshot.holderType}:${r.snapshot.holderNo}`} empty={t("没有匹配的积分账户。", "No matching points accounts.")} rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => { setSelected(r); setEntryAfter(0); }, onKeyDown: (e) => { if (e.key === "Enter") setSelected(r); } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={() => setAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!query.data?.hasMore || query.isFetching} onClick={() => setAfter(query.data?.next ?? 0)}>{t("下一页", "Next page")}</Button></div>
    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) setSelected(null); }} title={account?.holderNo ?? ""} desc={t("账户余额与最近账务分录", "Account balances and ledger entries")} width="w-[720px]">
      {account && <div className="space-y-5"><div className="grid grid-cols-2 gap-3 rounded-card border border-border p-4 sm:grid-cols-3"><div><p className="txt-caption">{t("持有人", "Holder")}</p><b>{account.holderType}</b></div><div><p className="txt-caption">{t("账户存在", "Exists")}</p><b>{selected?.exists ? t("是", "Yes") : t("否", "No")}</b></div><div><p className="txt-caption">{t("账户状态", "Status")}</p><b>{account.frozen ? t("冻结", "Frozen") : t("正常", "Active")}</b></div>{buckets.map((b) => <div key={b}><p className="txt-caption">{t(b, b)}</p><b className="tabular-nums">{(account.buckets[b] ?? 0).toLocaleString()}</b></div>)}</div>
      <h3 className="txt-heading">{t("账务分录", "Ledger entries")}</h3>{entries.error && <Notice tone="warning">{entries.error.message}<Button onClick={() => void entries.refetch()}>{t("重试", "Retry")}</Button></Notice>}
      <DataTable columns={[
        { header: t("时间", "Time"), cell: (e) => e.at ? new Date(e.at).toLocaleString() : "—" },
        { header: t("凭证 / 业务", "Voucher / business"), cell: (e) => <div><b>{e.voucherDocumentNo ?? "—"}</b><p className="txt-caption">{e.entryNo}</p></div> },
        { header: t("分桶", "Bucket"), cell: (e) => e.bucket },
        { header: t("变化", "Delta"), numeric: true, cell: (e) => e.deltaPoints.toLocaleString() },
        { header: t("余额", "After"), numeric: true, cell: (e) => e.afterPoints.toLocaleString() },
      ]} rows={entries.data?.rows} loading={entries.isPending} error={entries.error} onRetry={() => void entries.refetch()} rowKey={(e) => e.entryNo} empty={t("暂无分录。", "No entries.")} />
      <div className="flex justify-end gap-2"><Button variant="outline" disabled={!entryAfter} onClick={() => setEntryAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!entries.data?.hasMore || entries.isFetching} onClick={() => setEntryAfter(entries.data?.next ?? 0)}>{t("下一页", "Next page")}</Button></div></div>}
    </Drawer>
  </main>;
}
