"use client";

// 定时任务（矩阵 P-17.1「运行配置」）。
//
// **这个系统有 17 个后台任务，而它们在生产上一次都没跑过** ——
// `sys_job_run` 0 行、`shedlock` 0 行，因为跑的是 `api,ops` 而任务全挂在 `worker` 上。
// 隔离做完了，「谁来跑」这半件没做，而这件事没有任何地方会报错。
//
// **这一页直读库，不问调度器。** 调度器挂了的时候它仍要能显示「最后一次跑是 2 小时前」——
// 那正是最需要看的时刻。若这一页向调度器要数据，它一挂页面就是空白，
// 等于把最关键的那次故障变成盲区。
//
// 代价是「立即执行」也要经库：点了只是记下请求，调度器下一轮轮询（最多 30 秒）捡起来。
// 所以按钮点完要显示「已排队」——不显示的话点完什么都不发生，人会以为没点上。
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { useCan } from "@/lib/use-can";
import { useCopy } from "@/lib/use-copy";
import type { JobLogRow, JobRow, JobStatus } from "@/lib/types";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { Notice } from "@/components/ui/notice";
import { JOBS_COPY } from "./copy";

const MANAGE = "system:job:manage";

/**
 * 状态 → 颜色。**六个状态分两组**，颜色也照这个分：
 * 「业务说的」（成功/失败/跳过）与「调度器判的」（调不通/超时/执行中）。
 *
 * **跳过、调不通、超时都不是红的** —— 它们不是这个任务出了问题：
 * 跳过是正常的并发保护，调不通多半是业务正在发布，超时是结果未知。
 * 把它们涂红，运营就会去查一个不存在的故障，而下次真红了他不会再信。
 */
const TONE: Record<JobStatus, BadgeTone> = {
  SUCCESS: "success",
  FAILED: "danger",
  SKIPPED: "muted",
  UNREACHABLE: "warning",
  TIMEOUT: "warning",
  RUNNING: "info",
};

export default function JobsPage() {
  const c = useCopy(JOBS_COPY);
  const can = useCan();
  const qc = useQueryClient();
  const [logsOf, setLogsOf] = useState<JobRow | null>(null);

  const jobs = useQuery({ queryKey: ["ops-jobs"], queryFn: () => api.listJobs() });
  const refresh = () => qc.invalidateQueries({ queryKey: ["ops-jobs"] });

  const toggle = useMutation({
    mutationFn: (row: JobRow) =>
      row.enabled ? api.disableJob(row.jobName) : api.enableJob(row.jobName),
    onSuccess: refresh,
  });
  const trigger = useMutation({
    mutationFn: (row: JobRow) => api.triggerJob(row.jobName),
    onSuccess: refresh,
  });

  const columns: Column<JobRow>[] = [
    {
      header: c.jobsColName,
      cell: (r) => (
        <div className="space-y-0.5">
          {/* 显示中文名，不显示 jobName —— 运营看不懂锁名 */}
          <div className="font-medium">{r.displayName}</div>
          {r.description && <div className="text-xs text-muted-foreground">{r.description}</div>}
          {r.missing && (
            <Badge tone="danger" title={c.jobsMissingHint}>{c.jobsMissing}</Badge>
          )}
        </div>
      ),
    },
    { header: c.jobsColCron, cell: (r) => <code className="text-xs">{r.cron}</code>, width: "9rem" },
    {
      header: c.jobsColLast,
      cell: (r) =>
        // **从未执行要说成一句话**，不是空白：它是今天 17 个任务的普遍状态，
        // 而空白看起来像「加载失败」
        r.lastStatus === null
          ? <span className="text-muted-foreground">{c.jobsNeverRan}</span>
          : (
            <div className="space-y-0.5">
              <Badge tone={TONE[r.lastStatus]}>{c[`jobsStatus${r.lastStatus}` as keyof typeof c]}</Badge>
              <div className="text-xs text-muted-foreground">{r.lastRunAt}</div>
              {r.detail && <div className="text-xs">{r.detail}</div>}
              {r.error && <div className="text-xs text-destructive">{r.error}</div>}
            </div>
          ),
    },
    { header: c.jobsColNext, cell: (r) => r.nextRunAt ?? "—", width: "11rem" },
    {
      header: c.jobsColFails,
      numeric: true,
      width: "5rem",
      // 只统计 FAILED；跳过/超时/调不通都不算 —— 否则这个数会在一切正常时涨
      cell: (r) => (r.consecutiveFailures > 0
        ? <span className="text-destructive font-medium">{r.consecutiveFailures}</span>
        : r.consecutiveFailures),
    },
    {
      header: c.jobsColState,
      width: "8rem",
      cell: (r) => (
        <div className="space-y-1">
          <Badge tone={r.enabled ? "success" : "muted"}>{r.enabled ? c.jobsOn : c.jobsOff}</Badge>
          {r.running && <Badge tone="info">{c.jobsRunning}</Badge>}
          {r.triggerPending && (
            <Badge tone="info" title={c.jobsPendingHint}>{c.jobsPending}</Badge>
          )}
        </div>
      ),
    },
    {
      header: "",
      width: "16rem",
      cell: (r) => (
        <div className="flex flex-wrap gap-1.5">
          <Button size="sm" variant="ghost" onClick={() => setLogsOf(r)}>{c.jobsLogs}</Button>
          {/* 写操作按 manage 显隐：只读的人看得见状态，但点不动开关 */}
          {can(MANAGE) && !r.missing && (
            <>
              <Button size="sm" variant="ghost"
                      onClick={() => toggle.mutate(r)} disabled={toggle.isPending}>
                {r.enabled ? c.jobsDisable : c.jobsEnable}
              </Button>
              {r.manualTrigger && r.enabled && (
                <Button size="sm" variant="ghost"
                        onClick={() => trigger.mutate(r)} disabled={trigger.isPending}>
                  {c.jobsTrigger}
                </Button>
              )}
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-4">
      <Notice tone="info">{c.jobsNotice}</Notice>

      <DataTable
        columns={columns}
        rows={jobs.data}
        loading={jobs.isLoading}
        error={jobs.error}
        onRetry={() => jobs.refetch()}
        rowKey={(r) => r.jobName}
      />

      <Drawer open={!!logsOf} onOpenChange={(o) => !o && setLogsOf(null)}
              title={logsOf?.displayName ?? ""}>
        {logsOf && <JobLogs c={c} name={logsOf.jobName} />}
      </Drawer>
    </div>
  );
}

function JobLogs({ c, name }: { c: ReturnType<typeof useCopy<typeof JOBS_COPY.zh>>; name: string }) {
  const logs = useQuery({
    queryKey: ["ops-job-logs", name],
    queryFn: () => api.listJobLogs({ name }),
  });

  const columns: Column<JobLogRow>[] = [
    { header: c.jobsColStarted, cell: (l) => l.startedAt, width: "11rem" },
    { header: c.jobsColState, cell: (l) => <Badge tone={TONE[l.status]}>
        {c[`jobsStatus${l.status}` as keyof typeof c]}</Badge>, width: "10rem" },
    { header: c.jobsColTrigger, cell: (l) => l.triggerType, width: "6rem" },
    { header: c.jobsColDuration, numeric: true, width: "6rem",
      cell: (l) => (l.durationMs == null ? "—" : `${l.durationMs} ms`) },
    { header: c.jobsColDetail, cell: (l) => (
        <div className="space-y-0.5">
          <div>{l.detail ?? "—"}</div>
          {l.error && <div className="text-xs text-destructive">{l.error}</div>}
        </div>
      ) },
  ];

  return (
    <DataTable
      columns={columns}
      rows={logs.data}
      loading={logs.isLoading}
      error={logs.error}
      onRetry={() => logs.refetch()}
      rowKey={(l) => l.runId}
      empty={c.jobsLogEmpty}
    />
  );
}
