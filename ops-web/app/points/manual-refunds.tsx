"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export function ManualPointsRefunds({ storeNo, canEdit }: { storeNo: string; canEdit: boolean }) {
  const { t } = usePointsText();
  const [after, setAfter] = useState("");
  const [result, setResult] = useState<{ afterSaleNo: string; status: string } | null>(null);
  const query = useQuery({ queryKey: ["points-manual-refunds", storeNo, after],
    queryFn: () => api.manualOrderPointsRefunds(storeNo, after, 20), enabled: !!storeNo });
  return <div className="space-y-3 rounded-card border border-border p-4">
    <h3 className="txt-heading">{t("积分退款人工待处理", "Points refunds awaiting operator")}</h3>
    <p dir="auto">{t("只显示当前门店补收已回退、现金退款失败的售后。关闭保留原单；重试会关闭原单并创建新的退款尝试。", "Shows this store's compensated refunds after a failed cash refund. Close keeps the original case; retry closes it and creates a new attempt.")}</p>
    {!storeNo && <p>{t("先查询门店接线策略。", "Select a store above first.")}</p>}
    {query.isFetching && <p>{t("加载中…", "Loading…")}</p>}
    {query.error && <p role="alert" className="text-destructive">{query.error.message}</p>}
    {result && <p dir="auto">{t("处理结果", "Result")}: {result.afterSaleNo} · {result.status}</p>}
    {query.data?.rows.map((row) => <ManualRefundRow key={row.afterSaleNo} row={row} storeNo={storeNo} canEdit={canEdit} onResult={setResult} />)}
    {query.data?.rows.length === 0 && <p>{t("当前门店没有待人工处理的积分退款。", "No points refunds require operator action for this store.")}</p>}
    {query.data && <div className="flex gap-2">
      <Button disabled={!after} onClick={() => setAfter("")}>{t("首页", "First page")}</Button>
      <Button disabled={!query.data.hasMore || query.isFetching || !query.data.next} onClick={() => setAfter(query.data!.next!)}>{t("下一页", "Next page")}</Button>
      <Button onClick={() => void query.refetch()}>{t("刷新", "Refresh")}</Button>
    </div>}
  </div>;
}

function ManualRefundRow({ row, storeNo, canEdit, onResult }: {
  row: { afterSaleNo: string; subOrderNo: string; createdAt: number }; storeNo: string; canEdit: boolean;
  onResult: (result: { afterSaleNo: string; status: string }) => void;
}) {
  const { t } = usePointsText();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const action = useMutation({ mutationFn: (kind: "close" | "retry") =>
    kind === "close" ? api.closeOrderPointsRefund(row.afterSaleNo, reason.trim())
      : api.retryOrderPointsRefund(row.afterSaleNo, reason.trim()),
    onSuccess: (result) => { onResult(result); void qc.invalidateQueries({ queryKey: ["points-manual-refunds", storeNo] }); } });
  return <div className="space-y-2 border-t border-border py-3">
    <p dir="auto">{t("售后", "After-sale")}: {row.afterSaleNo} · {t("子单", "Sub-order")}: {row.subOrderNo}</p>
    <p dir="auto">{new Date(row.createdAt).toLocaleString()}</p>
    {canEdit && <div className="flex flex-wrap items-end gap-2">
      <label>{t("处理原因", "Reason")}<Input dir="auto" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500} required /></label>
      <Button disabled={!reason.trim() || action.isPending} onClick={() => action.mutate("close")}>{t("关闭原售后", "Close original")}</Button>
      <Button disabled={!reason.trim() || action.isPending} onClick={() => action.mutate("retry")}>{t("新建退款重试", "Retry as new case")}</Button>
    </div>}
    {action.error && <p role="alert" className="text-destructive">{action.error.message}</p>}
    {action.data && <p dir="auto">{t("处理结果", "Result")}: {action.data.afterSaleNo} · {action.data.status}</p>}
  </div>;
}
