package ai.neargo.shop.media;

import ai.neargo.shop.job.JobSupport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每晚一次的标记扫描。<b>只改状态，一个文件都不删。</b>
 *
 * <p>本期不做自动回收，所以这里没有任何破坏性动作 —— 它产出的只是一份待回收清单，
 * 删不删由运营在页面上决定。正因为它是只读的，才敢让它自动跑；
 * 而删除那一步永远要人工点头。
 *
 * <p><b>只在 worker 部署跑</b>：全表扫描与 API 抢同一个连接池时，
 * 扫描跑一轮能把下单接口拖到超时，而这种拖慢在监控上看起来像「数据库慢」。
 */
@Profile("worker")
@Component
public class MediaScanJob {

    private final MediaScanner scanner;
    private final JobSupport jobs;

    public MediaScanJob(MediaScanner scanner, JobSupport jobs) {
        this.scanner = scanner;
        this.jobs = jobs;
    }

    /**
     * 凌晨跑，与其它日更任务错开。
     *
     * <p>频率不必更高：清单是给人看的，而人是按天来处理它的。
     * 更高的频率只会让「早上看到的清单」和「点确认时的清单」更容易不一致 ——
     * 那个不一致由提交时的数量比对兜着，但没必要主动制造。
     */
    @Scheduled(cron = "${shop.media.scan.cron:0 20 3 * * *}")
    // 扫描本身幂等（同一份引用集算两遍结果一样），但两个实例同时跑会互相覆盖
    // last_ref_desc，且把一轮几分钟的全表扫描变成两轮
    @SchedulerLock(name = "media-scan", lockAtLeastFor = "PT4M", lockAtMostFor = "PT30M")
    public void scan() {
        jobs.run("media-scan", () -> {
            scanner.scan();
            return null;
        });
    }
}
