package ai.neargo.shop.media;

import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 捡起运营已经确认的回收批次去执行。<b>这是唯一真的删文件的定时任务。</b>
 *
 * <p>它与 {@code MediaScanJob} 的分工是本方案的核心判断：
 * <b>扫描只读、可以自动；删除破坏性、必须人工点头。</b>
 * 所以这个任务不会自己决定删什么 —— 它只执行已经有人确认过的批次。
 * 没有人确认，它每次跑起来都是空转。
 *
 * <p><b>可以一键停</b>（{@code shop.media.purge.enabled=false}）：
 * 破坏性任务必须有这个开关。停掉之后批次会一直排在 {@code QUEUED}，
 * 什么也不会丢，打开就接着跑。
 */
@Profile("worker")
@Component
public class MediaPurgeJob {

    private final MediaPurgeService purgeService;
    private final JobSupport jobs;
    private final boolean enabled;

    public MediaPurgeJob(MediaPurgeService purgeService, JobSupport jobs,
                         @Value("${shop.media.purge.enabled:true}") boolean enabled) {
        this.purgeService = purgeService;
        this.jobs = jobs;
        this.enabled = enabled;
    }

    /**
     * 半分钟一次。频率高是因为运营点完确认之后在页面上等着看进度 ——
     * 而这不是「自动回收」：要跑的东西已经被人确认过了。
     */
    @Scheduled(fixedDelayString = "${shop.media.purge.interval-ms:30000}")
    // 幂等（已 PURGED 的会跳过），但两个实例同时跑同一批会重复删同一批文件、
    // 重复写审计 —— 审计里出现两条「删了 137 张」，追溯时说不清到底删了几次
    @SchedulerLock(name = "media-purge", lockAtLeastFor = "PT10S", lockAtMostFor = "PT30M")
    public void purge() {
        if (!enabled) {
            return;
        }
        jobs.run("media-purge", () -> {
            purgeService.runQueued();
            return null;
        });
    }
}
