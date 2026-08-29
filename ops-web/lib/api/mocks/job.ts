// 定时任务（P-17.1）的内存 mock。
import type { JobLogRow, JobRow } from "@/lib/types";
import type { JobApi } from "../contracts/job";
import { notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

/*
 * 假数据**刻意覆盖四种状态**，因为这一页的价值全在「一眼看出哪条不对」：
 *   · 从未执行（lastStatus = null）—— **今天 17 个任务的真实状态**
 *   · 成功且有 detail —— 正常长什么样
 *   · 连续失败 —— 要显眼
 *   · 代码里已不存在（missing）—— 不删行是有意的，静默消失比留着危险
 * 只放成功的假数据，就看不出这一页是干什么的。
 */
const rows: JobRow[] = [
  {
    jobName: "order-auto-close", displayName: "订单超时自动关单",
    description: "关掉超时未支付的订单并释放库存、券与积分。不跑的话库存被永久占住",
    ownerModule: "shop-core", cron: "0 * * * * *", enabled: true, missing: false,
    manualTrigger: true, lastRunAt: "2026-08-27T14:31:00", lastStatus: "SUCCESS",
    durationMs: 142, detail: "关闭 12 单，释放库存 34 件", error: null,
    consecutiveFailures: 0, runCount: 8241, nextRunAt: "2026-08-27T14:32:00",
    running: false, triggerPending: false, updatedBy: null,
  },
  {
    jobName: "plan-expiry", displayName: "增值包到期扫描",
    description: "扫出到期的商家增值包：进宽限期或降级。不跑的话过期商家照常接单",
    ownerModule: "shop-merchant", cron: "0 25 3 * * *", enabled: true, missing: false,
    manualTrigger: true, lastRunAt: null, lastStatus: null, durationMs: null,
    detail: null, error: null, consecutiveFailures: 0, runCount: 0,
    nextRunAt: "2026-08-28T03:25:00", running: false, triggerPending: false, updatedBy: null,
  },
  {
    jobName: "recon-scan", displayName: "对账自查",
    description: "扫出平台账与渠道账对不上的流水",
    ownerModule: "shop-settle", cron: "0 */10 * * * *", enabled: true, missing: false,
    manualTrigger: true, lastRunAt: "2026-08-27T14:20:00", lastStatus: "FAILED",
    durationMs: 3120, detail: "自查 128 笔（补回 0 · 关单 0 · 留待 3）",
    error: "SQLTimeoutException", consecutiveFailures: 3, runCount: 412,
    nextRunAt: "2026-08-27T14:30:00", running: false, triggerPending: false, updatedBy: "ops:zhang",
  },
  {
    jobName: "media-scan", displayName: "媒体资源扫描",
    description: "扫一遍存储里的媒体文件，为「可回收空间」提供数据",
    ownerModule: "shop-base", cron: "0 20 3 * * *", enabled: false, missing: false,
    manualTrigger: true, lastRunAt: "2026-08-20T03:20:00", lastStatus: "SUCCESS",
    durationMs: 91200, detail: null, error: null, consecutiveFailures: 0, runCount: 31,
    nextRunAt: null, running: false, triggerPending: false, updatedBy: "ops:li",
  },
  {
    jobName: "legacy-cleanup", displayName: "历史清理（代码里已不存在）",
    description: "代码里已经没有这个任务了。**不删行是有意的** —— 静默消失比留着危险：运营会以为它还在跑",
    ownerModule: "shop-core", cron: "0 0 5 * * *", enabled: true, missing: true,
    manualTrigger: false, lastRunAt: "2026-07-02T05:00:00", lastStatus: "SUCCESS",
    durationMs: 220, detail: null, error: null, consecutiveFailures: 0, runCount: 120,
    nextRunAt: null, running: false, triggerPending: false, updatedBy: null,
  },
];

const logs: JobLogRow[] = [
  { runId: "r-3", jobName: "recon-scan", triggerType: "CRON", bizDate: "2026-08-26",
    startedAt: "2026-08-27T14:20:00", finishedAt: "2026-08-27T14:20:03", durationMs: 3120,
    status: "FAILED", detail: "自查 128 笔", error: "SQLTimeoutException",
    workerInstance: "job-1", httpStatus: 200 },
  { runId: "r-2", jobName: "recon-scan", triggerType: "MANUAL", bizDate: "2026-08-26",
    startedAt: "2026-08-27T14:10:00", finishedAt: "2026-08-27T14:10:01", durationMs: 980,
    status: "SKIPPED", detail: "上一轮仍在执行，本轮跳过", error: null,
    workerInstance: "job-1", httpStatus: 409 },
  { runId: "r-1", jobName: "recon-scan", triggerType: "CRON", bizDate: "2026-08-26",
    startedAt: "2026-08-27T14:00:00", finishedAt: "2026-08-27T14:00:02", durationMs: 1740,
    status: "SUCCESS", detail: "自查 96 笔（补回 1 · 关单 0 · 留待 2）", error: null,
    workerInstance: "job-1", httpStatus: 200 },
];

const find = (name: string) => {
  const row = rows.find((r) => r.jobName === name);
  // 与全站同一口径：错误提示也要跟着界面语言走 —— 页面切 EN 之后，
  // 报错还是中文的话，用户最需要看懂的那句话恰恰看不懂
  if (!row) notFound("任务", "Job", name);
  return row;
};

export const jobMock: JobApi = {
  listJobs: () => wait(rows.map((r) => ({ ...r }))),
  getJob: (name) => wait({ ...find(name) }),
  listJobLogs: (q) => wait(logs.filter((l) => l.jobName === q.name).map((l) => ({ ...l }))),
  enableJob: (name) => { const r = find(name); r.enabled = true; return wait({ ...r }, 400); },
  disableJob: (name) => { const r = find(name); r.enabled = false; return wait({ ...r }, 400); },
  updateJobCron: (name, cron) => { const r = find(name); r.cron = cron; return wait({ ...r }, 350); },
  triggerJob: (name) => {
    const r = find(name);
    // **只是记下请求** —— 与真实后端一致：调度器下一轮轮询才会跑
    r.triggerPending = true;
    return wait({ ...r }, 400);
  },
};
