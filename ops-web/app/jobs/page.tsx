"use client";

// 定时任务（矩阵 P-17.1「运行配置」）。
//
// **这一页是「那盏灯」**：后台任务不跑的时候不会报错 —— 订单不会关、库存不会释放、
// 过期的商家照常接单，而没有任何地方会亮红灯。
//
// **布局刻意做成固定两行高**。原先用宽表，而「最后一次」那一列会堆 1~4 行
// （徽标 / 时间 / detail / error），于是每行高度都不一样，十一行看下来像一堆碎片。
// 现在 detail 压成一行、超出截断，要看全的去日志抽屉里看 —— 扫一眼的场景和
// 查一件事的场景本来就该分开。
//
// **状态实时推送**（/ops/stream 的 jobs 事件），页面不再是打开那一刻的快照。
// 服务端仍然在轮询 job 库（它是另一个进程写的，没有别的办法），但那是一个循环，
// 不是每个开着页面的人各一份。
//
// **这一页直读库，不问调度器。** 调度器挂了的时候它仍要能显示「最后一次跑是 2 小时前」——
// 那正是最需要看的时刻。若这一页向调度器要数据，它一挂页面就是空白，
// 等于把最关键的那次故障变成盲区。
//
// 代价是「立即执行」也要经库：点了只是记下请求，调度器下一轮轮询（最多 30 秒）捡起来。
// 所以按钮点完要显示「已排队」——不显示的话点完什么都不发生，人会以为没点上。
import { useMemo, useState } from "react";
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
import { Tooltip } from "@/components/ui/tooltip";
import { useOpsStream } from "@/lib/use-ops-stream";
import { cronText, relTime, oneLine, TEXT_KEY } from "@/lib/job-format";
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
  // 推送连着 = 页面上的状态是活的。断了要说出来 —— 不说的话人会一直盯着
  // 一个不再变化的页面，以为「什么都没发生」
  const [live, setLive] = useState(false);

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

  // 推送来的是整份快照。**直接塞进缓存，不触发重新取数** ——
  // 再发一次请求等于把省下来的轮询原样加回去
  useOpsStream("jobs", (d) => {
    try {
      qc.setQueryData(["ops-jobs"], JSON.parse(d) as JobRow[]);
      setLive(true);
    } catch {
      // 帧坏了就当没收到，页面保持上一份 —— 显示半份数据比显示旧数据糟
    }
  });

  const rows = jobs.data ?? [];
  const sum = useMemo(() => ({
    total: rows.length,
    on: rows.filter((r) => r.enabled && !r.missing).length,
    off: rows.filter((r) => !r.enabled && !r.missing).length,
    failing: rows.filter((r) => r.consecutiveFailures > 0).length,
    missing: rows.filter((r) => r.missing).length,
  }), [rows]);

  /*
   * cronText / relTime 用的是短 key（`cron.dailyAt`），这里映到本页词条上。
   *
   * **写成显式表，不用正则拼**。第一版是
   * `k.replace(/^(cron|rel)\./, …).replace(/^([a-z])/, 大写)`，
   * 而 "cron." 换成 "Cron" 之后首字母已经是大写，第二个 replace 不生效 ——
   * 拼出来的是 `jobsCroneveryNMinutes`，词条查不到，页面上直接显示原始 key。
   * 这类错误编译期不报、类型也不管，只有真打开页面才看得见。
   */
  const tc = (k: string, p?: Record<string, unknown>) => {
    const key = TEXT_KEY[k];
    let out = key ? String(c[key as keyof typeof c] ?? k) : k;
    for (const [a, b] of Object.entries(p ?? {})) out = out.replace(`{${a}}`, String(b));
    return out;
  };

  return (
    <div className="space-y-4">
      <Notice tone="info">{c.jobsNotice}</Notice>

      {/* 概览条：十一行扫下来之前先给一个总览。**不重排行** ——
          实时更新时把出问题的挪到最前会让行位置跳，而人正要点某一行的按钮 */}
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-muted-foreground">{tcn(c.jobsSumTotal, sum.total)}</span>
        <Badge tone="success">{tcn(c.jobsSumOn, sum.on)}</Badge>
        <Badge tone="muted">{tcn(c.jobsSumOff, sum.off)}</Badge>
        {sum.failing > 0 && <Badge tone="danger">{tcn(c.jobsSumFailing, sum.failing)}</Badge>}
        {sum.missing > 0 && <Badge tone="danger">{tcn(c.jobsSumMissing, sum.missing)}</Badge>}
        <span className="ml-auto">
          <Tooltip label={live ? c.jobsLiveHint : c.jobsStaleHint} side="left">
            {(p) => (
              <span {...p}>
                <Badge tone={live ? "info" : "warning"}>{live ? c.jobsLive : c.jobsStale}</Badge>
              </span>
            )}
          </Tooltip>
        </span>
      </div>

      {jobs.isLoading && <div className="text-sm text-muted-foreground">…</div>}

      <div className="divide-y rounded-md border">
        {rows.map((r) => (
          <div key={r.jobName}
               className="grid grid-cols-[minmax(0,1fr)_9rem_14rem_auto] items-center gap-3 px-3 py-2.5">
            {/* ① 名称 + 描述。两行封顶，描述截断 —— 这是行高一致的关键 */}
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span aria-hidden
                      className={`inline-block size-2 shrink-0 rounded-full ${dotClass(r)}`} />
                <span className="truncate font-medium">{r.displayName}</span>
                {r.missing && (
                  <Tooltip label={c.jobsMissingHint}>
                    {(p) => <span {...p}><Badge tone="danger">{c.jobsMissing}</Badge></span>}
                  </Tooltip>
                )}
                {r.running && <Badge tone="info">{c.jobsRunning}</Badge>}
                {r.triggerPending && (
                  <Tooltip label={c.jobsPendingHint}>
                    {(p) => <span {...p}><Badge tone="info">{c.jobsPending}</Badge></span>}
                  </Tooltip>
                )}
              </div>
              <div className="truncate pl-4 text-xs text-muted-foreground">{r.description}</div>
            </div>

            {/* ② 频率说人话，原始 cron 收进 tooltip */}
            <div className="min-w-0 text-sm">
              <Tooltip label={<code>{r.cron}</code>}>
                {(p) => <span {...p} className="block truncate">{cronText(r.cron, tc)}</span>}
              </Tooltip>
              <div className="truncate text-xs text-muted-foreground">
                {c.jobsNextLabel} {relTime(r.nextRunAt, tc)}
              </div>
            </div>

            {/* ③ 最后一次。detail 压成一行 —— 要看全的去日志里看 */}
            <div className="min-w-0 text-sm">
              {r.lastStatus === null
                ? <span className="text-muted-foreground">{c.jobsNeverRan}</span>
                : (
                  <div className="flex items-center gap-2">
                    <Badge tone={TONE[r.lastStatus]}>
                      {c[`jobsStatus${r.lastStatus}` as keyof typeof c]}
                    </Badge>
                    <span className="truncate text-xs text-muted-foreground">
                      {relTime(r.lastRunAt, tc)}
                    </span>
                    {r.consecutiveFailures > 0 && (
                      <span className="shrink-0 text-xs font-medium text-destructive">
                        ×{r.consecutiveFailures}
                      </span>
                    )}
                  </div>
                )}
              <div className="truncate text-xs text-muted-foreground"
                   title={r.error ?? r.detail ?? ""}>
                {r.error
                  ? <span className="text-destructive">{oneLine(r.error)}</span>
                  : oneLine(r.detail)}
              </div>
            </div>

            {/* ④ 操作 */}
            <div className="flex shrink-0 items-center gap-1">
              <Button size="sm" variant="ghost" onClick={() => setLogsOf(r)}>{c.jobsLogs}</Button>
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
          </div>
        ))}
      </div>

      <Drawer open={!!logsOf} onOpenChange={(o) => !o && setLogsOf(null)}
              title={logsOf?.displayName ?? ""}>
        {logsOf && <JobLogs c={c} name={logsOf.jobName} />}
      </Drawer>
    </div>
  );
}

/** 把 "共 {n} 个" 里的占位换掉。**不引入模板引擎** —— 全页只有五处。 */
function tcn(tpl: string, n: number): string {
  return tpl.replace("{n}", String(n));
}

/**
 * 行首那颗点。**颜色只回答一个问题：这一行现在要不要管。**
 *
 * 失联 / 连败 → 红；已停 → 灰；正在跑 → 蓝；其余（开着且没连败）→ 绿。
 * 不按 lastStatus 上色：跳过、超时、调不通都不是「这个任务坏了」，
 * 涂红会让人去查一个不存在的故障。
 */
function dotClass(r: JobRow): string {
  if (r.missing || r.consecutiveFailures > 0) return "bg-destructive";
  if (!r.enabled) return "bg-muted-foreground/40";
  if (r.running) return "bg-sky-500 animate-pulse";
  return "bg-emerald-500";
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
