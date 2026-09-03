// 定时任务文案（矩阵 P-17.1「运行配置」）。
import type { PageCopy } from "@/lib/use-copy";

const zh = {
  jobsNotice:
    "这里列的是后台自己会跑的任务：关单、对账、到期扫描。它们不跑的时候不会报错——"
    + "订单不会关、库存不会释放、过期的商家照常接单，而没有任何地方会亮红灯。这一页就是那盏灯。",
  // ── 概览条 ────────────────────────────────
  jobsSumTotal: "共 {n} 个",
  jobsSumOn: "{n} 个运行中",
  jobsSumOff: "{n} 个已停",
  jobsSumNever: "{n} 个从没开过",
  jobsSumFailing: "{n} 个连败",
  jobsSumMissing: "{n} 个失联",
  jobsLive: "实时",
  jobsLiveHint: "状态由服务端推送，不用刷新页面。",
  jobsStale: "连接断了",
  jobsStaleHint: "推送连接断了，正在重连。此刻页面上的状态可能不是最新的。",
  // ── cron 说人话 ────────────────────────────
  jobsCronEveryMinute: "每分钟",
  jobsCronEveryNMinutes: "每 {n} 分钟",
  jobsCronHourlyAt: "每小时 {m} 分",
  jobsCronEveryNHours: "每 {n} 小时 {m} 分",
  jobsCronDailyAt: "每天 {hm}",
  // ── 相对时间 ───────────────────────────────
  jobsRelJustNow: "刚刚",
  jobsRelSoon: "即将",
  jobsRelMinAgo: "{n} 分钟前",
  jobsRelHourAgo: "{n} 小时前",
  jobsRelInMin: "{n} 分钟后",
  jobsRelInHour: "{n} 小时后",
  jobsNextLabel: "下次",
  jobsNeverRan: "从未执行",
  jobsColName: "任务",
  jobsColCron: "频率",
  jobsColLast: "最后一次",
  jobsColNext: "下次",
  jobsColFails: "连败",
  jobsColState: "状态",
  jobsOn: "运行中",
  jobsOff: "已停",
  jobsMissing: "代码里已不存在",
  jobsMissingHint: "这个任务在代码里已经没有了，但记录留着——静默消失比留着危险：运营会以为它还在跑。",
  jobsRunning: "正在执行",
  jobsPending: "已排队",
  jobsPendingHint: "已记下请求，调度器下一轮（最多 30 秒）会跑。运营端与调度器之间不直接通信。",
  jobsEnable: "启用",
  // 「说明」而不是「详情」：详情在运营端到处都是「点进去看一条记录」，
  // 而这里点开的是一段解释，不是另一个对象
  jobsDescToggle: "说明",
  jobsDisable: "停用",
  jobsTrigger: "立即执行",
  jobsEditCron: "改频率",
  jobsLogs: "执行日志",
  jobsCronInvalid: "不是合法的 cron 表达式（6 段）。",
  jobsDisableConfirm: "停用之后这个任务不再执行。关掉关单任务，库存就从那一刻起不再释放。",
  jobsLogEmpty: "还没有执行记录。",
  jobsColStarted: "开始",
  jobsColDuration: "用时",
  jobsColTrigger: "触发",
  jobsColDetail: "结果",
  // 六个状态各自的说法：**分组比取值本身重要**，页面要让人一眼知道该不该管
  jobsStatusSUCCESS: "成功",
  jobsStatusFAILED: "失败",
  jobsStatusSKIPPED: "跳过（上一轮还在跑）",
  jobsStatusUNREACHABLE: "调不通（多半在发布）",
  jobsStatusTIMEOUT: "超时（结果未知）",
  jobsStatusRUNNING: "执行中",

  // ── 按模块分组（M8）────────────────────────────────────────────────
  // 2026-09-02 之前进销存的三条命脉全是关着的，六条跨域链路堵了一整天 ——
  // 而在一张平铺的 17 行表里，「这三条都归进销存、而且都关着」读不出来
  jobsGroupCount: "{n} 个任务",
  jobsGroupOff: "{n} 个关着",
  jobsGroupAllOff: "整组都关着",
  jobsGroupOther: "其它",
};

const en: typeof zh = {
  jobsSumTotal: "{n} total",
  jobsSumOn: "{n} running",
  jobsSumOff: "{n} stopped",
  jobsSumNever: "{n} never enabled",
  jobsSumFailing: "{n} failing",
  jobsSumMissing: "{n} missing",
  jobsLive: "Live",
  jobsLiveHint: "Status is pushed from the server; no need to refresh.",
  jobsStale: "Disconnected",
  jobsStaleHint: "The live connection dropped and is reconnecting. What you see may be stale.",
  jobsCronEveryMinute: "Every minute",
  jobsCronEveryNMinutes: "Every {n} min",
  jobsCronHourlyAt: "Hourly at :{m}",
  jobsCronEveryNHours: "Every {n}h at :{m}",
  jobsCronDailyAt: "Daily at {hm}",
  jobsRelJustNow: "just now",
  jobsRelSoon: "soon",
  jobsRelMinAgo: "{n} min ago",
  jobsRelHourAgo: "{n} h ago",
  jobsRelInMin: "in {n} min",
  jobsRelInHour: "in {n} h",
  jobsNextLabel: "Next",

  jobsNotice:
    "These are the background jobs the platform runs on its own: closing orders, reconciliation, "
    + "expiry sweeps. When they stop, nothing raises an error — orders stay open, stock stays locked, "
    + "expired merchants keep selling. This page is the only place that shows it.",
  jobsNeverRan: "Never ran",
  jobsColName: "Job",
  jobsColCron: "Schedule",
  jobsColLast: "Last run",
  jobsColNext: "Next run",
  jobsColFails: "Fails",
  jobsColState: "State",
  jobsOn: "Running",
  jobsOff: "Stopped",
  jobsMissing: "Gone from code",
  jobsMissingHint: "This job no longer exists in the code, but the row is kept on purpose — "
    + "disappearing silently is worse: people would assume it still runs.",
  jobsRunning: "In progress",
  jobsPending: "Queued",
  jobsPendingHint: "Request recorded. The scheduler picks it up on its next poll (up to 30s). "
    + "This page never talks to the scheduler directly.",
  jobsEnable: "Enable",
  jobsDescToggle: "What it does",
  jobsDisable: "Disable",
  jobsTrigger: "Run now",
  jobsEditCron: "Edit schedule",
  jobsLogs: "Run log",
  jobsCronInvalid: "Not a valid cron expression (6 fields).",
  jobsDisableConfirm: "Once disabled this job stops running. Disable order auto-close and stock "
    + "stops being released from that moment on.",
  jobsLogEmpty: "No runs recorded yet.",
  jobsColStarted: "Started",
  jobsColDuration: "Took",
  jobsColTrigger: "Trigger",
  jobsColDetail: "Result",
  jobsStatusSUCCESS: "Success",
  jobsStatusFAILED: "Failed",
  jobsStatusSKIPPED: "Skipped (previous run still going)",
  jobsStatusUNREACHABLE: "Unreachable (likely deploying)",
  jobsStatusTIMEOUT: "Timed out (outcome unknown)",
  jobsStatusRUNNING: "Running",

  jobsGroupCount: "{n} jobs",
  jobsGroupOff: "{n} off",
  jobsGroupAllOff: "whole group off",
  jobsGroupOther: "Other",
};

export type JobsCopy = typeof zh;
export const JOBS_COPY: PageCopy<JobsCopy> = { zh, en };
