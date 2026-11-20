"use client";

// 请求日志（SY06）、渠道调用 / 渠道回调（SY07）：只读，码 pay:trace:view。报文一律是 near-pay 脱敏后的版本。
// 三个页签同一个骨架：表格 + 「查看」详情抽屉（详情都不是标准 Detail，各自取）。
import * as React from "react";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { DetailBody } from "@/components/ui/detail-drawer";
import type { ActionSpec } from "@/components/ui/action-host";
import type { Column } from "@/components/ui/data-table";
import { PayListPage, PayRefCell } from "@/app/payment-center/_kit/pay-kit";
import type { ChannelCallRow, ChannelCallbackRow, RequestLogRow } from "@/app/payment-center/_api/types";
import { fmtTime } from "@/lib/utils";
import { DetailLoader, MiniTable, useStatusMap, useTabList, usePc, type C } from "./shared";

const ENTRIES = ["OPEN_API", "TERMINAL_API", "INTERNAL_PORT", "INTERNAL_HTTP", "BIZ_API", "OPS_API", "CHECKOUT"] as const;
const AUTH = ["ACCEPTED", "REJECTED_AUTH", "REJECTED_PARAM", "REJECTED_FORBIDDEN", "DUPLICATE", "ERROR"] as const;
const CALL_ST = ["SUCCEEDED", "PENDING", "FAILED", "UNKNOWN"] as const;
const VERIFY = ["PENDING", "PASSED", "FAILED", "MERCHANT_MISMATCH", "STALE"] as const;
const OUTCOME = ["RECEIVED", "APPLIED", "DUPLICATE", "REJECTED", "ERROR"] as const;

const Payload = ({ text }: { text: string | null | undefined }) =>
  <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-field bg-muted/50 p-3 font-mono txt-caption">{text || "—"}</pre>;

function useMaps() {
  return {
    auth: useStatusMap("auth", AUTH, { ACCEPTED: "success", REJECTED_AUTH: "danger", REJECTED_FORBIDDEN: "danger", REJECTED_PARAM: "warning", ERROR: "danger" }),
    call: useStatusMap("callst", CALL_ST, { SUCCEEDED: "success", FAILED: "danger", UNKNOWN: "warning", PENDING: "info" }),
    verify: useStatusMap("verifyres", VERIFY, { PASSED: "success", FAILED: "danger", MERCHANT_MISMATCH: "danger", STALE: "warning" }),
    outcome: useStatusMap("outcome", OUTCOME, { APPLIED: "success", REJECTED: "danger", ERROR: "danger", DUPLICATE: "muted" }),
  };
}

const callColumns = (c: C, m: ReturnType<typeof useMaps>): Column<ChannelCallRow>[] => [
  { header: c.callNo, cell: (r) => <PayRefCell value={r.callNo} /> },
  { header: c.colChannel, cell: (r) => r.payChannel },
  { header: c.actionCol, cell: (r) => r.action },
  { header: c.bizNo, cell: (r) => r.bizNo ?? "—" },
  { header: c.resultCode, cell: (r) => r.resultCode ?? "—" },
  { header: c.latency, numeric: true, cell: (r) => r.latencyMs ?? "—" },
  { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={m.call} /> },
  { header: c.occurredAt, cell: (r) => fmtTime(r.occurredAt) },
];
const callbackColumns = (c: C, m: ReturnType<typeof useMaps>): Column<ChannelCallbackRow>[] => [
  { header: c.callbackNo, cell: (r) => <PayRefCell value={r.callbackNo} /> },
  { header: c.colChannel, cell: (r) => r.payChannel },
  { header: c.notifyType, cell: (r) => r.notifyType },
  { header: c.verifyResult, cell: (r) => <StatusBadge value={r.verifyResult} map={m.verify} /> },
  { header: c.outcome, cell: (r) => <StatusBadge value={r.outcome} map={m.outcome} /> },
  { header: c.bizNo, cell: (r) => r.bizNo ?? "—" },
  { header: c.occurredAt, cell: (r) => fmtTime(r.occurredAt) },
];

export function RequestLogsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useMaps();
  const entryMap = Object.fromEntries(ENTRIES.map((e) => [e, { label: e, tone: "muted" as const }]));
  // near-pay 按 requestId 精确查，搜索框即请求号
  const list = useTabList<RequestLogRow, { entry: string; authResult: string }>("request-logs", { entry: "", authResult: "" }, (api, q) => api.traces.requestLogs({ ...q, keyword: undefined, requestId: q.keyword }));
  const { api } = list;
  const actions: ActionSpec<RequestLogRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.requestLogDetailTitle,
    render: (ctx) => (
      <DetailLoader name="request-logs" id={ctx.row!.requestId} load={() => api.traces.requestLog(ctx.row!.requestId)}>
        {(d) => <DetailBody sections={[
          { title: c.sectionBasic, fields: [
            { label: c.requestId, value: <PayRefCell value={d.record.requestId} /> },
            { label: c.authResult, value: <StatusBadge value={d.record.authResult} map={m.auth} /> },
            { label: c.entry, value: d.record.entry },
            { label: c.appNo, value: d.record.appNo },
            { label: c.path, value: <span className="break-all font-mono txt-caption">{d.record.method} {d.record.path}</span>, full: true },
            { label: c.httpStatus, value: d.record.httpStatus },
            { label: c.resultCode, value: d.record.businessCode },
            { label: c.latency, value: d.record.latencyMs },
            { label: c.occurredAt, value: fmtTime(d.record.occurredAt) },
          ] },
          { title: c.sectionRequest, content: <Payload text={d.requestMasked} /> },
          { title: c.sectionResponse, content: <Payload text={d.responseMasked} /> },
          { title: c.sectionRelatedCalls, content: <MiniTable rows={d.calls} rowKey={(r) => `${r.callNo}:${r.attempt}`} empty={c.emptyRelatedCalls} columns={callColumns(c, m).slice(0, 7)} /> },
          { title: c.sectionRelatedCallbacks, content: <MiniTable rows={d.callbacks} rowKey={(r) => r.callbackNo} empty={c.emptyRelatedCallbacks} columns={callbackColumns(c, m).slice(0, 6)} /> },
        ]} />}
      </DetailLoader>
    ),
  }];
  const columns: Column<RequestLogRow>[] = [
    { header: c.requestId, cell: (r) => <PayRefCell value={r.requestId} /> },
    { header: c.entry, cell: (r) => r.entry },
    { header: c.path, cell: (r) => <span className="font-mono txt-caption">{r.method} {r.path}</span> },
    { header: c.httpStatus, numeric: true, cell: (r) => r.httpStatus ?? "—" },
    { header: c.authResult, cell: (r) => <StatusBadge value={r.authResult} map={m.auth} /> },
    { header: c.latency, numeric: true, cell: (r) => r.latencyMs ?? "—" },
    { header: c.occurredAt, cell: (r) => fmtTime(r.occurredAt) },
  ];
  return (
    <PayListPage<RequestLogRow> header={header} help={c.requestLogsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.requestLogsSearch}
      filters={<>
        <FilterSelect value={list.filters.entry} onChange={(v) => list.setFilter("entry", v)} options={entryMap} allLabel={c.entry} />
        <FilterSelect value={list.filters.authResult} onChange={(v) => list.setFilter("authResult", v)} options={m.auth} allLabel={c.authResult} />
      </>}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={columns} rowKey={(r) => r.requestId} actions={actions} empty={c.emptyRequestLogs} />
  );
}

export function ChannelCallsTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useMaps();
  // 搜索框按业务号精确查（near-pay 的 bizNo 参数）；调用号直达用详情链接
  const list = useTabList<ChannelCallRow, { status: string }>("channel-calls", { status: "" }, (api, q) => api.traces.calls({ ...q, keyword: undefined, bizNo: q.keyword }));
  const { api } = list;
  const actions: ActionSpec<ChannelCallRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.callDetailTitle,
    render: (ctx) => (
      <DetailLoader name="channel-calls" id={ctx.row!.callNo} load={() => api.traces.call(ctx.row!.callNo)}>
        {(d) => <DetailBody sections={[
          { title: c.sectionBasic, fields: [
            { label: c.callNo, value: <PayRefCell value={d.callNo} /> },
            { label: c.colStatus, value: <StatusBadge value={ctx.row!.status} map={m.call} /> },
            { label: c.colChannel, value: ctx.row!.payChannel },
            { label: c.actionCol, value: ctx.row!.action },
            { label: c.bizNo, value: ctx.row!.bizNo },
            { label: c.requestId, value: ctx.row!.requestId },
          ] },
          ...d.attempts.map((a, i) => ({
            title: `${c.sectionCallAttempts} #${a.record.attempt ?? i + 1}`,
            fields: [
              { label: c.urlPath, value: <span className="break-all font-mono txt-caption">{a.httpMethod} {a.urlPath}</span>, full: true },
              { label: c.httpStatus, value: a.httpStatus },
              { label: c.transport, value: a.record.transport },
              { label: c.breakerState, value: a.breakerState },
              { label: c.errorMessage, value: a.errorMessage, full: true },
            ],
            content: <><Payload text={a.requestMasked} /><div className="h-2" /><Payload text={a.responseMasked} /></>,
          })),
          ...(d.attempts.length ? [] : [{ title: c.sectionCallAttempts, content: <p className="txt-caption text-muted-foreground">{c.emptyCallAttempts}</p> }]),
          { title: c.sectionRelatedCallbacks, content: <MiniTable rows={d.callbacks} rowKey={(r) => r.callbackNo} empty={c.emptyRelatedCallbacks} columns={callbackColumns(c, m).slice(0, 6)} /> },
        ]} />}
      </DetailLoader>
    ),
  }];
  return (
    <PayListPage<ChannelCallRow> header={header} help={c.callsDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.callsSearch}
      filters={<FilterSelect value={list.filters.status} onChange={(v) => list.setFilter("status", v)} options={m.call} allLabel={c.allStatus} />}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={callColumns(c, m)} rowKey={(r) => `${r.callNo}:${r.attempt}`} actions={actions} empty={c.emptyCalls} />
  );
}

export function ChannelCallbacksTab({ header }: { header: React.ReactNode }) {
  const c = usePc();
  const m = useMaps();
  const list = useTabList<ChannelCallbackRow, { verifyResult: string; outcome: string }>("channel-callbacks", { verifyResult: "", outcome: "" }, (api, q) => api.traces.callbacks({ ...q, keyword: undefined, bizNo: q.keyword }));
  const { api } = list;
  const actions: ActionSpec<ChannelCallbackRow>[] = [{
    key: "view", label: c.view, kind: "drawer", width: "w-[720px]", title: () => c.callbackDetailTitle,
    render: (ctx) => (
      <DetailLoader name="channel-callbacks" id={ctx.row!.callbackNo} load={() => api.traces.callback(ctx.row!.callbackNo)}>
        {(d) => <DetailBody sections={[
          { title: c.sectionBasic, fields: [
            { label: c.callbackNo, value: <PayRefCell value={d.record.callbackNo} /> },
            { label: c.outcome, value: <StatusBadge value={d.record.outcome} map={m.outcome} /> },
            { label: c.colChannel, value: d.record.payChannel },
            { label: c.verifyResult, value: <StatusBadge value={d.record.verifyResult} map={m.verify} /> },
            { label: c.notifyType, value: d.record.notifyType },
            { label: c.bizNo, value: d.record.bizNo },
            { label: c.tradeNo, value: d.record.channelTradeNo },
            { label: c.occurredAt, value: fmtTime(d.record.occurredAt) },
            { label: c.outcomeReason, value: d.outcomeReason, full: true },
          ] },
          { title: c.sectionHeaders, content: <Payload text={d.headersMasked} /> },
          { title: c.sectionBody, content: <Payload text={d.bodyMasked} /> },
          { title: c.ackBody, content: <Payload text={d.ackBody} /> },
        ]} />}
      </DetailLoader>
    ),
  }];
  return (
    <PayListPage<ChannelCallbackRow> header={header} help={c.callbacksDesc} search={list.keyword} onSearch={list.setKeyword} searchPlaceholder={c.callbacksSearch}
      filters={<>
        <FilterSelect value={list.filters.verifyResult} onChange={(v) => list.setFilter("verifyResult", v)} options={m.verify} allLabel={c.verifyResult} />
        <FilterSelect value={list.filters.outcome} onChange={(v) => list.setFilter("outcome", v)} options={m.outcome} allLabel={c.outcome} />
      </>}
      query={list.query} page={list.page} size={list.size} onPage={list.setPage} onSize={list.setSize}
      columns={callbackColumns(c, m)} rowKey={(r) => r.callbackNo} actions={actions} empty={c.emptyCallbacks} />
  );
}
