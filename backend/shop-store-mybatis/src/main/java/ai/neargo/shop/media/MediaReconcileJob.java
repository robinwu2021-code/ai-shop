package ai.neargo.shop.media;

import org.springframework.context.annotation.Bean;
import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.job.JobSupport;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 收拾上传崩在半路留下的 {@code PENDING} 行。
 *
 * <p>上传的三步是「写 PENDING → 落盘 → 改 ACTIVE」，故意<b>不在同一个事务里</b>：
 * 包在一个事务里的话，落盘成功而事务回滚就留下「磁盘有文件、库里没有」的孤儿，
 * 而孤儿是查不出来的 —— 统计永远少算，清单里永远不出现，只能靠人去 {@code du} 才发现。
 *
 * <p>代价是崩在中间会留下 {@code PENDING} 行，而那正是这个任务存在的理由：
 * <ul>
 *   <li><b>有行无文件</b>（写库后崩） → 删行</li>
 *   <li><b>有行有文件</b>（落盘后、改 ACTIVE 前崩） → 补成 {@code ACTIVE}</li>
 * </ul>
 * 两种都能对上账，这就是「先记账后落地」换来的东西。
 */
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
@Component
public class MediaReconcileJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(MediaReconcileJob.class);

    private final SysMediaAssetMapper assetMapper;
    private final MediaStore mediaStore;
    private final JobSupport jobs;
    private final int staleMinutes;

    public MediaReconcileJob(SysMediaAssetMapper assetMapper, MediaStore mediaStore, JobSupport jobs,
                             @Value("${shop.media.reconcile.stale-minutes:10}") int staleMinutes) {
        this.assetMapper = assetMapper;
        this.mediaStore = mediaStore;
        this.jobs = jobs;
        this.staleMinutes = staleMinutes;
    }

    @Scheduled(cron = "${shop.media.reconcile.cron:0 5 * * * *}")
    // 幂等（处理过的行已经不是 PENDING 了），但两个实例同时跑会重复判定同一批
    @SchedulerLock(name = "media-reconcile", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void reconcile() {
        // 触发器只负责「到点了」；任务体在 run() 里。J1 只搬不改
        jobs.run("media-reconcile", () -> run(null).detail());
    }

    @Override
    public String name() {
        return "media-reconcile";
    }

    /** 声明。displayName 是运营页面直接显示的那句话 —— 不能是锁名。 */
    @Bean
    public JobDeclaration mediareconcileDeclaration() {
        return new JobDeclaration("media-reconcile", "图片记账对账",
                "把卡在 PENDING 的媒体行收拾掉：字节还在就补成 ACTIVE，字节没了就删行",
                "shop-base", "0 5 * * * *", true,
                480, 600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        /*
         * 只收拾「够旧」的：正在上传中的那一瞬间也是 PENDING，
         * 不留出这段时间就会把别人正传到一半的文件当成残留删掉。
         */
        LocalDateTime line = LocalDateTime.now().minusMinutes(staleMinutes);
        List<SysMediaAsset> stale = assetMapper.selectList(Wrappers.<SysMediaAsset>lambdaQuery()
                .eq(SysMediaAsset::getStatus, SysMediaAsset.PENDING)
                .lt(SysMediaAsset::getCreatedAt, line));
        if (stale.isEmpty()) {
            // **detail 保持 null** —— JobSupport 用它区分「跑了但没事」
            return JobResult.ok(null);
        }

        int completed = 0;
        int dropped = 0;
        for (SysMediaAsset a : stale) {
            SysMediaAsset upd = new SysMediaAsset();
            upd.setId(a.getId());
            upd.setUpdatedAt(LocalDateTime.now());
            if (mediaStore.exists(a.getAssetKey())) {
                // 字节在，只是第三步没走到 —— 补成 ACTIVE，这张图本来就是好的
                upd.setStatus(SysMediaAsset.ACTIVE);
                assetMapper.updateById(upd);
                completed++;
            } else {
                // 字节不在，这一行没有对应的东西。删行而不是留着：
                // 留着会让它永远出现在对账里，而它什么也不代表
                assetMapper.deleteById(a.getId());
                dropped++;
            }
        }
        log.info("图片记账对账：补齐 {} 行、清掉 {} 行残留", completed, dropped);
        // 原本这里也 return null（做了事但不汇报数字）—— J1 只搬不改，保持原样
        return JobResult.ok(null);
    }
}
