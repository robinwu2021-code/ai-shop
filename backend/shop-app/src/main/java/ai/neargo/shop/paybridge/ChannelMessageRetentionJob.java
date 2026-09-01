package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.pay.channel.ChannelMessageRecorder;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 渠道报文保留期清理（V286）。
 *
 * <p><b>为什么这张表必须配一个清理任务，而不是「以后再说」</b>：
 * 它随交易量线性增长，每笔支付至少一行回调、每次分账退款各一行发送。
 * 而它的价值几乎全在最近几天 —— 没有人会去查半年前那笔的原始报文。
 * 一张只增不减、又没人读旧数据的表，半年后是备份变慢、
 * 是 {@code SELECT} 走错索引、是磁盘告警，而<b>那时再加清理任务，
 * 第一次跑会删几百万行并锁住表</b>。现在配上，每天删的是几百行。
 *
 * <p><b>每次删有上限</b>：不是为了性能，是为了「万一保留期被改成 1 天」
 * 这种手滑不会在一次任务里把整张表删空 —— 分多轮删，中间有人看得到 WARN。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class ChannelMessageRetentionJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageRetentionJob.class);

    /** 一轮最多删这么多。见类注释：这是手滑的护栏，不是性能考虑 */
    private static final int BATCH = 5000;

    private final ChannelMessageRecorder recorder;
    private final JobSupport jobs;

    public ChannelMessageRetentionJob(ChannelMessageRecorder recorder, JobSupport jobs) {
        this.recorder = recorder;
        this.jobs = jobs;
    }

    @Scheduled(cron = "${shop.job.channel-message-retention.cron:0 40 3 * * *}")
    @SchedulerLock(name = "channel-message-retention",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void purge() {
        jobs.run("channel-message-retention", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "channel-message-retention";
    }

    @Bean
    public JobDeclaration channelmessageretentionDeclaration() {
        return new JobDeclaration("channel-message-retention", "渠道报文清理",
                "删掉超过保留期的渠道发送与回调报文。报文只用于排查，价值全在最近几天",
                "shop-app", "0 40 3 * * *", true,
                1500, 1800, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int deleted = recorder.purgeOlderThanRetention(BATCH);
        if (deleted == 0) {
            // detail 保持 null：JobSupport 用它区分「跑了但没事」与「跑了并做了事」
            return JobResult.ok(null);
        }
        if (deleted >= BATCH) {
            /*
             * 撞到上限说明积压比一天的量大得多 —— 要么保留期刚被调短，
             * 要么任务停了一段时间。两种都要人看一眼，所以是 WARN 不是 INFO。
             */
            log.warn("[channel-message] 清理撞到单轮上限 {} 条 —— "
                    + "保留期刚被调短？还是任务停过？剩下的下一轮继续", BATCH);
        }
        return JobResult.ok("清理渠道报文 %d 条".formatted(deleted));
    }
}
