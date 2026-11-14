"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PointsAdjustmentSubmission, PointsAdjustmentReview, PointsAdjustmentView } from "@/lib/api/points-adjustment-types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import styles from "./accounts/page.module.css";

type Command = { kind: "SUBMIT"; value: PointsAdjustmentSubmission } | { kind: "REVIEW"; value: PointsAdjustmentReview };
export function PointsAdjustments({ typeCode, typeName, canMake, canCheck }: { typeCode: string; typeName?: string; canMake: boolean; canCheck: boolean }) {
  const { t } = usePointsText(); const qc = useQueryClient();
  const [after, setAfter] = useState(0);
  const [creating, setCreating] = useState(false); const [selectedNo, setSelectedNo] = useState<string | null>(null);
  const [reason, setReason] = useState(""); const [validation, setValidation] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const page = useQuery({ queryKey: ["points-adjustments", typeCode, after], queryFn: () => api.pointsAdjustments({ typeCode, after, limit: 30 }), enabled: !!typeCode });
  const selected = page.data?.rows.find(r => r.adjustmentNo === selectedNo);
  const intents = useRef(new Map<string, { body: string; requestId: string }>());
  const mutation = useMutation({ mutationFn: (command: Command) => command.kind === "SUBMIT" ? api.submitPointsAdjustment(command.value) : api.reviewPointsAdjustment(command.value),
    onSuccess: (_result, command) => {
      intents.current.delete(JSON.stringify({ ...command, value: { ...command.value, requestId: "" } })); setCreating(false); setSelectedNo(null); setReason(""); setSubmitted(true);
      for (const key of ["points-adjustments", "points-accounts", "points-account-entries", "points-transactions", "points-overview"]) void qc.invalidateQueries({ queryKey: [key] });
    } });
  function send(command: Command) {
    if (IS_MOCK || mutation.isPending || (command.kind === "SUBMIT" ? !canMake : !canCheck)) return;
    const body = JSON.stringify(command);
    const next = pointsCommand(command, intents.current.get(body)); intents.current.set(body, next.intent);
    if (command.kind === "SUBMIT") mutation.mutate({ kind: "SUBMIT", value: { ...command.value, requestId: next.command.requestId } });
    else mutation.mutate({ kind: "REVIEW", value: { ...command.value, requestId: next.command.requestId } });
  }
  function open(row?: PointsAdjustmentView) { mutation.reset(); setReason(""); setValidation(""); setSubmitted(false); setCreating(!row); setSelectedNo(row?.adjustmentNo ?? null); }
  const stateLabel = (state: string) => ({ SUBMITTED: t("待复核", "Awaiting review"), APPROVED: t("已批准", "Approved"), REJECTED: t("已驳回", "Rejected") }[state] ?? state);
  const directionLabel = (direction: string) => direction === "INCREASE" ? t("调增", "Increase") : t("调减", "Decrease");
  const blocked = mutation.isPending || IS_MOCK;
  return <div className="space-y-3">
    <div className="flex flex-wrap items-center justify-between gap-2"><span>{typeName ?? "—"} · {typeCode}</span><div className="flex gap-2"><Button variant="outline" disabled={page.isFetching} onClick={() => void page.refetch()}>{t("刷新", "Refresh")}</Button>{canMake && <Button disabled={!typeCode || IS_MOCK} onClick={() => open()}>{t("申请调账", "New adjustment")}</Button>}</div></div>
    <Notice>{t("调增消耗平台预算；调减仅扣可用积分并释放回平台预算。申请与复核必须由不同人员完成。", "Increases consume platform budget; decreases release available points to the budget. A different operator must review each request.")}</Notice>
    {submitted && <Notice>{t("操作已受理，记录和账户余额已重新查询。", "Operation accepted; records and balances are being refreshed.")}</Notice>}
    <DataTable columns={[
      { header: t("申请号", "Request number"), cell: (r: PointsAdjustmentView) => r.adjustmentNo },
      { header: t("会员编号", "Member number"), cell: (r: PointsAdjustmentView) => r.userNo },
      { header: t("方向", "Direction"), cell: (r: PointsAdjustmentView) => directionLabel(r.direction) },
      { header: t("积分数量", "Points"), numeric: true, cell: (r: PointsAdjustmentView) => r.points.toLocaleString() },
      { header: t("状态", "Status"), cell: (r: PointsAdjustmentView) => stateLabel(r.state) },
      { header: t("申请人", "Submitted by"), cell: (r: PointsAdjustmentView) => r.submittedBy },
    ]} rows={page.data?.rows} loading={page.isPending} error={page.error} onRetry={() => void page.refetch()} rowKey={r => r.adjustmentNo} empty={t("暂无调账记录", "No adjustment requests")} rowProps={r => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => open(r), onKeyDown: e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); open(r); } } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after || page.isFetching} onClick={() => setAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!page.data?.hasMore || page.isFetching} onClick={() => setAfter(page.data!.next)}>{t("下一页", "Next page")}</Button></div>
    <Drawer open={creating || !!selected} onOpenChange={o => { if (!o && !mutation.isPending) { setCreating(false); setSelectedNo(null); } }} title={creating ? t("申请调账", "New adjustment") : t("调账详情", "Adjustment details")} width="w-[560px]" headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody}>
      {creating ? <form className="space-y-4" onSubmit={e => {
        e.preventDefault(); const d = new FormData(e.currentTarget);
        const adjustmentNo = String(d.get("no") ?? "").trim(), userNo = String(d.get("user") ?? "").trim(), points = Number(d.get("points"));
        if (!adjustmentNo || !userNo || !reason.trim() || !Number.isSafeInteger(points) || points <= 0) { setValidation(t("请填写完整编号、正整数积分及非空原因。", "Enter complete identifiers, a positive integer amount and a reason.")); return; }
        setValidation(""); send({ kind: "SUBMIT", value: { requestId: "", adjustmentNo, typeCode, userNo, direction: d.get("direction") as "INCREASE" | "DECREASE", points, reason: reason.trim() } });
      }}>
        <fieldset disabled={blocked} className="grid gap-4 sm:grid-cols-2">
          <label className="space-y-1 block">{t("申请号（重试保留原号）", "Request number (retain for retry)")}<Input name="no" required maxLength={40} /></label>
          <label className="space-y-1 block">{t("会员编号", "Member number")}<Input name="user" required maxLength={40} /></label>
          <label className="space-y-1 block">{t("方向", "Direction")}<Select name="direction"><option value="INCREASE">{t("调增", "Increase")}</option><option value="DECREASE">{t("调减", "Decrease")}</option></Select></label>
          <label className="space-y-1 block">{t("积分数量", "Points")}<Input name="points" type="number" min={1} max={Number.MAX_SAFE_INTEGER} step={1} required /></label>
          <label className="space-y-1 block sm:col-span-2">{t("操作原因", "Reason")}<Input value={reason} required maxLength={1000} onChange={e => setReason(e.target.value)} /></label>
        </fieldset>
        <Button type="submit" disabled={blocked || !reason.trim()}>{t("提交申请", "Submit request")}</Button>
      </form> : selected && <div className="space-y-4">
        <section className={styles.surface}><h3>{t("申请信息", "Request information")}</h3><dl className={styles.fields}>{[
          [t("申请号", "Request number"), selected.adjustmentNo], [t("会员编号", "Member number"), selected.userNo],
          [t("方向", "Direction"), directionLabel(selected.direction)], [t("积分数量", "Points"), selected.points.toLocaleString()],
          [t("状态", "Status"), stateLabel(selected.state)], [t("版本", "Version"), selected.version],
          [t("申请人", "Submitted by"), selected.submittedBy], [t("复核人", "Reviewed by"), selected.approvedBy ?? "—"],
          [t("申请时间", "Submitted at"), selected.submittedAt ? new Date(selected.submittedAt).toLocaleString() : "—"],
          [t("复核时间", "Reviewed at"), selected.reviewedAt ? new Date(selected.reviewedAt).toLocaleString() : "—"],
          [t("申请原因", "Submission reason"), selected.reason],
        ].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></section>
        {canCheck && selected.state === "SUBMITTED" && <div className="space-y-3"><label className="space-y-1 block">{t("复核原因", "Review reason")}<Input value={reason} maxLength={1000} disabled={blocked} onChange={e => setReason(e.target.value)} /></label><div className="flex gap-2">{[true, false].map(approve => <Button key={String(approve)} variant={approve ? "default" : "outline"} disabled={blocked || !reason.trim()} onClick={() => send({ kind: "REVIEW", value: { requestId: "", adjustmentNo: selected.adjustmentNo, typeCode: selected.typeCode, expectedVersion: selected.version, approve, reason: reason.trim() } })}>{approve ? t("批准", "Approve") : t("驳回", "Reject")}</Button>)}</div></div>}
      </div>}
      {(mutation.error || validation) && <Notice tone="warning">{mutation.error?.message || validation}</Notice>}
    </Drawer>
  </div>;
}
