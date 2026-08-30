/**
 * 定时任务（P-17.1「运行配置」）。
 *
 * <p>这个系统有 17 个定时任务，而**它们在生产上一次都没跑过** ——
 * `sys_job_run` 0 行、`shedlock` 0 行，因为跑的是 `api,ops` 而任务全挂在 `worker` 上。
 * 这一页存在之前，「该跑的没跑」没有任何人会发现。
 */

/** 一轮执行的结局。**分组比取值本身重要** —— 它决定页面上要提示人做什么。 */
export type JobStatus =
  /** 跑完了，成功 */
  | "SUCCESS"
  /** 跑了，失败。**只有它计入连续失败** */
  | "FAILED"
  /** 锁没抢到，上一轮还在跑。正常的并发保护，不是故障 */
  | "SKIPPED"
  /** 调不通业务系统（多半正在发布）。不是这个任务的问题 */
  | "UNREACHABLE"
  /** 超时没回。**结果未知，不等于失败** —— 业务侧多半还在跑 */
  | "TIMEOUT"
  /** 已发起，还没回 */
  | "RUNNING";

/**
 * 列表里的一行：**任务定义 + 当前状态**，后端已经合好。
 *
 * 前端不该发两次请求再自己 join —— 那样「有定义但从没跑过」这种状态要靠前端拼，
 * 而它恰恰是今天最常见的状态。
 */
export interface JobRow {
  jobName: string;
  /** 给人看的中文名。**页面显示这个，不显示 jobName** —— 运营看不懂锁名 */
  displayName: string;
  description: string | null;
  ownerModule: string | null;
  cron: string;
  enabled: boolean;
  /** 代码里已经没有这个任务了。**不删行是有意的**：静默消失比留着危险 */
  missing: boolean;
  /** 页面上显不显示「立即执行」。秒级任务给 false —— 它们本来就一直在跑 */
  manualTrigger: boolean;

  /** `null` = **从未执行**。这是今天 17 个任务的普遍状态，要显示成一句话而不是空白 */
  lastRunAt: string | null;
  lastStatus: JobStatus | null;
  durationMs: number | null;
  /** 业务写的一句人话：「关闭 12 单，释放库存 34 件」。运营唯一能看懂的东西 */
  detail: string | null;
  error: string | null;
  /** **只统计 FAILED**；SKIPPED / TIMEOUT / UNREACHABLE 都不算 —— 否则告警会在一切正常时响 */
  consecutiveFailures: number;
  runCount: number;
  nextRunAt: string | null;
  running: boolean;
  /** 点过「立即执行」但调度器还没捡起来。没有这一格的话，点完页面毫无反应 */
  triggerPending: boolean;
  updatedBy: string | null;
}

/** 执行日志一行。 */
export interface JobLogRow {
  runId: string;
  jobName: string;
  triggerType: JobTriggerType;
  bizDate: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  status: JobStatus;
  detail: string | null;
  error: string | null;
  workerInstance: string | null;
  httpStatus: number | null;
}

/**
 * 这一轮是被什么触发的。**排障时第一个要看的就是它** ——
 * 同一个任务，定时跑失败和人手动补跑失败，要找的人不是同一个。
 */
export type JobTriggerType = "CRON" | "MANUAL" | "RETRY" | "BACKFILL";
