"use client";

import { useRef, useState } from "react";
import type { ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, IS_MOCK } from "@/lib/api";
import type { PointsAccountQ, PointsEntriesQ, PointsFreeze, PointsStatsQ, PointsStatsRebuild, PointsReconRun, PointsPositionQ, PointsHolder, PointsPositionKind, PointsEventRetry, PointsOperation } from "@/lib/types";
import { pointsCommand } from "@/lib/points-command";
import { usePointsText } from "@/lib/points-i18n";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Notice } from "@/components/ui/notice";

type Command = { kind: "FREEZE"; value: PointsFreeze } | { kind: "STATS"; value: PointsStatsRebuild } | { kind: "RECON"; value: PointsReconRun } | { kind: "RETRY"; value: PointsEventRetry };
const HOLDERS: PointsHolder[] = ["STORE", "USER", "ISSUE", "RECEIVE", "MINT"];
const BUCKETS = ["ISSUED", "ISSUABLE", "PENDING", "AVAILABLE", "HELD", "REFUND_RESERVED", "RECIRCULATABLE"] as const;
export function PointsQueriesTab({ canEdit, canReconRead, canReconRun }: { canEdit: boolean; canReconRead: boolean; canReconRun: boolean }) {
  const { locale, t } = usePointsText();
  const en = locale !== "zh";
  const qc = useQueryClient();
  const types = useQuery({ queryKey: ["points-types"], queryFn: () => api.pointsTypes() });
  const [selected, select] = useState("");
  const code = selected || types.data?.[0]?.typeCode || "";
  const type = types.data?.find((x) => x.typeCode === code);
  const [accountQ, setAccountQ] = useState<PointsAccountQ | null>(null);
  const [entryQ, setEntryQ] = useState<PointsEntriesQ | null>(null);
  const [entryMode, setEntryMode] = useState("ACCOUNT");
  const [entryAfter, setEntryAfter] = useState(0);
  const [ledgerAfter, setLedgerAfter] = useState(0);
  const [eventAfter, setEventAfter] = useState(0);
  const [ledgerKind, setLedgerKind] = useState("VOUCHERS");
  const [positionQ, setPositionQ] = useState<PointsPositionQ | null>(null);
  const [statsQ, setStatsQ] = useState<PointsStatsQ | null>(null);
  const [statsScope, setStatsScope] = useState("STORE");
  const [reason, setReason] = useState("");
  const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
  const [reconDay, setReconDay] = useState(today);
  const account = useQuery({ queryKey: ["points-account", accountQ], queryFn: () => api.pointsAccount(accountQ!), enabled: !!accountQ });
  const entries = useQuery({ queryKey: ["points-entries", entryQ, entryAfter], queryFn: () => api.pointsEntries({ ...entryQ!, after: entryAfter, limit: 50 }), enabled: !!entryQ });
  const vouchers = useQuery({ queryKey: ["points-vouchers", code, ledgerAfter], queryFn: () => api.pointsVouchers({ typeCode: code, after: ledgerAfter, limit: 50 }), enabled: !!code && ledgerKind === "VOUCHERS" });
  const flows = useQuery({ queryKey: ["points-flows", code, ledgerAfter], queryFn: () => api.pointsFlows({ typeCode: code, after: ledgerAfter, limit: 50 }), enabled: !!code && ledgerKind === "FLOWS" });
  const position = useQuery({ queryKey: ["points-position", positionQ], queryFn: () => api.pointsPosition(positionQ!), enabled: !!positionQ });
  const stats = useQuery({ queryKey: ["points-stats", statsQ], queryFn: () => api.pointsStats(statsQ!), enabled: !!statsQ });
  const recon = useQuery({ queryKey: ["points-reconciliation", code, reconDay], queryFn: () => api.pointsReconciliation({ typeCode: code, day: reconDay }), enabled: !!code && !!reconDay && canReconRead });
  const events = useQuery({ queryKey: ["points-events", code, eventAfter], queryFn: () => api.pointsEvents({ typeCode: code, after: eventAfter, limit: 50 }), enabled: !!code });
  const intent = useRef<{ body: string; requestId: string } | undefined>(undefined);
  const save = useMutation<PointsOperation, Error, Command>({ mutationFn: (v) => {
    switch (v.kind) {
      case "FREEZE": return api.freezePointsAccount(v.value);
      case "STATS": return api.rebuildPointsStats(v.value);
      case "RECON": return api.reconcilePoints(v.value);
      case "RETRY": return api.retryPointsEvent(v.value);
    }
  }, onSuccess: (result, command) => {
    intent.current = undefined;
    if (command.kind === "RECON") setReconDay(result.reference);
    for (const key of ["points-account", "points-stats", "points-reconciliation", "points-events"]) void qc.invalidateQueries({ queryKey: [key] });
  } });
  function send(command: Command) {
    const next = pointsCommand({ ...command, value: { ...command.value, requestId: "" } }, intent.current);
    intent.current = next.intent;
    save.mutate({ ...command, value: { ...command.value, requestId: next.command.requestId } } as Command);
  }
  const disabled = save.isPending || !reason.trim() || IS_MOCK;
  const card = "space-y-3 rounded-card border border-border p-4";
  const error = types.error || account.error || entries.error || vouchers.error || flows.error || position.error || stats.error || recon.error || events.error;
  const activeLedger = ledgerKind === "VOUCHERS" ? vouchers : flows;
  const money = (n: number) => type ? new Intl.NumberFormat(en ? "en" : "zh-CN", { style: "currency", currency: type.currency }).format(n / 10 ** type.scale) : String(n);
  const time = (at: number | null) => at ? new Date(at).toLocaleString(en ? "en" : "zh-CN") : "—";
  return <div className="space-y-4">
    <Notice>{t("查询只读。统计和对账快照需显式刷新，不自动修账；未生成、扫描未完成和无差异是不同状态。冻结只控制账户使用与到期时钟，不改积分余额。", "Queries are read-only. Statistics and reconciliation require explicit refresh and never repair balances. No snapshot, incomplete scan and no differences are distinct states. Freezing controls use and the expiry clock, preserving balances.")}</Notice>
    {IS_MOCK && <Notice tone="warning">{t("演示账户和流水为空，对账不模拟平账结果。", "Demo accounts and ledger rows are empty. Reconciliation does not simulate a balanced result.")}</Notice>}
    <label className="block">{t("积分类型", "Points type")}<Select value={code} onChange={(e) => { select(e.target.value); setAccountQ(null); setEntryQ(null); setPositionQ(null); setStatsQ(null); setEntryAfter(0); setLedgerAfter(0); setEventAfter(0); save.reset(); }}>{types.data?.map((x) => <option key={x.typeCode} value={x.typeCode}>{x.name} · {x.typeCode} · {x.market} · {x.currency}</option>)}</Select></label>
    {error && <Notice tone="warning">{t(error.message, error.message)}<Button onClick={() => { void types.refetch(); void qc.invalidateQueries({ predicate: (q) => String(q.queryKey[0]).startsWith("points-") }); }}>{t("重试", "Retry")}</Button></Notice>}
    {save.isError && <Notice tone="warning">{t(save.error.message, save.error.message)}</Notice>}
    {save.data && <Notice>{t("操作已受理", "Operation accepted")} {save.data.requestId} · {save.data.reference}</Notice>}
    <label className="block">{t("操作原因", "Operation reason")}<Input dir="auto" value={reason} onChange={(e) => setReason(e.target.value)} /></label>
    <div className={card}><h3 className="txt-heading">{t("门店 / 用户账户分格", "Store / user account buckets")}</h3>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setAccountQ({ typeCode: code, holderType: String(d.get("holder")) as PointsHolder, holderNo: String(d.get("subject")).trim() }); }}><label>{t("账户类型", "Holder type")}<Select name="holder">{HOLDERS.map((h) => <option key={h}>{h}</option>)}</Select></label><label>{t("门店 / 用户 / 平台编号", "Store / user / platform ID")}<Input dir="auto" name="subject" required /></label><Button disabled={!code} type="submit">{t("查询", "Search")}</Button></form>
      {account.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {account.data && (account.data.exists ? <><p dir="auto">{account.data.snapshot.holderType} · {account.data.snapshot.holderNo} · {account.data.snapshot.frozen ? t("已冻结", "Frozen") : t("未冻结", "Not frozen")} · v{account.data.version}</p><dl className="grid gap-2 sm:grid-cols-4">{BUCKETS.map((b) => <div key={b}><dt>{b}</dt><dd className="font-semibold tabular-nums">{account.data.snapshot.buckets[b] ?? 0}</dd></div>)}</dl><p dir="auto">{t("到期 / 最近活跃 / 到期修订", "Expires / last active / expiry revision")}: {time(account.data.snapshot.expireAt)} / {time(account.data.snapshot.lastActiveAt)} / {account.data.snapshot.expiryRevision}</p>
        {canEdit && account.data.snapshot.holderType === "USER" && <Button disabled={disabled} onClick={() => send({ kind: "FREEZE", value: { requestId: "", typeCode: code, userNo: account.data.snapshot.holderNo, expectedVersion: account.data.version, frozen: !account.data.snapshot.frozen, reason: reason.trim() } })}>{account.data.snapshot.frozen ? t("解除冻结", "Unfreeze") : t("冻结账户", "Freeze account")}</Button>}
      </> : <p dir="auto">{t("此账户不存在，不能按零余额账户处理。", "This account has no record; it is not a confirmed zero balance.")}</p>)}
    </div>
    <div className={card}><h3 className="txt-heading">{t("积分流水（按账户或凭证过滤）", "Points entries (filter by account or voucher)")}</h3>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setEntryQ(entryMode === "VOUCHER" ? { typeCode: code, voucherNo: String(d.get("voucher")).trim() } : { typeCode: code, holderType: String(d.get("holder")) as PointsHolder, holderNo: String(d.get("subject")).trim() }); setEntryAfter(0); }}>
        <Select aria-label={t("流水筛选方式", "Entry filter mode")} value={entryMode} onChange={(e) => setEntryMode(e.target.value)}><option value="ACCOUNT">{t("账户", "Account")}</option><option value="VOUCHER">{t("凭证", "Voucher")}</option></Select>
        {entryMode === "ACCOUNT" ? <><Select name="holder" aria-label={t("账户类型", "Holder type")}>{HOLDERS.map((h) => <option key={h}>{h}</option>)}</Select><label>{t("对象编号", "Subject ID")}<Input dir="auto" name="subject" required /></label></> : <label>{t("凭证号", "Voucher ID")}<Input dir="auto" name="voucher" required /></label>}
        <Button disabled={!code} type="submit">{t("查询", "Search")}</Button>
      </form>
      {entries.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {entries.data && <><Rows error={entries.error} onRetry={() => void entries.refetch()} headers={[t("流水 / 凭证", "Entry / voucher"), t("账户", "Account"), t("分格", "Bucket"), t("增减 / 变后", "Delta / after")]} rows={entries.data.rows.map((r) => [<span key={r.id}>{r.entryNo}<br />{r.voucherNo}</span>, `${r.holderType} / ${r.holderNo}`, r.bucket, `${r.deltaPoints} / ${r.afterPoints}`])} empty={t("无匹配流水。", "No matching entries.")} /><Cursor current={entryAfter} next={entries.data.next} more={entries.data.hasMore} busy={entries.isFetching} set={setEntryAfter} first={t("首页", "First page")} following={t("下一页", "Next page")} /></>}
    </div>
    <div className={card}><h3 className="txt-heading">{t("凭证与资金流水", "Vouchers and funds flows")}</h3><Select aria-label={t("账务查询类型", "Ledger view")} value={ledgerKind} onChange={(e) => { setLedgerKind(e.target.value); setLedgerAfter(0); }}><option value="VOUCHERS">{t("凭证", "Vouchers")}</option><option value="FLOWS">{t("资金流水", "Funds flows")}</option></Select>
      {activeLedger.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {ledgerKind === "VOUCHERS" && vouchers.data && <Rows error={vouchers.error} onRetry={() => void vouchers.refetch()} headers={[t("凭证号", "Voucher"), t("业务引用", "Business reference"), t("币种 / 比例", "Currency / rate")]} rows={vouchers.data.rows.map((r) => [r.voucherNo, r.bizRef, `${r.currency} · ${r.ratePoints}:${r.rateMinor}`])} empty={t("暂无凭证。", "No vouchers.")} />}
      {ledgerKind === "FLOWS" && flows.data && <Rows error={flows.error} onRetry={() => void flows.refetch()} headers={[t("流水 / 凭证", "Flow / voucher"), t("资金格", "Fund bucket"), t("增减 / 变后", "Delta / after")]} rows={flows.data.rows.map((r) => [`${r.flowNo} / ${r.voucherNo}`, r.bucket, `${money(r.deltaMinor)} / ${money(r.afterMinor)}`])} empty={t("暂无资金流水。", "No funds flows.")} />}
      {activeLedger.data && <Cursor current={ledgerAfter} next={activeLedger.data.next} more={activeLedger.data.hasMore} busy={activeLedger.isFetching} set={setLedgerAfter} first={t("首页", "First page")} following={t("下一页", "Next page")} />}
    </div>
    <div className={card}><h3 className="txt-heading">{t("头寸查询", "Position lookup")}</h3><form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setPositionQ({ typeCode: code, kind: String(d.get("kind")) as PointsPositionKind, reference: String(d.get("reference")).trim() }); }}><Select name="kind" aria-label={t("头寸类型", "Position kind")}>{["GRANT", "REDEEM", "REFUND", "PAYOUT", "STATEMENT", "TRANSFER"].map((k) => <option key={k}>{k}</option>)}</Select><label>{t("原编号", "Original reference")}<Input dir="auto" name="reference" required /></label><Button disabled={!code} type="submit">{t("查询", "Search")}</Button></form>
      {position.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}{position.data && <p dir="auto">{position.data.reference} · {position.data.state} · {position.data.originalPoints} / {position.data.processedPoints} {t("积分（原始 / 已处理）", "points (original / processed)")} · v{position.data.version}</p>}
    </div>
    <div className={card}><h3 className="txt-heading">{t("统计快照", "Statistics snapshots")}</h3><form className="flex flex-wrap items-end gap-3" onSubmit={(e) => { e.preventDefault(); const d = new FormData(e.currentTarget); setStatsQ({ typeCode: code, day: String(d.get("day")), scope: statsScope as PointsStatsQ["scope"], subjectNo: statsScope === "TOTAL" ? undefined : String(d.get("subject")).trim() }); }}><label>{t("日期", "Date")}<Input dir="auto" type="date" name="day" required defaultValue={today()} /></label><Select value={statsScope} onChange={(e) => setStatsScope(e.target.value)} aria-label={t("统计范围", "Statistics scope")}>{["STORE", "USER", "TOTAL"].map((s) => <option key={s}>{s}</option>)}</Select>{statsScope !== "TOTAL" && <label>{t("门店 / 用户编号", "Store / user ID")}<Input dir="auto" name="subject" required /></label>}<Button disabled={!code} type="submit">{t("查询", "Search")}</Button></form>
      {stats.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {statsQ && stats.isSuccess && (stats.data ? <><p dir="auto">{stats.data.day} · {statsQ.scope} · {statsQ.subjectNo} · {t("截至凭证 ID / 重建时间", "Through voucher ID / rebuilt at")}: {stats.data.throughVoucherId} / {time(stats.data.rebuiltAt)}</p><Rows error={stats.error} onRetry={() => void stats.refetch()} headers={[t("指标", "Metric"), t("数值", "Value")]} rows={Object.entries(stats.data.metrics).map(([k,v]) => [k, /minor$/i.test(k) ? money(v) : v])} empty={t("暂无指标。", "No metrics.")} /><Rows error={stats.error} onRetry={() => void stats.refetch()} headers={[t("跳过原因", "Skip reason"), t("次数", "Count")]} rows={Object.entries(stats.data.skipReasons)} empty={t("无跳过记录。", "No skips recorded.")} /></> : <p dir="auto">{t("尚无此范围的统计快照；不代表业务量为零。", "No snapshot exists for this scope; this does not imply zero activity.")}</p>)}
      {canEdit && statsQ && <Button disabled={disabled} onClick={() => send({ kind: "STATS", value: { requestId: "", typeCode: code, day: statsQ.day, reason: reason.trim() } })}>{t("重建该日统计（所有范围）", "Rebuild this day's statistics (all scopes)")}</Button>}
    </div>
    {canReconRead && <div className={card}><h3 className="txt-heading">{t("对账快照与差异", "Reconciliation snapshots and differences")}</h3><p dir="auto">{t("快照按 Asia/Shanghai 记账日期查询。刷新按服务器当前时间扫描；扫描不完整时不能判为平账。", "Snapshots use the Asia/Shanghai accounting date. Refresh scans at server time; an incomplete scan cannot be declared balanced.")}</p><label>{t("记账日期", "Accounting date")}<Input dir="auto" type="date" required value={reconDay} onChange={(e) => setReconDay(e.target.value)} /></label>
      {recon.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}
      {recon.isSuccess && (recon.data ? <><Notice tone={recon.data.complete && recon.data.differences.length === 0 ? "muted" : "warning"}>{!recon.data.complete ? t("扫描未完成，不能判定平账。", "Scan incomplete; balance is undetermined.") : recon.data.differences.length ? t("发现差异，请核对原凭证。", "Differences found. Inspect the original vouchers.") : t("该次完整扫描未发现差异。", "No differences found in this complete scan.")} · {time(recon.data.checkedAt)}</Notice><Rows error={recon.error} onRetry={() => void recon.refetch()} headers={[t("差异", "Difference"), t("引用", "Reference"), t("预期 / 实际", "Expected / actual")]} rows={recon.data.differences.map((d) => [d.code, d.reference, `${d.expected} / ${d.actual}`])} empty={t("无差异条目。", "No difference entries.")} /></> : <p dir="auto">{t("尚未生成该日对账快照。", "No reconciliation snapshot for this date.")}</p>)}
      {canReconRun && <Button disabled={disabled || !code} onClick={() => send({ kind: "RECON", value: { requestId: "", typeCode: code, reason: reason.trim() } })}>{t("执行只读资金核对并记录结果", "Scan funds and record reconciliation")}</Button>}
    </div>}
    <div className={card}><h3 className="txt-heading">{t("异常与事件恢复", "Attention and event recovery")}</h3>{events.isFetching && <p dir="auto">{t("加载中…", "Loading…")}</p>}{events.data?.rows.map((e) => <div key={e.id} className="space-y-2 border-t border-border py-2"><p dir="auto" className="break-all">{e.eventNo} · {e.kind} · {e.state} · v{e.version}</p><p dir="auto">{t("尝试次数 / 下次执行", "Attempts / next attempt")}: {e.attempt} / {time(e.nextAttempt)}</p>{canEdit && e.state === "MANUAL" && <Button disabled={disabled} onClick={() => send({ kind: "RETRY", value: { requestId: "", typeCode: code, eventId: e.id, expectedVersion: e.version, reason: reason.trim() } })}>{t("保留原事件号重试", "Retry the original event")}</Button>}</div>)}{events.data?.rows.length === 0 && <p dir="auto">{t("暂无事件记录。", "No event records.")}</p>}{events.data && <Cursor current={eventAfter} next={events.data.next} more={events.data.hasMore} busy={events.isFetching} set={setEventAfter} first={t("首页", "First page")} following={t("下一页", "Next page")} />}</div>
  </div>;
}
function Rows({ headers, rows, empty, error, onRetry }: { headers: string[]; rows: ReactNode[][]; empty: string; error: Error | null; onRetry: () => void }) {
  const data = rows.map((cells, index) => ({ key: String(index), cells }));
  return <DataTable rows={data} rowKey={(r) => r.key} empty={empty} error={error} onRetry={onRetry}
    columns={headers.map((header, index) => ({ header,
      cell: (r: { key: string; cells: ReactNode[] }) => <span dir="auto" className="break-all">{r.cells[index]}</span> }))} />;
}
function Cursor({ current, next, more, busy, set, first, following }: { current: number; next: number; more: boolean; busy: boolean; set: (n: number) => void; first: string; following: string }) { return <div className="flex gap-2"><Button disabled={!current} onClick={() => set(0)}>{first}</Button><Button disabled={!more || busy} onClick={() => set(next)}>{following}</Button></div>; }
