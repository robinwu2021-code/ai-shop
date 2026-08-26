package ai.neargo.job.worker;

import ai.neargo.job.store.JobLogDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

/**
 * 清理 {@code job_log}。**worker 里唯一一个不走 HTTP 的任务** ——
 * 它只碰 job 库，业务系统与它无关，让它绕一圈调回来毫无意义。
 *
 * <p>分批删而不是一条 {@code DELETE} 删干净：一次删几十万行会长时间持锁，
 * 而这张表同时正被写入。
 */
class LogPurge {

    private static final Logger log = LoggerFactory.getLogger(LogPurge.class);

    /** 单轮最多删几批，避免一次占住线程太久。剩下的下一轮接着删。 */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final JobLogDao logs;
    private final JobWorkerProperties props;

    LogPurge(JobLogDao logs, JobWorkerProperties props) {
        this.logs = logs;
        this.props = props;
    }

    int purge() {
        LocalDateTime before = LocalDateTime.now().minusDays(props.getLogRetentionDays());
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = logs.purgeBefore(before, props.getLogPurgeBatch());
            total += deleted;
            if (deleted < props.getLogPurgeBatch()) {
                break;
            }
        }
        if (total > 0) {
            log.info("清理执行日志 {} 行（保留 {} 天）", total, props.getLogRetentionDays());
        }
        return total;
    }
}
