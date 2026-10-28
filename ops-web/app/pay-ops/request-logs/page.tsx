"use client";
// 请求记录（开发计划 M5b.2）：读 pay_api_request_log，含内部调用来源；按追踪号把业务服务与支付两边串起来。报文是写入时就脱敏过的。
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PayOpsRequestLogQ, PayOpsRequestLogRow } from "@/lib/api/contracts/payops";
import { fmtTime } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input, Select } from "@/components/ui/input";
import { DataTable } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { TabHeader } from "@/components/ui/tab-header";
import { usePaging } from "@/lib/use-paging";
import { StatusBadge, useTT } from "../shared";

const ENTRIES = ["", "OPEN_API", "TERMINAL_API", "INTERNAL_HTTP", "INTERNAL_PORT", "BIZ_API", "OPS_API", "CHECKOUT", "CALLBACK"];
const OUTCOMES = ["", "ACCEPTED", "REJECTED_AUTH", "REJECTED_PARAM", "REJECTED_FORBIDDEN", "DUPLICATE", "ERROR"];

export default function PayOpsRequestLogsPage() {
  const tt = useTT();
  const [entry, setEntry] = useState("");
  const [outcome, setOutcome] = useState("");
  const [text, setText] = useState("");
  const [query, setQuery] = useState<PayOpsRequestLogQ>({});
  const { page, setPage, size } = usePaging(20);
  const [open, setOpen] = useState<number | null>(null);
  const list = useQuery({ queryKey: ["pay-ops-request-logs", entry, outcome, query, page, size],
    queryFn: () => api.payOpsRequestLogs({ entry: entry || undefined, outcome: outcome || undefined, ...query, page, size }) });
  const detail = useQuery({ queryKey: ["pay-ops-request-log", open], queryFn: () => api.payOpsRequestLog(open!), enabled: open != null });

  /** 一个框：T… 当追踪号，含 APP / 大写下划线当接入方，其余当来源服务 */
  function search() {
    const v = text.trim();
    setQuery(!v ? {} : v.startsWith("T") && v.length > 12 ? { traceId: v } : /^[A-Z0-9_-]+$/.test(v) ? { appNo: v } : { sourceService: v });
    setPage(1);
  }

  const columns = [
    { header: tt("时间", "Received"), cell: (r: PayOpsRequestLogRow) => fmtTime(r.receivedAt) },
    { header: tt("入口", "Entry"), cell: (r: PayOpsRequestLogRow) => r.entry },
    { header: tt("接口", "API"), cell: (r: PayOpsRequestLogRow) => <span dir="ltr">{r.api}</span> },
    { header: tt("调用方", "Caller"), cell: (r: PayOpsRequestLogRow) => <span dir="ltr">{r.appNo ?? "—"}</span> },
    { header: tt("来源服务", "Source"), cell: (r: PayOpsRequestLogRow) => r.sourceService
      ? <span dir="ltr">{`${r.sourceService}${r.sourceModule ? "/" + r.sourceModule : ""}`}</span> : "—" },
    { header: tt("操作人", "Operator"), cell: (r: PayOpsRequestLogRow) => r.operatorNo ?? "—" },
    { header: tt("耗时", "Elapsed"), numeric: true, cell: (r: PayOpsRequestLogRow) => r.elapsedMs == null ? "—" : `${r.elapsedMs} ms` },
    { header: tt("结果", "Outcome"), cell: (r: PayOpsRequestLogRow) => <StatusBadge status={r.outcome} /> },
  ];
  const d = detail.data;
  return <main className="space-y-3">
    <TabHeader tabs={[{ key: "logs", label: tt("请求记录", "Request logs") }]} value="logs" onChange={() => {}}
      desc={tt("每个入口的每一次请求，含内部调用的来源服务与发起端；同一追踪号在业务服务与支付两边是同一个。", "Every request at every entry, with the internal caller's source; one trace id spans both services.")} />
    <form className="flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); search(); }}>
      <label className="flex flex-col gap-1">{tt("入口", "Entry")}
        <Select value={entry} onChange={(e) => { setEntry(e.target.value); setPage(1); }}>
          {ENTRIES.map((x) => <option key={x} value={x}>{x || tt("全部", "All")}</option>)}
        </Select></label>
      <label className="flex flex-col gap-1">{tt("结果", "Outcome")}
        <Select value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(1); }}>
          {OUTCOMES.map((x) => <option key={x} value={x}>{x || tt("全部", "All")}</option>)}
        </Select></label>
      <label className="flex flex-col gap-1">{tt("追踪号 / 接入方 / 来源服务", "Trace id / app / source service")}
        <Input dir="ltr" value={text} onChange={(e) => setText(e.target.value)} placeholder="T… / APP-… / shop" /></label>
      <Button variant="outline" type="submit">{tt("查询", "Search")}</Button>
    </form>
    <DataTable columns={columns} rows={list.data} loading={list.isPending} error={list.error} onRetry={() => void list.refetch()}
      rowKey={(r) => String(r.id)} empty={tt("没有匹配的请求。", "No matching requests.")}
      rowProps={(r) => ({ role: "button", tabIndex: 0, className: "cursor-pointer hover:bg-accent/50", onClick: () => setOpen(r.id) })} />
    <div className="flex justify-end gap-2">
      <Button variant="outline" disabled={page === 1} onClick={() => setPage(page - 1)}>{tt("上一页", "Previous")}</Button>
      <Button variant="outline" disabled={(list.data?.length ?? 0) < size} onClick={() => setPage(page + 1)}>{tt("下一页", "Next")}</Button>
    </div>
    <Drawer open={open != null} onOpenChange={(o) => { if (!o) setOpen(null); }} title={d?.row.traceId ?? ""} width="w-[860px]"
      desc={tt("来源与脱敏后的报文", "Source and masked bodies")}>
      {detail.isPending && open != null && <p>{tt("加载中…", "Loading…")}</p>}
      {detail.error && <p className="text-destructive">{detail.error.message}</p>}
      {d && <div className="space-y-4">
        <dl className="grid grid-cols-[10rem_1fr] gap-x-4 gap-y-1">
          <dt>{tt("接口", "API")}</dt><dd dir="ltr">{d.row.api}</dd>
          <dt>{tt("来源服务 / 实例", "Source / instance")}</dt><dd dir="ltr">{[d.row.sourceService, d.row.sourceModule, d.row.sourceInstance].filter(Boolean).join(" · ") || "—"}</dd>
          <dt>{tt("来源地址", "Source IP")}</dt><dd dir="ltr">{d.row.sourceIp ?? d.row.clientIp ?? "—"}</dd>
          <dt>{tt("发起端 / 上游接口", "Origin")}</dt><dd dir="ltr">{[d.row.originClient, d.row.originApi].filter(Boolean).join(" · ") || "—"}</dd>
          <dt>{tt("签名密钥版本", "Key version")}</dt><dd>{d.row.signKeyVersion ?? "—"}</dd>
          <dt>{tt("HTTP / 结果码", "HTTP / code")}</dt><dd dir="ltr">{`${d.row.httpStatus ?? "—"} / ${d.row.resultCode ?? "—"}`}</dd>
        </dl>
        <section><h3 className="font-semibold">{tt("请求（已脱敏）", "Request (masked)")}</h3>
          <pre dir="ltr" className="whitespace-pre-wrap break-all rounded-field bg-muted p-2 txt-caption">{d.requestMasked ?? "—"}</pre></section>
        <section><h3 className="font-semibold">{tt("响应（已脱敏）", "Response (masked)")}</h3>
          <pre dir="ltr" className="whitespace-pre-wrap break-all rounded-field bg-muted p-2 txt-caption">{d.responseMasked ?? "—"}</pre></section>
      </div>}
    </Drawer>
  </main>;
}
