// 定时任务（P-17.1「运行配置」）—— `/ops/jobs/**`。
import type { JobLogRow, JobRow } from "@/lib/types";

export interface JobApi {
  /**
   * 任务清单：定义与当前状态**已在后端合成一行**。
   *
   * 页面读的是库，**不问调度器** —— 调度器挂了的时候这一页仍要能显示
   * 「最后一次跑是 2 小时前」，而那正是最需要看的时刻。
   * 若这一页向调度器要数据，它一挂页面就是空白，等于把最关键的那次故障变成盲区。
   */
  listJobs(): Promise<JobRow[]>;

  getJob(name: string): Promise<JobRow>;

  /** 执行日志，倒序。排查时一屏一屏翻的东西。 */
  listJobLogs(q: { name: string; page?: number; size?: number }): Promise<JobLogRow[]>;

  /** 开。关掉的任务**不会空跑一趟**，是真的从调度里摘掉。 */
  enableJob(name: string): Promise<JobRow>;

  /**
   * 关。**当场改变系统行为** —— 关掉关单任务，库存从那一刻起不再释放。
   * 所以它与只读不是同一个权限码。
   */
  disableJob(name: string): Promise<JobRow>;

  /** 改频率。**非法 cron 后端直接 400** —— 落进去的话页面说「改成功了」而任务再也排不上。 */
  updateJobCron(name: string, cron: string): Promise<JobRow>;

  /**
   * 立即执行一次。
   *
   * **它只是记下请求**：运营端与调度器之间不通信，调度器下一轮轮询（默认 30 秒）捡起来。
   * 所以返回的行里 `triggerPending` 会是 true —— 页面要据此显示「已排队」，
   * 否则点完什么都不发生，人会以为没点上。
   */
  triggerJob(name: string): Promise<JobRow>;
}
