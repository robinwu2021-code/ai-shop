"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { useConfirm } from "@/components/ui/confirm-dialog";
import styles from "./accounts/page.module.css";

type RefundRow = { afterSaleNo: string; subOrderNo: string; createdAt: number };

/** Store-scoped queue: the endpoint does not filter by the transaction page's points type. */
export function ManualPointsRefundWorkspace({ canEdit, onBusyChange }: { canEdit: boolean; onBusyChange?: (busy: boolean) => void }) {
  const { t } = usePointsText();
  const [input, setInput] = useState(""); const [storeNo, setStoreNo] = useState("");
  const [busy, setBusy] = useState(false);
  return <div className="space-y-3">
    <Notice>{t("按门店查询积分关联退款，不受外层积分类型和流水筛选条件影响。", "Queries points-related refunds by store, independently of transaction filters and points type.")}</Notice>
    <form className="flex flex-wrap items-end gap-2" onSubmit={e => { e.preventDefault(); if (!busy) setStoreNo(input.trim()); }}>
      <label className="space-y-1 block">{t("门店编号", "Store number")}<Input required maxLength={64} value={input} disabled={busy} onChange={e => setInput(e.target.value)} placeholder={t("输入完整门店编号", "Enter exact store number")} /></label>
      <Button type="submit" variant="outline" disabled={busy || !input.trim()}>{t("查询", "Search")}</Button>
    </form>
    <ManualPointsRefunds key={storeNo} storeNo={storeNo} canEdit={canEdit} onBusyChange={value => { setBusy(value); onBusyChange?.(value); }} />
  </div>;
}

export function ManualPointsRefunds({ storeNo, canEdit, onBusyChange }: { storeNo: string; canEdit: boolean; onBusyChange?: (busy: boolean) => void }) {
  const { t } = usePointsText();
  const [after, setAfter] = useState("");
  const [selected, setSelected] = useState<RefundRow | null>(null);
  const [result, setResult] = useState<{ afterSaleNo: string; status: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const query = useQuery({ queryKey: ["points-manual-refunds", storeNo, after], queryFn: () => api.manualOrderPointsRefunds(storeNo, after, 20), enabled: !!storeNo });
  function changeBusy(value: boolean) { setBusy(value); onBusyChange?.(value); }
  return <div className="space-y-3">
    <p className="txt-caption text-muted-foreground">{t("只显示现金退款明确失败、积分准备已安全中止的售后。关闭保留原单；重试会关闭原单并创建新的退款尝试。", "Only failed cash refunds after safely aborted points preparation are shown. Close retains the original case; retry closes it and creates a new attempt.")}</p>
    {!storeNo ? <Notice>{t("请先输入门店编号查询待处理退款。", "Enter a store number to find pending refunds.")}</Notice> : <>
      <p>{t("当前门店", "Current store")}: {storeNo}</p>
      {result && <Notice>{t("处理结果", "Result")}: {result.afterSaleNo} · {result.status}</Notice>}
      <DataTable columns={[
        { header: t("售后编号", "After-sale number"), cell: (r: RefundRow) => r.afterSaleNo },
        { header: t("关联子单号", "Sub-order number"), cell: (r: RefundRow) => r.subOrderNo },
        { header: t("创建时间", "Created at"), cell: (r: RefundRow) => new Date(r.createdAt).toLocaleString() },
      ]} rows={query.data?.rows} loading={query.isPending} error={query.error} onRetry={() => void query.refetch()} rowKey={r => r.afterSaleNo} empty={t("当前门店没有待人工处理的积分退款。", "No points refunds require operator action for this store.")} rowProps={r => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setSelected(r), onKeyDown: e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); setSelected(r); } } })} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" disabled={!after || query.isFetching || busy} onClick={() => setAfter("")}>{t("首页", "First page")}</Button>
        <Button variant="outline" disabled={!query.data?.hasMore || query.isFetching || !query.data.next || busy} onClick={() => setAfter(query.data!.next!)}>{t("下一页", "Next page")}</Button>
        <Button variant="outline" disabled={query.isFetching || busy} onClick={() => void query.refetch()}>{t("刷新", "Refresh")}</Button>
      </div>
    </>}
    <Drawer open={!!selected} onOpenChange={open => { if (!open && !busy) setSelected(null); }} title={t("退款人工处理", "Manual refund handling")} width="w-[560px]" headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody}>
      {selected && <ManualRefundDetails key={selected.afterSaleNo} row={selected} storeNo={storeNo} canEdit={canEdit} onBusyChange={changeBusy} onResult={value => { setResult(value); setSelected(null); }} />}
    </Drawer>
  </div>;
}

function ManualRefundDetails({ row, storeNo, canEdit, onResult, onBusyChange }: {
  row: RefundRow; storeNo: string; canEdit: boolean; onBusyChange: (value: boolean) => void;
  onResult: (result: { afterSaleNo: string; status: string }) => void;
}) {
  const { t } = usePointsText(); const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [reason, setReason] = useState(""); const [confirming, setConfirming] = useState(false);
  const action = useMutation({ mutationFn: (kind: "close" | "retry") => kind === "close" ? api.closeOrderPointsRefund(row.afterSaleNo, reason.trim()) : api.retryOrderPointsRefund(row.afterSaleNo, reason.trim()) });
  async function process(kind: "close" | "retry") {
    if (!canEdit || IS_MOCK || confirming || !reason.trim()) return;
    setConfirming(true); onBusyChange(true);
    let completed: { afterSaleNo: string; status: string } | undefined;
    try {
      const ok = await confirm({ title: kind === "close" ? t("确认关闭原售后？", "Close the original case?") : t("确认新建退款重试？", "Create a new refund attempt?"),
        desc: `${row.afterSaleNo} · ${kind === "close" ? t("保留原单，不会创建新的退款。", "Retains the original case without a new refund.") : t("原单将关闭，并创建新的退款尝试，请勿重复处理。", "Closes the original case and creates a new refund attempt. Do not duplicate this action.")}`,
        danger: true, action: async () => { completed = await action.mutateAsync(kind); } });
      if (ok) {
        void qc.invalidateQueries({ queryKey: ["points-manual-refunds", storeNo] });
        void qc.invalidateQueries({ queryKey: ["points-transactions"] });
        if (completed) onResult(completed);
      }
    } finally { setConfirming(false); onBusyChange(false); }
  }
  return <div className="space-y-4">
    <section className={styles.surface}><h3>{t("售后信息", "After-sale information")}</h3><dl className={styles.fields}>
      <div><dt>{t("门店编号", "Store number")}</dt><dd>{storeNo}</dd></div><div><dt>{t("售后编号", "After-sale number")}</dt><dd>{row.afterSaleNo}</dd></div>
      <div><dt>{t("关联子单号", "Sub-order number")}</dt><dd>{row.subOrderNo}</dd></div><div><dt>{t("创建时间", "Created at")}</dt><dd>{new Date(row.createdAt).toLocaleString()}</dd></div>
    </dl></section>
    {canEdit && <div className="space-y-3"><label className="space-y-1 block">{t("处理原因", "Reason")}<Input value={reason} onChange={e => setReason(e.target.value)} maxLength={500} disabled={confirming} required /></label>
      <div className="flex gap-2"><Button variant="outline" disabled={!reason.trim() || confirming || IS_MOCK} onClick={() => void process("close")}>{t("关闭原售后", "Close original")}</Button><Button disabled={!reason.trim() || confirming || IS_MOCK} onClick={() => void process("retry")}>{t("新建退款重试", "Retry as new case")}</Button></div>
    </div>}
    {action.error && <Notice tone="warning">{action.error.message}</Notice>}
    {dialog}
  </div>;
}
