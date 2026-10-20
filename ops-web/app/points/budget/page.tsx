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
import { SectionHeader } from "@/components/ui/section-header";
import { TabHeader } from "@/components/ui/tab-header";

export default function PointsBudgetPage() {
  const { t, locale } = usePointsText(); const can = useCan(); const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [chosenType, setChosenType] = useState(""); const code = chosenType || types.data?.[0]?.typeCode || "";
  const type = types.data?.find((x) => x.typeCode === code); const money = (n: number) => type ? new Intl.NumberFormat(locale === "zh" ? "zh-CN" : "en", { style: "currency", currency: type.currency }).format(n / 10 ** type.scale) : String(n);
  const [amountDraft, setAmountDraft] = useState(0);
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
    { header: t("动作", "Action"), cell: (r: PointsRequestView) => r.action },
    { header: t("金额", "Amount"), numeric: true, cell: (r: PointsRequestView) => money(r.amountMinor) },
    { header: t("状态", "Status"), cell: (r: PointsRequestView) => r.state },
    { header: t("申请人 / 复核人", "Maker / checker"), cell: (r: PointsRequestView) => `${r.submittedBy} / ${r.approvedBy ?? "—"}` },
    { header: "", cell: () => <span className="text-muted-foreground">{t("查看详情 →", "View details →")}</span> },
  ];
  const card = "rounded-card border border-border p-4";
  return <main className="space-y-4">
    <TabHeader tabs={[{ key: "budget", label: t("平台预算", "Platform budget") }]} value="budget" onChange={() => {}} desc={t("查看预算余额与平台资金申请、复核记录", "Review balances and platform funding requests")} />
    <div className="grid min-w-0 gap-3 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end"><SectionHeader title={t("预算申请", "Budget requests")} summary={requests.data?.rows.length ?? 0} className="mb-0 min-w-0" /><div className="flex min-w-0 flex-wrap items-end gap-3"><label className="min-w-0 flex-1 sm:flex-none">{t("积分类型", "Points type")}<Select className="w-full sm:w-64" value={code} onChange={(e) => { setChosenType(e.target.value); setAfter(0); setSelected(null); setCreateOpen(false); }}>{types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode}</option>)}</Select></label>{can("finance:settle:execute") && <Button className="shrink-0" disabled={!code || IS_MOCK} onClick={() => { save.reset(); setAmountDraft(0); setCreateOpen(true); }}>{t("新增预算申请", "New budget request")}</Button>}</div></div>
    <Notice>{t("所有门店默认参与积分计划，本期不提供单店开关。预算变更必须通过申请和复核入账。", "All stores participate by default; store-level switches are not part of this release. Budget changes require submission and review.")}</Notice>
    {(types.error || requests.error || overview.error) && <Notice tone="warning">{String(types.error?.message ?? requests.error?.message ?? overview.error?.message)}<Button onClick={() => { void types.refetch(); void requests.refetch(); void overview.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
    {save.isError && <Notice tone="warning">{save.error.message}</Notice>}
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{([["uMinor", "U · 发行备款"], ["bMinor", "B · 退款备付"], ["reservedBMinor", "B · 已预留"], ["lockedUMinor", "U · 待决占用"]] as const).map(([key, label]) => <div className={card} key={key}><p className="txt-caption text-muted-foreground">{t(label, label)}</p><p className="mt-2 txt-heading tabular-nums">{overview.data ? money(overview.data[key]) : "—"}</p></div>)}</section>
    <DataTable columns={columns} rows={requests.data?.rows} loading={requests.isPending} error={requests.error} onRetry={() => void requests.refetch()} rowKey={(r) => r.requestNo} empty={t("暂无平台预算申请。", "No platform budget requests.")} rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => { setSelected(r); setReason(""); save.reset(); }, onKeyDown: (e) => { if (e.key === "Enter") setSelected(r); } })} />
    <div className="flex justify-end gap-2"><Button variant="outline" disabled={!after} onClick={() => setAfter(0)}>{t("首页", "First page")}</Button><Button variant="outline" disabled={!requests.data?.hasMore || requests.isFetching} onClick={() => setAfter(requests.data?.next ?? 0)}>{t("下一页", "Next page")}</Button></div>
    <Drawer open={createOpen} onOpenChange={setCreateOpen} title={t("新增预算申请", "New budget request")} desc={t("另一位有复核权限的运营人员审核后生效。", "A second authorized operator must review this request.")} width="w-[600px]" footer={<><Button variant="outline" onClick={() => setCreateOpen(false)}>{t("取消", "Cancel")}</Button><Button type="submit" form="points-budget-form" disabled={save.isPending || IS_MOCK}>{t("提交申请", "Submit request")}</Button></>}>
      <p className="txt-title">{type?.name}</p><p>{t("积分代码", "Points code")}: {code}</p>
      <form id="points-budget-form" className="space-y-4" onSubmit={(e) => { e.preventDefault(); const f = new FormData(e.currentTarget); send({ kind: "SUBMIT", value: { requestId: "", typeCode: code, requestNo: String(f.get("requestNo")).trim(), action, amountMinor: Number(f.get("amount")), evidenceNo: String(f.get("evidence") || "").trim() || null, fundsContext: null, reason: String(f.get("reason")).trim() } }); }}>
        <label>{t("申请编号", "Request number")}<Input name="requestNo" required maxLength={64} /></label>
        <label>{t("预算操作", "Budget action")}<Select value={action} onChange={(e) => setAction(e.target.value as PointsSubmission["action"])}><option value="TOPUP_U">{t("补充发行备款 U", "Top up issuance reserve U")}</option><option value="TOPUP_B">{t("补充退款备付 B", "Top up refund reserve B")}</option><option value="TRANSFER_U_TO_B">{t("U 划拨至 B", "Transfer U to B")}</option></Select></label>
        <label>{t("金额（最小货币单位）", "Amount (minor currency units)")}<Input name="amount" type="number" required min={1} max={Number.MAX_SAFE_INTEGER} step={1} onChange={(e) => setAmountDraft(Number(e.target.value) || 0)} /></label>
        {action === "TRANSFER_U_TO_B" && amountDraft > 0 && overview.data && <div className="rounded-card border border-border bg-muted/30 p-3"><p className="txt-label">{t("划拨后预算预览（仅供核对，最终以复核结果为准）", "Projected balances (review required; final balances come from the backend)")}</p><p className="mt-2 txt-body">U：{money(overview.data.uMinor)} → {money(overview.data.uMinor - amountDraft)} · B：{money(overview.data.bMinor)} → {money(overview.data.bMinor + amountDraft)}</p></div>}
        {(action === "TOPUP_U" || action === "TOPUP_B") && <label>{t("资金凭证编号", "Funding evidence reference")}<Input name="evidence" required maxLength={100} /></label>}
        <label>{t("申请原因", "Reason")}<Input name="reason" required maxLength={500} /></label>
      </form>
    </Drawer>
    <Drawer open={!!selected} onOpenChange={(open) => { if (!open) setSelected(null); }} title={selected?.requestNo ?? ""} desc={t("预算申请详情与复核", "Budget request and review")} width="w-[560px]">
      {selected && <div className="space-y-4"><dl className="grid grid-cols-2 gap-3 rounded-card border border-border p-4 txt-body"><dt>{t("积分名称", "Points name")}</dt><dd>{type?.name}</dd><dt>{t("积分代码", "Points code")}</dt><dd>{code}</dd><dt>{t("操作 / 金额", "Action / amount")}</dt><dd>{selected.action} · {money(selected.amountMinor)}</dd><dt>{t("状态 / 版本", "State / version")}</dt><dd>{selected.state} · v{selected.version}</dd><dt>{t("申请人 / 复核人", "Maker / checker")}</dt><dd>{selected.submittedBy} / {selected.approvedBy ?? "—"}</dd><dt>{t("资金凭证", "Evidence")}</dt><dd>{selected.hasEvidence ? t("已登记", "Recorded") : "—"}</dd><dt>{t("资金指令", "Ticket")}</dt><dd>{selected.ticketNo ?? "—"}</dd></dl>
      <label>{t("复核原因", "Review reason")}<Input value={reason} onChange={(e) => setReason(e.target.value)} required maxLength={500} /></label>
      {can("finance:payout:execute") && selected.state === "SUBMITTED" && <div className="flex gap-2"><Button disabled={!reason.trim() || save.isPending || IS_MOCK} onClick={() => send({ kind: "REVIEW", value: { requestId: "", typeCode: code, requestNo: selected.requestNo, expectedVersion: selected.version, approve: true, reason: reason.trim() } })}>{t("复核通过", "Approve")}</Button><Button variant="outline" disabled={!reason.trim() || save.isPending || IS_MOCK} onClick={() => send({ kind: "REVIEW", value: { requestId: "", typeCode: code, requestNo: selected.requestNo, expectedVersion: selected.version, approve: false, reason: reason.trim() } })}>{t("驳回", "Reject")}</Button></div>}{save.isError && <Notice tone="warning">{save.error.message}</Notice>}</div>}
    </Drawer>
  </main>;
}
