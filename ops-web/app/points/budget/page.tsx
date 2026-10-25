"use client";
import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PointsRequestView, PointsSubmission, PointsRequestReview, PointsOperation } from "@/lib/types";
import { pointsCommand } from "@/lib/points-command";
import { useCan } from "@/lib/use-can";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { TabHeader } from "@/components/ui/tab-header";
import { pointsCurrency } from "@/lib/points-currency";
import { PointsSettlementsTab } from "../settlements-tab";
import styles from "./page.module.css";

export default function PointsBudgetPage() {
  const { t, locale } = usePointsText(); const can = useCan(); const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [chosenType, setChosenType] = useState(""); const code = chosenType || types.data?.[0]?.typeCode || "";
  const type = types.data?.find((x) => x.typeCode === code); const money = (n: number) => type ? new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en", { style: "currency", currency: type.currency }).format(n / 10 ** type.scale) : String(n);
  const [amountDraft, setAmountDraft] = useState("");
  const [formError, setFormError] = useState("");
  const [settlementBusy, setSettlementBusy] = useState(false);
  const [settlementsOpen, setSettlementsOpen] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  const currency = pointsCurrency(type?.currency ?? "");
  const unit = currency?.units[0];
  const actionLabel = (value: string) => ({ TOPUP_U: t("补充发行备款", "Top up issuance reserve"), TOPUP_B: t("补充退款备付", "Top up refund reserve"), TRANSFER_U_TO_B: t("发行备款划拨至退款备付", "Transfer issuance reserve to refund reserve") }[value] ?? value);
  const stateLabel = (value: string) => ({ SUBMITTED: t("待复核", "Pending review"), APPROVED: t("已通过", "Approved"), REJECTED: t("已驳回", "Rejected"), EXECUTED: t("已执行", "Executed") }[value] ?? value);
  function openRequest(row: PointsRequestView) { setSelected(row); setReason(""); save.reset(); }
  function openCreate() { save.reset(); setFormError(""); setAction("TOPUP_U"); setAmountDraft(""); setCreateOpen(true); }

  const [after, setAfter] = useState(0); const [createOpen, setCreateOpen] = useState(false); const [selected, setSelected] = useState<PointsRequestView | null>(null);
  const [action, setAction] = useState<PointsSubmission["action"]>("TOPUP_U"); const [reason, setReason] = useState("");
  const requests = useQuery({ queryKey: ["points-requests", code, after], queryFn: () => api.pointsRequests({ typeCode: code, after, limit: 50 }), enabled: !!code });
  const overview = useQuery({ queryKey: ["points-overview", code], queryFn: () => api.pointsOverview(type?.market ?? "CN", code), enabled: !!code });
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  type Command = { kind: "SUBMIT"; value: PointsSubmission } | { kind: "REVIEW"; value: PointsRequestReview };
  const save = useMutation<PointsOperation, Error, Command>({ mutationFn: (c) => c.kind === "SUBMIT" ? api.submitPointsRequest(c.value) : api.reviewPointsRequest(c.value), onSuccess: () => { intent.current = undefined; setCreateOpen(false); setSelected(null); void qc.invalidateQueries({ queryKey: ["points-requests"] }); void qc.invalidateQueries({ queryKey: ["points-overview"] }); } });
  function send(c: Command) { const next = pointsCommand({ ...c, value: { ...c.value, requestId: "" } }, intent.current); intent.current = next.intent; save.mutate({ ...c, value: { ...c.value, requestId: next.command.requestId } } as Command); }
  const columns = [
    { header: t("积分名称", "Points name"), cell: (_r: PointsRequestView) => types.data?.find(x => x.typeCode === code)?.name ?? "—" },
    { header: t("积分代码", "Points code"), cell: (_r: PointsRequestView) => code },
    { header: t("申请编号", "Request"), cell: (r: PointsRequestView) => <b>{r.requestNo}</b> },
    { header: t("动作", "Action"), cell: (r: PointsRequestView) => actionLabel(r.action) },
    { header: t("金额", "Amount"), numeric: true, cell: (r: PointsRequestView) => money(r.amountMinor) },
    { header: t("状态", "Status"), cell: (r: PointsRequestView) => stateLabel(r.state) },
    { header: t("申请人", "Applicant"), cell: (r: PointsRequestView) => r.submittedBy },
    { header: t("复核人", "Reviewer"), cell: (r: PointsRequestView) => r.approvedBy ?? "—" },
    { header: t("币种", "Currency"), cell: () => type?.currency ?? "—" },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const card = "rounded-card border border-border p-4";
  return <main className={`space-y-3 ${styles.page}`}>

    <div className="grid min-w-0 gap-3 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end"><TabHeader tabs={[{ key: "budget", label: t("平台预算", "Platform budget") }]} value="budget" onChange={() => {}} desc={t("申请、审批与核对积分预算", "Request, review and reconcile points budgets")} /><div className="flex min-w-0 flex-wrap items-end gap-3"><label className="flex min-w-0 flex-1 flex-col gap-1.5 sm:flex-none">{t("积分名称", "Points name")}<Select className="w-full sm:w-64" value={code} disabled={save.isPending} onChange={(e) => { setChosenType(e.target.value); setAfter(0); setSelected(null); setCreateOpen(false); }}>{types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode}</option>)}</Select></label>{can("finance:settle:execute") && <Button className="shrink-0" disabled={!code} onClick={openCreate}>{t("新增预算申请", "New budget request")}</Button>}</div></div>
    <div className="flex justify-end gap-2">{can("finance:settle:read") && <Button variant="outline" onClick={() => setSettlementsOpen(true)}>{t("账单结算", "Statements")}</Button>}<Button variant="ghost" size="sm" aria-expanded={showHelp} aria-controls="budget-help" onClick={() => setShowHelp(!showHelp)}>{t("使用说明", "Help")}</Button></div>
    {showHelp && <div id="budget-help"><Notice>{t("预算变更需提交申请，由另一位有权限的运营人员复核后处理。金额按所选积分币种分别核算。", "Budget changes require a request and review by another authorized operator. Amounts are tracked separately by points currency.")}</Notice></div>}
    {(types.error || requests.error || overview.error) && <Notice tone="warning">{String(types.error?.message ?? requests.error?.message ?? overview.error?.message)}<Button onClick={() => { void types.refetch(); void requests.refetch(); void overview.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{([["uMinor", "发行备款", "Issuance reserve"], ["bMinor", "退款备付", "Refund reserve"], ["reservedBMinor", "退款已预留", "Reserved for refunds"], ["lockedUMinor", "发行备款待决占用", "Locked issuance reserve"]] as const).map(([key, label, english]) => <div className={card} key={key}><p className="txt-caption text-muted-foreground">{t(label, english)}</p><p className="mt-2 txt-heading tabular-nums">{overview.data ? money(overview.data[key]) : "—"}</p><p className="txt-caption text-muted-foreground">{type?.currency}</p></div>)}</section>
    <DataTable columns={columns} rows={requests.data?.rows} loading={requests.isPending} error={requests.error} onRetry={() => void requests.refetch()} rowKey={(r) => r.requestNo} empty={t("暂无平台预算申请。", "No platform budget requests.")} rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => openRequest(r), onKeyDown: (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openRequest(r); } } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={() => setAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!requests.data?.hasMore || requests.isFetching} onClick={() => setAfter(requests.data?.next ?? 0)}>{t("下一页", "Next page")}</Button></div>
    <Drawer open={settlementsOpen} onOpenChange={open => { if (!open && settlementBusy) return; setSettlementsOpen(open); }} title={t("账单结算", "Statements")} width="w-[1100px]"><PointsSettlementsTab onBusyChange={setSettlementBusy} canMake={can("finance:settle:execute")} canExecute={can("finance:payout:execute")} /></Drawer>
    <Drawer open={createOpen} onOpenChange={(open) => { if (!save.isPending) setCreateOpen(open); }} title={t("新增预算申请", "New budget request")} desc={t("另一位有复核权限的运营人员审核后生效。", "A second authorized operator must review this request.")} width="w-[560px]" className={styles.drawer} headerClassName={styles.drawerHead} bodyClassName={styles.drawerBody} footer={<><Button variant="outline" disabled={save.isPending} onClick={() => setCreateOpen(false)}>{t("取消", "Cancel")}</Button><Button type="submit" form="points-budget-form" disabled={save.isPending || IS_MOCK}>{t("提交申请", "Submit request")}</Button></>}>
      <section className={styles.summary}><h3>{t("积分信息", "Points information")}</h3><dl className={styles.context}>
        <div><dt>{t("积分名称", "Points name")}</dt><dd>{type?.name}</dd></div>
        <div><dt>{t("积分代码", "Points code")}</dt><dd dir="ltr">{code}</dd></div>
        <div><dt>{t("货币类型", "Currency")}</dt><dd>{currency ? t(currency.name, currency.english) : type?.currency}</dd></div>
        <div><dt>{t("货币代码", "Currency code")}</dt><dd dir="ltr">{type?.currency}</dd></div>
      </dl></section>
      <form id="points-budget-form" className={styles.form} onSubmit={(e) => { e.preventDefault();
        if (save.isPending || IS_MOCK) return;
        const f = new FormData(e.currentTarget);
        const requestNo = String(f.get("requestNo") ?? "").trim();
        const reason = String(f.get("reason") ?? "").trim();
        const evidenceNo = String(f.get("evidence") ?? "").trim();
        const amount = Number(f.get("amount"));
        if (!requestNo || !reason || !Number.isSafeInteger(amount) || amount <= 0 || (action !== "TRANSFER_U_TO_B" && !evidenceNo)) {
          setFormError(t("请填写申请编号、原因、正整数金额及所需的资金凭证编号。", "Enter a request number, reason, positive whole-number amount and the required funding evidence reference.")); return;
        }
        setFormError("");
        send({ kind: "SUBMIT", value: { requestId: "", typeCode: code, requestNo, action, amountMinor: amount, evidenceNo: action === "TRANSFER_U_TO_B" ? null : evidenceNo, fundsContext: null, reason } }); }}>
        <fieldset disabled={save.isPending} className={styles.fields}><legend>{t("申请信息", "Request information")}</legend>
        <label>{t("预算操作", "Budget action")}<Select value={action} onChange={(e) => { setAction(e.target.value as PointsSubmission["action"]); setAmountDraft(""); setFormError(""); save.reset(); }}><option value="TOPUP_U">{actionLabel("TOPUP_U")}</option><option value="TOPUP_B">{actionLabel("TOPUP_B")}</option><option value="TRANSFER_U_TO_B">{actionLabel("TRANSFER_U_TO_B")}</option></Select></label>
        <label>{t(`金额（${unit?.name ?? "最小货币单位"}）`, `Amount (${unit?.english ?? "minor units"})`)}<Input name="amount" type="number" required min={1} max={Number.MAX_SAFE_INTEGER} step={1} value={amountDraft} onChange={(e) => { setAmountDraft(e.target.value); setFormError(""); }} /></label>
        <label>{t("申请编号", "Request number")}<Input name="requestNo" required maxLength={64} /></label>
        {(action === "TOPUP_U" || action === "TOPUP_B") && <label>{t("资金凭证编号", "Funding evidence reference")}<Input key={action} name="evidence" required maxLength={100} /></label>}
        {action === "TRANSFER_U_TO_B" && Number(amountDraft) > 0 && overview.data && <div className={`${styles.fullRow} rounded-card border border-border bg-muted/30 p-3`}><p className="txt-label">{t("划拨后预算预览（仅供核对，最终以复核结果为准）", "Projected balances (review required; final balances come from the backend)")}</p><p className="mt-2 txt-body">{t("发行备款", "Issuance reserve")}：{money(overview.data.uMinor)} → {money(overview.data.uMinor - Number(amountDraft))} · {t("退款备付", "Refund reserve")}：{money(overview.data.bMinor)} → {money(overview.data.bMinor + Number(amountDraft))}</p></div>}
        <label className={styles.fullRow}>{t("申请原因", "Reason")}<Input name="reason" required maxLength={500} /></label>
        </fieldset>
        {formError && <p role="alert" className="txt-caption text-destructive-ink">{formError}</p>}
        {save.isError && <Notice tone="warning">{save.error.message}</Notice>}
      </form>
    </Drawer>
    <Drawer open={!!selected} onOpenChange={(open) => { if (!open && !save.isPending) setSelected(null); }} title={selected?.requestNo ?? ""} desc={t("预算申请详情与复核", "Budget request and review")} width="w-[560px]">
      {selected && <div className="space-y-4"><dl className="grid grid-cols-2 gap-3 rounded-card border border-border p-4 txt-body"><dt>{t("积分名称", "Points name")}</dt><dd>{type?.name}</dd><dt>{t("积分代码", "Points code")}</dt><dd>{code}</dd><dt>{t("预算操作", "Budget action")}</dt><dd>{actionLabel(selected.action)}</dd><dt>{t("金额", "Amount")}</dt><dd>{money(selected.amountMinor)}</dd><dt>{t("币种", "Currency")}</dt><dd>{type?.currency}</dd><dt>{t("状态", "Status")}</dt><dd>{stateLabel(selected.state)}</dd><dt>{t("版本", "Version")}</dt><dd>{selected.version}</dd><dt>{t("申请人", "Applicant")}</dt><dd>{selected.submittedBy}</dd><dt>{t("复核人", "Reviewer")}</dt><dd>{selected.approvedBy ?? "—"}</dd><dt>{t("申请时间", "Submitted at")}</dt><dd>{selected.submittedAt ? new Date(selected.submittedAt).toLocaleString() : "—"}</dd><dt>{t("复核时间", "Reviewed at")}</dt><dd>{selected.reviewedAt ? new Date(selected.reviewedAt).toLocaleString() : "—"}</dd><dt>{t("资金凭证", "Evidence")}</dt><dd>{selected.hasEvidence ? t("已登记", "Recorded") : "—"}</dd><dt>{t("资金指令", "Ticket")}</dt><dd>{selected.ticketNo ?? "—"}</dd></dl>
      {can("finance:payout:execute") && selected.state === "SUBMITTED" && <label className="flex flex-col gap-1.5">{t("复核原因", "Review reason")}<Input disabled={save.isPending} value={reason} onChange={(e) => setReason(e.target.value)} required maxLength={500} /></label>}
      {can("finance:payout:execute") && selected.state === "SUBMITTED" && <div className="flex gap-2"><Button disabled={!reason.trim() || save.isPending || IS_MOCK} onClick={() => send({ kind: "REVIEW", value: { requestId: "", typeCode: code, requestNo: selected.requestNo, expectedVersion: selected.version, approve: true, reason: reason.trim() } })}>{t("复核通过", "Approve")}</Button><Button variant="outline" disabled={!reason.trim() || save.isPending || IS_MOCK} onClick={() => send({ kind: "REVIEW", value: { requestId: "", typeCode: code, requestNo: selected.requestNo, expectedVersion: selected.version, approve: false, reason: reason.trim() } })}>{t("驳回", "Reject")}</Button></div>}{save.isError && <Notice tone="warning">{save.error.message}</Notice>}</div>}
    </Drawer>
  </main>;
}
