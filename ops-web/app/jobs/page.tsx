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
import { useT } from "@/lib/i18n";
import type { JobLogRow, JobRow, JobStatus } from "@/lib/types";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { Drawer } from "@/components/ui/drawer";
import { HelpNote } from "@/components/ui/help-note";
import { Tooltip } from "@/components/ui/tooltip";
import { useOpsStream } from "@/lib/use-ops-stream";
import { cronText, relTime, oneLine, TEXT_KEY } from "@/lib/job-format";
import { JOBS_COPY } from "./copy";

const MANAGE = "system:job:manage";

/**
 * 四列的栅格定义。
 *
 * **表头与所有数据行是同一个 grid 容器**，每一行靠 `display:contents` 把自己的
 * 四个格子交给它排 —— 这不是写法偏好，是唯一能对齐的做法：grid 的列宽在**每个
 * 容器内部**各算各的，表头一个 grid、每行一个 grid 的话，`auto` 与 `1fr` 会按
 * 各自的内容重新分配。实测：操作列三行分别宽 212 / 132 / 76px（按钮数不同），
 * 于是每一行的前三列起点都不一样，表头更是差了 190px。四列全给固定宽度也能
 * 对齐，但那要按最长那一行留（英文更长），表格白白宽出一截。
 *
 * **`1fr` 给「最后一次」，不给「任务」。** 任务名是一句短标题（最长的
 * 「历史清理（代码里已不存在）」也就十来个字），给它 `1fr` 等于让它把整行的
 * 剩余宽度全吃掉 —— 实测 1280 视口下四列是 442 / 128 / 192 / 236px，
 * 名称列独占 442 而右边那列的时间戳被截成 `2026-08-27 1…`。
 * 宽度该给内容最长、且**截断会丢信息**的那一列：状态徽章 + 时间 + 连败次数。
 *
 * 任务列的下限 `11rem` 不能写成 `0`：写 `minmax(0,…)` 时那个 0 允许它一路缩到
 * 0px —— 实测容器 494px、后三列固定 580px，于是名称列真的成了 0 宽，
 * 页面第一列显示的是「频率」，**任务叫什么完全看不见**，且没有任何报错。
 * 上限 `20rem` 是为了宽屏：再宽也没有意义，那时该让给右边。
 */
const GRID = "grid grid-cols-[minmax(11rem,20rem)_10rem_minmax(14rem,1fr)_auto] items-start min-w-[42rem]";
/** 单元格通用间距。列间距靠它，不用 `gap` —— gap 会把行分隔线切成四段。 */
const CELL = "border-t px-3 py-2.5";


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
  const t = useT();
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
    /*
     * **从没开过** —— 注册进来就是停的，而后没有任何人动过它。
     *
     * 与「已停」是两回事：已停是有人主动关的（他知道自己在做什么），
     * 从没开过是**没人管过**。两者混在同一个数里，后者就永远看不见。
     *
     * 2026-09-02 的代价：五个任务因此从未运行 —— 其中三条把商品域与进销存
     * 之间的全部跨域链路堵着（216 条事件排队），另外两条是资金对账
     *（「支付成功但订单未转已支付」躺了 34 小时）。
     *
     * 判据用 `lastStatus === null`（从未执行过一次）而不是 run_count：
     * 一个开过又被关掉的任务不该算进来，那是有人做过决定的。
     */
    never: rows.filter((r) => !r.enabled && !r.missing && r.lastStatus === null).length,
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
      <HelpNote>{c.jobsNotice}</HelpNote>

      {/* 概览条：十一行扫下来之前先给一个总览。**不重排行** ——
          实时更新时把出问题的挪到最前会让行位置跳，而人正要点某一行的按钮 */}
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="text-muted-foreground">{tcn(c.jobsSumTotal, sum.total)}</span>
        <Badge tone="success">{tcn(c.jobsSumOn, sum.on)}</Badge>
        <Badge tone="muted">{tcn(c.jobsSumOff, sum.off)}</Badge>
        {/* 用 warning 而不是 muted：它要被看见 —— 这一档的存在本身就是「有事没人管」 */}
        {sum.never > 0 && <Badge tone="warning">{tcn(c.jobsSumNever, sum.never)}</Badge>}
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

      {/*
        * 窄屏时**横向滚动，不是把列压没**。
        *
        * 第一版第一列写的是 minmax(0,1fr) —— 那个 0 是为了让 truncate 生效，
        * 但它同时允许这一列一路缩到 0px。实测：容器 494px 而后三列固定占 580px，
        * 于是名称列宽度真的成了 0，页面上第一列显示的是「频率」，
        * **任务叫什么完全看不见**，而没有任何报错。
        *
        * 给它一个真实下限，整行给最小宽度，装不下就在这个容器里横滚 ——
        * 页面本身不横滚，这是仓库里宽表格一贯的做法。
        */}
      <div className="overflow-x-auto rounded-card border">
       <div className={GRID}>
        {/*
          * 表头。**加它是因为没有标题时那些数字读不出来**：
          * 「每天 03:25」下面那个时间到底是上次还是下次，只能猜。
          */}
        {[c.jobsColName, c.jobsColCron, c.jobsColLast, t("common.actions")].map((h, i) => (
          <div key={i} className="bg-muted/40 px-3 py-2 text-xs font-medium text-muted-foreground">{h}</div>
        ))}
        {rows.map((r) => (
          /* display:contents —— 这一行的四个格子直接交给外层 grid 排，见 GRID 的注释 */
          <div key={r.jobName} className="contents">
            {/* ① 名称 + 说明触发器 */}
            <div className={`${CELL} min-w-0`}>
              <div className="flex items-center gap-2">
                <span aria-hidden
                      className={`inline-block size-2 shrink-0 rounded-chip ${dotClass(r)}`} />
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
              {/*
                * 描述进浮层，不占行高。
                *
                * 它此前是 `truncate` 的一行：这几条描述都是两三句话，一句也看不全，
                * 却天天占着一行 —— 而每天开这一页的人早就知道每个任务干什么，
                * 他要的是「跑没跑、什么时候跑」。第一次来的人点一下就有。
                *
                * 中间试过就地展开（`<details>`），不行：展开会把下面的行推下去，
                * 而且那一行比别的行高，**字段就不再横向对齐了** —— 加了表头之后
                * 这一点尤其明显。浮层不占布局流，点开点关表格纹丝不动。
                */}
              {r.description && (
                <HelpNote inline title={c.jobsDescToggle} className="mt-0.5 text-xs">
                  {r.description}
                </HelpNote>
              )}
            </div>

            {/* ② 频率说人话，原始 cron 收进 tooltip */}
            <div className={`${CELL} min-w-0 text-sm`}>
              <Tooltip label={<code>{r.cron}</code>}>
                {(p) => <span {...p} className="block truncate">{cronText(r.cron, tc)}</span>}
              </Tooltip>
              {/*
                * 截断了要能 hover 看全 —— 与下一列的 detail 同一个做法。
                * 正常情况「下次」是近期，relTime 给的是「12 分钟后」这类短文本；
                * 只有任务停了很久（下次时间是远期或过期）才回落成绝对时间戳，
                * 而那恰恰是最需要看清楚的时候。
                */}
              <div className="truncate text-xs text-muted-foreground"
                   title={`${c.jobsNextLabel} ${relTime(r.nextRunAt, tc)}`}>
                {c.jobsNextLabel} {relTime(r.nextRunAt, tc)}
              </div>
            </div>

            {/* ③ 最后一次。detail 压成一行 —— 要看全的去日志里看 */}
            <div className={`${CELL} min-w-0 text-sm`}>
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
            <div className={`${CELL} flex shrink-0 items-center gap-1`}>
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
