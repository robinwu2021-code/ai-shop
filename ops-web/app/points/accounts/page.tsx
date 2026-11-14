"use client";
import { PointsAdjustments } from "../adjustments";
import { useCan } from "@/lib/use-can";
import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PointsAccountView, PointsHolder, PointsBucket } from "@/lib/types";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Badge } from "@/components/ui/badge";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import styles from "./page.module.css";

const buckets: PointsBucket[] = ["AVAILABLE", "HELD", "PENDING", "REFUND_RESERVED", "RECIRCULATABLE"];
const bucketNames: Record<PointsBucket, [string, string]> = {
  AVAILABLE: ["可用积分", "Available points"], HELD: ["抵扣冻结", "Held for redemption"], PENDING: ["待入账积分", "Pending points"],
  REFUND_RESERVED: ["退款预留", "Reserved for refunds"], RECIRCULATABLE: ["可再流通积分", "Recirculatable points"],
  ISSUED: ["已发行积分", "Issued points"], ISSUABLE: ["可发行积分", "Issuable points"],
};
const accountKey = (r: PointsAccountView) => `${r.snapshot.typeCode}:${r.snapshot.holderType}:${r.snapshot.holderNo}`;
export default function PointsAccountsPage() {
  const can = useCan(); const [adjustmentsOpen, setAdjustmentsOpen] = useState(false);
  const { t, locale } = usePointsText();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [chosenType, setChosenType] = useState("");
  const typeCode = chosenType || types.data?.[0]?.typeCode || "";
  const type = types.data?.find(x => x.typeCode === typeCode);
  const [holderType, setHolderType] = useState<PointsHolder>("USER");
  const [holderNo, setHolderNo] = useState(""); const [search, setSearch] = useState("");
  const [after, setAfter] = useState(0); const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [entryAfter, setEntryAfter] = useState(0); const [showHelp, setShowHelp] = useState(false);
  const query = useQuery({ queryKey: ["points-accounts", typeCode, holderType, search, after], queryFn: () => api.pointsAccounts({ typeCode, holderType, ...(search ? { holderNo: search } : {}), after, limit: 50 }), enabled: !!typeCode });
  const selected = query.data?.rows.find(r => accountKey(r) === selectedKey);
  const account = selected?.snapshot;
  const entries = useQuery({ queryKey: ["points-account-entries", typeCode, account?.holderType, account?.holderNo, entryAfter], queryFn: () => api.pointsEntries({ typeCode, holderType: account!.holderType, holderNo: account!.holderNo, after: entryAfter, limit: 50 }), enabled: !!account && !!selected?.exists });
  const number = (n: number) => n.toLocaleString(locale === "zh" ? "zh-CN" : locale);
  const bucketLabel = (b: PointsBucket) => bucketNames[b] ? t(...bucketNames[b]) : b;
  const holderLabel = (h: PointsHolder) => h === "USER" ? t("会员", "Member") : h === "STORE" ? t("门店", "Store") : h;
  const status = (r: PointsAccountView) => <Badge tone={!r.exists ? "muted" : r.snapshot.frozen ? "warning" : "success"}>{!r.exists ? t("未建账", "No account") : r.snapshot.frozen ? t("冻结", "Frozen") : t("正常", "Active")}</Badge>;
  function resetSelection() { setSelectedKey(null); setEntryAfter(0); setAfter(0); }
  function openAccount(row: PointsAccountView) { setSelectedKey(accountKey(row)); setEntryAfter(0); }
  function searchAccounts() { setSearch(holderNo.trim()); resetSelection(); }
  const columns = [
    { header: t("积分名称", "Points name"), cell: () => type?.name ?? "—" },
    { header: t("积分代码", "Points code"), cell: () => <span dir="ltr">{typeCode}</span> },
    { header: t("持有人编号", "Holder number"), cell: (r: PointsAccountView) => <span dir="ltr">{r.snapshot.holderNo}</span> },
    { header: t("账户类型", "Holder type"), cell: (r: PointsAccountView) => holderLabel(r.snapshot.holderType) },
    ...buckets.map(bucket => ({ header: bucketLabel(bucket), numeric: true, cell: (r: PointsAccountView) => r.exists ? number(r.snapshot.buckets[bucket] ?? 0) : "—" })),
    { header: t("状态", "Status"), cell: status },
  ];
  return <main className={`space-y-3 ${styles.page}`}>
    <div className="flex flex-wrap items-center justify-between gap-2"><TabHeader tabs={[{ key: "accounts", label: t("积分账户", "Points accounts") }]} value="accounts" onChange={() => {}} desc={t("查询积分账户余额与流水", "View points account balances and entries")} />{can("finance:settle:read") && <Button variant="outline" disabled={!typeCode} onClick={() => setAdjustmentsOpen(true)}>{t("调账记录", "Adjustment records")}</Button>}</div>
    <form className={styles.filters} onSubmit={e => { e.preventDefault(); searchAccounts(); }}>
      <label>{t("积分名称", "Points name")}<Select value={typeCode} onChange={e => { setChosenType(e.target.value); setAdjustmentsOpen(false); setHolderNo(""); setSearch(""); resetSelection(); }}>{types.data?.map(x => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode}</option>)}</Select></label>
      <label>{t("账户对象", "Account holder")}<Select value={holderType} onChange={e => { setHolderType(e.target.value as PointsHolder); setHolderNo(""); setSearch(""); resetSelection(); }}><option value="USER">{t("会员", "Member")}</option><option value="STORE">{t("门店", "Store")}</option></Select></label>
      <label>{holderType === "STORE" ? t("门店编号", "Store number") : t("会员编号", "Member number")}<Input type="text" dir="ltr" value={holderNo} onChange={e => setHolderNo(e.target.value)} placeholder={t("输入完整编号", "Enter exact number")} /></label>
      <Button variant="outline" type="submit" disabled={!typeCode}>{t("查询", "Search")}</Button>
      <Button variant="ghost" type="button" onClick={() => { setHolderNo(""); setSearch(""); resetSelection(); }}>{t("重置", "Reset")}</Button>
      <Button variant="ghost" type="button" className={styles.help} aria-expanded={showHelp} aria-controls="points-account-help" onClick={() => setShowHelp(!showHelp)}>{t("使用说明", "Help")}</Button>
    </form>
    {showHelp && <div id="points-account-help"><Notice>{t("余额单位为积分。未建账与余额为零是两种状态；人工调账通过调账记录申请并由另一人复核。编号支持精确查询。", "Balances are in points. A missing account differs from a zero balance. Adjustments require a request and independent review. Numbers use exact matching.")}</Notice></div>}
    {types.error && <Notice tone="warning">{types.error.message}<Button onClick={() => void types.refetch()}>{t("重试", "Retry")}</Button></Notice>}
    <DataTable columns={columns} rows={query.data?.rows} loading={types.isPending || (!!typeCode && query.isPending)} error={query.error} onRetry={() => void query.refetch()} rowKey={accountKey} empty={t("没有匹配的积分账户。", "No matching points accounts.")} rowProps={r => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary", onClick: () => openAccount(r), onKeyDown: e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openAccount(r); } } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={() => { setAfter(0); setSelectedKey(null); }}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!query.data?.hasMore || query.isFetching} onClick={() => { setAfter(query.data?.next ?? 0); setSelectedKey(null); }}>{t("下一页", "Next page")}</Button></div>
    <Drawer open={adjustmentsOpen && can("finance:settle:read")} onOpenChange={setAdjustmentsOpen} title={t("调账记录", "Adjustment records")} width="w-[900px]" headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody}>
      {adjustmentsOpen && can("finance:settle:read") && <PointsAdjustments key={typeCode} typeCode={typeCode} typeName={type?.name} canMake={can("finance:settle:execute")} canCheck={can("finance:payout:execute")} />}
    </Drawer>
    <Drawer open={!!selected} onOpenChange={open => { if (!open) setSelectedKey(null); }} title={t("账户详情", "Account details")} desc={t("余额与流水均以积分为单位。", "Balances and entries are in points.")} width="w-[680px]" headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody}>
      {account && selected && <div className="space-y-3">
        <section className={styles.surface}><h3>{t("账户信息", "Account information")}</h3><dl className={styles.fields}>
          <DetailField label={t("积分名称", "Points name")}>{type?.name ?? "—"}</DetailField><DetailField label={t("积分代码", "Points code")}><span dir="ltr">{typeCode}</span></DetailField>
          <DetailField label={t("账户类型", "Holder type")}>{holderLabel(account.holderType)}</DetailField><DetailField label={t("持有人编号", "Holder number")}><span dir="ltr">{account.holderNo}</span></DetailField>
          <DetailField label={t("账户存在", "Exists")}>{selected.exists ? t("是", "Yes") : t("否", "No")}</DetailField><DetailField label={t("状态", "Status")}>{status(selected)}</DetailField>
        </dl></section>
        <section className={styles.surface}><h3>{t("积分余额", "Points balances")}</h3><dl className={styles.fields}>{buckets.map(b => <DetailField key={b} label={bucketLabel(b)}>{selected.exists ? number(account.buckets[b] ?? 0) : "—"}</DetailField>)}</dl></section>
        <section className={styles.surface}><h3>{t("账户流水", "Account entries")}</h3><div className={styles.entries}>
          {!selected.exists ? <Notice>{t("账户尚未建账，暂无账务流水。", "This account has not been created and has no ledger entries.")}</Notice> : <>
            <DataTable columns={[
              { header: t("时间", "Time"), cell: e => e.at ? new Date(e.at).toLocaleString(locale === "zh" ? "zh-CN" : locale) : "—" },
              { header: t("凭证编号", "Voucher number"), cell: e => <span dir="ltr">{e.voucherDocumentNo ?? "—"}</span> },
              { header: t("分录编号", "Entry number"), cell: e => <span dir="ltr">{e.entryNo}</span> },
              { header: t("余额类型", "Balance type"), cell: e => bucketLabel(e.bucket) },
              { header: t("变动积分", "Points change"), numeric: true, cell: e => `${e.deltaPoints > 0 ? "+" : ""}${number(e.deltaPoints)}` },
              { header: t("变动后余额", "Balance after"), numeric: true, cell: e => number(e.afterPoints) },
            ]} rows={entries.data?.rows} loading={entries.isPending} error={entries.error} onRetry={() => void entries.refetch()} rowKey={e => e.entryNo} empty={t("暂无流水。", "No entries.")} />
            <div className="mt-3 flex justify-end gap-2"><Button variant="outline" disabled={!entryAfter} onClick={() => setEntryAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!entries.data?.hasMore || entries.isFetching} onClick={() => setEntryAfter(entries.data?.next ?? 0)}>{t("下一页", "Next page")}</Button></div>
          </>}
        </div></section>
      </div>}
    </Drawer>
  </main>;
}
function DetailField({ label, children }: { label: string; children: ReactNode }) { return <div><dt>{label}</dt><dd>{children}</dd></div>; }
