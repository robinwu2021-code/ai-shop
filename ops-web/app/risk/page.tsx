"use client";

// 风控（矩阵 P-16.2）。三类事件同表用 type 区分：结构一致，
// 拆三张表会让「这个用户同时命中几类」看不出来 —— 而那恰恰最该优先处理。
//
// 事件的证据 refs 指向归因链路号（/growth?tab=traces），点过去能看「人是怎么进来的」。
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { fill, useCopy } from "@/lib/use-copy";
import { RISK_COPY } from "./copy";
import { usePaging } from "@/lib/use-paging";
import { usePageTab, useNavTabs } from "@/lib/use-page-tab";
import { fmtTime } from "@/lib/utils";
import { useCan } from "@/lib/use-can";
import { notify } from "@/lib/notify";
import type { BlacklistEntry, RiskEvent, RiskRule, RiskType, SubjectType } from "@/lib/types";
import { RiskStatusBadge, useBlacklistAppealMap, useRiskStatusMap, useRiskTypeMap } from "@/components/status";
import { ReadOnlyNotice } from "@/components/read-only-notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input, Select } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { HelpNote } from "@/components/ui/help-note";
import { StatRow, Pagination, StatCard } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { Switch } from "@/components/ui/switch";
import { TabHeader } from "@/components/ui/tab-header";
import { Textarea } from "@/components/ui/textarea";
import { Toolbar } from "@/components/ui/toolbar";

type Copy = (typeof RISK_COPY)["zh"];
const TAB_KEYS = ["events", "blacklist", "rules"] as const;

const SUBJECT_LABEL = (c: Copy): Record<SubjectType, string> => ({ USER: c.subjectUser, MERCHANT: c.subjectMerchant, DEVICE: c.subjectDevice });
const SUBJECT_OPTIONS = (c: Copy) => (Object.keys(SUBJECT_LABEL(c)) as SubjectType[]).map((v) => ({ value: v, label: SUBJECT_LABEL(c)[v] }));

export default function RiskPage() {
  return <Suspense fallback={null}><RiskInner /></Suspense>;
}

function RiskInner() {
  const c = useCopy(RISK_COPY);
  const tabs = useNavTabs("/risk", TAB_KEYS);
  const subjectLabel = SUBJECT_LABEL(c);
  const subjectOptions = SUBJECT_OPTIONS(c);
  const qc = useQueryClient();
  const allow = useCan();

  const [tab, setTab] = usePageTab(tabs, () => { setPage(1); setKeyword(""); setType(""); setStatus(""); });

  const { page, setPage, size, setSize } = usePaging();
  const [keyword, setKeyword] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [subjectType, setSubjectType] = useState("");
  const [activeOnly, setActiveOnly] = useState("");
  const [current, setCurrent] = useState<RiskEvent | null>(null);
  const [verdict, setVerdict] = useState("");
  const [appeal, setAppeal] = useState<BlacklistEntry | null>(null);
  const [appealVerdict, setAppealVerdict] = useState("");
  const [adding, setAdding] = useState(false);
  const [addForm, setAddForm] = useState({ subjectType: "USER" as SubjectType, subject: "", reason: "", until: "2026-12-31T00:00:00Z" });

  const canUpdateRule = allow("risk:rule:update");
  const canBlacklist = allow("risk:blacklist:update");
  const typeMap = useRiskTypeMap();
  const statusMap = useRiskStatusMap();
  const appealMap = useBlacklistAppealMap();

  const eventQ = { keyword, type, status, page, size };
  const events = useQuery({ queryKey: ["risk-events", eventQ], queryFn: () => api.listRiskEvents(eventQ), enabled: tab === "events" });
  const blackQ = { keyword, subjectType, activeOnly, page, size };
  const blacks = useQuery({ queryKey: ["blacklists", blackQ], queryFn: () => api.listBlacklists(blackQ), enabled: tab === "blacklist" });
  const rules = useQuery({ queryKey: ["risk-rules"], queryFn: () => api.listRiskRules(), enabled: tab === "rules" });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["risk-events"] });
    qc.invalidateQueries({ queryKey: ["blacklists"] });
    qc.invalidateQueries({ queryKey: ["risk-rules"] });
  };

  const decide = useMutation({
    mutationFn: (v: { eventNo: string; confirmed: boolean }) => api.decideRiskEvent(v.eventNo, v.confirmed, verdict),
    onSuccess: (e) => {
      invalidate(); setCurrent(null); setVerdict("");
      notify.success(e.status === "CONFIRMED" ? c.toastConfirmed : c.toastDismissed);
    },
  });
  const addBlack = useMutation({
    mutationFn: () => api.addBlacklist(addForm),
    onSuccess: () => { invalidate(); setAdding(false); notify.success(c.toastBlacklisted); },
  });
  const decideAppeal = useMutation({
    mutationFn: (v: { blackNo: string; accept: boolean }) => api.decideBlacklistAppeal(v.blackNo, v.accept, appealVerdict),
    onSuccess: (b) => {
      invalidate(); setAppeal(null); setAppealVerdict("");
      notify.success(b.appealStatus === "UPHELD" ? c.toastAppealAccepted : c.toastAppealRejected);
    },
  });
  const saveRule = useMutation({
    mutationFn: (v: { type: RiskType; threshold: number; autoBlock: boolean }) => api.saveRiskRule(v.type, v.threshold, v.autoBlock),
    onSuccess: () => { invalidate(); notify.success(c.toastRuleSaved); },
  });

  const [ruleForm, setRuleForm] = useState<Record<string, string>>({});

  // 工具条与空态指向同一个动作 —— 内联两份迟早会漂
  const openAddBlack = () => {
    setAdding(true);
    setAddForm({ subjectType: "USER", subject: "", reason: "", until: "2026-12-31T00:00:00Z" });
  };

  const eventColumns: Column<RiskEvent>[] = [
    { header: c.colEventNo, cell: (e) => e.eventNo, numeric: true, align: "start" },
    { header: c.colType, cell: (e) => <StatusBadge map={typeMap} value={e.type} /> },
    { header: c.colSubject, cell: (e) => fill(c.subjectText, { kind: subjectLabel[e.subjectType], id: e.subject }) },
    {
      header: c.colSignals,
      width: "24rem",
      className: "whitespace-normal",
      // 不给分值：分值口径要等有真实样本后由风控定，编一个看起来很准的分数只会让人照着它做决定
      cell: (e) => (
        <span className="flex flex-wrap gap-1">
          {e.signals.map((s) => <Badge key={s} tone="warning">{s}</Badge>)}
        </span>
      ),
    },
    { header: c.colFoundAt, cell: (e) => fmtTime(e.createdAt) },
    { header: c.colStatus, cell: (e) => <RiskStatusBadge value={e.status} /> },
    {
      header: c.colActions,
      cell: (e) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(e); setVerdict(e.verdict ?? ""); }}>
          {e.status === "PENDING" && canBlacklist ? c.actionHandle : c.actionView}
        </Button>
      ),
    },
  ];

  const blackColumns: Column<BlacklistEntry>[] = [
    { header: c.colBlackNo, cell: (b) => b.blackNo, numeric: true, align: "start" },
    { header: c.colSubject, cell: (b) => fill(c.subjectText, { kind: subjectLabel[b.subjectType], id: b.subject }) },
    { header: c.colReason, cell: (b) => b.reason, className: "whitespace-normal", width: "20rem" },
    { header: c.colUntil, cell: (b) => fmtTime(b.until) },
    { header: c.colAppeal, cell: (b) => <StatusBadge map={appealMap} value={b.appealStatus} /> },
    {
      header: c.colActive,
      cell: (b) => (b.active ? <Badge tone="danger">{c.yes}</Badge> : <span className="text-muted-foreground">{c.no}</span>),
    },
    {
      header: c.colActions,
      cell: (b) =>
        b.appealStatus === "PENDING" && canBlacklist ? (
          <Button size="sm" onClick={() => { setAppeal(b); setAppealVerdict(""); }}>{c.actionDecideAppeal}</Button>
        ) : <span className="text-muted-foreground">—</span>,
    },
  ];

  const rows = events.data?.records ?? [];
  const openCount = rows.filter((e) => e.status === "PENDING").length;

  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} />

      {tab !== "rules" && !canBlacklist && (
        <ReadOnlyNotice what={c.readOnlyWhat} perm="risk:blacklist:update" note={c.readOnlyNote} className="mb-3" />
      )}

      {tab === "events" && (
        <>
          <StatRow>
            <StatCard label={c.kpiOpen} value={openCount} sub={openCount > 0 ? c.kpiOpenSub : c.kpiOpenNone} tone={openCount > 0 ? "down" : undefined} />
            <StatCard label={c.kpiPageCount} value={rows.length} />
            <StatCard label={c.kpiActiveBlack} value={blacks.data?.total ?? "—"} />
          </StatRow>
          <HelpNote className="mb-3">
            {c.eventsNotice}
          </HelpNote>
          <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchEvents}>
            <FilterSelect aria-label={c.filterType} value={type} onChange={(v) => { setType(v); setPage(1); }} options={typeMap} allLabel={c.filterTypeAll} />
            <FilterSelect aria-label={c.filterStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }} options={statusMap} allLabel={c.filterStatusAll} />
          </Toolbar>
          <DataTable
            columns={eventColumns} rows={events.data?.records} loading={events.isLoading}
            error={events.error} onRetry={() => events.refetch()}
            rowKey={(e) => e.eventNo}
            empty={c.emptyEvents}
          />
          <Pagination page={page} size={size} onSize={setSize} total={events.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "blacklist" && (
        <>
          <HelpNote className="mb-3">
            {c.blacklistNotice}
          </HelpNote>
          <Toolbar
            search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }}
            searchPlaceholder={c.searchBlacklist}
            onAdd={canBlacklist ? openAddBlack : undefined}
            addLabel={c.addLabel}
          >
            <FilterSelect aria-label={c.filterSubject} value={subjectType} onChange={(v) => { setSubjectType(v); setPage(1); }} options={subjectOptions} allLabel={c.filterSubjectAll} />
            <FilterSelect aria-label={c.filterActive} value={activeOnly} onChange={(v) => { setActiveOnly(v); setPage(1); }}
              options={[{ value: "1", label: c.filterActiveOnly }]} allLabel={c.filterActiveAll} />
          </Toolbar>
          <DataTable
            columns={blackColumns} rows={blacks.data?.records} loading={blacks.isLoading}
            error={blacks.error} onRetry={() => blacks.refetch()}
            rowKey={(b) => b.blackNo}
            empty={c.emptyBlacklist}
            emptyAction={canBlacklist ? <Button size="sm" onClick={openAddBlack}>{c.addLabel}</Button> : undefined}
          />
          <Pagination page={page} size={size} onSize={setSize} total={blacks.data?.total ?? 0} onPage={setPage} />
        </>
      )}

      {tab === "rules" && (
        <Card className="max-w-2xl">
          <CardHeader><CardTitle>{c.rulesTitle}</CardTitle></CardHeader>
          <CardContent>
            {!canUpdateRule && <ReadOnlyNotice what={c.rulesReadOnlyWhat} perm="risk:rule:update" className="mb-3" />}
            <HelpNote className="mb-4">
              {c.rulesNotice}
            </HelpNote>
            <div className="space-y-5">
              {(rules.data ?? []).map((r: RiskRule) => (
                <div key={r.type} className="rounded-card bg-secondary p-4">
                  <div className="mb-2 flex items-center gap-2">
                    <StatusBadge map={typeMap} value={r.type} />
                    <span className="txt-caption text-muted-foreground">{fill(c.ruleUpdatedAt, { time: fmtTime(r.updatedAt) })}</span>
                  </div>
                  <div className="flex flex-wrap items-end gap-3">
                    <div className="space-y-1">
                      <Label htmlFor={`th-${r.type}`}>{c.fieldThreshold}</Label>
                      <Input
                        id={`th-${r.type}`} className="w-32" disabled={!canUpdateRule}
                        value={ruleForm[r.type] ?? String(r.threshold)}
                        onChange={(e) => setRuleForm({ ...ruleForm, [r.type]: e.target.value })}
                      />
                    </div>
                    <div className="flex items-center gap-2 pb-2">
                      <Switch
                        checked={r.autoBlock} disabled={!canUpdateRule} aria-label={fill(c.ariaAutoBlock, { type: r.type })}
                        onChange={(v) => saveRule.mutate({ type: r.type, threshold: Number(ruleForm[r.type] ?? r.threshold), autoBlock: v })}
                      />
                      <span className="txt-body">{c.autoBlock}</span>
                    </div>
                    <Button
                      size="sm" className="mb-2" disabled={!canUpdateRule}
                      onClick={() => saveRule.mutate({ type: r.type, threshold: Number(ruleForm[r.type] ?? r.threshold), autoBlock: r.autoBlock })}
                    >
                      {c.save}
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* 事件处置 */}
      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current ? `${current.eventNo} · ${current.subject}` : ""}
        desc={current ? subjectLabel[current.subjectType] : undefined}
        width="w-[520px]"
        footer={
          current?.status === "PENDING" && canBlacklist ? (
            <>
              <Button variant="outline" onClick={() => decide.mutate({ eventNo: current.eventNo, confirmed: false })}>{c.btnDismiss}</Button>
              <Button onClick={() => decide.mutate({ eventNo: current.eventNo, confirmed: true })}>{c.btnConfirmRisk}</Button>
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <Field label={c.colType}><StatusBadge map={typeMap} value={current.type} /></Field>
            <Field label={c.fieldSignals}>
              <ul className="list-inside list-disc space-y-1">
                {current.signals.map((s) => <li key={s}>{s}</li>)}
              </ul>
            </Field>
            <Field label={c.fieldEvidence}>
              {current.refs.length ? (
                <div className="space-y-1">
                  {current.refs.map((r) => (
                    <div key={r}>
                      <code className="txt-caption">{r}</code>
                      {/* 归因链路号能跳过去看「人是怎么进来的」—— 异常裂变的判断就靠它 */}
                      {r.startsWith("AT") && (
                        <a className="focus-ring ms-2 text-[var(--primary)] underline" href={`/growth?tab=traces&keyword=${r}`}>
                          {c.linkTrace}
                        </a>
                      )}
                    </div>
                  ))}
                </div>
              ) : c.none}
            </Field>
            <Field label={c.fieldVerdict}>
              {current.status === "PENDING" && canBlacklist ? (
                <Textarea value={verdict} onChange={setVerdict}
                  placeholder={c.verdictPlaceholder} />
              ) : (
                current.verdict || "—"
              )}
            </Field>
          </div>
        )}
      </Drawer>

      {/* 加入黑名单 */}
      <Drawer
        open={adding}
        onOpenChange={(o) => !o && setAdding(false)}
        title={c.addTitle}
        footer={<Button loading={addBlack.isPending} onClick={() => addBlack.mutate()}>{c.btnConfirmBlack}</Button>}
      >
        <div className="space-y-4">
          <div className="space-y-1">
            <Label htmlFor="bl-type">{c.fieldSubjectType}</Label>
            <Select id="bl-type" className="w-full" value={addForm.subjectType}
              onChange={(e) => setAddForm({ ...addForm, subjectType: e.target.value as SubjectType })}>
              {subjectOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </Select>
          </div>
          <div className="space-y-1">
            <Label htmlFor="bl-subject" required>{c.fieldSubject}</Label>
            <Input id="bl-subject" className="w-full" placeholder={c.subjectPlaceholder} value={addForm.subject}
              onChange={(e) => setAddForm({ ...addForm, subject: e.target.value })} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="bl-reason" required>{c.fieldBlackReason}</Label>
            <Textarea value={addForm.reason} onChange={(v) => setAddForm({ ...addForm, reason: v })}
              placeholder={c.blackReasonPlaceholder} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="bl-until" required>{c.fieldUntil}</Label>
            <Input id="bl-until" className="w-full" value={addForm.until}
              onChange={(e) => setAddForm({ ...addForm, until: e.target.value })} />
            <p className="txt-caption text-muted-foreground">{c.untilHint}</p>
          </div>
        </div>
      </Drawer>

      {/* 申诉裁决 */}
      <Drawer
        open={!!appeal}
        onOpenChange={(o) => !o && setAppeal(null)}
        title={appeal ? fill(c.appealTitle, { subject: appeal.subject }) : ""}
        desc={appeal?.blackNo}
        footer={
          appeal && canBlacklist ? (
            <>
              <Button variant="outline" onClick={() => decideAppeal.mutate({ blackNo: appeal.blackNo, accept: false })}>{c.btnRejectAppeal}</Button>
              <Button onClick={() => decideAppeal.mutate({ blackNo: appeal.blackNo, accept: true })}>{c.btnAcceptAppeal}</Button>
            </>
          ) : null
        }
      >
        {appeal && (
          <div>
            <Field label={c.fieldBlackReason}>{appeal.reason}</Field>
            <Field label={c.colUntil}>{fmtTime(appeal.until)}</Field>
            <Field label={c.fieldAppealReason}><p className="whitespace-pre-wrap">{appeal.appealReason ?? "—"}</p></Field>
            <Field label={c.fieldAppealVerdict}>
              <Textarea value={appealVerdict} onChange={setAppealVerdict}
                placeholder={c.appealVerdictPlaceholder} />
            </Field>
          </div>
        )}
      </Drawer>
    </div>
  );
}
