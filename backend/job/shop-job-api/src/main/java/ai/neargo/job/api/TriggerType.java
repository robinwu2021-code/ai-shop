package ai.neargo.job.api;

/** 这一轮是怎么被触发的。落进 {@code job_log.trigger_type}，排查时第一眼要看的就是它。 */
public enum TriggerType {
    /** 到点了，调度器发起 */
    CRON,
    /** 运营在页面上点了「立即执行」 */
    MANUAL,
    /** 上一轮没调通，退避后重试 */
    RETRY,
    /** 漏跑补跑（补偿层，J7 才有；先占位以免将来改枚举顺序） */
    BACKFILL
}
