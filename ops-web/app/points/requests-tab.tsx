"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PointsOperation, PointsExecution, PointsSubmission, PointsRequestReview, PointsWithdrawal, PointsPlanChange, PointsPlanGenerate, PointsPlanReceipt, PointsPlanCancellation, PointsPoolAction } from "@/lib/types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { Notice } from "@/components/ui/notice";
import { SectionHeader } from "@/components/ui/section-header";

type Command = { kind: "SUBMIT"; value: PointsSubmission } | { kind: "REVIEW"; value: PointsRequestReview } |
  { kind: "EXECUTE"; value: PointsWithdrawal } | { kind: "PLAN"; value: PointsPlanChange } |
  { kind: "GENERATE"; value: PointsPlanGenerate } | { kind: "RECEIPT"; value: PointsPlanReceipt } | { kind: "CANCEL"; value: PointsPlanCancellation };
export function PointsTreasuryTab({ canMake, canCheck }: { canMake: boolean; canCheck: boolean }) {
  const { locale, t } = usePointsText();
  const en = locale !== "zh";
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [selected, select] = useState("");
  const code = selected || types.data?.[0]?.typeCode || "";
  const type = types.data?.find((x) => x.typeCode === code);
  const [requestAfter, setRequestAfter] = useState(0);
  const [planAfter, setPlanAfter] = useState(0);
  const requests = useQuery({ queryKey: ["points-requests", code, requestAfter], queryFn: () => api.pointsRequests({ typeCode: code, after: requestAfter, limit: 50 }), enabled: !!code });
  const plans = useQuery({ queryKey: ["points-plans", code, planAfter], queryFn: () => api.pointsPlans({ typeCode: code, after: planAfter, limit: 50 }), enabled: !!code });
  const [action, setAction] = useState<PointsPoolAction>("TOPUP_U");
  const [reason, setReason] = useState("");
  const [evidence, setEvidence] = useState("");
  const [editingPlan, setEditingPlan] = useState<string | null>(null);
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const save = useMutation<PointsOperation | PointsExecution, Error, Command>({ mutationFn: (v: Command) => {
    switch (v.kind) {
      case "SUBMIT": return api.submitPointsRequest(v.value);
      case "REVIEW": return api.reviewPointsRequest(v.value);
      case "EXECUTE": return api.executePointsWithdrawal(v.value);
      case "PLAN": return api.savePointsPlan(v.value);
      case "GENERATE": return api.generatePointsPeriods(v.value);
      case "RECEIPT": return api.declarePointsReceipt(v.value);
      case "CANCEL": return api.cancelPointsPeriod(v.value);
    }
  }, onSuccess: () => { intent.current = undefined; void qc.invalidateQueries({ queryKey: ["points-requests"] }); void qc.invalidateQueries({ queryKey: ["points-plans"] }); void qc.invalidateQueries({ queryKey: ["points-overview"] }); } });
  function send(command: Command) {
    const next = pointsCommand({ ...command, value: { ...command.value, requestId: "" } }, intent.current);
    intent.current = next.intent;
    save.mutate({ ...command, value: { ...command.value, requestId: next.command.requestId } } as Command);
  }
  const amount = (minor: number) => type ? new Intl.NumberFormat(locale === "ar" ? "ar" : en ? "en" : "zh-CN", { style: "currency", currency: type.currency }).format(minor / 10 ** type.scale) : String(minor);
  const withdrawal = action === "WITHDRAW_U" || action === "WITHDRAW_B";
  const card = "space-y-3 rounded-card border border-border p-4";
  const busy = save.isPending;
  const disabled = busy || !reason.trim();
  const error = types.error || requests.error || plans.error;
  return <div className="space-y-4">
    <SectionHeader title={t("积分资金申请与复核", "Points treasury requests and review")} />
    <Notice>{t("审批通过只代表申请受理。提取须执行并回查原指令；UNKNOWN 保持占用，不能换号重付。外部备款须有已核验的资金证据，周期计划不自动到账。", "Approval accepts a request. Withdrawals must execute and resolve the original command. UNKNOWN retains its lock; do not create a replacement payment. External top-ups need verified evidence; schedules never imply cash arrival.")}</Notice>
    {IS_MOCK && <Notice tone="warning">{t("演示环境仅展示结构，不模拟资金审批或银行到账。资金操作请使用隔离后端验证。", "The demo shows structure only and simulates neither treasury approval nor bank arrival. Verify operations against an isolated backend.")}</Notice>}
    {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void types.refetch(); if (code) { void requests.refetch(); void plans.refetch(); } }}>{t("重试", "Retry")}</Button></Notice>}
    {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
    {save.data && <Notice>{"status" in save.data ? `${save.data.ticketNo} · ${save.data.status} · ${amount(save.data.confirmedMinor)}` : `${t("已受理操作", "Operation accepted")} ${save.data.requestId} · ${save.data.reference}`}</Notice>}
    <label className="block">{t("积分类型", "Points type")}<Select value={code} onChange={(e) => { select(e.target.value); setRequestAfter(0); setPlanAfter(0); setEditingPlan(null); save.reset(); }}>
      {!types.data?.length && <option value="">{types.isPending ? t("加载中…", "Loading…") : t("尚无积分类型", "No points types")}</option>}
      {types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.typeCode} · {x.currency} · {x.status}</option>)}
    </Select></label>
    <label className="block">{t("本次操作原因（复核和执行均必填）", "Operation reason (required for review and execution)")}<Input dir="auto" value={reason} onChange={(e) => setReason(e.target.value)} required /></label>
    {canMake && code && <form className={card} onSubmit={(e) => {
      e.preventDefault(); const d = new FormData(e.currentTarget);
      send({ kind: "SUBMIT", value: { requestId: "", requestNo: String(d.get("requestNo")).trim(), typeCode: code, action, amountMinor: Number(d.get("amount")), evidenceNo: evidence.trim() || null,
        fundsContext: withdrawal ? { path: "DIRECT", channel: String(d.get("channel")).trim(), merchantNo: "PLATFORM", storeNo: "PLATFORM", accountVersion: String(d.get("accountVersion")).trim(), subMerchantId: String(d.get("recipient")).trim(), tradeNo: null, outTradeNo: null, orderTotalMinor: 0 } : null, reason: reason.trim() } });
    }}>
      <h3 className="txt-heading">{t("新增资金申请", "New treasury request")}</h3><div className="grid gap-3 md:grid-cols-3">
        <label>{t("申请号（重试使用原号）", "Request number (retain for retries)")}<Input dir="auto" name="requestNo" required maxLength={40} /></label>
        <label>{t("动作", "Action")}<Select className="w-full" value={action} onChange={(e) => setAction(e.target.value as PointsPoolAction)}>{["TOPUP_U", "TOPUP_B", "TRANSFER_U_TO_B", "WITHDRAW_U", "WITHDRAW_B"].map((a) => <option key={a}>{a}</option>)}</Select></label>
        <label>{t("金额（最小货币单位）", "Amount (minor currency units)")}<Input dir="auto" name="amount" type="number" required min={1} max={Number.MAX_SAFE_INTEGER} step={1} /></label>
        {(action === "TOPUP_U" || action === "TOPUP_B") && <label>{t("已核验的资金证据编号", "Verified funds evidence ID")}<Input dir="auto" required value={evidence} onChange={(e) => setEvidence(e.target.value)} /></label>}
        {withdrawal && <><label>{t("平台出款通道", "Platform withdrawal channel")}<Input dir="auto" name="channel" required /></label><label>{t("平台收款账户版本", "Platform recipient account version")}<Input dir="auto" name="accountVersion" required /></label><label>{t("平台收款路由编号", "Platform recipient routing ID")}<Input dir="auto" name="recipient" required /></label></>}
      </div><Button type="submit" disabled={disabled || IS_MOCK}>{t("提交申请", "Submit request")}</Button>
    </form>}
    <div className={card}><h3 className="txt-heading">{t("资金申请 · 审批与原号回查", "Requests · review and original-ticket resolution")}</h3>
      {requests.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {requests.data?.rows.map((r) => <div key={r.id} className="space-y-2 border-t border-border py-3"><p dir="auto">{r.requestNo} · {r.action} · {amount(r.amountMinor)} · {r.state} · v{r.version}</p><p dir="auto">{t("申请人 / 复核人", "Maker / checker")}: {r.submittedBy} / {r.approvedBy || "—"} · {t("资金证据", "Funds evidence")}: {r.hasEvidence ? t("已声明（审批时核验）", "Declared (verified at approval)") : "—"}</p>
        <div className="flex flex-wrap gap-2">{canCheck && r.state === "SUBMITTED" && <><Button disabled={disabled || IS_MOCK} onClick={() => send({ kind: "REVIEW", value: { requestId: "", typeCode: code, requestNo: r.requestNo, expectedVersion: r.version, approve: true, reason: reason.trim() } })}>{t("复核通过", "Approve")}</Button><Button disabled={disabled || IS_MOCK} onClick={() => send({ kind: "REVIEW", value: { requestId: "", typeCode: code, requestNo: r.requestNo, expectedVersion: r.version, approve: false, reason: reason.trim() } })}>{t("驳回", "Reject")}</Button></>}
          {canCheck && r.state === "APPROVED" && r.ticketNo && <Button disabled={disabled || IS_MOCK} onClick={() => send({ kind: "EXECUTE", value: { requestId: "", typeCode: code, ticketNo: r.ticketNo!, reason: reason.trim() } })}>{t("执行 / 回查原指令", "Execute / resolve original ticket")} {r.ticketNo}</Button>}
        </div>
      </div>)}
      {requests.data?.rows.length === 0 && <p dir="auto">{t("暂无申请。", "No requests.")}</p>}
      {requests.data && <div className="flex gap-2"><Button disabled={!requestAfter} onClick={() => setRequestAfter(0)}>{t("首页", "First page")}</Button><Button disabled={!requests.data.hasMore || requests.isFetching} onClick={() => setRequestAfter(requests.data.next)}>{t("下一页", "Next page")}</Button></div>}
    </div>
    {canMake && code && <form key={`${code}:${editingPlan ?? "new"}`} className={card} onSubmit={(e) => {
      e.preventDefault(); const d = new FormData(e.currentTarget); const old = plans.data?.rows.find((p) => p.period === "CONFIG" && p.planNo === editingPlan);
      send({ kind: "PLAN", value: { requestId: "", expectedVersion: old?.version ?? -1, reason: reason.trim(), plan: { planNo: String(d.get("planNo")).trim(), typeCode: code, action: String(d.get("action")) as PointsPlanChange["plan"]["action"], amountMinor: Number(d.get("amount")), anchor: String(d.get("anchor")), everyDays: Number(d.get("cadence")), enabled: d.get("enabled") === "on" } } });
    }}>
      <h3 className="txt-heading">{editingPlan ? t("修改周期计划", "Edit scheduled plan") : t("新增周期计划", "Create scheduled plan")}</h3>
      {(() => { const old = plans.data?.rows.find((p) => p.period === "CONFIG" && p.planNo === editingPlan); return <div className="grid gap-3 md:grid-cols-3">
        <label>{t("计划号", "Plan number")}<Input dir="auto" name="planNo" required maxLength={40} readOnly={!!old} defaultValue={old?.planNo} /></label>
        <label>{t("动作（创建后固定）", "Action (immutable)")}<Select name="action" defaultValue={old?.action ?? "TOPUP_U"} className={old ? "pointer-events-none" : ""} onChange={(e) => { if (old) e.target.value = old.action; }}>{["TOPUP_U", "TOPUP_B", "TRANSFER_U_TO_B"].map((a) => <option key={a}>{a}</option>)}</Select></label>
        <label>{t("金额（最小货币单位）", "Amount (minor currency units)")}<Input dir="auto" name="amount" type="number" min={1} max={Number.MAX_SAFE_INTEGER} step={1} required defaultValue={old?.amountMinor} /></label>
        <label>{t("起始日（区域当地日期）", "Anchor date (market local date)")}<Input dir="auto" name="anchor" type="date" required readOnly={!!old} defaultValue={old?.anchor} /></label>
        <label>{t("间隔天数（创建后固定）", "Interval days (immutable)")}<Input dir="auto" name="cadence" type="number" required min={1} max={365} step={1} readOnly={!!old} defaultValue={old?.everyDays ?? 1} /></label>
        <label className="flex items-center gap-2"><input name="enabled" type="checkbox" defaultChecked={old?.enabled ?? true} />{t("启用", "Enabled")}</label>
      </div>; })()}
      <div className="flex gap-2"><Button type="submit" disabled={disabled || IS_MOCK}>{t("保存计划", "Save plan")}</Button>{editingPlan && <Button type="button" onClick={() => setEditingPlan(null)}>{t("改为新建", "Create another")}</Button>}</div>
    </form>}
    <div className={card}><h3 className="txt-heading">{t("周期与待回执声明", "Plan periods and pending receipt declarations")}</h3>
      {canMake && <Button disabled={!code || disabled || IS_MOCK} onClick={() => send({ kind: "GENERATE", value: { requestId: "", typeCode: code, limit: 50, reason: reason.trim() } })}>{t("生成截至今天的到期申请（最多 50）", "Generate due requests through today (up to 50)")}</Button>}
      <label className="block">{t("本次回执声明编号", "Receipt evidence ID for this declaration")}<Input dir="auto" value={evidence} onChange={(e) => setEvidence(e.target.value)} /></label>
      {plans.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {plans.data?.rows.map((p) => <div key={p.id} className="space-y-2 border-t border-border py-3"><p dir="auto">{p.planNo} · {p.period} · {p.action} · {amount(p.amountMinor)} · {p.state} · v{p.version}</p><p dir="auto">{p.nextPeriod && `${t("下一周期", "Next period")}: ${p.nextPeriod}`} {p.requestNo}</p>
        <div className="flex gap-2">{canMake && p.period === "CONFIG" && <Button onClick={() => setEditingPlan(p.planNo)}>{t("修改金额 / 暂停", "Change amount / pause")}</Button>}
          {canMake && p.state === "AWAITING_RECEIPT" && <><Button disabled={disabled || !evidence.trim() || IS_MOCK} onClick={() => send({ kind: "RECEIPT", value: { requestId: "", typeCode: code, planNo: p.planNo, period: p.period, evidenceNo: evidence.trim(), expectedVersion: p.version, reason: reason.trim() } })}>{t("声明回执并制单", "Declare receipt and create request")}</Button><Button disabled={disabled || IS_MOCK} onClick={() => send({ kind: "CANCEL", value: { requestId: "", typeCode: code, planNo: p.planNo, period: p.period, expectedVersion: p.version, reason: reason.trim() } })}>{t("取消本周期", "Cancel period")}</Button></>}
        </div></div>)}
      {plans.data?.rows.length === 0 && <p dir="auto">{t("暂无计划或周期。", "No plans or periods.")}</p>}
      {plans.data && <div className="flex gap-2"><Button disabled={!planAfter} onClick={() => setPlanAfter(0)}>{t("首页", "First page")}</Button><Button disabled={!plans.data.hasMore || plans.isFetching} onClick={() => setPlanAfter(plans.data.next)}>{t("下一页", "Next page")}</Button></div>}
    </div>
  </div>;
}
