"use client";

// 种草内容审核（矩阵 P-15.2.1）。
//
// 这一页有批量通过，所以最要紧的设计是**把风险内容挡在批量之外**：
// 命中风险词的行不给勾选框，全选也选不上它们。规则在 mock 层同样拦一遍
// （拒绝整批而不是静默跳过），两边同向。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import { fmtTime } from "@/lib/utils";
import type { Post, PostStatus } from "@/lib/types";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer, DrawerSection, Field, FieldGrid } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Pagination } from "@/components/ui/misc";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import { Toolbar } from "@/components/ui/toolbar";
import { Notice } from "@/components/ui/notice";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Textarea } from "@/components/ui/textarea";
import type { ContentsCopy } from "./copy";

const usePostStatusMap = (c: ContentsCopy): StatusMap<PostStatus> => ({
  PENDING: { label: c.psPending, tone: "warning" },
  PASSED: { label: c.psPassed, tone: "success" },
  REJECTED: { label: c.psRejected, tone: "muted" },
  OFFLINE: { label: c.psOffline, tone: "muted" },
});

export function AuditTab({ c, canAudit }: { c: ContentsCopy; canAudit: boolean }) {
  const qc = useQueryClient();
  const statusMap = usePostStatusMap(c);
  const [keyword, setKeyword] = useState("");
  const [status, setStatus] = useState("");
  const [hasRisk, setHasRisk] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const [picked, setPicked] = useState<string[]>([]);
  const [current, setCurrent] = useState<Post | null>(null);
  const [remark, setRemark] = useState("");

  const q = { keyword, status, hasRisk, page, size };
  const list = useQuery({ queryKey: ["posts", q], queryFn: () => api.listPosts(q) });
  const rows = list.data?.records ?? [];

  // 可批量的行 = 待审 + **没命中风险词**。全选只覆盖这批
  const batchable = rows.filter((p) => p.status === "PENDING" && !p.riskHits.length);
  const allPicked = batchable.length > 0 && batchable.every((p) => picked.includes(p.postNo));

  const done = () => { qc.invalidateQueries({ queryKey: ["posts"] }); setPicked([]); };
  const decide = useMutation({
    mutationFn: (to: PostStatus) => api.decidePost({ postNo: current!.postNo, to, remark }),
    onSuccess: () => { done(); setCurrent(null); notify.success(c.toastPostDecided); },
  });
  const batchPass = useMutation({
    mutationFn: () => api.batchPassPosts(picked),
    onSuccess: (r) => { done(); notify.success(fill(c.toastBatchPassed, { n: r.length })); },
  });

  const columns: Column<Post>[] = [
    {
      header: (
        <Checkbox
          aria-label={c.selectAll}
          checked={allPicked ? true : picked.length ? "indeterminate" : false}
          disabled={!canAudit || !batchable.length}
          onChange={(v) => setPicked(v === true ? batchable.map((p) => p.postNo) : [])}
        />
      ),
      cell: (p) =>
        // 命中风险词的**根本不给勾选框** —— 让"能不能批量"在界面上就是可见的
        p.status === "PENDING" && !p.riskHits.length ? (
          <Checkbox
            aria-label={p.postNo}
            checked={picked.includes(p.postNo)}
            disabled={!canAudit}
            onChange={(v) => setPicked((prev) => (v === true ? [...prev, p.postNo] : prev.filter((x) => x !== p.postNo)))}
          />
        ) : <span className="text-muted-foreground">{c.none}</span>,
    },
    { header: c.colPostNo, cell: (p) => p.postNo, numeric: true, align: "start" },
    { header: c.colPostTitle, cell: (p) => p.title },
    { header: c.colAuthor, cell: (p) => `${p.authorName}（${p.authorType === "USER" ? c.authorUser : c.authorMerchant}）` },
    { header: c.colCommunity, cell: (p) => p.communityName },
    {
      header: c.colRisk,
      cell: (p) =>
        p.riskHits.length ? (
          <div className="flex flex-wrap gap-1">
            {p.riskHits.map((w) => <Badge key={w} tone="danger">{w}</Badge>)}
          </div>
        ) : <span className="text-muted-foreground">{c.noRisk}</span>,
    },
    { header: c.colPostStatus, cell: (p) => <StatusBadge map={statusMap} value={p.status} /> },
    { header: c.colCreatedAt, cell: (p) => fmtTime(p.createdAt) },
    {
      header: c.colActions,
      cell: (p) => (
        <Button size="sm" variant="outline" onClick={() => { setCurrent(p); setRemark(""); }}>
          {p.status === "PENDING" ? c.actionAudit : c.actionView}
        </Button>
      ),
    },
  ];

  const next = current ? ({ PENDING: ["PASSED", "REJECTED"], PASSED: ["OFFLINE"], REJECTED: [], OFFLINE: [] } as Record<PostStatus, PostStatus[]>)[current.status] : [];

  return (
    <>
      <Notice className="mb-3">{c.auditNotice}</Notice>
      <Toolbar search={keyword} onSearch={(v) => { setKeyword(v); setPage(1); }} searchPlaceholder={c.searchPost}>
        <FilterSelect aria-label={c.filterPostStatus} value={status} onChange={(v) => { setStatus(v); setPage(1); }}
          options={statusMap} allLabel={c.filterPostStatusAll} />
        <FilterSelect aria-label={c.filterRisk} value={hasRisk} onChange={(v) => { setHasRisk(v); setPage(1); }}
          options={{ "1": { label: c.riskOnly, tone: "danger" }, "0": { label: c.riskNone, tone: "muted" } }}
          allLabel={c.filterRiskAll} />
      </Toolbar>

      {canAudit && (
        <div className="mb-3 flex items-center gap-3">
          <Button size="sm" disabled={!picked.length} loading={batchPass.isPending} onClick={() => batchPass.mutate()}>
            {fill(c.btnBatchPass, { n: picked.length })}
          </Button>
          <span className="txt-caption text-muted-foreground">{c.batchHint}</span>
        </div>
      )}

      <DataTable
        columns={columns} rows={rows} loading={list.isLoading}
        error={list.error} onRetry={() => list.refetch()}
        rowKey={(p) => p.postNo}
        empty={c.emptyPost}
      />
      <Pagination page={page} size={size} onSize={setSize} total={list.data?.total ?? 0} onPage={setPage} />

      <Drawer
        open={!!current}
        onOpenChange={(o) => !o && setCurrent(null)}
        title={current?.title ?? ""}
        desc={current ? statusMap[current.status].label : undefined}
        width="w-[560px]"
        footer={
          current && canAudit && next.length ? (
            <>
              {next.map((to) => (
                <Button key={to} variant={to === "PASSED" ? "default" : "outline"}
                  loading={decide.isPending} onClick={() => decide.mutate(to)}>
                  {statusMap[to].label}
                </Button>
              ))}
            </>
          ) : null
        }
      >
        {current && (
          <div>
            <DrawerSection first title={c.secPostOverview}>
              <FieldGrid>
                <Field className="mb-3" label={c.colAuthor}>{current.authorName}</Field>
                <Field className="mb-3" label={c.colCommunity}>{current.communityName}</Field>
                <Field className="mb-3" label={c.colSku}>{current.skuNo ?? c.none}</Field>
                <Field className="mb-3" label={c.colLikes}>{current.likeCount}</Field>
              </FieldGrid>
            </DrawerSection>

            {current.riskHits.length > 0 && (
              <DrawerSection title={c.secRisk}>
                <div className="mb-2 flex flex-wrap gap-1">
                  {current.riskHits.map((w) => <Badge key={w} tone="danger">{w}</Badge>)}
                </div>
                <p className="txt-caption text-muted-foreground">{c.riskHint}</p>
              </DrawerSection>
            )}

            <DrawerSection title={c.secPostBody}>
              <p className="txt-body whitespace-pre-wrap">{current.content}</p>
            </DrawerSection>

            <DrawerSection title={c.secPostRemark}>
              <Field className="mb-0" label={c.fieldPostRemark}>
                <Textarea value={remark} onChange={setRemark} rows={3} placeholder={c.postRemarkPlaceholder} />
              </Field>
              <p className="mt-1 txt-caption text-muted-foreground">{c.postRemarkHint}</p>
              {current.auditRemark && (
                <p className="mt-2 txt-caption text-muted-foreground">{fill(c.lastRemark, { text: current.auditRemark })}</p>
              )}
            </DrawerSection>
          </div>
        )}
      </Drawer>
    </>
  );
}
