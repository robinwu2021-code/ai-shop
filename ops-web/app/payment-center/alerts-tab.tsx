"use client";

// 监控告警（SY10）：事件（默认，工作队列）与规则两个页签。
// 事件：查看 · 认领（确认）· 指派（抽屉）· 处置完成（抽屉：结论 + 关联单号 + 说明）· 关闭（理由弹框）。改派与处置别人认领的由 near-pay 判 escalate 码。
// 规则：查看（含历史版本）· 编辑（抽屉，改完回草稿）· 发布（理由弹框，审批码；发布人 ≠ 录入人）· 停用（理由弹框）。页面级：新增。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailActions, DetailBody } from "@/components/ui/detail-drawer";
import { ActionForm } from "@/components/ui/action-form";
import { Notice } from "@/components/ui/notice";
import type { FieldDef } from "@/components/ui/form-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell, useDraftOperator } from "@/app/payment-center/_kit/pay-kit";
import type { AlertRuleSave } from "@/app/payment-center/_api/api";
import { ALERT_EVENT_STATUSES, ALERT_METRICS, ALERT_OPERATORS, ALERT_RESOLUTIONS, ALERT_RULE_STATUSES, ALERT_SEVERITIES, type AlertEventRow, type AlertRuleRow } from "@/app/payment-center/_api/types";
import { useAuth } from "@/lib/auth";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, csv, joinCsv, options, reasonField, useRun, useStatusMap, useTabList, usePc, type C } from "./shared";

const OP_SIGN: Record<string, string> = { GT: ">", GTE: "≥", LT: "<", LTE: "≤" };
const SEV_TONE = { P1: "danger", P2: "warning", P3: "info" } as const;

export function AlertEventsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const me = useAuth((s) => s.username);
  const operator = useDraftOperator();
  const run = useRun("alert-events");
  const statusMap = useStatusMap("alertev", ALERT_EVENT_STATUSES, { OPEN: "warning", CLAIMED: "info", RESOLVED: "success" });
  const sevMap = useStatusMap("sev", ALERT_SEVERITIES, SEV_TONE);
  const metricMap = useStatusMap("metric", ALERT_METRICS);
  const resMap = useStatusMap("res", ALERT_RESOLUTIONS);
  const list = useTabList<AlertEventRow, { status: string; severity: string }>("alert-events", { status: "OPEN", severity: "" }, (api, q) => api.alerts.events(q));
  const { api } = list;
  const draft = (action: string, id: string) => (operator ? { operator, page: "pay-alert-events", action, id } : null);
  const versionOf = async (no: string) => (await api.alerts.event(no)).version;

  const actions: ActionSpec<AlertEventRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[560px]", title: () => c.alertEventDetailTitle,
      render: (ctx) => {
        const r = ctx.row!;
        return (
          <DetailBody
            sections={[{ title: c.sectionBasic, fields: [
              { label: c.eventNo, value: <PayRefCell value={r.eventNo} /> },
              { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
              { label: c.metric, value: metricMap[r.metric]?.label ?? r.metric },
              { label: c.severity, value: <StatusBadge value={r.severity} map={sevMap} /> },
              { label: c.conditionLabel, value: `${r.observedValue} ${OP_SIGN[r.operator] ?? r.operator} ${r.threshold}` },
              { label: c.scopeLabel, value: `${r.scopeType}${r.scopeRef ? ` · ${r.scopeRef}` : ""}` },
              { label: c.triggerCount, value: r.triggerCount },
              { label: c.ruleNo, value: `${r.ruleNo} · v${r.ruleRevisionNo}` },
              { label: c.firstFired, value: fmtTime(r.firstTriggeredAt) },
              { label: c.lastFired, value: fmtTime(r.firedAt) },
              { label: c.assignee, value: r.assigneeNo },
              { label: c.resolution, value: r.resolution ? resMap[r.resolution]?.label ?? r.resolution : "—" },
              { label: c.relatedRef, value: r.relatedRef },
              { label: c.resolutionNote, value: r.resolutionNote, full: true },
            ] }]}
            actions={<DetailActions ctx={ctx} keys={["close", "assign", "claim", "resolve"]} />}
          />
        );
      },
    },
    { key: "claim", label: c.claimAlert, kind: "confirm", perm: "pay:alert-event:handle", visible: (r) => r?.status === "OPEN",
      confirm: () => ({ title: c.claimAlertTitle }), run: (r) => run(async () => api.alerts.claim(r!.eventNo, { expectedVersion: await versionOf(r!.eventNo), reason: c.claimAlertReason })) },
    {
      key: "assign", label: c.assignAlert, kind: "drawer", perm: "pay:alert-event:handle", title: () => c.assignAlertTitle, visible: (r) => !!r && ["OPEN", "CLAIMED"].includes(r.status),
      render: (ctx) => (
        <ActionForm notify={false} fields={[{ key: "assigneeNo", label: c.assigneeNo, required: true, maxLength: 64 }, reasonField(c)]} initial={{ assigneeNo: "", reason: "" }}
          draft={draft("assign", ctx.row!.eventNo)} baseVersion={ctx.row!.version}
          onSubmit={(v) => run(async () => api.alerts.assign(ctx.row!.eventNo, { expectedVersion: await versionOf(ctx.row!.eventNo), assigneeNo: String(v.assigneeNo).trim(), reason: String(v.reason) }))}
          onDone={ctx.back ?? ctx.close} />
      ),
    },
    {
      key: "resolve", label: c.resolveAlert, kind: "drawer", perm: "pay:alert-event:handle", title: () => c.resolveAlertTitle,
      visible: (r) => r?.status === "OPEN" || (r?.status === "CLAIMED" && r.assigneeNo === me),
      render: (ctx) => (
        <ActionForm notify={false}
          fields={[
            { key: "resolution", label: c.resolution, type: "select", required: true, options: [{ value: "", label: "—" }, ...options(ALERT_RESOLUTIONS, (v) => resMap[v].label)] },
            { key: "relatedRef", label: c.relatedRef, maxLength: 64 },
            { ...reasonField(c), label: c.resolutionNote },
          ]}
          initial={{ resolution: "", relatedRef: "", reason: "" }}
          draft={draft("resolve", ctx.row!.eventNo)} baseVersion={ctx.row!.version}
          onSubmit={(v) => run(async () => api.alerts.resolve(ctx.row!.eventNo, { expectedVersion: await versionOf(ctx.row!.eventNo), resolution: String(v.resolution), relatedRef: String(v.relatedRef || "") || null, reason: String(v.reason) }))}
          onDone={ctx.back ?? ctx.close} />
      ),
    },
    { key: "close", label: c.closeAlert, kind: "dialog", perm: "pay:alert-event:handle", visible: (r) => r?.status === "RESOLVED",
      confirm: () => ({ title: c.closeAlertTitle, reasonHint: c.reasonHint }), run: (r, reason) => run(async () => api.alerts.close(r!.eventNo, { expectedVersion: await versionOf(r!.eventNo), reason })) },
  ];

  const columns: Column<AlertEventRow>[] = [
    { header: c.eventNo, cell: (r) => <PayRefCell value={r.eventNo} /> },
    { header: c.metric, cell: (r) => metricMap[r.metric]?.label ?? r.metric },
    { header: c.severity, cell: (r) => <StatusBadge value={r.severity} map={sevMap} /> },
    { header: c.conditionLabel, cell: (r) => `${r.observedValue} ${OP_SIGN[r.operator] ?? r.operator} ${r.threshold}` },
    { header: c.scopeLabel, cell: (r) => r.scopeRef ?? r.scopeType },
    { header: c.assignee, cell: (r) => r.assigneeNo ?? "—" },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.lastFired, cell: (r) => fmtTime(r.firedAt) },
  ];

  return (
    <PayListPage<AlertEventRow> header={header} help={c.alertEventsDesc}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.severity} onChange={(v) => list.setFilter("severity", v)} options={sevMap} allLabel={c.severity} />
      </>}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.eventNo} actions={actions} resolve={(id) => api.alerts.event(id).then((d) => d.record)} draftPage="pay-alert-events"
      empty={c.emptyAlertEvents} />
  );
}

function ruleFields(c: C, metricLabel: (v: string) => string, sevLabel: (v: string) => string): FieldDef[] {
  return [
    { key: "name", label: c.ruleName, required: true, maxLength: 64 },
    { key: "metric", label: c.metric, type: "select", required: true, options: [{ value: "", label: "—" }, ...options(ALERT_METRICS, metricLabel)] },
    { key: "scopeRef", label: c.scopeRef, help: c.scopeRefHelp, maxLength: 64 },
    { key: "operator", label: c.conditionLabel, type: "select", required: true, options: options(ALERT_OPERATORS, (v) => `${OP_SIGN[v]} ${v}`) },
    { key: "threshold", label: c.thresholdLabel, required: true, help: c.thresholdHelp, pattern: { re: "^-?\\d+(\\.\\d+)?$", msg: "95.5" } },
    { key: "windowSeconds", label: c.windowSeconds, type: "number", required: true, min: 60 },
    { key: "minSampleCount", label: c.minSamples, type: "number", required: true, min: 0 },
    { key: "consecutiveBreaches", label: c.consecutiveBreaches, type: "number", required: true, min: 1 },
    { key: "severity", label: c.severity, type: "select", required: true, options: options(ALERT_SEVERITIES, sevLabel) },
    { key: "notifyTargets", label: c.notifyTargets, help: c.notifyTargetsHelp },
    reasonField(c),
  ];
}
const ruleForm = (r: AlertRuleRow | null) => ({
  name: r?.name ?? "", metric: r?.metric ?? "", scopeRef: r?.scopeRef ?? "", operator: r?.operator ?? "GT", threshold: r?.threshold ?? "", windowSeconds: r?.windowSeconds ?? 300,
  minSampleCount: r?.minSampleCount ?? 20, consecutiveBreaches: r?.consecutiveBreaches ?? 2, severity: r?.severity ?? "P2", notifyTargets: joinCsv(r?.notifyTargets), reason: "",
});
/** 范围对象留空 = 整个实例；填了就是那个渠道（near-pay 的 scopeType 只有 INSTANCE / CHANNEL） */
const ruleSave = (v: Record<string, unknown>, expectedVersion: number): AlertRuleSave => {
  const ref = String(v.scopeRef ?? "").trim();
  return {
    expectedVersion, name: String(v.name).trim(), metric: String(v.metric), scopeType: ref ? "CHANNEL" : "INSTANCE", scopeRef: ref || null, operator: String(v.operator),
    threshold: String(v.threshold).trim(), windowSeconds: Number(v.windowSeconds), minSampleCount: Number(v.minSampleCount), consecutiveBreaches: Number(v.consecutiveBreaches),
    severity: String(v.severity), notifyTargets: csv(v.notifyTargets), reason: String(v.reason),
  };
};

export function AlertRulesTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const operator = useDraftOperator();
  const run = useRun("alert-rules");
  const statusMap = useStatusMap("alertrule", ALERT_RULE_STATUSES, { PUBLISHED: "success", DRAFT: "warning" });
  const sevMap = useStatusMap("sev", ALERT_SEVERITIES, SEV_TONE);
  const metricMap = useStatusMap("metric", ALERT_METRICS);
  const list = useTabList<AlertRuleRow, { status: string; metric: string }>("alert-rules", { status: "", metric: "" }, (api, q) => api.alerts.rules(q));
  const { api } = list;
  const draft = (action: string, id?: string) => (operator ? { operator, page: "pay-alert-rules", action, id } : null);
  const versionOf = async (no: string) => (await api.alerts.rule(no)).version;
  const fields = ruleFields(c, (v) => metricMap[v]?.label ?? v, (v) => v);

  const actions: ActionSpec<AlertRuleRow>[] = [
    {
      key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.alertRuleDetailTitle,
      render: (ctx) => (
        <DetailLoader name="alert-rules" id={ctx.row!.ruleNo} load={() => api.alerts.rule(ctx.row!.ruleNo)}>
          {(d) => {
            const r = d.record;
            return (
              <DetailBody
                sections={[
                  { title: c.sectionBasic, fields: [
                    { label: c.ruleNo, value: <PayRefCell value={r.ruleNo} /> },
                    { label: c.colStatus, value: <StatusBadge value={r.status} map={statusMap} /> },
                    { label: c.ruleName, value: r.name, full: true },
                    { label: c.metric, value: metricMap[r.metric]?.label ?? r.metric },
                    { label: c.severity, value: <StatusBadge value={r.severity} map={sevMap} /> },
                    { label: c.conditionLabel, value: `${OP_SIGN[r.operator] ?? r.operator} ${r.threshold}${r.unit ? ` ${r.unit}` : ""}` },
                    { label: c.scopeLabel, value: `${r.scopeType}${r.scopeRef ? ` · ${r.scopeRef}` : ""}` },
                    { label: c.windowSeconds, value: r.windowSeconds },
                    { label: c.minSamples, value: r.minSampleCount },
                    { label: c.consecutiveBreaches, value: r.consecutiveBreaches },
                    { label: c.notifyTargets, value: joinCsv(r.notifyTargets) || "—" },
                    { label: c.enteredBy, value: r.enteredBy },
                    { label: c.enabledBy, value: r.publishedBy },
                  ] },
                  { title: c.sectionRevisions, content: <MiniTable rows={d.revisions.filter((x) => x.revisionNo !== r.revisionNo)} rowKey={(x) => String(x.revisionNo)} empty={c.emptyRevisions} columns={[
                    { header: c.revision, cell: (x) => x.revisionNo },
                    { header: c.conditionLabel, cell: (x) => `${OP_SIGN[x.operator] ?? x.operator} ${x.threshold}` },
                    { header: c.colStatus, cell: (x) => <StatusBadge value={x.status} map={statusMap} /> },
                    { header: c.enabledBy, cell: (x) => x.publishedBy ?? "—" },
                  ]} /> },
                ]}
                actions={<DetailActions ctx={ctx} keys={d.allowedActions.map((a) => ({ update: "edit", enable: "publish", disable: "retire" } as Record<string, string>)[a] ?? a)} />}
              />
            );
          }}
        </DetailLoader>
      ),
    },
    {
      key: "edit", label: c.edit, kind: "drawer", perm: "pay:alert-rule:manage", width: "w-[560px]", title: () => c.editRuleTitle,
      render: (ctx) => (
        <>
          <Notice tone="warning" className="mb-4">{c.editRuleNote}</Notice>
          <ActionForm notify={false} fields={fields} initial={ruleForm(ctx.row)} isEdit draft={draft("edit", ctx.row!.ruleNo)} baseVersion={ctx.row!.version}
            onSubmit={(v) => run(async () => api.alerts.updateRule(ctx.row!.ruleNo, ruleSave(v, await versionOf(ctx.row!.ruleNo))))} onDone={ctx.back ?? ctx.close} />
        </>
      ),
    },
    { key: "publish", label: c.publishRule, kind: "dialog", perm: "pay:alert-rule:approve", visible: (r) => r?.status === "DRAFT",
      confirm: () => ({ title: c.publishRuleTitle, desc: c.publishRuleDesc, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => api.alerts.enableRule(r!.ruleNo, { expectedVersion: await versionOf(r!.ruleNo), reason })) },
    { key: "retire", label: c.retireRule, kind: "dialog", perm: "pay:alert-rule:manage", danger: true, visible: (r) => r?.status === "PUBLISHED",
      confirm: () => ({ title: c.retireRuleTitle, danger: true, reasonHint: c.reasonHint }),
      run: (r, reason) => run(async () => api.alerts.disableRule(r!.ruleNo, { expectedVersion: await versionOf(r!.ruleNo), reason })) },
  ];

  const pageActions: ActionSpec<AlertRuleRow>[] = [{
    key: "create", label: c.create, kind: "drawer", perm: "pay:alert-rule:manage", width: "w-[560px]", title: () => c.createRuleTitle,
    render: (ctx) => (
      <ActionForm notify={false} fields={fields} initial={ruleForm(null)} draft={draft("create")}
        onSubmit={(v) => run(() => api.alerts.createRule(ruleSave(v, 0)))} onDone={ctx.close} />
    ),
  }];

  const columns: Column<AlertRuleRow>[] = [
    { header: c.ruleNo, cell: (r) => <PayRefCell value={r.ruleNo} /> },
    { header: c.ruleName, cell: (r) => r.name },
    { header: c.metric, cell: (r) => metricMap[r.metric]?.label ?? r.metric },
    { header: c.conditionLabel, cell: (r) => `${OP_SIGN[r.operator] ?? r.operator} ${r.threshold}` },
    { header: c.severity, cell: (r) => <StatusBadge value={r.severity} map={sevMap} /> },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
  ];

  return (
    <PayListPage<AlertRuleRow> header={header} help={c.alertRulesDesc}
      filters={<>
        <FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={statusMap} allLabel={c.allStatus} />
        <FilterSelect value={list.filters.metric} onChange={(v) => list.setFilter("metric", v)} options={metricMap} allLabel={c.metric} />
      </>}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.ruleNo} actions={actions} pageActions={pageActions}
      resolve={(id) => api.alerts.rule(id).then((d) => d.record)} draftPage="pay-alert-rules" empty={c.emptyAlertRules} />
  );
}
