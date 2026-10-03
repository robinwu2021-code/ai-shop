package ai.neargo.job.api;

/**
 * 一轮执行的结局。六个值分成两组，**分组比取值本身重要**：
 *
 * <ul>
 *   <li><b>业务侧判定</b>（worker 收到了回答）：{@link #SUCCESS} / {@link #FAILED} / {@link #SKIPPED}</li>
 *   <li><b>worker 判定</b>（没收到回答）：{@link #UNREACHABLE} / {@link #TIMEOUT} / {@link #RUNNING}</li>
 * </ul>
 *
 * 这条界线决定了「连续失败」怎么算：只有**确实失败**才计数。
 * 把 SKIPPED 或 TIMEOUT 算进去，告警会在一切正常时响 —— 而那样的告警等于没有告警。
 */
public enum JobStatus {
    /** 业务侧跑完，成功 */
    SUCCESS,
    /** 业务侧跑完，失败。**只有这个计入 consecutive_failures** */
    FAILED,
    /** 锁没抢到，上一轮还在跑。这是正常的并发保护，**不是故障** */
    SKIPPED,
    /** 重试完仍然调不通业务系统（典型：业务正在发布） */
    UNREACHABLE,
    /** 超过 timeout_sec 没返回。**结果未知，不等于失败** —— 业务侧多半还在跑 */
    TIMEOUT,
    /** 已发起，尚未收到结果 */
    RUNNING;

    /** 是不是「确实失败」。连续失败计数、告警都只认它。 */
    public boolean countsAsFailure() {
        return this == FAILED;
    }

    /** 是不是已经有结论（不再变化）。RUNNING 之外都是终态。 */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
