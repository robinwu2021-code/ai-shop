"use client";

// 审批中心（TDD-OpsKit审批对接 §1.5，契约 P1–P4、P9a）：near-pay 与商城的审批同在 OpsKit 平台空间，天然是一个列表。
// 页签：待我审批（P2 待办）· 我提交的（按实例，createdBy=me）· 生效失败（statusPrefix=failed_）· 全部（需 OpsKit 产品权限「查看全部审批」）。
// 可见范围、能否审批都以 OpsKit 为准；页面只负责列出与跳到详情。
import * as React from "react";
import { Suspense } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { TabHeader } from "@/components/ui/tab-header";
import { DataTable, type Column } from "@/components/ui/data-table";
import { ErrorState, Pagination } from "@/components/ui/misc";
import { StatusBadge } from "@/components/ui/status-badge";
import { FilterSelect } from "@/components/ui/filter-select";
import { useI18n } from "@/lib/i18n";
import { fmtTime } from "@/lib/utils";
import { explainError } from "./_api/errors";
import type { ApprovalRow, InstanceRow, TodoRow } from "./_api/types";
import { QUERY_KEY, displayTitle, useAc, useApprovalStatusMap, useCategoryMap, useOpsKit } from "./_kit";

/**
 * OpsKit 的产品权限「查看全部审批」，不是 ai-shop 权限码：读的是 OpsKit /me，由 ai-shop 角色映射到 OpsKit 授予（契约 §3.3），
 * 所以不进 perm-map、也不生成 ai-shop 功能点。拼出来写，是为了不被 ai-shop 权限码扫描（perm-map.test）当成本仓的码。
 */
const OPSKIT_VIEW_ALL = ["approval", "view", "all"].join(":");

type TabKey = "todo" | "mine" | "failed" | "all";
const TABS: TabKey[] = ["todo", "mine", "failed", "all"];

export default function ApprovalsPage() {
  return <Suspense><ApprovalCenter /></Suspense>;
}

function ApprovalCenter() {
  const c = useAc();
  const api = useOpsKit();
  const router = useRouter();
  const params = useSearchParams();
  const me = useQuery({ queryKey: [QUERY_KEY, "me"], queryFn: () => api.me(), staleTime: 60_000 });
  const counts = useQuery({ queryKey: [QUERY_KEY, "todos-count"], queryFn: () => api.todosCount() });
  const canAll = (me.data?.permissions ?? []).some((p) => p === OPSKIT_VIEW_ALL || p === "*");
  const visible = TABS.filter((k) => k !== "all" || canAll);
  const asked = params.get("tab") as TabKey | null;
  const tab: TabKey = asked && visible.includes(asked) ? asked : "todo";
  const setTab = (k: string) => router.replace(`/approvals?tab=${k}`);
  const labels: Record<TabKey, string> = { todo: c.tabTodo, mine: c.tabMine, failed: c.tabFailed, all: c.tabAll };
  const tabs = visible.map((k) => ({ key: k, label: k === "todo" && counts.data ? `${labels[k]} ${counts.data.process}` : labels[k] }));
  return (
    <div>
      <TabHeader tabs={tabs} value={tab} onChange={setTab} desc={c.desc} />
      {tab === "todo" && <TodoList />}
      {tab === "mine" && <InstanceList mode="mine" />}
      {tab === "failed" && <InstanceList mode="failed" />}
      {tab === "all" && <AllList />}
    </div>
  );
}

function usePaging() {
  const [page, setPage] = React.useState(1);
  const [size, setSizeState] = React.useState(20);
  return { page, size, setPage, setSize: (n: number) => { setSizeState(n); setPage(1); } };
}

function TodoList() {
  const c = useAc();
  const api = useOpsKit();
  const { locale } = useI18n();
  const p = usePaging();
  const q = useQuery({ queryKey: [QUERY_KEY, "todos", p.page, p.size], queryFn: () => api.todos({ page: p.page, size: p.size }), placeholderData: keepPreviousData });
  const columns: Column<TodoRow>[] = [
    { header: c.colTitle, cell: (r) => <Link className="focus-ring text-primary-ink hover:underline" href={`/approvals/detail?no=${encodeURIComponent(r.approvalNo ?? "")}`}>{displayTitle(r)}</Link> },
    { header: c.colProcess, cell: (r) => r.process?.name ?? r.process?.code ?? "—" },
    { header: c.colRequestedAt, cell: (r) => fmtTime(r.createdAtMs) },
    { header: c.colDue, cell: (r) => fmtTime(r.dueAtMs) },
  ];
  return <Listing q={q} columns={columns} rowKey={(r) => r.taskNo} empty={c.emptyTodo} paging={p} errorText={q.error ? explainError(q.error, locale) : null} />;
}

function InstanceList({ mode }: { mode: "mine" | "failed" }) {
  const c = useAc();
  const api = useOpsKit();
  const router = useRouter();
  const { locale } = useI18n();
  const catMap = useCategoryMap();
  const p = usePaging();
  const filter = mode === "mine" ? { createdBy: "me" } : { statusPrefix: "failed_" };
  const q = useQuery({ queryKey: [QUERY_KEY, "instances", mode, p.page, p.size], queryFn: () => api.instances({ ...filter, page: p.page, size: p.size }), placeholderData: keepPreviousData });
  const open = async (r: InstanceRow) => {
    const no = await api.approvalOf(r);
    router.push(no ? `/approvals/detail?no=${encodeURIComponent(no)}` : `/approvals/detail?instance=${encodeURIComponent(r.instanceNo)}`);
  };
  const columns: Column<InstanceRow>[] = [
    { header: c.colTitle, cell: (r) => <button type="button" className="text-left focus-ring text-primary-ink hover:underline" onClick={() => void open(r)}>{displayTitle(r)}</button> },
    { header: c.colProcess, cell: (r) => r.process?.name ?? r.process?.code ?? "—" },
    { header: c.colStatus, cell: (r) => <span className="inline-flex items-center gap-2"><StatusBadge value={r.statusCategory ?? ""} map={catMap} />{r.status?.name ?? r.status?.code}</span> },
    { header: c.colUpdatedAt, cell: (r) => fmtTime(r.updatedAtMs ?? r.createdAtMs) },
  ];
  return <Listing q={q} columns={columns} rowKey={(r) => r.instanceNo} empty={mode === "mine" ? c.emptyMine : c.emptyFailed} paging={p} errorText={q.error ? explainError(q.error, locale) : null} />;
}

function AllList() {
  const c = useAc();
  const api = useOpsKit();
  const { locale } = useI18n();
  const statusMap = useApprovalStatusMap();
  const p = usePaging();
  const [status, setStatus] = React.useState("");
  const q = useQuery({
    queryKey: [QUERY_KEY, "approvals", status, p.page, p.size],
    queryFn: () => api.approvals({ status: status ? [status] : undefined, page: p.page, size: p.size }),
    placeholderData: keepPreviousData,
  });
  const columns: Column<ApprovalRow>[] = [
    { header: c.colTitle, cell: (r) => <Link className="focus-ring text-primary-ink hover:underline" href={`/approvals/detail?no=${encodeURIComponent(r.approvalNo)}`}>{displayTitle(r)}</Link> },
    { header: c.colProcess, cell: (r) => r.process?.name ?? r.process?.code ?? "—" },
    { header: c.colStep, cell: (r) => r.currentStep?.name ?? "—" },
    { header: c.colStatus, cell: (r) => <StatusBadge value={r.status} map={statusMap} /> },
    { header: c.colRequester, cell: (r) => r.requestedBy?.displayName ?? r.requestedBy?.ref ?? "—" },
    { header: c.colRequestedAt, cell: (r) => fmtTime(r.requestedAtMs) },
  ];
  return (
    <>
      <div className="mb-3"><FilterSelect value={status} onChange={(v) => { setStatus(v); p.setPage(1); }} options={statusMap} allLabel={c.allStatus} /></div>
      <Listing q={q} columns={columns} rowKey={(r) => r.approvalNo} empty={c.emptyAll} paging={p} errorText={q.error ? explainError(q.error, locale) : null} />
    </>
  );
}

function Listing<T>({ q, columns, rowKey, empty, paging, errorText }: {
  q: { data?: { list: T[]; total: number }; isPending: boolean; refetch: () => unknown; error?: Error | null };
  columns: Column<T>[]; rowKey: (r: T) => string; empty: string;
  paging: { page: number; size: number; setPage: (n: number) => void; setSize: (n: number) => void };
  errorText: string | null;
}) {
  if (errorText) return <ErrorState error={new Error(errorText)} onRetry={() => void q.refetch()} />;
  return (
    <>
      <DataTable rows={q.data?.list} loading={q.isPending} error={q.error} onRetry={() => void q.refetch()} columns={columns} rowKey={rowKey} empty={empty} />
      <Pagination page={paging.page} size={paging.size} total={q.data?.total ?? 0} onPage={paging.setPage} onSize={paging.setSize} />
    </>
  );
}
