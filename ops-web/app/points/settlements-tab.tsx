"use client";

import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PointsOperation, PointsStatementDraft, PointsStatementChange, PointsRevision, PointsReceivableRecovery, PointsRecovery, PointsRecoveryExecution, PointsFundsReference, PointsReference, PointsReceivable, PointsRecoveryOperation } from "@/lib/types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";

type Command = { kind: "DRAFT"; value: PointsStatementDraft } | { kind: "STATEMENT"; value: PointsStatementChange } |
  { kind: "REVISE"; value: PointsRevision } | { kind: "RECEIVABLE"; value: PointsReceivableRecovery } | { kind: "EXECUTE"; value: PointsRecovery };
export function PointsSettlementsTab({ canMake, canExecute, onBusyChange }: { canMake: boolean; canExecute: boolean; onBusyChange?: (busy: boolean) => void }) {
  const { locale, t } = usePointsText();
  const en = locale !== "zh";
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [filter, setFilter] = useState<{ typeCode: string; storeNo: string } | null>(null);
  const [statementAfter, setStatementAfter] = useState(0);
  const [receivableAfter, setReceivableAfter] = useState(0);
  const [panel, setPanel] = useState<"DRAFT" | "STATEMENT" | "RECEIVABLES" | "REFUND" | "FUNDS" | null>(null);
  const [statementNo, setStatementNo] = useState("");
  const [validation, setValidation] = useState("");
  const openPanel = (next: typeof panel) => { setReason(""); setValidation(""); save.reset(); setDebt(null); setFundsRef(null); setRefundRef(null); setPanel(next); };
  const [reason, setReason] = useState("");
  const [fundsRef, setFundsRef] = useState<PointsFundsReference | null>(null);
  const [refundRef, setRefundRef] = useState<PointsReference | null>(null);
  const [debt, setDebt] = useState<PointsReceivable | null>(null);
  const statements = useQuery({ queryKey: ["points-statements", filter, statementAfter], queryFn: () => api.pointsStatements({ ...filter!, after: statementAfter, limit: 50 }), enabled: !!filter });
  const debts = useQuery({ queryKey: ["points-receivables", filter, receivableAfter], queryFn: () => api.pointsReceivables({ ...filter!, after: receivableAfter, limit: 50 }), enabled: !!filter });
  const funds = useQuery({ queryKey: ["points-funds", fundsRef], queryFn: () => api.pointsFunds(fundsRef!), enabled: !!fundsRef });
  const refund = useQuery({ queryKey: ["points-refund", refundRef], queryFn: () => api.pointsRefund(refundRef!), enabled: !!refundRef });
  const type = types.data?.find((x) => x.typeCode === filter?.typeCode);
  const amount = (n: number) => type ? new Intl.NumberFormat(en ? "en" : "zh-CN", { style: "currency", currency: type.currency }).format(n / 10 ** type.scale) : String(n);
  const intents = useRef(new Map<string, { body: string; requestId: string }>());
  const save = useMutation<PointsOperation | PointsRecoveryExecution, Error, Command>({ mutationFn: (c) => {
    switch (c.kind) {
      case "DRAFT": return api.draftPointsStatements(c.value);
      case "STATEMENT": return api.changePointsStatement(c.value);
      case "REVISE": return api.revisePointsRefund(c.value);
      case "RECEIVABLE": return api.preparePointsReceivable(c.value);
      case "EXECUTE": return api.recoverPointsFunds(c.value);
    }
  }, onSuccess: () => {
    setPanel(null);
    for (const key of ["points-statements", "points-receivables", "points-funds", "points-refund", "points-overview"]) void qc.invalidateQueries({ queryKey: [key] });
  } });
  useEffect(() => { onBusyChange?.(save.isPending); return () => onBusyChange?.(false); }, [save.isPending, onBusyChange]);
  function send(c: Command) {
    if (save.isPending || IS_MOCK || !reason.trim()) return;
    const needsPayout = c.kind === "EXECUTE" || (c.kind === "STATEMENT" && c.value.action === "PREPARE_PAYOUT");
    if (needsPayout ? !canExecute : !canMake) return;
    const body = JSON.stringify(c);
    const next = pointsCommand({ ...c, value: { ...c.value, requestId: "" } }, intents.current.get(body));
    intents.current.set(body, next.intent);
    save.mutate({ ...c, value: { ...c.value, requestId: next.command.requestId } } as Command);
  }
  const disabled = save.isPending || !reason.trim() || IS_MOCK;
  const statementState = (value: string) => ({DRAFT:t("待确认", "Draft"),CONFIRMED:t("已确认", "Confirmed"),DISPUTED:t("有争议", "Disputed"),PAID:t("已付款", "Paid"),PAYOUT_PREPARED:t("已准备付款", "Payout prepared")}[value] ?? t("状态待核实", "Status needs verification"));
  const card = "space-y-3 rounded-card border border-border p-4";
  const error = types.error || statements.error || debts.error || funds.error || refund.error;
  return <div className="space-y-4">
    <Notice>{t("按门店核对每个原路由账单组。R 是应收，账单付款时只抵销同店债务。退款准备来自原交易事实；现金确认和恢复以支付系统证据为准。", "Review every original-route statement group by store. R is a receivable; statement payouts offset debts of the same store only. Refund preparations originate from source transactions. Cash confirmation and restoration require payment evidence.")}</Notice>
    {IS_MOCK && <Notice tone="warning">{t("演示没有真实门店、订单和银行数据；操作请连接隔离后端验证。", "The demo has no real stores, source orders or bank data. Verify operations with an isolated backend.")}</Notice>}
    <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); if (save.isPending) return; const d = new FormData(e.currentTarget); setPanel(null); setFilter({ typeCode: String(d.get("type")), storeNo: String(d.get("store")).trim() }); setStatementAfter(0); setReceivableAfter(0); setDebt(null); setFundsRef(null); setRefundRef(null); setReason(""); save.reset(); }}>
      <label>{t("积分类型", "Points type")}<Select name="type" disabled={save.isPending} required>{types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode} · {x.market}</option>)}</Select></label>
      <label>{t("门店编号", "Store ID")}<Input dir="auto" name="store" disabled={save.isPending} required maxLength={64} /></label><Button disabled={!types.data?.length || save.isPending} type="submit">{t("查询门店", "Search store")}</Button>
    </form>
    {filter && <div><p dir="auto" className="txt-title">{type?.name ?? t("积分名称未加载", "Points name unavailable")}</p><p dir="auto">{t("积分代码", "Points code")}: {filter.typeCode}</p><p dir="auto">{filter.storeNo} · {type?.currency}</p></div>}
    {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void types.refetch(); if (filter) { void statements.refetch(); void debts.refetch(); } if (fundsRef) void funds.refetch(); if (refundRef) void refund.refetch(); }}>{t("重试", "Retry")}</Button></Notice>}
    {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
    {save.data && <Notice>{"status" in save.data ? `${save.data.reference} · ${save.data.status}` : `${t("操作已受理", "Operation accepted")} ${save.data.requestId} · ${save.data.reference}`}</Notice>}
    {filter && <>
      <div className="flex flex-wrap gap-2">{canMake && <Button onClick={() => openPanel("DRAFT")}>{t("生成账单", "Draft statements")}</Button>}<Button variant="outline" onClick={() => openPanel("RECEIVABLES")}>{t("门店应收", "Store receivables")}</Button><Button variant="outline" onClick={() => openPanel("REFUND")}>{t("退款准备查询", "Refund preparation")}</Button><Button variant="outline" onClick={() => openPanel("FUNDS")}>{t("资金指令查询", "Funds lookup")}</Button></div>
      <DataTable rows={statements.data?.rows} loading={statements.isPending} error={statements.error} onRetry={() => void statements.refetch()} rowKey={r => String(r.id)} empty={t("此门店暂无账单", "No statements for this store")} columns={[
        {header:t("账单编号", "Statement"), cell:r=>r.snapshot.statementNo},
        {header:t("状态", "Status"), cell:r=>statementState(r.snapshot.state)},
        {header:t("修订", "Revision"), cell:r=>r.snapshot.revisions.at(-1)?.number ?? "—"},
        {header:t("积分数量", "Points"), cell:r=>r.snapshot.revisions.at(-1)?.points.toLocaleString() ?? "—"},
        {header:t("开始时间", "From"), cell:r=>new Date(r.snapshot.period.fromMillis).toLocaleString()},
        {header:t("结束时间", "To"), cell:r=>new Date(r.snapshot.period.toMillis).toLocaleString()},
        {header:"",cell:r=><Button variant="ghost" disabled={save.isPending} onClick={()=>{setStatementNo(r.snapshot.statementNo);openPanel("STATEMENT");}}>{t("查看详情", "Details")}</Button>},
      ]}/>
      <div className="flex justify-end gap-2"><Button disabled={!statementAfter || statements.isFetching} onClick={()=>setStatementAfter(0)}>{t("首页", "First page")}</Button><Button disabled={!statements.data?.hasMore || statements.isFetching} onClick={()=>setStatementAfter(statements.data!.next)}>{t("下一页", "Next page")}</Button></div>
      <Drawer open={!!panel} onOpenChange={open=>{if(!open && !save.isPending)setPanel(null);}} title={panel === "STATEMENT" ? statementNo : panel === "DRAFT" ? t("生成账单", "Draft statements") : panel === "RECEIVABLES" ? t("门店应收", "Store receivables") : panel === "REFUND" ? t("退款准备查询", "Refund preparation") : t("资金指令查询", "Funds lookup")} width="w-[760px]">
        <div className="space-y-4"><p>{type?.name} · {filter.storeNo}</p>
        {(canMake || canExecute) && <label className="block space-y-2">{t("操作原因（执行操作时填写）", "Reason (required for actions)")}<Input disabled={save.isPending} maxLength={500} value={reason} onChange={e=>setReason(e.target.value)}/></label>}
        {validation && <Notice tone="warning">{validation}</Notice>}
        {save.error && <Notice tone="warning">{save.error.message}</Notice>}
        {panel === "DRAFT" && <>      {canMake && <form className={card} onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); const fromMillis = Date.parse(String(d.get("from"))), toMillis = Date.parse(String(d.get("to"))); if (!Number.isFinite(fromMillis) || !Number.isFinite(toMillis) || toMillis <= fromMillis) { setValidation(t("结束时间必须晚于开始时间", "End must be after start")); return; } setValidation(""); send({ kind: "DRAFT", value: { requestId: "", ...filter, fromMillis, toMillis, reason: reason.trim() } }); }}>
        <h3 className="txt-heading">{t("生成门店账单组", "Draft store statement groups")}</h3><p dir="auto">{t("时间采用当前浏览器时区：", "Dates use the browser time zone: ")}{Intl.DateTimeFormat().resolvedOptions().timeZone}</p>
        <div className="flex flex-wrap items-end gap-3"><label>{t("开始（含）", "From (inclusive)")}<Input dir="auto" type="datetime-local" name="from" required /></label><label>{t("结束（不含）", "To (exclusive)")}<Input dir="auto" type="datetime-local" name="to" required /></label><Button type="submit" disabled={disabled}>{t("生成所有原路由组", "Draft all original-route groups")}</Button></div>
      </form>}
</>}
        {panel === "STATEMENT" && <>      <div className={card}><h3 className="txt-heading">{t("门店账单与修订", "Store statements and revisions")}</h3>
        {statements.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
        {statements.data?.rows.filter(r => r.snapshot.statementNo === statementNo).map((row) => { const s = row.snapshot; const revision = s.revisions.at(-1); if (!revision) return <Notice key={row.id} tone="warning">{t("账单修订信息缺失，请刷新后核对", "Statement revision unavailable; refresh to verify")}</Notice>; const change = (action: PointsStatementChange["action"]) => send({ kind: "STATEMENT", value: { requestId: "", ...filter, statementNo: s.statementNo, revision: revision.number, action, reason: reason.trim() } }); return <div key={row.id} className="space-y-2 border-t border-border py-3">
          <p dir="auto">{s.statementNo} · {statementState(s.state)} · {t("修订", "Revision")} {revision.number} · {revision.points} {t("积分", "points")}</p><p dir="auto" className="break-all">{t("路由组", "Route group")}: {s.period.routeKey || "—"} · {new Date(s.period.fromMillis).toISOString()} → {new Date(s.period.toMillis).toISOString()}</p>
          {s.disputeReason && <p dir="auto">{s.disputeReason}</p>}
          <details><summary>{t("原始头寸和历史修订", "Source positions and revision history")}</summary>{s.revisions.map((r) => <div key={r.number} className="py-2"><p dir="auto">v{r.number} · {r.points} · {r.actor} · {r.reason}</p>{r.lines.map((l) => <p key={l.positionNo}>{l.positionNo} · v{l.sourceVersion} · {l.points}</p>)}</div>)}</details>
          <div className="flex gap-2">{canMake && s.state === "DRAFT" && <><Button disabled={disabled || revision.points <= 0} onClick={() => change("CONFIRM")}>{t("确认本组修订", "Confirm this revision")}</Button><Button disabled={disabled} onClick={() => change("DISPUTE")}>{t("提交争议", "Dispute")}</Button></>}{canMake && ["DRAFT", "DISPUTED"].includes(s.state) && <Button disabled={disabled} onClick={() => change("REDRAFT")}>{t("重算修订", "Redraft")}</Button>}{canExecute && s.state === "CONFIRMED" && <Button disabled={disabled} onClick={() => change("PREPARE_PAYOUT")}>{t("准备本组付款（同店抵销 R）", "Prepare payout (offset same-store R)")}</Button>}</div>
        </div>; })}
        {statements.data?.rows.length === 0 && <p dir="auto">{t("此门店暂无账单组。", "No statement groups for this store.")}</p>}
      </div>
</>}
        {panel === "RECEIVABLES" && <>      <div className={card}><h3 className="txt-heading">{t("门店应收 R（不计入现金）", "Store receivables R (not cash)")}</h3>
        {debts.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
        {debts.data?.rows.map((r) => <div key={r.id} className="flex flex-wrap items-center gap-3 border-t border-border py-2"><p dir="auto">{r.receivableNo} · {r.state} · {t("未收", "Outstanding")} {amount(r.outstandingMinor)} · {t("已锁定", "Locked")} {amount(r.lockedMinor)}</p>{canMake && r.outstandingMinor > r.lockedMinor && <Button onClick={() => setDebt(r)}>{t("准备补收", "Prepare collection")}</Button>}</div>)}
        {debts.data?.rows.length === 0 && <p dir="auto">{t("此门店暂无应收。", "No receivables for this store.")}</p>}
        {debt && canMake && <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); const amountMinor = Number(d.get("amount")); if (!Number.isSafeInteger(amountMinor) || amountMinor <= 0 || amountMinor > debt.outstandingMinor - debt.lockedMinor) { setValidation(t("请填写可补收范围内的正整数金额", "Enter a positive integer within the outstanding amount")); return; } send({ kind: "RECEIVABLE", value: { requestId: "", ...filter, receivableNo: debt.receivableNo, expectedVersion: debt.version, amountMinor, reason: reason.trim() } }); }}><label>{debt.receivableNo} · {t("金额（最小货币单位）", "Amount (minor currency units)")}<Input dir="auto" name="amount" type="number" step={1} required min={1} max={debt.outstandingMinor - debt.lockedMinor} /></label><Button disabled={disabled} type="submit">{t("准备原账户补收指令", "Prepare collection to original account")}</Button></form>}
        {debts.data && <div className="flex gap-2"><Button disabled={!receivableAfter} onClick={() => setReceivableAfter(0)}>{t("首页", "First page")}</Button><Button disabled={!debts.data.hasMore || debts.isFetching} onClick={() => setReceivableAfter(debts.data.next)}>{t("下一页", "Next page")}</Button></div>}
      </div>
</>}
        {panel === "REFUND" && <>      <div className={card}><h3 className="txt-heading">{t("退款准备查询", "Refund preparation lookup")}</h3>
        <form className="flex items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setRefundRef({ ...filter, reference: String(d.get("reference")).trim() }); }}><label>{t("原准备号", "Original preparation ID")}<Input dir="auto" name="reference" required /></label><Button type="submit">{t("查询", "Search")}</Button></form>
        {refund.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
        {refund.data && <><p dir="auto">{refund.data.preparation.preparationNo} · {refund.data.state} · v{refund.data.version} · {refund.data.subOrderNo} · {refund.data.afterSaleNo}</p><p dir="auto">{t("返还积分 / 回收待生效 / 回收可用", "Returned / reclaimed pending / reclaimed available")}: {refund.data.preparation.returnedPoints} / {refund.data.preparation.reclaimedPending} / {refund.data.preparation.reclaimedAvailable}</p><p dir="auto">{t("现金扣减 / 现金应退 / 豁免积分", "Cash offset / cash refund due / waived points")}: {amount(refund.data.preparation.cashOffsetMinor)} / {amount(refund.data.preparation.netCashMinor)} / {refund.data.preparation.waivedPoints}</p>
          {canMake && refund.data.state === "COLLECTION_FAILED" && <Button disabled={disabled} onClick={() => send({ kind: "REVISE", value: { requestId: "", ...refundRef!, expectedVersion: refund.data.version, reason: reason.trim() } })}>{t("重新计算失败补收准备", "Revise failed collection preparation")}</Button>}
        </>}
      </div>
</>}
        {panel === "FUNDS" && <>      <div className={card}><h3 className="txt-heading">{t("付款、补收与补偿原号回查", "Resolve original payout, collection or compensation")}</h3>
        <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setFundsRef({ ...filter, operation: String(d.get("operation")) as PointsRecoveryOperation, reference: String(d.get("reference")).trim() }); }}><label>{t("类型", "Operation")}<Select name="operation">{["PAYOUT", "TRANSFER", "REFUND_COLLECTION", "REFUND_COMPENSATION"].map((o) => <option key={o}>{o}</option>)}</Select></label><label>{t("原票号（退款使用原准备号）", "Original ticket (preparation ID for refunds)")}<Input dir="auto" name="reference" required /></label><Button type="submit">{t("查询", "Search")}</Button></form>
        {funds.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
        {funds.data && <><p dir="auto">{funds.data.reference} · {funds.data.state} · v{funds.data.version}</p><p dir="auto">{t("总额 / 抵销 / 现金", "Gross / offset / cash")}: {amount(funds.data.grossMinor)} / {amount(funds.data.offsetMinor)} / {amount(funds.data.cashMinor)}</p>
          {canExecute && ["READY", "DISPATCHING", "UNKNOWN", "PREPARED", "COLLECTING", "COLLECTION_UNKNOWN", "COMPENSATION_REQUIRED", "COMPENSATING", "COMPENSATION_UNKNOWN"].includes(funds.data.state) && <Button disabled={disabled} onClick={() => send({ kind: "EXECUTE", value: { requestId: "", ...fundsRef!, expectedVersion: funds.data.version, reason: reason.trim() } })}>{t("执行或回查原指令", "Execute or resolve the original command")}</Button>}
        </>}
      </div>
</>}
        </div>
      </Drawer>
    </>}
  </div>;
}
